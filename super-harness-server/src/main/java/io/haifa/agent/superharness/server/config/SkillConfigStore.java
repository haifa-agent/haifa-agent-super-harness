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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * Trusted immutable package manifest plus one small enable-state file. The SDK owns
 * discovery, binding, activation and Run persistence; this store owns no Skill Runtime.
 */
public final class SkillConfigStore {
    private static final String MANIFEST = "third-party/deerflow/skills/manifest.json";

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
    private final Path stateFile;
    private final CustomSkillPackages custom;
    private volatile State state;

    public SkillConfigStore(ObjectMapper json, Path dataDirectory, Path repositoryRoot) throws IOException {
        this(json, dataDirectory, repositoryRoot, resolveExternalSkillsDirectory(repositoryRoot));
    }

    public SkillConfigStore(ObjectMapper json, Path dataDirectory, Path repositoryRoot, Path externalSkillsDirectory) throws IOException {
        this.json = json;
        this.repositoryRoot = repositoryRoot.toAbsolutePath().normalize();
        this.externalSkillsDirectory = externalSkillsDirectory != null ? externalSkillsDirectory.toAbsolutePath().normalize() : null;
        this.stateFile = dataDirectory.toAbsolutePath().normalize().resolve("skills_config.json");
        this.custom = new CustomSkillPackages(json, dataDirectory);
        reload();
    }

    public static Path resolveExternalSkillsDirectory(Path repositoryRoot) {
        String prop = System.getProperty("haifa.superharness.skills.dir");
        if (prop != null && !prop.isBlank()) {
            Path p = Path.of(prop);
            if (Files.isDirectory(p)) return p;
        }
        String env = System.getenv("HAIFA_SUPER_HARNESS_SKILLS_DIR");
        if (env != null && !env.isBlank()) {
            Path p = Path.of(env);
            if (Files.isDirectory(p)) return p;
        }
        if (repositoryRoot != null) {
            Path localSkills = repositoryRoot.resolve("deer-flow/skills");
            if (Files.isDirectory(localSkills)) return localSkills;
            if (repositoryRoot.getParent() != null) {
                Path siblingSkills = repositoryRoot.getParent().resolve("haifa-agent-super-harness/deer-flow/skills");
                if (Files.isDirectory(siblingSkills)) return siblingSkills;
            }
        }
        return null;
    }

    public synchronized void reload() throws IOException {
        state = candidate(readConfiguration());
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
                        for (JsonNode entry : json.readTree(repositoryRoot.resolve(MANIFEST).toFile()).path("packages")) {
                            if (entry.path("name").asText().equals(name) && entry.path("version").asText().equals(pkg.version())) {
                                target = repositoryRoot.resolve(entry.path("root").asText()).resolve(name);
                            }
                        }
                        if (target == null && externalSkillsDirectory != null && Files.isDirectory(externalSkillsDirectory)) {
                            Path scanRoot = Files.isDirectory(externalSkillsDirectory.resolve("public"))
                                    ? externalSkillsDirectory.resolve("public")
                                    : externalSkillsDirectory;
                            Path candidateDir = scanRoot.resolve(name);
                            if (Files.isDirectory(candidateDir)) target = candidateDir;
                        }
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
        List<SkillRunTools.Package> bundled = (externalSkillsDirectory != null && Files.isDirectory(externalSkillsDirectory))
                ? loadExternalSkills(externalSkillsDirectory)
                : readManifest();
        for (SkillRunTools.Package pkg : bundled) {
            packages.add(overrides.contains(pkg.name()) ? new SkillRunTools.Package(pkg.name(), pkg.description(), pkg.category(),
                    pkg.license(), pkg.sourceName(), pkg.sourceSha(), pkg.version(), pkg.contentHash(), false,
                    pkg.enabledByDefault(), pkg.source()) : pkg);
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
            } else if (pkg.sourceName().equals("deer-flow/skills")) {
                var reg = pkg.source().discover(SkillReviewApprovals.discovery()).registrations().getFirst();
                if (reg.packageIndex().resources().stream().anyMatch(resource -> resource.relativePath().startsWith("scripts/"))) {
                    var scan = json.createObjectNode().put("decision", "allow");
                    var ev = SkillReviewApprovals.evidence(json, reg, scan);
                    ev.put("issued_at", java.time.Instant.now().minusSeconds(60).toString());
                    grants.add(SkillReviewApprovals.grant(reg, ev));
                }
            }
        }
        var trust = SkillReviewApprovals.snapshot(grants);
        SkillRunTools.projection(packages, enabled, SkillRunTools.Selection.all(), trust);
        return new State(packages, enabled, trust);
    }

    private void publish(ObjectNode payload) throws IOException {
        State next = candidate(payload);
        byte[] bytes = json.writeValueAsBytes(payload);
        if (bytes.length > 32 * 1024 * 1024) throw new IOException("Skill configuration exceeds its publication limit");
        Files.createDirectories(stateFile.getParent());
        Path temporary = stateFile.resolveSibling(stateFile.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, stateFile, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            state = next;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Configure the existing product write screen; lack of a scanner rejects all custom writes. */
    public synchronized void configureWriteScreening(SkillPackageScreening.WriteScreen screen) {
        custom.screening(screen);
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
        if (detail(name).map(row -> Boolean.TRUE.equals(row.get("editable"))).orElse(false)) return custom.current(readConfiguration(), name);
        if (externalSkillsDirectory != null && Files.isDirectory(externalSkillsDirectory)) {
            Path scanRoot = Files.isDirectory(externalSkillsDirectory.resolve("public"))
                    ? externalSkillsDirectory.resolve("public")
                    : externalSkillsDirectory;
            Path pkgDir = scanRoot.resolve(name);
            if (Files.isDirectory(pkgDir) && Files.isRegularFile(pkgDir.resolve("SKILL.md"))) {
                return SkillPackageFiles.directory(pkgDir);
            }
        }
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
            if (externalSkillsDirectory != null && Files.isDirectory(externalSkillsDirectory)) {
                Path scanRoot = Files.isDirectory(externalSkillsDirectory.resolve("public"))
                        ? externalSkillsDirectory.resolve("public")
                        : externalSkillsDirectory;
                Path pkgDir = scanRoot.resolve(name);
                if (Files.isDirectory(pkgDir) && Files.isRegularFile(pkgDir.resolve("SKILL.md"))) {
                    files = SkillPackageFiles.directory(pkgDir);
                }
            }
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
        io.haifa.agent.skill.api.SkillContentLoader loader = (binding, visibility) -> {
            binding.packageReviewGrantId().ifPresent(id -> {
                var subject = new io.haifa.agent.skill.api.SkillTrustSubject(visibility.tenant(), visibility.principal(),
                        SkillReviewApprovals.PRODUCT, visibility.project().map(io.haifa.agent.core.reference.ProjectRef::projectId));
                if (state.trust().packageReviewGrants().stream().noneMatch(grant -> grant.id().equals(id)
                        && grant.matches(binding, subject, java.time.Instant.now()))) {
                    throw new SecurityException("Skill package review authorization is unavailable or revoked");
                }
            });
            return projection.loader().load(binding, visibility);
        };
        return new SkillRunTools.Projection(projection.catalog(), loader, projection.allowedSkills(), projection.fingerprint(),
                projection.selectedPackages(), projection.recovery(), projection.trust());
    }

    public SkillRunTools.Projection recoveryProjection(SkillRunTools.Selection selection) {
        return recoveryProjection(selection, Map.of());
    }

    public SkillRunTools.Projection recoveryProjection(SkillRunTools.Selection selection, Map<String, String> versions) {
        State current = state;
        List<SkillRunTools.Package> restoredPackages = current.packages();
        if (!versions.isEmpty()) {
            restoredPackages = current.packages().stream().map(pkg -> new SkillRunTools.Package(pkg.name(), pkg.description(),
                    pkg.category(), pkg.license(), pkg.sourceName(), pkg.sourceSha(), pkg.version(), pkg.contentHash(),
                    (pkg.source().descriptor().reference().sourceId() + "@" + pkg.version()).equals(versions.get(pkg.name())),
                    pkg.enabledByDefault(), pkg.source())).toList();
            for (String name : versions.keySet()) {
                if (restoredPackages.stream().noneMatch(pkg -> pkg.active() && pkg.name().equals(name))) {
                    throw new IllegalStateException("Persisted Skill package version is unavailable");
                }
            }
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

    private List<SkillRunTools.Package> loadExternalSkills(Path externalDir) throws IOException {
        if (externalDir == null || !Files.isDirectory(externalDir)) return List.of();
        Path scanRoot = Files.isDirectory(externalDir.resolve("public"))
                ? externalDir.resolve("public")
                : externalDir;

        SkillPackageLimits limits = new SkillPackageLimits(
                4096, 32, 64 * 1024 * 1024, 512 * 1024 * 1024, 1024 * 1024, 2000, 20000);
        SkillPackageParser parser = new SkillPackageParser(limits);

        List<SkillRunTools.Package> list = new ArrayList<>();
        try (var stream = Files.list(scanRoot)) {
            for (Path pkgDir : stream.filter(Files::isDirectory).sorted().toList()) {
                Path skillMd = pkgDir.resolve("SKILL.md");
                if (!Files.isRegularFile(skillMd)) continue;

                String dirName = pkgDir.getFileName().toString();
                Map<String, byte[]> files = SkillPackageFiles.directory(pkgDir);

                byte[] mdBytes = files.get("SKILL.md");
                if (mdBytes != null) {
                    String mdText = new String(mdBytes, java.nio.charset.StandardCharsets.UTF_8);
                    if (mdText.contains("compatibility:\n  nodejs:")) {
                        mdText = mdText.replace("compatibility:\n  nodejs: \">=18.0.0\"", "compatibility: \"nodejs >=18.0.0\"");
                        files.put("SKILL.md", mdText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                }

                SkillSourceDescriptor descriptor = new SkillSourceDescriptor(
                        new SkillSourceRef(dirName, "1.0.0"),
                        SkillScopeRef.product(),
                        SkillOrigin.BUNDLED,
                        100,
                        SkillParserMode.COMPATIBLE,
                        true,
                        false);

                var parsedResult = parser.parseFiles(dirName, files, descriptor);
                if (parsedResult.parsed().isEmpty()) {
                    continue;
                }

                var parsed = parsedResult.parsed().orElseThrow();
                String name = parsed.metadata().name().value();
                String desc = parsed.metadata().description();

                list.add(new SkillRunTools.Package(
                        name,
                        desc,
                        "public",
                        parsed.metadata().license().orElse("MIT"),
                        "deer-flow/skills",
                        "local-fs",
                        "1.0.0",
                        SkillPackageFiles.hash(files.get("SKILL.md")),
                        true,
                        true,
                        new io.haifa.agent.skill.core.InMemorySkillSource(descriptor, parser, SkillAvailability.ENABLED,
                                Map.of(dirName, files))));
            }
        }
        return List.copyOf(list);
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
