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
import io.haifa.agent.superharness.workspace.AtomicMoves;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
                var content = packageVersion(config, name, revision);
                Map<String, byte[]> files = content.files();
                if (!SkillPackageFiles.digest(content).equals(revision)) throw new IOException("Custom Skill package hash mismatch");
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

    SkillPackageFiles.PackageContent currentPackage(ObjectNode config, String name) throws IOException {
        String revision = config.path("custom").path(SkillPackageFiles.name(name)).path("current").asText("");
        if (revision.isEmpty()) throw new java.io.FileNotFoundException("Custom skill '" + name + "' not found");
        return packageVersion(config, name, revision);
    }

    private SkillPackageFiles.PackageContent packageVersion(ObjectNode config, String name, String revision) throws IOException {
        JsonNode metadata = null;
        for (JsonNode version : config.path("custom").path(name).path("versions")) {
            if (version.path("revision").asText().equals(revision)) { metadata = version; break; }
        }
        if (metadata == null) throw new IOException("Custom Skill version metadata is missing");
        java.util.Set<String> directories = new java.util.TreeSet<>(), executable = new java.util.TreeSet<>();
        for (var entry : Map.of("empty_directories", directories, "executable_files", executable).entrySet()) {
            JsonNode rows = metadata.path(entry.getKey());
            if (!rows.isMissingNode() && !rows.isArray()) throw new IOException("Custom Skill entry metadata is invalid");
            for (JsonNode row : rows) {
                if (!row.isTextual() || !entry.getValue().add(SkillPackageFiles.path(row.asText()))) {
                    throw new IOException("Custom Skill entry metadata is invalid");
                }
            }
        }
        var content = new SkillPackageFiles.PackageContent(files(name, revision), directories, executable);
        SkillPackageFiles.validatePackagePaths(content);
        for (String directory : directories) {
            if (!Files.isDirectory(versionRoot(name, revision).resolve(name).resolve(directory))) {
                throw new IOException("Custom Skill empty directory is missing");
            }
        }
        return content;
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

    /**
     * True only when the operator authorized this exact package revision. The authorization is bound
     * to the 64-hex package digest, so any byte change invalidates it.
     */
    private boolean humanAuthorized(ObjectNode config, String name, String revision) {
        JsonNode entry = config.path("authorized_packages").path(name);
        String recorded = entry.path("revision").asText("");
        return recorded.matches("[a-f0-9]{64}") && recorded.equals(revision)
                && !entry.path("authorized_by").asText("").isBlank();
    }

    /** Declared name plus the exact 64-hex package revision, computed without screening or publication. */
    java.util.Map<String, Object> preview(byte[] archive) throws IOException {
        var content = SkillPackageFiles.archivePackage(archive);
        byte[] markdown = content.files().get("SKILL.md");
        if (markdown == null) throw new IllegalArgumentException("Skill archive requires SKILL.md");
        var match = DECLARED_NAME.matcher(new String(markdown, StandardCharsets.UTF_8));
        if (!match.find()) throw new IllegalArgumentException("Skill archive requires a valid name in SKILL.md");
        return java.util.Map.of("name", SkillPackageFiles.name(match.group(1)),
                "revision", SkillPackageFiles.digest(content));
    }

    String install(ObjectNode config, byte[] archive, String author, String thread) throws IOException {
        var content = SkillPackageFiles.archivePackage(archive);
        Map<String, byte[]> files = content.files();
        String markdown = new String(files.get("SKILL.md"), StandardCharsets.UTF_8);
        var match = DECLARED_NAME.matcher(markdown);
        if (!match.find()) throw new IllegalArgumentException("Skill archive requires a valid name in SKILL.md");
        String name = SkillPackageFiles.name(match.group(1));
        if (!config.path("custom").path(name).path("current").asText("").isEmpty()) {
            throw new java.nio.file.FileAlreadyExistsException("Skill '" + name + "' already exists");
        }
        publishVersion(config, name, content, "install", author, thread, "SKILL.md", null, () -> {});
        return name;
    }

    void manage(ObjectNode config, String action, String name, String content, String support,
            String find, String replacement, Integer expectedCount, String author, String thread, Runnable admission) throws IOException {
        SkillPackageFiles.name(name);
        var original = action.equals("create") ? SkillPackageFiles.PackageContent.filesOnly(Map.of()) : currentPackage(config, name);
        Map<String, byte[]> files = new TreeMap<>(original.files());
        java.util.Set<String> directories = new java.util.TreeSet<>(original.emptyDirectories());
        java.util.Set<String> executable = new java.util.TreeSet<>(original.executableFiles());
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
                if (action.equals("write_file")) {
                    files.put(path, required(content, "content").getBytes(StandardCharsets.UTF_8));
                    String supportPath = path;
                    directories.removeIf(dir -> supportPath.startsWith(dir + "/"));
                } else {
                    if (files.remove(path) == null) throw new java.io.FileNotFoundException("Supporting Skill file not found");
                    executable.remove(path);
                    String parent = path.substring(0, path.lastIndexOf('/'));
                    if (files.keySet().stream().noneMatch(file -> file.startsWith(parent + "/"))
                            && directories.stream().noneMatch(dir -> dir.startsWith(parent + "/"))) directories.add(parent);
                }
            }
            default -> throw new IllegalArgumentException("Unsupported Skill action");
        }
        publishVersion(config, name, new SkillPackageFiles.PackageContent(files, directories, executable),
                action, author, thread, path, previous, admission);
    }

    void rollback(ObjectNode config, String name, int index) throws IOException {
        JsonNode stored = config.path("custom").path(SkillPackageFiles.name(name));
        if (!stored.path("history").isArray()) throw new java.io.FileNotFoundException("Custom Skill not found");
        ArrayNode history = (ArrayNode) stored.path("history");
        if (index < 0) index += history.size();
        if (index < 0 || index >= history.size()) throw new IllegalArgumentException("history_index is out of range");
        JsonNode entry = history.get(index);
        String prior = entry.path("previous_revision").asText("");
        if (prior.isEmpty()) throw new IllegalArgumentException("Selected history entry has no previous package version");
        var priorPackage = packageVersion(config, name, prior);
        Map<String, byte[]> priorFiles = priorPackage.files();
        if (!SkillPackageFiles.digest(priorPackage).equals(prior)) throw new IOException("Custom Skill package hash mismatch");
        String path = SkillPackageFiles.path(entry.path("file_path").asText());
        Map<String, byte[]> files;
        java.util.Set<String> directories, executable;
        String current = record(config, name).path("current").asText("");
        var currentPackage = current.isEmpty() ? priorPackage : currentPackage(config, name);
        files = new TreeMap<>(currentPackage.files());
        directories = new java.util.TreeSet<>(currentPackage.emptyDirectories());
        executable = new java.util.TreeSet<>(currentPackage.executableFiles());
        String previous = text(files.get(path));
        if (entry.path("action").asText().equals("delete")) {
            files = new TreeMap<>(priorFiles);
            directories = new java.util.TreeSet<>(priorPackage.emptyDirectories());
            executable = new java.util.TreeSet<>(priorPackage.executableFiles());
        } else if (priorFiles.containsKey(path)) {
            files.put(path, priorFiles.get(path));
            directories.removeIf(dir -> path.startsWith(dir + "/"));
            if (priorPackage.executableFiles().contains(path)) executable.add(path);
            else executable.remove(path);
        } else if (!path.equals("SKILL.md")) {
            files.remove(path); executable.remove(path);
            String parent = path.substring(0, path.lastIndexOf('/'));
            if (files.keySet().stream().noneMatch(file -> file.startsWith(parent + "/"))
                    && directories.stream().noneMatch(dir -> dir.startsWith(parent + "/"))) directories.add(parent);
        }
        else throw new IllegalArgumentException("Selected history entry has no previous SKILL.md");
        publishVersion(config, name, new SkillPackageFiles.PackageContent(files, directories, executable),
                "rollback", "human", null, path, previous, () -> {});
    }

    private void publishVersion(ObjectNode config, String name, SkillPackageFiles.PackageContent content, String action,
            String author, String thread, String file, String previous, Runnable admission) throws IOException {
        admission.run();
        SkillPackageFiles.validatePackagePaths(content);
        Map<String, byte[]> files = content.files();
        String revision = SkillPackageFiles.digest(content);
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
                if (Files.getFileAttributeView(target, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
                    String permissions = content.executableFiles().contains(entry.getKey()) ? "rwxr-xr-x" : "rw-r--r--";
                    Files.setPosixFilePermissions(target, java.nio.file.attribute.PosixFilePermissions.fromString(permissions));
                }
            }
            for (String directory : content.emptyDirectories()) Files.createDirectories(packageDir.resolve(directory));
            var reviewed = reviewedCurrent(config, name);
            java.util.Set<String> changed = new java.util.HashSet<>(files.keySet());
            if (reviewed != null) changed.removeIf(path -> java.util.Arrays.equals(files.get(path), reviewed.files().get(path))
                    && content.executableFiles().contains(path) == reviewed.executableFiles().contains(path));
            boolean humanAuthorized = humanAuthorized(config, name, revision);
            JsonNode scan = humanAuthorized ? screen.staticScreen(packageDir)
                    : (reviewed == null ? screen.screen(packageDir, files) : screen.screenChanges(packageDir, files, changed));
            admission.run();
            if (reviewed != null && scan instanceof ObjectNode summary) {
                summary.put("unchanged_reviewed_revision", config.path("custom").path(name).path("current").asText());
            }
            Path target = versionRoot(name, revision);
            requireNoLinkedAncestors(target);
            Files.createDirectories(target.getParent());
            requireNoLinks(target.getParent());
            publishDirectory(staging, target, name, content, revision, config);
            ObjectNode record = record(config, name);
            ArrayNode versions = record.withArray("versions");
            boolean exists = false;
            for (JsonNode version : versions) if (version.path("revision").asText().equals(revision)) exists = true;
            if (!exists) {
                var registration = new InMemorySkillSource(descriptor(name, revision), parser, SkillAvailability.ENABLED, Map.of(name, files))
                        .discover(SkillReviewApprovals.discovery()).registrations().getFirst();
                ObjectNode version = versions.addObject().put("revision", revision)
                        .put("source", author.equals("human") ? "user-upload-or-edit" : "agent-managed");
                var empty = version.putArray("empty_directories");
                new java.util.TreeSet<>(content.emptyDirectories()).forEach(empty::add);
                var executableFiles = version.putArray("executable_files");
                new java.util.TreeSet<>(content.executableFiles()).forEach(executableFiles::add);
                version.set("review", SkillReviewApprovals.evidence(json, registration, scan));
            }
            appendHistory(record, action, author, thread, file, previous, text(files.get(file)), scan);
            record.put("current", revision);
        } finally {
            deleteTreeQuietly(staging);
        }
    }

    private void publishDirectory(Path staging, Path target, String name, SkillPackageFiles.PackageContent content,
            String revision, ObjectNode config) throws IOException {
        if (Files.exists(target)) {
            if (isVersionRegistered(config, name, revision)) {
                if (!SkillPackageFiles.digest(packageVersion(config, name, revision)).equals(revision)) {
                    throw new IOException("Immutable Skill version changed");
                }
                return;
            }
            cleanResidualDirectory(target);
        }
        if (!Files.exists(target)) {
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (IOException renameFailure) {
                publishByMovingContent(staging, target, name, content, revision, renameFailure);
            }
        } else {
            publishByMovingContent(staging, target, name, content, revision, null);
        }
    }

    private static boolean isVersionRegistered(ObjectNode config, String name, String revision) {
        JsonNode versions = config.path("custom").path(name).path("versions");
        if (versions.isArray()) {
            for (JsonNode version : versions) {
                if (revision.equals(version.path("revision").asText(""))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void cleanResidualDirectory(Path target) {
        if (!Files.exists(target)) {
            return;
        }
        try (var walk = Files.walk(target)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                AtomicMoves.deleteQuietly(path);
            }
        } catch (IOException ignored) {
            // Best effort cleanup of residual uncommitted files.
        }
    }

    private void publishByMovingContent(Path staging, Path target, String name, SkillPackageFiles.PackageContent content,
            String revision, IOException renameFailure) throws IOException {
        try {
            Path targetPackageDir = target.resolve(name);
            Files.createDirectories(targetPackageDir);
            for (String directory : content.emptyDirectories()) {
                Files.createDirectories(targetPackageDir.resolve(directory));
            }
            Path stagingPackageDir = staging.resolve(name);
            for (var entry : content.files().entrySet()) {
                Path relativePath = Path.of(SkillPackageFiles.path(entry.getKey()));
                Path sourceFile = stagingPackageDir.resolve(relativePath);
                Path targetFile = targetPackageDir.resolve(relativePath);
                Files.createDirectories(targetFile.getParent());
                AtomicMoves.replace(sourceFile, targetFile);
                if (Files.getFileAttributeView(targetFile, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
                    String permissions = content.executableFiles().contains(entry.getKey()) ? "rwxr-xr-x" : "rw-r--r--";
                    Files.setPosixFilePermissions(targetFile, java.nio.file.attribute.PosixFilePermissions.fromString(permissions));
                }
            }
            if (Files.isDirectory(targetPackageDir)) {
                try (var walk = Files.walk(targetPackageDir)) {
                    for (Path path : walk.filter(Files::isRegularFile).toList()) {
                        String rel = SkillPackageFiles.path(targetPackageDir.relativize(path).toString().replace('\\', '/'));
                        if (!content.files().containsKey(rel)) {
                            AtomicMoves.deleteQuietly(path);
                        }
                    }
                }
            }
            Map<String, byte[]> publishedFiles = SkillPackageFiles.directory(targetPackageDir);
            var published = new SkillPackageFiles.PackageContent(publishedFiles, content.emptyDirectories(), content.executableFiles());
            if (!SkillPackageFiles.digest(published).equals(revision)) {
                throw new IOException("Skill version publish is incomplete");
            }
        } catch (IOException fallbackFailure) {
            if (renameFailure != null) {
                fallbackFailure.addSuppressed(renameFailure);
            }
            throw fallbackFailure;
        }
    }

    private static void deleteTreeQuietly(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                AtomicMoves.deleteQuietly(path);
            }
        } catch (IOException ignored) {
            // Cleanup failure must never mask publication results.
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
    private SkillPackageFiles.PackageContent reviewedCurrent(ObjectNode config, String name) throws IOException {
        JsonNode stored = config.path("custom").path(name);
        String current = stored.path("current").asText("");
        if (current.isEmpty()) return null;
        var packageContent = packageVersion(config, name, current);
        if (!SkillPackageFiles.digest(packageContent).equals(current)) throw new IOException("Custom Skill package hash mismatch");
        for (JsonNode version : stored.path("versions")) {
            if (!version.path("revision").asText().equals(current)) continue;
            var registration = new InMemorySkillSource(descriptor(name, current), parser, SkillAvailability.ENABLED, Map.of(name, packageContent.files()))
                    .discover(SkillReviewApprovals.discovery()).registrations().getFirst();
            var grant = SkillReviewApprovals.grant(registration, version.path("review"));
            if (grant.activeAt(Instant.now())) return packageContent;
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
