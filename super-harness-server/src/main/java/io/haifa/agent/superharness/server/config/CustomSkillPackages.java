package io.haifa.agent.superharness.server.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.skill.api.SkillAvailability;
import io.haifa.agent.skill.api.SkillOrigin;
import io.haifa.agent.skill.api.SkillParserMode;
import io.haifa.agent.skill.api.SkillScopeRef;
import io.haifa.agent.skill.api.SkillSourceDescriptor;
import io.haifa.agent.skill.api.SkillSourceRef;
import io.haifa.agent.skill.core.InMemorySkillSource;
import io.haifa.agent.skill.core.SkillPackageLimits;
import io.haifa.agent.skill.core.SkillPackageParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/** Immutable user resource versions. Their publication pointer/history live in skills_config.json. */
final class CustomSkillPackages {
    private static final Pattern DECLARED_NAME = Pattern.compile("(?m)^name:\\s*[\"']?([a-z][a-z0-9-]{0,62})[\"']?\\s*$");
    private final ObjectMapper json;
    private final Path root;
    private final SkillPackageParser parser = new SkillPackageParser(new SkillPackageLimits(
            4096, 32, 64 * 1024 * 1024, 512 * 1024 * 1024, 1024 * 1024, 2000, 20000));
    private SkillPackageScreening.WriteScreen screen = (directory, files) -> {
        throw new IOException("Configured Skill write security scanner is unavailable");
    };

    CustomSkillPackages(ObjectMapper json, Path dataRoot) {
        this.json = json;
        this.root = dataRoot.toAbsolutePath().normalize().resolve("skills/custom/.versions");
    }

    void screening(SkillPackageScreening.WriteScreen screen) { this.screen = java.util.Objects.requireNonNull(screen); }

    JsonNode screen(Path directory, Map<String, byte[]> files) throws IOException { return screen.screen(directory, files); }

    List<io.haifa.agent.skill.api.SkillPackageReviewGrant> approvals(ObjectNode config, List<SkillRunTools.Package> packages) {
        List<io.haifa.agent.skill.api.SkillPackageReviewGrant> result = new ArrayList<>();
        for (SkillRunTools.Package pkg : packages) {
            if (!pkg.sourceName().equals("user-installed")) continue;
            for (JsonNode version : config.path("custom").path(pkg.name()).path("versions")) {
                if (!version.path("revision").asText().equals(pkg.sourceSha())) continue;
                var registration = pkg.source().discover(SkillReviewApprovals.discovery()).registrations().getFirst();
                result.add(SkillReviewApprovals.grant(registration, version.path("review")));
            }
        }
        return List.copyOf(result);
    }

    List<SkillRunTools.Package> packages(ObjectNode config) throws IOException {
        List<SkillRunTools.Package> result = new ArrayList<>();
        JsonNode records = config.path("custom");
        if (records.isMissingNode()) return result;
        if (!records.isObject()) throw new IOException("Custom Skill configuration is invalid");
        for (var entry : records.properties()) {
            String name = SkillPackageFiles.name(entry.getKey());
            JsonNode record = entry.getValue();
            String current = record.path("current").asText("");
            if (!record.path("versions").isArray() || !record.path("history").isArray()) throw new IOException("Custom Skill version index is invalid");
            boolean found = current.isEmpty();
            java.util.Set<String> versions = new java.util.HashSet<>();
            for (JsonNode version : record.path("versions")) {
                String revision = revision(version.path("revision").asText());
                if (!versions.add(revision)) throw new IOException("Duplicate custom Skill version");
                Map<String, byte[]> files = files(name, revision);
                if (!SkillPackageFiles.digest(files).equals(revision)) throw new IOException("Custom Skill package hash mismatch");
                var parsed = parser.parseFiles(name, files, descriptor(name, revision));
                if (parsed.parsed().isEmpty()) throw new IOException("Custom Skill package is invalid");
                var metadata = parsed.parsed().orElseThrow().metadata();
                boolean active = revision.equals(current); found |= active;
                result.add(new SkillRunTools.Package(name, metadata.description(), "custom", metadata.license().orElse(""),
                        "user-installed", revision, "sha256:" + revision,
                        SkillPackageFiles.hash(files.get("SKILL.md")), active, true,
                        new InMemorySkillSource(descriptor(name, revision), parser, SkillAvailability.ENABLED, Map.of(name, files))));
            }
            if (!found) throw new IOException("Current custom Skill version is unavailable");
        }
        return List.copyOf(result);
    }

    private static SkillSourceDescriptor descriptor(String name, String revision) {
        var caller = io.haifa.agent.sdk.api.SdkCaller.defaultPublicUser();
        return new SkillSourceDescriptor(new SkillSourceRef("user-" + name, "sha256:" + revision),
                SkillScopeRef.user(caller.tenant(), caller.principal()), SkillOrigin.IMPORTED, 200, SkillParserMode.COMPATIBLE, true, false);
    }

    Map<String, byte[]> current(ObjectNode config, String name) throws IOException {
        String revision = config.path("custom").path(SkillPackageFiles.name(name)).path("current").asText("");
        if (revision.isEmpty()) throw new java.io.FileNotFoundException("Custom skill '" + name + "' not found");
        return files(name, revision);
    }

    Map<String, byte[]> files(String name, String revision) throws IOException {
        Path directory = versionRoot(name, revision).resolve(name);
        if (!Files.isDirectory(directory)) throw new IOException("Custom Skill version is missing");
        requireNoLinks(directory);
        return SkillPackageFiles.directory(directory);
    }

    private Path versionRoot(String name, String revision) {
        return root.resolve(SkillPackageFiles.name(name)).resolve(revision(revision));
    }

    private static String revision(String revision) {
        if (!revision.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid Skill version");
        return revision;
    }

    private void requireNoLinks(Path target) throws IOException {
        requireNoLinkedAncestors(target);
        if (!target.toRealPath().startsWith(root.toRealPath())) throw new IOException("Custom Skill directory leaves data root");
    }

    private void requireNoLinkedAncestors(Path target) throws IOException {
        for (Path cursor = target; cursor != null && cursor.startsWith(root.getParent().getParent().getParent()); cursor = cursor.getParent()) {
            if (Files.isSymbolicLink(cursor)) throw new IOException("Custom Skill directory contains a link");
        }
    }

    String install(ObjectNode config, byte[] archive, String author, String thread) throws IOException {
        Map<String, byte[]> files = SkillPackageFiles.archive(archive);
        String markdown = new String(files.get("SKILL.md"), StandardCharsets.UTF_8);
        var match = DECLARED_NAME.matcher(markdown);
        if (!match.find()) throw new IllegalArgumentException("Skill archive requires a valid name in SKILL.md");
        String name = SkillPackageFiles.name(match.group(1));
        if (!config.path("custom").path(name).path("current").asText("").isEmpty()) {
            throw new java.nio.file.FileAlreadyExistsException("Skill '" + name + "' already exists");
        }
        publishVersion(config, name, files, "install", author, thread, "SKILL.md", null);
        return name;
    }

    void manage(ObjectNode config, String action, String name, String content, String support,
            String find, String replacement, Integer expectedCount, String author, String thread) throws IOException {
        SkillPackageFiles.name(name);
        Map<String, byte[]> files = action.equals("create") ? new TreeMap<>() : new TreeMap<>(current(config, name));
        if (action.equals("create") && !config.path("custom").path(name).path("current").asText("").isEmpty()) {
            throw new java.nio.file.FileAlreadyExistsException("Skill '" + name + "' already exists");
        }
        if (action.equals("delete")) {
            ObjectNode record = record(config, name);
            appendHistory(record, "delete", author, thread, "SKILL.md", text(files.get("SKILL.md")), null,
                    json.createObjectNode().put("decision", "allow").put("reason", "Deletion requested."));
            record.put("current", "");
            return;
        }
        String path = "SKILL.md";
        String previous = text(files.get(path));
        switch (action) {
            case "create", "edit", "human_edit" -> files.put(path, required(content, "content").getBytes(StandardCharsets.UTF_8));
            case "patch" -> {
                find = required(find, "find"); replacement = required(replacement, "replace");
                if (find.isEmpty()) throw new IllegalArgumentException("Patch target cannot be empty");
                int occurrences = 0, cursor = 0;
                while ((cursor = previous.indexOf(find, cursor)) >= 0) { occurrences++; cursor += find.length(); }
                if (occurrences == 0 || (expectedCount != null && (expectedCount <= 0 || occurrences != expectedCount))) {
                    throw new IllegalArgumentException("Patch replacement count does not match");
                }
                String next = previous;
                int limit = expectedCount == null ? 1 : expectedCount;
                StringBuilder patched = new StringBuilder(); cursor = 0;
                for (int i = 0; i < limit; i++) {
                    int at = next.indexOf(find, cursor); patched.append(next, cursor, at).append(replacement); cursor = at + find.length();
                }
                patched.append(next.substring(cursor)); files.put(path, patched.toString().getBytes(StandardCharsets.UTF_8));
            }
            case "write_file", "remove_file" -> {
                path = SkillPackageFiles.path(support);
                if (!path.startsWith("scripts/") && !path.startsWith("references/") && !path.startsWith("assets/")
                        && !path.startsWith("evals/") && !path.startsWith("templates/")) {
                    throw new IllegalArgumentException("Unsupported Skill supporting file path");
                }
                previous = text(files.get(path));
                if (action.equals("write_file")) files.put(path, required(content, "content").getBytes(StandardCharsets.UTF_8));
                else if (files.remove(path) == null) throw new java.io.FileNotFoundException("Supporting Skill file not found");
            }
            default -> throw new IllegalArgumentException("Unsupported Skill action");
        }
        publishVersion(config, name, files, action, author, thread, path, previous);
    }

    void rollback(ObjectNode config, String name, int index) throws IOException {
        JsonNode stored = config.path("custom").path(SkillPackageFiles.name(name));
        if (!stored.path("history").isArray()) throw new java.io.FileNotFoundException("Custom Skill not found");
        ArrayNode history = (ArrayNode) stored.path("history");
        if (index < 0) index += history.size();
        if (index < 0 || index >= history.size()) throw new IllegalArgumentException("history_index is out of range");
        JsonNode entry = history.get(index);
        if (entry.path("prev_content").isNull() || entry.path("prev_content").isMissingNode()) {
            throw new IllegalArgumentException("Selected history entry has no previous content to roll back to");
        }
        Map<String, byte[]> files;
        String current = record(config, name).path("current").asText("");
        if (current.isEmpty()) files = new TreeMap<>(files(name, entry.path("previous_revision").asText()));
        else files = new TreeMap<>(current(config, name));
        String previous = text(files.get("SKILL.md"));
        files.put("SKILL.md", entry.path("prev_content").asText().getBytes(StandardCharsets.UTF_8));
        publishVersion(config, name, files, "rollback", "human", null, "SKILL.md", previous);
    }

    private void publishVersion(ObjectNode config, String name, Map<String, byte[]> files, String action,
            String author, String thread, String file, String previous) throws IOException {
        String revision = SkillPackageFiles.digest(files);
        var parsed = parser.parseFiles(name, files, descriptor(name, revision));
        if (parsed.parsed().isEmpty() || !parsed.parsed().orElseThrow().metadata().name().value().equals(name)) {
            throw new IllegalArgumentException("Invalid Skill package/frontmatter");
        }
        requireNoLinkedAncestors(root);
        Files.createDirectories(root);
        requireNoLinks(root);
        Path staging = Files.createTempDirectory(root, ".publishing-");
        try {
            Path packageDir = staging.resolve(name); Files.createDirectory(packageDir);
            for (var entry : files.entrySet()) {
                Path target = packageDir.resolve(SkillPackageFiles.path(entry.getKey()));
                Files.createDirectories(target.getParent()); Files.write(target, entry.getValue());
            }
            Map<String, byte[]> reviewed = reviewedCurrent(config, name);
            java.util.Set<String> changed = new java.util.HashSet<>(files.keySet());
            if (reviewed != null) changed.removeIf(path -> java.util.Arrays.equals(files.get(path), reviewed.get(path)));
            JsonNode scan = reviewed == null ? screen.screen(packageDir, files) : screen.screenChanges(packageDir, files, changed);
            if (reviewed != null && scan instanceof ObjectNode summary) {
                summary.put("unchanged_reviewed_revision", config.path("custom").path(name).path("current").asText());
            }
            Path target = versionRoot(name, revision);
            requireNoLinkedAncestors(target);
            Files.createDirectories(target.getParent());
            requireNoLinks(target.getParent());
            if (!Files.exists(target)) Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            else if (!SkillPackageFiles.digest(files(name, revision)).equals(revision)) throw new IOException("Immutable Skill version changed");
            ObjectNode record = record(config, name);
            ArrayNode versions = record.withArray("versions");
            boolean exists = false;
            for (JsonNode version : versions) if (version.path("revision").asText().equals(revision)) exists = true;
            if (!exists) {
                var registration = new InMemorySkillSource(descriptor(name, revision), parser, SkillAvailability.ENABLED, Map.of(name, files))
                        .discover(SkillReviewApprovals.discovery()).registrations().getFirst();
                ObjectNode version = versions.addObject().put("revision", revision)
                        .put("source", author.equals("human") ? "user-upload-or-edit" : "agent-managed");
                version.set("review", SkillReviewApprovals.evidence(json, registration, scan));
            }
            appendHistory(record, action, author, thread, file, previous, text(files.get(file)), scan);
            record.put("current", revision);
        } finally {
            if (Files.exists(staging)) {
                try (var walk = Files.walk(staging)) {
                    for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
        }
    }

    private ObjectNode record(ObjectNode config, String name) {
        ObjectNode custom = config.withObject("custom");
        if (!custom.has(name)) {
            ObjectNode record = custom.putObject(name); record.put("current", ""); record.putArray("versions"); record.putArray("history");
        }
        return (ObjectNode) custom.get(name);
    }

    /** Reuse only unchanged bytes from an exact SDK-reviewed, non-revoked current package. */
    private Map<String, byte[]> reviewedCurrent(ObjectNode config, String name) throws IOException {
        JsonNode stored = config.path("custom").path(name);
        String current = stored.path("current").asText("");
        if (current.isEmpty()) return null;
        Map<String, byte[]> previous = files(name, current);
        if (!SkillPackageFiles.digest(previous).equals(current)) throw new IOException("Custom Skill package hash mismatch");
        for (JsonNode version : stored.path("versions")) {
            if (!version.path("revision").asText().equals(current)) continue;
            var registration = new InMemorySkillSource(descriptor(name, current), parser, SkillAvailability.ENABLED, Map.of(name, previous))
                    .discover(SkillReviewApprovals.discovery()).registrations().getFirst();
            var grant = SkillReviewApprovals.grant(registration, version.path("review"));
            if (grant.activeAt(Instant.now())) return previous;
        }
        return null;
    }

    private void appendHistory(ObjectNode record, String action, String author, String thread, String file,
            String previous, String next, JsonNode scan) {
        ObjectNode row = record.withArray("history").addObject();
        row.put("ts", Instant.now().toString()); row.put("action", action); row.put("author", author);
        row.put("thread_id", thread); row.put("file_path", file); row.put("prev_content", previous); row.put("new_content", next);
        row.put("previous_revision", record.path("current").asText("")); row.set("scanner", scan);
    }

    private static String text(byte[] value) { return value == null ? null : new String(value, StandardCharsets.UTF_8); }
    private static String required(String value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
