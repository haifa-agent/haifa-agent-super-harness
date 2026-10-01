package io.haifa.agent.superharness.server;

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
final class SkillConfigStore {
    private static final String MANIFEST = "third-party/deerflow/skills/manifest.json";

    static Path repositoryRootFromWorkingDirectory() throws IOException {
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
    private final Path stateFile;
    private volatile State state;

    SkillConfigStore(ObjectMapper json, Path dataDirectory, Path repositoryRoot) throws IOException {
        this.json = json;
        this.repositoryRoot = repositoryRoot.toAbsolutePath().normalize();
        this.stateFile = dataDirectory.toAbsolutePath().normalize().resolve("skills_config.json");
        reload();
    }

    synchronized void reload() throws IOException {
        List<SkillRunTools.Package> packages = readManifest();
        Map<String, Boolean> enabled = readEnabled(packages);
        // Constructing the SDK catalog before the swap makes reload all-or-nothing.
        SkillRunTools.projection(packages, enabled, SkillRunTools.Selection.all());
        state = new State(packages, enabled);
    }

    synchronized void setEnabled(String name, boolean enabled) throws IOException {
        State before = state;
        if (before.packages().stream().noneMatch(pkg -> pkg.active() && pkg.name().equals(name))) {
            throw new IllegalArgumentException("Skill '" + name + "' is not registered");
        }
        List<SkillRunTools.Package> packages = readManifest();
        Map<String, Boolean> values = new LinkedHashMap<>(before.enabled());
        values.put(name, enabled);
        SkillRunTools.projection(packages, values, SkillRunTools.Selection.all());
        ObjectNode payload = json.createObjectNode();
        payload.put("schema_version", 1);
        ObjectNode map = payload.putObject("enabled");
        new TreeMap<>(values).forEach(map::put);
        Files.createDirectories(stateFile.getParent());
        Path temporary = stateFile.resolveSibling(stateFile.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temporary, json.writeValueAsBytes(payload));
            Files.move(temporary, stateFile, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            state = new State(packages, values);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    SkillRunTools.Projection projection(SkillRunTools.Selection selection) {
        State current = state;
        return SkillRunTools.projection(current.packages(), current.enabled(), selection);
    }

    SkillRunTools.Projection recoveryProjection(SkillRunTools.Selection selection) {
        State current = state;
        // Restore the admitted product declaration, irrespective of later UI enable toggles.
        // Immutable package sources and the retained loader remain SDK inputs.
        Map<String, Boolean> original = new LinkedHashMap<>();
        current.packages().forEach(pkg -> original.put(pkg.name(), selection.admits(pkg.name())));
        return SkillRunTools.projection(current.packages(), original, selection).forRecovery();
    }

    List<Map<String, Object>> list() {
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
            row.put("editable", false);
            row.put("source", pkg.sourceName());
            row.put("source_sha", pkg.sourceSha());
            row.put("version", pkg.version());
            row.put("sha256", pkg.contentHash());
            response.add(row);
        }
        return List.copyOf(response);
    }

    Optional<Map<String, Object>> detail(String name) {
        return list().stream().filter(row -> row.get("name").equals(name)).findFirst();
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
                    defaults.maxFiles(), defaults.maxDepth(), defaults.maxFileBytes(),
                    defaults.maxPackageBytes(), defaults.maxSkillBytes(), maxLines, maxTokens));
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
                    new LocalDirectorySkillSource(root, descriptor, parser, SkillAvailability.ENABLED)));
        }
        return List.copyOf(packages);
    }

    private Map<String, Boolean> readEnabled(List<SkillRunTools.Package> packages) throws IOException {
        Map<String, Boolean> defaults = new LinkedHashMap<>();
        packages.stream().filter(SkillRunTools.Package::active)
                .forEach(pkg -> defaults.put(pkg.name(), pkg.enabledByDefault()));
        if (!Files.exists(stateFile)) return Map.copyOf(defaults);
        JsonNode stored = json.readTree(stateFile.toFile());
        if (stored == null || stored.path("schema_version").asInt(-1) != 1
                || !stored.path("enabled").isObject()) {
            throw new IllegalStateException("Skill enable configuration is invalid");
        }
        for (var field : stored.path("enabled").properties()) {
            if (!defaults.containsKey(field.getKey()) || !field.getValue().isBoolean()) {
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

    private record State(List<SkillRunTools.Package> packages, Map<String, Boolean> enabled) {
        State {
            packages = List.copyOf(packages);
            enabled = Map.copyOf(enabled);
        }
    }
}
