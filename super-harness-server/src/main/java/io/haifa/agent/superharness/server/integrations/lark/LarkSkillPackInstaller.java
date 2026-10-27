package io.haifa.agent.superharness.server.integrations.lark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.superharness.workspace.AtomicMoves;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Installs the official, versioned Lark skill pack into one shared read-only integration root.
 *
 * <p>Discovery, enable state and execution stay in the existing {@code SkillConfigStore}/SDK Skill
 * platform; this class only materializes trusted first-party package bytes, validates their
 * structure and records a content digest. It owns no Skill Runtime and no per-user registry.
 *
 * <p>Integrity is content-based: the archive source is pinned to the official GitHub host, every
 * member passes structural guards, and the SHA-256 of the extracted, guidance-injected tree is
 * recorded in the manifest so a reinstall whose effective content changed is auditable.
 */
public final class LarkSkillPackInstaller {
    public static final String MANIFEST_FILE = ".haifa-lark-manifest.json";
    static final int MAX_ARCHIVE_BYTES = 128 * 1024 * 1024;
    static final int MAX_EXTRACTED_BYTES = 256 * 1024 * 1024;
    static final String GUIDANCE_RESOURCE = "/upstream/lark/lark-shared-guidance.md";
    static final String GUIDANCE_MARKER = "<!-- haifa-lark-cli-auth-guidance-v1 -->";
    private static final Pattern FRONTMATTER_NAME = Pattern.compile("(?m)^name:\\s*[\"']?([a-z0-9-]+)[\"']?\\s*$");
    private static final ConcurrentHashMap<Path, Object> JVM_LOCKS = new ConcurrentHashMap<>();

    /** Installed pack identity. */
    public record Installed(Set<String> names, String version, String contentSha256, boolean contentChanged) {}

    /** Read-only view of the installed manifest/pack. */
    public record Manifest(String version, String source, String contentSha256, Instant installedAt, Set<String> skills) {}

    private final ObjectMapper json;
    private final Path integrationRoot;
    private final Path packRoot;

    public LarkSkillPackInstaller(ObjectMapper json, Path integrationRoot) {
        this.json = json;
        this.integrationRoot = integrationRoot.toAbsolutePath().normalize();
        this.packRoot = this.integrationRoot.resolve("skills");
    }

    public Path packRoot() {
        return packRoot;
    }

    public Path manifestPath() {
        return packRoot.resolve(MANIFEST_FILE);
    }

    /** Manifest when present and readable. */
    public Optional<Manifest> manifest() {
        Path path = manifestPath();
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            JsonNode node = json.readTree(path.toFile());
            if (node == null || !node.isObject()) {
                return Optional.empty();
            }
            Set<String> skills = new LinkedHashSet<>();
            for (JsonNode skill : node.path("skills")) {
                if (skill.isTextual()) {
                    skills.add(skill.asText());
                }
            }
            Instant installedAt = null;
            String raw = node.path("installed_at").asText("");
            if (!raw.isBlank()) {
                try {
                    installedAt = Instant.parse(raw);
                } catch (RuntimeException ignored) {
                    installedAt = null;
                }
            }
            return Optional.of(new Manifest(
                    node.path("version").asText(LarkSkillCatalog.FALLBACK_VERSION),
                    node.path("source").asText(""),
                    node.path("content_sha256").asText(""),
                    installedAt,
                    Set.copyOf(skills)));
        } catch (IOException invalid) {
            return Optional.empty();
        }
    }

    /** Skill names whose {@code SKILL.md} is present on disk, regardless of the manifest. */
    public Set<String> installedNames() {
        Set<String> names = new TreeSet<>();
        if (!Files.isDirectory(packRoot)) {
            return names;
        }
        for (String name : LarkSkillCatalog.NAMES) {
            if (Files.isRegularFile(packRoot.resolve(name).resolve("SKILL.md"))) {
                names.add(name);
            }
        }
        return names;
    }

    /** Whether the managed pack is installed (manifest plus the guard skill), as frozen reports it. */
    public boolean installed() {
        return manifest().isPresent() && installedNames().contains(LarkSkillCatalog.GUARD_SKILL);
    }

    /**
     * Atomically install {@code archive} as version {@code version}.
     *
     * <p>Same-version reinstalls are idempotent but still re-verify content. A failed validation or
     * rename restores the previous pack; user credential directories are never touched.
     */
    public Installed install(Path archive, String version) throws IOException {
        return install(archive, version, () -> {});
    }

    /**
     * Install and then run {@code afterReplace} while the previous pack is still available as a
     * rollback. A failure in the callback restores the previous pack before propagating.
     */
    public Installed install(Path archive, String version, CheckedRunnable afterReplace) throws IOException {
        if (archive == null || !Files.isRegularFile(archive)) {
            throw new java.io.FileNotFoundException("Lark CLI skills archive not found: " + archive);
        }
        if (Files.size(archive) > MAX_ARCHIVE_BYTES) {
            throw new IllegalArgumentException("Lark CLI source archive is too large.");
        }
        String resolvedVersion = LarkCliProcessGateway.normalizeVersionTag(version);
        if (resolvedVersion == null) {
            throw new IllegalArgumentException("Invalid Lark CLI version tag: " + version);
        }
        return withInstallLock(() -> installLocked(archive, resolvedVersion, afterReplace));
    }

    /** The same narrow publication lock also covers CLI preparation and version observation. */
    <T> T withInstallLock(CheckedSupplier<T> action) throws IOException {
        Object jvmLock = JVM_LOCKS.computeIfAbsent(packRoot.getParent(), ignored -> new Object());
        synchronized (jvmLock) {
            Files.createDirectories(integrationRoot);
            Path lockFile = integrationRoot.resolve(".lark.install.lock");
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock ignored = channel.lock()) {
                return action.get();
            }
        }
    }

    Installed installLocked(Path archive, String version, CheckedRunnable afterReplace) throws IOException {
        if (!Files.isRegularFile(archive) || Files.size(archive) > MAX_ARCHIVE_BYTES) throw new IOException("Invalid Lark archive");
        Optional<Manifest> previous = manifest();
        String previousContent = previous.map(Manifest::contentSha256).orElse(null);

        Path stagingParent = Files.createTempDirectory(integrationRoot, ".installing-lark-");
        Path stagingTarget = stagingParent.resolve("skills");
        Path backup = null;
        boolean published = false;
        try {
            Files.createDirectories(stagingTarget);
            Set<String> extracted;
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                extracted = extract(zip, stagingTarget);
            }
            validate(stagingTarget, extracted);
            appendGuidance(stagingTarget);
            String contentSha = contentSha256(stagingTarget, extracted);
            writeManifest(stagingTarget, extracted, version, contentSha, archive);

            if (Files.exists(packRoot)) {
                backup = integrationRoot.resolve(".replacing-lark-" + UUID.randomUUID());
                AtomicMoves.create(packRoot, backup);
            }
            AtomicMoves.create(stagingTarget, packRoot);
            published = true;
            afterReplace.run();
            if (backup != null) {
                deleteQuietly(backup);
            }
            boolean changed = previousContent != null && !previousContent.equals(contentSha);
            return new Installed(new TreeSet<>(extracted), version, contentSha, changed);
        } catch (IOException | RuntimeException failure) {
            if (backup != null && Files.exists(backup)) {
                deleteQuietly(packRoot);
                try {
                    AtomicMoves.create(backup, packRoot);
                    afterReplace.run();
                } catch (IOException restoreFailure) {
                    failure.addSuppressed(restoreFailure);
                }
            } else if (published) {
                // No previous pack to restore: leaving the new bytes behind would expose a pack
                // whose discovery/reload already failed.
                deleteQuietly(packRoot);
                try { afterReplace.run(); } catch (IOException | RuntimeException restoreFailure) {
                    failure.addSuppressed(restoreFailure);
                }
            }
            throw failure;
        } finally {
            deleteQuietly(stagingParent);
        }
    }

    private static Set<String> extract(ZipFile zip, Path destination) throws IOException {
        Set<String> extracted = new TreeSet<>();
        Path root = destination.toRealPath();
        long written = 0;
        byte[] license = null;
        for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
            if (entry.getName().matches("[^/]+/LICENSE")) {
                try (InputStream input = zip.getInputStream(entry)) { license = input.readNBytes(65537); }
                if (license.length == 0 || license.length > 65536) throw new IOException("Invalid official Lark license");
            }
        }
        if (license == null) throw new IOException("Official Lark archive license is missing");
        for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
            if (entry.isDirectory()) {
                continue;
            }
            // Extraction writes every member as a regular file through a stream, so a symlink
            // member can never materialize as a link; structural names are still rejected.
            if (isUnsafe(entry.getName())) {
                throw new IllegalArgumentException("Unsafe Lark CLI archive member: " + entry.getName());
            }
            Resolved resolved = resolveMember(entry.getName());
            if (resolved == null) {
                continue;
            }
            Path target = root.resolve(resolved.skill()).resolve(resolved.relative()).normalize();
            if (!target.startsWith(root)) {
                throw new IllegalArgumentException("Archive member escapes destination: " + entry.getName());
            }
            Files.createDirectories(target.getParent());
            try (InputStream input = zip.getInputStream(entry)) {
                byte[] buffer = new byte[65536];
                boolean first = true;
                try (var output = Files.newOutputStream(target)) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (first && read > 0 && hasExecutableImagePrefix(buffer, read)) {
                            throw new IllegalArgumentException(
                                    "Archive contains executable binary member: " + entry.getName());
                        }
                        first = false;
                        written += read;
                        if (written > MAX_EXTRACTED_BYTES) {
                            throw new IllegalArgumentException("Lark CLI skills archive expands to too much data.");
                        }
                        output.write(buffer, 0, read);
                    }
                }
            }
            extracted.add(resolved.skill());
        }
        Files.write(root.resolve("LICENSE"), license);
        for (String name : extracted) Files.write(root.resolve(name).resolve("LICENSE.txt"), license);
        return extracted;
    }

    private static void validate(Path root, Set<String> extracted) throws IOException {
        List<String> missing = LarkSkillCatalog.NAMES.stream().filter(name -> !extracted.contains(name)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Lark CLI archive is missing required skills: " + String.join(", ", missing));
        }
        for (String name : LarkSkillCatalog.NAMES) {
            Path skillFile = root.resolve(name).resolve("SKILL.md");
            if (!Files.isRegularFile(skillFile)) {
                throw new IllegalArgumentException("Invalid Lark skill metadata: " + name + "/SKILL.md");
            }
            String text = Files.readString(skillFile, StandardCharsets.UTF_8);
            Matcher matcher = FRONTMATTER_NAME.matcher(text);
            if (!matcher.find() || !matcher.group(1).equals(name)) {
                throw new IllegalArgumentException("Lark skill directory '" + name + "' declares a different name");
            }
        }
    }

    private static void appendGuidance(Path root) throws IOException {
        Path skillFile = root.resolve(LarkSkillCatalog.GUARD_SKILL).resolve("SKILL.md");
        String content = Files.readString(skillFile, StandardCharsets.UTF_8);
        if (content.contains(GUIDANCE_MARKER)) {
            return;
        }
        int legacy = content.indexOf("<!-- haifa-lark-cli-auth-guidance-");
        if (legacy >= 0) {
            content = content.substring(0, legacy).stripTrailing();
        }
        String guidance;
        try (InputStream resource = LarkSkillPackInstaller.class.getResourceAsStream(GUIDANCE_RESOURCE)) {
            if (resource == null) {
                throw new IOException("Bundled Lark authorization guidance resource is unavailable");
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            resource.transferTo(buffer);
            guidance = buffer.toString(StandardCharsets.UTF_8).strip();
        }
        Files.writeString(skillFile, content.stripTrailing() + "\n\n" + guidance + "\n", StandardCharsets.UTF_8);
    }

    private static String contentSha256(Path root, Set<String> names) throws IOException {
        MessageDigest digest = sha256();
        for (String name : new TreeSet<>(names)) {
            Path directory = root.resolve(name);
            List<Path> files;
            try (var walk = Files.walk(directory)) {
                files = walk.filter(Files::isRegularFile).sorted().toList();
            }
            for (Path file : files) {
                digest.update(root.relativize(file).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(file));
                digest.update((byte) 0);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void writeManifest(Path root, Set<String> skills, String version, String contentSha, Path archive) throws IOException {
        ObjectNode manifest = json.createObjectNode();
        manifest.put("provider", "lark-cli");
        manifest.put("version", version);
        manifest.put("source", archiveUrl(version));
        manifest.put("content_sha256", contentSha);
        manifest.put("license", "See LICENSE and per-package LICENSE.txt");
        manifest.put("license_sha256", HexFormat.of().formatHex(sha256().digest(Files.readAllBytes(root.resolve("LICENSE")))));
        MessageDigest archiveDigest = sha256();
        try (var input = Files.newInputStream(archive)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) >= 0) archiveDigest.update(buffer, 0, count);
        }
        manifest.put("archive_sha256", HexFormat.of().formatHex(archiveDigest.digest()));
        manifest.put("platforms", "gateway-host (skill content is platform-neutral)");
        manifest.put("installed_at", Instant.now().toString());
        var names = manifest.putArray("skills");
        new TreeSet<>(skills).forEach(names::add);
        Path target = root.resolve(MANIFEST_FILE);
        Path temporary = root.resolve(MANIFEST_FILE + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.writeString(temporary, json.writerWithDefaultPrettyPrinter().writeValueAsString(manifest) + "\n",
                    StandardCharsets.UTF_8);
            AtomicMoves.replace(temporary, target);
        } finally {
            AtomicMoves.deleteQuietly(temporary);
        }
    }

    /** Official content archive URL used for the manifest and the default download. */
    public static String archiveUrl(String version) {
        String tag = LarkCliProcessGateway.normalizeVersionTag(version);
        if (tag == null) {
            throw new IllegalArgumentException("Invalid Lark CLI version tag: " + version);
        }
        return "https://codeload.github.com/" + LarkSkillCatalog.GITHUB_REPOSITORY + "/zip/refs/tags/" + tag;
    }

    private static Resolved resolveMember(String rawName) {
        String normalized = normalizeMemberName(rawName);
        if (normalized.isEmpty() || normalized.equals(".") || normalized.startsWith("../")) {
            return null;
        }
        List<String> parts = new ArrayList<>(List.of(normalized.split("/")));
        int skillsIndex = parts.indexOf("skills");
        String skill;
        List<String> relative;
        if (skillsIndex >= 0) {
            if (parts.size() <= skillsIndex + 2) {
                return null;
            }
            skill = parts.get(skillsIndex + 1);
            relative = parts.subList(skillsIndex + 2, parts.size());
        } else if (!parts.isEmpty() && LarkSkillCatalog.NAME_SET.contains(parts.get(0))) {
            skill = parts.get(0);
            relative = parts.subList(1, parts.size());
        } else {
            return null;
        }
        if (!LarkSkillCatalog.NAME_SET.contains(skill) || relative.isEmpty()) {
            return null;
        }
        for (String part : relative) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("Unsafe Lark skill archive member: " + rawName);
            }
        }
        return new Resolved(skill, String.join("/", relative));
    }

    /** Collapse repeated separators and drop {@code .} segments without touching {@code ..}. */
    static String normalizeMemberName(String rawName) {
        String text = rawName.replace('\\', '/');
        List<String> parts = new ArrayList<>();
        for (String part : text.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            parts.add(part);
        }
        return String.join("/", parts);
    }

    private static boolean isUnsafe(String name) {
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
            return true;
        }
        for (String part : normalized.split("/")) {
            if (part.equals("..")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasExecutableImagePrefix(byte[] buffer, int length) {
        if (length >= 4 && buffer[0] == 0x7F && buffer[1] == 'E' && buffer[2] == 'L' && buffer[3] == 'F') {
            return true;
        }
        if (length >= 2 && buffer[0] == 'M' && buffer[1] == 'Z') {
            return true;
        }
        if (length >= 4) {
            int magic = ((buffer[0] & 0xFF) << 24) | ((buffer[1] & 0xFF) << 16) | ((buffer[2] & 0xFF) << 8) | (buffer[3] & 0xFF);
            return magic == 0xFEEDFACE || magic == 0xFEEDFACF || magic == 0xCAFEBABE || magic == 0xCEFAEDFE;
        }
        return false;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            if (!Files.exists(path)) {
                return;
            }
            if (Files.isDirectory(path)) {
                try (var walk = Files.walk(path)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(candidate -> {
                        try {
                            Files.deleteIfExists(candidate);
                        } catch (IOException ignored) {
                            // Best-effort staging cleanup.
                        }
                    });
                }
            } else {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // Best-effort staging cleanup.
        }
    }

    /** Convenience map for diagnostics: name to declared version/digest. */
    public Map<String, String> diagnostics() {
        Map<String, String> result = new LinkedHashMap<>();
        manifest().ifPresent(value -> {
            result.put("version", value.version());
            result.put("content_sha256", value.contentSha256());
        });
        return result;
    }

    private record Resolved(String skill, String relative) {}

    /** Publication callback that may fail and roll the installed pack back. */
    @FunctionalInterface
    public interface CheckedRunnable {
        void run() throws IOException;
    }

    @FunctionalInterface
    interface CheckedSupplier<T> { T get() throws IOException; }
}
