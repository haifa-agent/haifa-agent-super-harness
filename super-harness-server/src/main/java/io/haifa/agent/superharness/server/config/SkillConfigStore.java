package io.haifa.agent.superharness.server.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.skill.api.SkillAvailability;
import io.haifa.agent.skill.api.SkillOrigin;
import io.haifa.agent.skill.api.SkillParserMode;
import io.haifa.agent.skill.api.SkillScopeRef;
import io.haifa.agent.skill.api.SkillSourceDescriptor;
import io.haifa.agent.skill.api.SkillSourceRef;
import io.haifa.agent.skill.core.LocalDirectorySkillSource;
import io.haifa.agent.skill.core.SkillPackageLimits;
import io.haifa.agent.skill.core.SkillPackageParser;
import io.haifa.agent.superharness.workspace.AtomicMoves;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Configured directory or trusted immutable manifest plus one enable-state file. The SDK owns
 * discovery, binding, activation and Run persistence; this store owns no Skill Runtime.
 */
public final class SkillConfigStore {
    private static final String MANIFEST = "third-party/deerflow/skills/manifest.json";
    private static final System.Logger RECOVERY_LOG =
            System.getLogger(SkillConfigStore.class.getName() + ".recovery");
    private static final System.Logger MANAGED_LOG =
            System.getLogger(SkillConfigStore.class.getName() + ".managed-integration");
    /** Bounded length of a persisted Skill name echoed into a recovery diagnostic. */
    private static final int DIAGNOSTIC_NAME_LENGTH = 64;

    public static Path repositoryRootFromWorkingDirectory() throws IOException {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.isRegularFile(current.resolve(MANIFEST))) return current;
        if (current.getFileName() != null && current.getFileName().toString().equals("super-harness-server")
                && current.getParent() != null && Files.isRegularFile(current.getParent().resolve(MANIFEST))) {
            return current.getParent();
        }
        throw new IOException("trusted Skill manifest is unavailable from the repository working directory");
    }

    private final ObjectMapper json;
    private final Path repositoryRoot;
    private final Path externalSkillsDirectory;
    private final Path managedIntegrationsRoot;
    private final Path stateFile;
    private final CustomSkillPackages custom;
    private volatile State state;

    public SkillConfigStore(ObjectMapper json, Path dataDirectory, Path repositoryRoot) throws IOException {
        this(json, dataDirectory, repositoryRoot, configuredDirectory(json, dataDirectory, repositoryRoot));
    }

    public SkillConfigStore(ObjectMapper json, Path dataDirectory, Path repositoryRoot, Path externalSkillsDirectory) throws IOException {
        this.json = json;
        this.repositoryRoot = repositoryRoot.toAbsolutePath().normalize();
        this.externalSkillsDirectory = externalSkillsDirectory != null ? externalSkillsDirectory.toAbsolutePath().normalize() : null;
        // Product-managed first-party integration packs (today: the official Lark office pack).
        // They are discovered through this same store; no parallel Skill registry exists.
        this.managedIntegrationsRoot = dataDirectory.toAbsolutePath().normalize()
                .resolve("integrations").resolve("lark").resolve("skills");
        this.stateFile = dataDirectory.toAbsolutePath().normalize().resolve("skills_config.json");
        this.custom = new CustomSkillPackages(json, dataDirectory);
        reload();
    }

    public static Path resolveExternalSkillsDirectory(Path repositoryRoot) throws IOException {
        String prop = System.getProperty("haifa.superharness.skills.dir");
        if (prop != null && !prop.isBlank()) {
            return requireExternalDirectory(Path.of(prop));
        }
        String env = System.getenv("HAIFA_SUPER_HARNESS_SKILLS_DIR");
        if (env != null && !env.isBlank()) {
            return requireExternalDirectory(Path.of(env));
        }
        return null;
    }

    private static Path configuredDirectory(ObjectMapper json, Path dataDirectory, Path repositoryRoot) throws IOException {
        Path explicit = resolveExternalSkillsDirectory(repositoryRoot);
        if (explicit != null) return explicit;
        Path file = dataDirectory.toAbsolutePath().normalize().resolve("skills_config.json");
        if (!Files.exists(file)) return null;
        if (Files.isSymbolicLink(file) || Files.size(file) > 32 * 1024 * 1024) {
            throw new IOException("Skill configuration is unsafe or too large");
        }
        JsonNode config = json.readTree(file.toFile());
        JsonNode directory = config == null ? null : config.get("external_directory");
        if (directory == null) return null;
        if (!directory.isTextual() || directory.asText().isBlank() || !Path.of(directory.asText()).isAbsolute()) {
            throw new IOException("external_directory must be an absolute Skill directory");
        }
        return requireExternalDirectory(Path.of(directory.asText()));
    }

    private static Path requireExternalDirectory(Path directory) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IOException("Configured Skill directory is unavailable: " + root);
        for (Path ancestor = root; ancestor != null; ancestor = ancestor.getParent()) {
            if (Files.isSymbolicLink(ancestor) || Files.readAttributes(ancestor,
                    java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS).isOther()) {
                throw new IOException("Configured Skill directory contains a link: " + root);
            }
        }
        return root;
    }

    private Path externalScanRoot() throws IOException {
        Path root = requireExternalDirectory(externalSkillsDirectory);
        return Files.isDirectory(root.resolve("public")) ? requireExternalDirectory(root.resolve("public")) : root;
    }

    private Path externalPackageDirectory(String name) throws IOException {
        SkillPackageFiles.name(name);
        try (var entries = Files.list(externalScanRoot())) {
            for (Path directory : entries.filter(Files::isDirectory).sorted().toList()) {
                requireExternalDirectory(directory);
                Path markdown = directory.resolve("SKILL.md");
                if (!Files.isRegularFile(markdown)) continue;
                Map<String, byte[]> files = externalPackageFiles(directory);
                var parser = externalParser();
                var parsed = parser.parseFiles(directory.getFileName().toString(), files,
                        externalDescriptor(directory.getFileName().toString(), SkillPackageFiles.digest(files)));
                if (parsed.parsed().isEmpty()) throw new IOException("External Skill failed parsing: " + directory.getFileName());
                if (parsed.parsed().orElseThrow().metadata().name().value().equals(name)) return directory;
            }
        }
        throw new java.io.FileNotFoundException("External Skill not found: " + name);
    }

    public synchronized void reload() throws IOException {
        State next = candidate(readConfiguration());
        retainExternalFiles(next);
        state = next;
    }

    public synchronized void setEnabled(String name, boolean enabled) throws IOException {
        State before = state;
        if (before.packages().stream().noneMatch(pkg -> pkg.active() && pkg.name().equals(name))) {
            throw new IllegalArgumentException("Skill '" + name + "' is not registered");
        }
        ObjectNode payload = readConfiguration();
        if (enabled) {
            SkillRunTools.Package pkg = before.packages().stream().filter(value -> value.active() && value.name().equals(name)).findFirst().orElseThrow();
            if (!pkg.sourceName().equals("user-installed")) {
                var registration = pkg.source().discover(SkillReviewApprovals.discovery()).registrations().getFirst();
                if (registration.packageIndex().resources().stream().anyMatch(resource -> resource.relativePath().startsWith("scripts/"))) {
                    String key = pkg.name() + "@" + pkg.version();
                    if (!payload.path("bundled_reviews").has(key)) {
                        Map<String, byte[]> files = reviewFiles(name);
                        // The screen only reads the frozen target. Its decision is bound to the exact SDK digest.
                        Path target = null;
                        if (externalSkillsDirectory == null) for (JsonNode entry : json.readTree(repositoryRoot.resolve(MANIFEST).toFile()).path("packages")) {
                            if (entry.path("name").asText().equals(name) && entry.path("version").asText().equals(pkg.version())) {
                                target = repositoryRoot.resolve(entry.path("root").asText()).resolve(name);
                            }
                        }
                        if (externalSkillsDirectory != null) target = externalPackageDirectory(name);
                        if (target == null) throw new IOException("Frozen Skill review target is unavailable");
                        JsonNode result = custom.screen(target, files);
                        payload.withObject("bundled_reviews").set(key, SkillReviewApprovals.evidence(json, registration, result));
                    }
                }
            }
        }
        payload.withObject("enabled").put(name, enabled);
        publish(payload);
    }

    private ObjectNode readConfiguration() throws IOException {
        if (!Files.exists(stateFile)) {
            ObjectNode result = json.createObjectNode(); result.put("schema_version", 1); result.putObject("enabled");
            return result;
        }
        if (Files.isSymbolicLink(stateFile) || Files.size(stateFile) > 32 * 1024 * 1024) throw new IOException("Skill configuration is unsafe or too large");
        JsonNode stored = json.readTree(stateFile.toFile());
        if (stored == null || !stored.isObject() || stored.path("schema_version").asInt(-1) != 1 || !stored.path("enabled").isObject()) {
            throw new IllegalStateException("Skill enable configuration is invalid");
        }
        return (ObjectNode) stored;
    }

    private State candidate(ObjectNode config) throws IOException {
        List<SkillRunTools.Package> users = custom.packages(config);
        Set<String> overrides = users.stream().filter(SkillRunTools.Package::active).map(SkillRunTools.Package::name)
                .collect(java.util.stream.Collectors.toSet());
        List<SkillRunTools.Package> packages = new ArrayList<>();
        List<SkillRunTools.Package> bundled = externalSkillsDirectory != null
                ? loadExternalSkills()
                : readManifest();
        if (externalSkillsDirectory != null) {
            bundled = new ArrayList<>(bundled);
            Set<String> versions = bundled.stream().map(pkg -> pkg.name() + "@" + pkg.version())
                    .collect(java.util.stream.Collectors.toSet());
            for (SkillRunTools.Package legacy : readManifest()) {
                if (versions.add(legacy.name() + "@" + legacy.version())) {
                    bundled.add(new SkillRunTools.Package(legacy.name(), legacy.description(), legacy.category(),
                            legacy.license(), legacy.sourceName(), legacy.sourceSha(), legacy.version(), legacy.contentHash(),
                            false, false, legacy.source()));
                }
            }
        }
        for (SkillRunTools.Package pkg : bundled) {
            packages.add(overrides.contains(pkg.name()) ? new SkillRunTools.Package(pkg.name(), pkg.description(), pkg.category(),
                    pkg.license(), pkg.sourceName(), pkg.sourceSha(), pkg.version(), pkg.contentHash(), false,
                    pkg.enabledByDefault(), pkg.source()) : pkg);
        }
        Set<String> knownNames = packages.stream().map(SkillRunTools.Package::name)
                .collect(java.util.stream.Collectors.toSet());
        for (SkillRunTools.Package managed : loadManagedIntegrationSkills()) {
            if (knownNames.add(managed.name())) {
                packages.add(managed);
            } else {
                MANAGED_LOG.log(System.Logger.Level.WARNING,
                        "Managed integration Skill ignored because its name is already registered: "
                                + managed.name());
            }
        }
        packages.addAll(users);
        Map<String, Boolean> enabled = readEnabled(packages, config);
        List<io.haifa.agent.skill.api.SkillPackageReviewGrant> grants = new ArrayList<>(custom.approvals(config, packages));
        for (SkillRunTools.Package pkg : packages) {
            if (pkg.sourceName().equals("user-installed")) continue;
            JsonNode evidence = config.path("bundled_reviews").path(pkg.name() + "@" + pkg.version());
            if (!evidence.isMissingNode()) {
                grants.add(SkillReviewApprovals.grant(
                        pkg.source().discover(SkillReviewApprovals.discovery()).registrations().getFirst(), evidence));
            }
        }
        var trust = SkillReviewApprovals.snapshot(grants);
        SkillRunTools.projection(packages, enabled, SkillRunTools.Selection.all(), trust);
        return new State(packages, enabled, trust);
    }

    private void publish(ObjectNode payload) throws IOException {
        State next = candidate(payload);
        retainExternalFiles(next);
        byte[] bytes = json.writeValueAsBytes(payload);
        if (bytes.length > 32 * 1024 * 1024) throw new IOException("Skill configuration exceeds its publication limit");
        Files.createDirectories(stateFile.getParent());
        Path temporary = stateFile.resolveSibling(stateFile.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temporary, bytes);
            AtomicMoves.replace(temporary, stateFile);
            state = next;
        } finally {
            AtomicMoves.deleteQuietly(temporary);
        }
    }

    /** Configure the existing product write screen; lack of a scanner rejects all custom writes. */
    public synchronized void configureWriteScreening(SkillPackageScreening.WriteScreen screen) {
        custom.screening(screen);
    }

    /**
     * Records a human authorization for an exact custom Skill package revision. The record only
     * relaxes the per-file semantic review for that digest; the deterministic static gate still runs.
     */
    /** Identity of an uploaded archive, so an operator can authorize the exact revision before install. */
    public synchronized java.util.Map<String, Object> previewArchive(byte[] archive) throws IOException {
        return custom.preview(archive);
    }

    public synchronized ObjectNode authorizePackage(String name, String revision, String authorizedBy)
            throws IOException {
        var normalized = SkillPackageFiles.name(name);
        if (revision == null || !revision.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Authorization revision must be the 64-hex package digest");
        }
        if (authorizedBy == null || authorizedBy.isBlank()) {
            throw new IllegalArgumentException("authorized_by is required");
        }
        ObjectNode payload = readConfiguration();
        ObjectNode entry = payload.withObject("authorized_packages").withObject(normalized);
        entry.put("revision", revision);
        entry.put("authorized_by", authorizedBy);
        entry.put("authorized_at", java.time.Instant.now().toString());
        entry.put("scope", "semantic-review");
        publish(payload);
        return payload.path("authorized_packages").path(normalized).deepCopy();
    }

    public synchronized String install(byte[] archive, String threadId) throws IOException {
        ObjectNode config = readConfiguration();
        String name = custom.install(config, archive, "human", threadId);
        publish(config);
        return name;
    }

    public synchronized void manage(String action, String name, String content, String path, String find,
            String replacement, Integer expectedCount, String author, String threadId) throws IOException {
        manage(action, name, content, path, find, replacement, expectedCount, author, threadId, () -> {});
    }

    public synchronized void manage(String action, String name, String content, String path, String find,
            String replacement, Integer expectedCount, String author, String threadId, Runnable admission) throws IOException {
        admission.run();
        ObjectNode config = readConfiguration();
        custom.manage(config, action, name, content, path, find, replacement, expectedCount, author, threadId, admission);
        admission.run();
        publish(config);
    }

    public synchronized Map<String, Object> customDetail(String name) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>(detail(name)
                .filter(row -> Boolean.TRUE.equals(row.get("editable")))
                .orElseThrow(() -> new java.io.FileNotFoundException("Custom Skill not found")));
        result.put("content", new String(custom.current(readConfiguration(), name).get("SKILL.md"), java.nio.charset.StandardCharsets.UTF_8));
        return result;
    }

    public synchronized List<JsonNode> history(String name) throws IOException {
        JsonNode rows = readConfiguration().path("custom").path(SkillPackageFiles.name(name)).path("history");
        if (!rows.isArray()) throw new java.io.FileNotFoundException("Custom Skill not found");
        List<JsonNode> result = new ArrayList<>(); rows.forEach(row -> result.add(row.deepCopy()));
        return List.copyOf(result);
    }

    public synchronized void rollback(String name, int index) throws IOException {
        ObjectNode config = readConfiguration(); custom.rollback(config, name, index); publish(config);
    }

    public synchronized Map<String, Object> exportManifest(String name) throws IOException {
        return SkillPackageFiles.exportManifest(name, custom.currentPackage(readConfiguration(), name));
    }

    public synchronized byte[] exportArchive(String name, String expectedRevision) throws IOException {
        if (expectedRevision == null || !expectedRevision.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("A valid expected revision is required");
        var content = custom.currentPackage(readConfiguration(), name);
        var manifest = SkillPackageFiles.exportManifest(name, content);
        if (!Boolean.TRUE.equals(manifest.get("can_export"))) throw new IllegalStateException("skill_export_unsupported");
        if (!expectedRevision.equals(manifest.get("revision"))) throw new IllegalStateException("skill_changed");
        return SkillPackageFiles.export(name, content);
    }

    public synchronized Map<String, byte[]> reviewFiles(String name) throws IOException {
        SkillPackageFiles.name(name);
        if (detail(name).map(row -> Boolean.TRUE.equals(row.get("editable"))).orElse(false)) return custom.current(readConfiguration(), name);
        if (externalSkillsDirectory != null) return externalPackageFiles(externalPackageDirectory(name));
        for (JsonNode entry : json.readTree(repositoryRoot.resolve(MANIFEST).toFile()).path("packages")) {
            if (entry.path("name").asText().equals(SkillPackageFiles.name(name)) && entry.path("active").asBoolean()) {
                Path root = repositoryRoot.resolve(entry.path("root").asText());
                validateFiles(root, entry.path("files"), name);
                return SkillPackageFiles.directory(root.resolve(name));
            }
        }
        throw new java.io.FileNotFoundException("Skill not found");
    }

    public synchronized Map<String, byte[]> installedReviewFiles(String category, String relative) throws IOException {
        if (!Set.of("public", "custom", "legacy").contains(category)) throw new IllegalArgumentException("Invalid installed Skill category");
        String[] path = relative.split("/", 2); String name = SkillPackageFiles.name(path[0]);
        Map<String, byte[]> files;
        if (category.equals("custom")) files = custom.current(readConfiguration(), name);
        else if (category.equals("public")) {
            files = null;
            if (externalSkillsDirectory != null) files = externalPackageFiles(externalPackageDirectory(name));
            if (files == null) {
                for (JsonNode entry : json.readTree(repositoryRoot.resolve(MANIFEST).toFile()).path("packages")) {
                    if (entry.path("name").asText().equals(name) && entry.path("category").asText().equals("public") && entry.path("active").asBoolean()) {
                        Path root = repositoryRoot.resolve(entry.path("root").asText());
                        validateFiles(root, entry.path("files"), name); files = SkillPackageFiles.directory(root.resolve(name)); break;
                    }
                }
            }
            if (files == null) throw new java.io.FileNotFoundException("Public Skill not found");
        } else throw new java.io.FileNotFoundException("Legacy Skill is not installed");
        if (path.length == 1) return files;
        String prefix = SkillPackageFiles.path(path[1]) + "/";
        Map<String, byte[]> nested = new TreeMap<>();
        files.forEach((key, value) -> { if (key.startsWith(prefix)) nested.put(key.substring(prefix.length()), value); });
        if (!nested.containsKey("SKILL.md")) throw new java.io.FileNotFoundException("Installed review target has no root SKILL.md");
        return nested;
    }

    public SkillRunTools.Projection projection(SkillRunTools.Selection selection) {
        State current = state;
        return currentReviewLoader(SkillRunTools.projection(current.packages(), current.enabled(), selection, current.trust()));
    }

    /** Explicit operator withdrawal, distinct from the gallery's enable toggle. */
    public synchronized void revokePackageReview(String name) throws IOException {
        SkillPackageFiles.name(name);
        ObjectNode config = readConfiguration();
        JsonNode versions = config.path("custom").path(name).path("versions");
        if (!versions.isArray()) throw new java.io.FileNotFoundException("Custom Skill not found");
        for (JsonNode version : versions) ((ObjectNode) version.path("review")).put("revoked", true);
        config.withObject("enabled").put(name, false);
        publish(config);
    }

    private SkillRunTools.Projection currentReviewLoader(SkillRunTools.Projection projection) {
        // Publication is a product concern; validity remains the public SDK grant's matches() contract.
        // Consult current authority even when this loader belongs to an older frozen Run.
        // The wrapper must implement BOTH seams the trusted Skill script chain reads through:
        // load(...) for the activation content and loadResourceBytes(...) for the raw package
        // bytes the platform materializes the frozen entry execution directory from. The
        // interface default byte view is derived from the readable activation content, not the
        // frozen package bytes, so relying on it would break the entry-file execution path.
        io.haifa.agent.skill.api.SkillContentLoader loader = new io.haifa.agent.skill.api.SkillContentLoader() {
            @Override
            public io.haifa.agent.skill.api.SkillContent load(
                    io.haifa.agent.skill.api.FrozenSkillBinding binding,
                    io.haifa.agent.skill.api.SkillVisibilityContext visibility) {
                requireReviewAuthority(binding, visibility);
                return projection.loader().load(binding, visibility);
            }

            @Override
            public java.util.Optional<byte[]> loadResourceBytes(
                    io.haifa.agent.skill.api.FrozenSkillBinding binding,
                    io.haifa.agent.skill.api.SkillVisibilityContext visibility,
                    String resource) {
                requireReviewAuthority(binding, visibility);
                return projection.loader().loadResourceBytes(binding, visibility, resource);
            }
        };
        return new SkillRunTools.Projection(projection.catalog(), loader, projection.allowedSkills(), projection.fingerprint(),
                projection.selectedPackages(), projection.recovery(), projection.trust());
    }

    private void requireReviewAuthority(
            io.haifa.agent.skill.api.FrozenSkillBinding binding,
            io.haifa.agent.skill.api.SkillVisibilityContext visibility) {
        binding.packageReviewGrantId().ifPresent(id -> {
            var subject = new io.haifa.agent.skill.api.SkillTrustSubject(visibility.tenant(), visibility.principal(),
                    SkillReviewApprovals.PRODUCT, visibility.project().map(io.haifa.agent.core.reference.ProjectRef::projectId));
            if (state.trust().packageReviewGrants().stream().noneMatch(grant -> grant.id().equals(id)
                    && grant.matches(binding, subject, java.time.Instant.now()))) {
                throw new SecurityException("Skill package review authorization is unavailable or revoked");
            }
        });
    }

    public SkillRunTools.Projection recoveryProjection(SkillRunTools.Selection selection) {
        return recoveryProjection(selection, Map.of());
    }

    public SkillRunTools.Projection recoveryProjection(SkillRunTools.Selection selection, Map<String, String> versions) {
        State current = state;
        List<SkillRunTools.Package> restoredPackages = current.packages();
        if (!versions.isEmpty()) {
            List<SkillRunTools.Package> pinned = current.packages().stream().map(pkg -> new SkillRunTools.Package(pkg.name(), pkg.description(),
                    pkg.category(), pkg.license(), pkg.sourceName(), pkg.sourceSha(), pkg.version(), pkg.contentHash(),
                    (pkg.source().descriptor().reference().sourceId() + "@" + pkg.version()).equals(versions.get(pkg.name())),
                    pkg.enabledByDefault(), pkg.source())).toList();
            // A Thread records the exact packages it admitted. When one of them no longer exists in
            // this catalog, the Thread keeps its history: the missing package (and any package whose
            // version no longer matches) stays inactive, so no Run can silently bind a different
            // version, and only the affected Tool registration is lost.
            List<String> unavailable = versions.keySet().stream()
                    .filter(name -> pinned.stream().noneMatch(pkg -> pkg.active() && pkg.name().equals(name)))
                    .map(SkillConfigStore::diagnosticName)
                    .sorted()
                    .toList();
            if (!unavailable.isEmpty()) {
                RECOVERY_LOG.log(System.Logger.Level.WARNING,
                        "Persisted Skill package version is unavailable; recovering this Thread without"
                                + " those Skill Tools: " + unavailable);
            }
            restoredPackages = pinned;
        }
        // Restore the admitted product declaration, irrespective of later UI enable toggles.
        // Immutable package sources and the retained loader remain SDK inputs.
        Map<String, Boolean> original = new LinkedHashMap<>();
        current.packages().forEach(pkg -> original.put(pkg.name(), selection.admits(pkg.name())));
        SkillRunTools.Projection restored = SkillRunTools.projection(restoredPackages, original, selection, current.trust());
        // Thread metadata records the actually admitted Skill names. An explicit empty list means
        // no Skill Tools were registered: adding them changes every frozen Tool's catalog digest.
        // Nonempty and legacy-unknown declarations retain the historical recovery registrations.
        if (selection.names().isPresent() && selection.names().orElseThrow().isEmpty()) return currentReviewLoader(restored);
        return currentReviewLoader(restored.forRecovery());
    }

    private static String diagnosticName(String name) {
        String single = name.replace('\n', ' ').replace('\r', ' ');
        return single.length() > DIAGNOSTIC_NAME_LENGTH
                ? single.substring(0, DIAGNOSTIC_NAME_LENGTH) + "..."
                : single;
    }

    public List<Map<String, Object>> list() {
        State current = state;
        List<Map<String, Object>> response = new ArrayList<>();
        for (SkillRunTools.Package pkg : current.packages()) {
            if (!pkg.active()) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", pkg.name());
            row.put("description", pkg.description());
            row.put("category", pkg.category());
            row.put("license", pkg.license());
            row.put("enabled", current.enabled().getOrDefault(pkg.name(), pkg.enabledByDefault()));
            row.put("editable", pkg.sourceName().equals("user-installed"));
            row.put("source", pkg.sourceName());
            row.put("source_sha", pkg.sourceSha());
            row.put("version", pkg.version());
            row.put("sha256", pkg.contentHash());
            response.add(row);
        }
        return List.copyOf(response);
    }

    public Optional<Map<String, Object>> detail(String name) {
        return list().stream().filter(row -> row.get("name").equals(name)).findFirst();
    }

    private static SkillPackageParser externalParser() {
        return new SkillPackageParser(new SkillPackageLimits(
                4096, 32, 64 * 1024 * 1024, 512 * 1024 * 1024, 1024 * 1024, 2000, 20000));
    }

    private static SkillSourceDescriptor externalDescriptor(String directory, String digest) {
        return new SkillSourceDescriptor(new SkillSourceRef(directory, "sha256:" + digest),
                SkillScopeRef.product(), SkillOrigin.BUNDLED, 100, SkillParserMode.COMPATIBLE, true, false);
    }

    /** Normalize only parser input; the source files and raw package identity remain unchanged. */
    private static Map<String, byte[]> externalPackageFiles(Path directory) throws IOException {
        return normalizedExternalFiles(directory, SkillPackageFiles.directory(requireExternalDirectory(directory)));
    }

    private static Map<String, byte[]> normalizedExternalFiles(Path directory, Map<String, byte[]> files) throws IOException {
        byte[] markdown = files.get("SKILL.md");
        if (markdown == null) throw new IOException("External Skill has no SKILL.md: " + directory.getFileName());
        String text = new String(markdown, java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n");
        if (text.startsWith("---\n")) {
            int end = text.indexOf("\n---", 4);
            if (end >= 0) {
                var yaml = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory()
                        .disable(com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature.WRITE_DOC_START_MARKER));
                JsonNode frontmatter = yaml.readTree(text.substring(4, end));
                JsonNode compatibility = frontmatter == null ? null : frontmatter.get("compatibility");
                if (frontmatter instanceof ObjectNode metadata && compatibility != null && compatibility.isObject()) {
                    List<String> requirements = new ArrayList<>();
                    for (var field : compatibility.properties()) {
                        if (!field.getValue().isValueNode()) throw new IOException("Unsupported Skill compatibility mapping");
                        requirements.add(field.getKey() + " " + field.getValue().asText());
                    }
                    metadata.put("compatibility", String.join(", ", requirements));
                    text = "---\n" + yaml.writeValueAsString(metadata) + text.substring(end + 1);
                    files.put("SKILL.md", text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        }
        return files;
    }

    private List<SkillRunTools.Package> loadExternalSkills() throws IOException {
        Path scanRoot = externalScanRoot();
        SkillPackageParser parser = externalParser();
        List<SkillRunTools.Package> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        try (var entries = Files.list(scanRoot)) {
            for (Path directory : entries.filter(Files::isDirectory).sorted().toList()) {
                requireExternalDirectory(directory);
                if (!Files.isRegularFile(directory.resolve("SKILL.md"))) continue;
                String directoryName = SkillPackageFiles.name(directory.getFileName().toString());
                Map<String, byte[]> files = SkillPackageFiles.directory(directory);
                String rawDigest = SkillPackageFiles.digest(files);
                files = normalizedExternalFiles(directory, files);
                SkillSourceDescriptor descriptor = externalDescriptor(directoryName, rawDigest);
                var parsed = parser.parseFiles(directoryName, files, descriptor);
                if (parsed.parsed().isEmpty()) {
                    throw new IOException("External Skill failed parsing: " + directoryName + " diagnostics=" + parsed.diagnostics());
                }
                var metadata = parsed.parsed().orElseThrow().metadata();
                String name = SkillPackageFiles.name(metadata.name().value());
                if (!names.add(name)) throw new IOException("Duplicate external Skill name: " + name);
                result.add(externalPackage(directoryName, rawDigest, files, true));
            }
        }
        if (result.isEmpty()) throw new IOException("Configured Skill directory contains no Skill packages");
        Path retained = externalCache();
        if (Files.exists(retained)) {
            requireExternalDirectory(retained);
            Set<String> current = result.stream().map(pkg -> pkg.name() + "@" + pkg.version())
                    .collect(java.util.stream.Collectors.toSet());
            try (var directories = Files.list(retained)) {
                for (Path directory : directories.sorted().toList()) {
                    requireExternalDirectory(directory);
                    String directoryName = SkillPackageFiles.name(directory.getFileName().toString());
                    try (var versions = Files.list(directory)) {
                        for (Path version : versions.sorted().toList()) {
                            String filename = version.getFileName().toString();
                            if (filename.startsWith(".pending-")) continue;
                            if (!filename.matches("[0-9a-f]{64}\\.zip")) throw new IOException("Invalid retained Skill version");
                            String digest = filename.substring(0, 64);
                            Map<String, byte[]> files = retainedFiles(version, digest);
                            SkillRunTools.Package pkg = externalPackage(directoryName, digest,
                                    normalizedExternalFiles(version, files), false);
                            if (current.add(pkg.name() + "@" + pkg.version())) result.add(pkg);
                        }
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * First-party integration packs materialized by a product installer (today the official Lark
     * office pack). Discovery uses this store's parser and loader; no enable state, name map or
     * lifecycle is duplicated here. An unreadable pack stays inactive instead of failing startup,
     * so a corrupted pack surfaces as "not installed" and a reinstall repairs it.
     */
    private List<SkillRunTools.Package> loadManagedIntegrationSkills() {
        if (managedIntegrationsRoot == null || !Files.isDirectory(managedIntegrationsRoot)) {
            return List.of();
        }
        List<SkillRunTools.Package> result = new ArrayList<>();
        try (var entries = Files.list(managedIntegrationsRoot)) {
            for (Path directory : entries.filter(Files::isDirectory).sorted().toList()) {
                String directoryName = directory.getFileName().toString();
                if (!directoryName.matches("[a-z][a-z0-9-]{0,62}")) continue;
                if (!Files.isRegularFile(directory.resolve("SKILL.md"))) continue;
                try {
                    Map<String, byte[]> files = SkillPackageFiles.directory(directory);
                    String digest = SkillPackageFiles.digest(files);
                    files = normalizedExternalFiles(directory, files);
                    SkillSourceDescriptor descriptor = externalDescriptor(directoryName, digest);
                    SkillPackageParser parser = externalParser();
                    var parsed = parser.parseFiles(directoryName, files, descriptor);
                    if (parsed.parsed().isEmpty()) {
                        throw new IOException("Managed integration Skill failed parsing: " + directoryName);
                    }
                    var metadata = parsed.parsed().orElseThrow().metadata();
                    String name = SkillPackageFiles.name(metadata.name().value());
                    boolean hasScripts = files.keySet().stream().anyMatch(path -> path.startsWith("scripts/"));
                    String license = metadata.license().orElse(
                            files.containsKey("LICENSE.txt") ? "See package LICENSE.txt" : "");
                    result.add(new SkillRunTools.Package(name, metadata.description(), "integration", license,
                            "integration-managed", "sha256:" + digest, "sha256:" + digest,
                            SkillPackageFiles.hash(files.get("SKILL.md")), true, !hasScripts,
                            new io.haifa.agent.skill.core.InMemorySkillSource(descriptor, parser,
                                    SkillAvailability.ENABLED, Map.of(directoryName, files))));
                } catch (IOException | RuntimeException invalid) {
                    MANAGED_LOG.log(System.Logger.Level.WARNING,
                            "Managed integration Skill is unreadable and stays inactive: " + directoryName);
                }
            }
        } catch (IOException unavailable) {
            MANAGED_LOG.log(System.Logger.Level.WARNING,
                    "Managed integration Skill directory is unavailable: " + managedIntegrationsRoot);
        }
        return result;
    }

    private SkillRunTools.Package externalPackage(String directory, String digest, Map<String, byte[]> files,
            boolean active) throws IOException {
        SkillPackageParser parser = externalParser();
        SkillSourceDescriptor descriptor = externalDescriptor(directory, digest);
        var parsed = parser.parseFiles(directory, files, descriptor);
        if (parsed.parsed().isEmpty()) throw new IOException("External Skill failed parsing: " + directory);
        var metadata = parsed.parsed().orElseThrow().metadata();
        boolean hasScripts = files.keySet().stream().anyMatch(path -> path.startsWith("scripts/"));
        String license = metadata.license().orElse(files.containsKey("LICENSE.txt") ? "See package LICENSE.txt" : "");
        return new SkillRunTools.Package(SkillPackageFiles.name(metadata.name().value()), metadata.description(),
                "public", license, "external-directory", "sha256:" + digest, "sha256:" + digest,
                SkillPackageFiles.hash(files.get("SKILL.md")), active, active && !hasScripts,
                new io.haifa.agent.skill.core.InMemorySkillSource(descriptor, parser, SkillAvailability.ENABLED,
                        Map.of(directory, files)));
    }

    private Path externalCache() { return stateFile.getParent().resolve("skills/external/.versions"); }

    private Map<String, byte[]> retainedFiles(Path archive, String digest) throws IOException {
        if (Files.isSymbolicLink(archive) || !Files.isRegularFile(archive, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                || Files.size(archive) > SkillPackageFiles.MAX_ARCHIVE_BYTES) throw new IOException("Retained Skill archive is unsafe");
        try (var input = Files.newInputStream(archive)) {
            Map<String, byte[]> files = SkillPackageFiles.retainedArchive(input.readNBytes(SkillPackageFiles.MAX_ARCHIVE_BYTES + 1));
            if (!SkillPackageFiles.digest(files).equals(digest)) throw new IOException("Retained Skill bytes changed");
            return files;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Retained Skill bytes changed", invalid);
        }
    }

    /** Retain resource bytes for SDK-pinned Runs; no Run state or recovery semantics live here. */
    private void retainExternalFiles(State candidate) throws IOException {
        if (externalSkillsDirectory == null) return;
        Path root = externalCache();
        Path existing = root;
        while (!Files.exists(existing)) existing = existing.getParent();
        requireExternalDirectory(existing);
        Files.createDirectories(root); requireExternalDirectory(root);
        for (SkillRunTools.Package pkg : candidate.packages()) {
            if (!pkg.sourceName().equals("external-directory") || !pkg.active()) continue;
            String directory = pkg.source().descriptor().reference().sourceId();
            Map<String, byte[]> files = SkillPackageFiles.directory(requireExternalDirectory(externalScanRoot().resolve(directory)));
            String digest = SkillPackageFiles.digest(files);
            if (!pkg.version().equals("sha256:" + digest)) throw new IOException("External Skill changed during publication");
            Path parent = root.resolve(directory);
            if (Files.exists(parent)) requireExternalDirectory(parent);
            Files.createDirectories(parent);
            Path target = parent.resolve(digest + ".zip");
            if (Files.exists(target)) {
                retainedFiles(target, digest);
                continue;
            }
            Path pending = parent.resolve(".pending-" + UUID.randomUUID());
            try {
                Files.write(pending, SkillPackageFiles.export(directory, files));
                AtomicMoves.create(pending, target);
            } finally {
                AtomicMoves.deleteQuietly(pending);
            }
        }
    }

    private List<SkillRunTools.Package> readManifest() throws IOException {
        Path trustedRoot = repositoryRoot.resolve("third-party/deerflow/skills").toRealPath();
        JsonNode manifest = json.readTree(repositoryRoot.resolve(MANIFEST).toFile());
        if (manifest == null || manifest.path("schema_version").asInt(-1) != 1
                || !manifest.path("packages").isArray()) {
            throw new IllegalStateException("trusted Skill manifest is invalid");
        }
        List<SkillRunTools.Package> packages = new ArrayList<>();
        Set<String> versions = new HashSet<>();
        Set<String> active = new HashSet<>();
        for (JsonNode entry : manifest.path("packages")) {
            if (!entry.isObject()) throw new IllegalStateException("Skill manifest package must be an object");
            String name = text(entry, "name");
            String category = text(entry, "category");
            String license = text(entry, "license");
            String licenseFile = text(entry, "license_file");
            String licenseHash = text(entry, "license_sha256");
            String sourceName = text(entry, "source");
            String sourceSha = text(entry, "source_sha");
            String version = text(entry, "version");
            String relativeRoot = text(entry, "root");
            if (!name.matches("[a-z][a-z0-9-]{0,62}") || !Set.of("public", "custom").contains(category)) {
                throw new IllegalStateException("Skill manifest name/category is invalid");
            }
            Path licensePath = repositoryRoot.resolve(licenseFile).normalize();
            if (!licensePath.startsWith(repositoryRoot.resolve("third-party/deerflow/LICENSES"))
                    || !Files.isRegularFile(licensePath) || Files.isSymbolicLink(licensePath)
                    || !sha256(Files.readAllBytes(licensePath)).equalsIgnoreCase(licenseHash)) {
                throw new IllegalStateException("Skill source license is unavailable: " + name);
            }
            if (!versions.add(name + "@" + version)) {
                throw new IllegalStateException("duplicate Skill version: " + name + "@" + version);
            }
            boolean isActive = booleanField(entry, "active");
            boolean enabledByDefault = booleanField(entry, "enabled_by_default");
            if (isActive && !active.add(name)) {
                throw new IllegalStateException("duplicate active Skill: " + name);
            }
            if (!isActive && enabledByDefault) {
                throw new IllegalStateException("retained Skill version cannot be enabled by default");
            }
            Path root = repositoryRoot.resolve(relativeRoot).normalize();
            if (!root.startsWith(repositoryRoot.resolve("third-party/deerflow/skills"))
                    || !root.toRealPath().startsWith(trustedRoot)) {
                throw new IllegalStateException("Skill package path leaves trusted root: " + name);
            }
            validateFiles(root, entry.path("files"), name);
            String parserName = text(entry, "parser");
            SkillParserMode mode = switch (parserName) {
                case "compatible" -> SkillParserMode.COMPATIBLE;
                case "strict" -> SkillParserMode.STRICT;
                default -> throw new IllegalStateException("invalid Skill parser mode: " + parserName);
            };
            SkillPackageLimits defaults = SkillPackageLimits.defaults();
            int maxLines = positiveBound(entry, "max_instruction_lines", defaults.maxSkillLines(), 2_000);
            int maxTokens = positiveBound(entry, "max_estimated_tokens", defaults.maxEstimatedTokens(), 20_000);
            SkillPackageParser parser = new SkillPackageParser(new SkillPackageLimits(
                    4096, 32, 64 * 1024 * 1024,
                    512 * 1024 * 1024, defaults.maxSkillBytes(), maxLines, maxTokens));
            SkillSourceDescriptor descriptor = new SkillSourceDescriptor(
                    new SkillSourceRef(name, version), SkillScopeRef.product(), SkillOrigin.BUNDLED,
                    100, mode, true, false);
            var parsed = parser.parseDirectory(root.resolve(name), descriptor);
            if (parsed.parsed().isEmpty() || !parsed.parsed().orElseThrow().metadata().name().value().equals(name)) {
                throw new IllegalStateException("trusted Skill package failed parsing: " + name
                        + " diagnostics=" + parsed.diagnostics());
            }
            String description = parsed.parsed().orElseThrow().metadata().description();
            String contentHash = text(entry.path("files"), name + "/SKILL.md");
            packages.add(new SkillRunTools.Package(name, description, category, license,
                    sourceName, sourceSha, version, contentHash, isActive, enabledByDefault,
                    new io.haifa.agent.skill.core.InMemorySkillSource(descriptor, parser, SkillAvailability.ENABLED,
                            Map.of(name, SkillPackageFiles.directory(root.resolve(name))))));
        }
        return List.copyOf(packages);
    }

    private Map<String, Boolean> readEnabled(List<SkillRunTools.Package> packages, ObjectNode stored) throws IOException {
        Map<String, Boolean> defaults = new LinkedHashMap<>();
        packages.stream().filter(SkillRunTools.Package::active)
                .forEach(pkg -> defaults.put(pkg.name(), pkg.enabledByDefault()));
        Set<String> known = packages.stream().map(SkillRunTools.Package::name).collect(java.util.stream.Collectors.toSet());
        for (var field : stored.path("enabled").properties()) {
            if (!known.contains(field.getKey()) || !field.getValue().isBoolean()) {
                throw new IllegalStateException("Skill enable configuration has unknown or invalid entry");
            }
            defaults.put(field.getKey(), field.getValue().asBoolean());
        }
        return Map.copyOf(defaults);
    }

    private static void validateFiles(Path root, JsonNode files, String name) throws IOException {
        if (!files.isObject() || files.isEmpty()) {
            throw new IllegalStateException("Skill package file manifest is empty: " + name);
        }
        Set<String> expected = new HashSet<>();
        for (var field : files.properties()) {
            Path relative = Path.of(field.getKey()).normalize();
            if (relative.isAbsolute() || relative.startsWith("..") || !field.getValue().isTextual()) {
                throw new IllegalStateException("Skill package file path/hash is invalid: " + name);
            }
            Path file = root.resolve(relative).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file) || Files.isSymbolicLink(file)) {
                throw new IllegalStateException("Skill package file is missing or unsafe: " + name);
            }
            String actual = sha256(Files.readAllBytes(file));
            if (!actual.equalsIgnoreCase(field.getValue().asText())) {
                throw new IllegalStateException("Skill package hash mismatch: " + name + "/" + relative);
            }
            expected.add(relative.toString().replace('\\', '/'));
        }
        try (var paths = Files.walk(root)) {
            Set<String> actual = new HashSet<>();
            for (Path path : paths.toList()) {
                if (Files.isSymbolicLink(path)) {
                    throw new IllegalStateException("Skill package contains a link: " + name);
                }
                if (Files.isRegularFile(path)) {
                    actual.add(root.relativize(path).toString().replace('\\', '/'));
                }
            }
            if (!actual.equals(expected)) {
                throw new IllegalStateException("Skill package file manifest is incomplete: " + name);
            }
        }
    }

    private static int positiveBound(JsonNode entry, String name, int defaultValue, int maximum) {
        JsonNode value = entry.get(name);
        if (value == null) return defaultValue;
        if (!value.isIntegralNumber() || value.asInt() < 1 || value.asInt() > maximum) {
            throw new IllegalStateException("Skill parser limit is invalid: " + name);
        }
        return value.asInt();
    }

    private static String text(JsonNode entry, String name) {
        JsonNode value = entry.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException("Skill manifest field is missing: " + name);
        }
        return value.asText();
    }

    private static boolean booleanField(JsonNode entry, String name) {
        JsonNode value = entry.get(name);
        if (value == null || !value.isBoolean()) {
            throw new IllegalStateException("Skill manifest field is invalid: " + name);
        }
        return value.asBoolean();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record State(List<SkillRunTools.Package> packages, Map<String, Boolean> enabled,
            io.haifa.agent.skill.api.SkillTrustSnapshot trust) {
        State {
            packages = List.copyOf(packages);
            enabled = Map.copyOf(enabled);
        }
    }
}
