package io.haifa.agent.superharness.server.config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Bounded product archive IO; all Skill parsing and bindings remain in the SDK. */
public final class SkillPackageFiles {
    public static final int MAX_ARCHIVE_BYTES = 100 * 1024 * 1024;
    static final int MAX_PACKAGE_BYTES = 512 * 1024 * 1024;
    static final int MAX_FILE_BYTES = 64 * 1024 * 1024;
    static final int MAX_FILES = 4096;
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    private SkillPackageFiles() {}

    public static String name(String name) {
        if (name == null || !NAME.matcher(name).matches()) throw new IllegalArgumentException("Invalid Skill name");
        return name;
    }

    static String path(String path) {
        if (path == null || path.isEmpty() || path.getBytes(StandardCharsets.UTF_8).length > 1024 || path.contains("\\")
                || path.startsWith("/") || path.contains(":") || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Unsafe Skill package path");
        }
        String[] parts = path.split("/", -1);
        if (parts.length > 32) throw new IllegalArgumentException("Skill package path is too deep");
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".")
                    || part.endsWith(" ") || part.chars().anyMatch(c -> Character.isISOControl(c) || "<>\"|?*".indexOf(c) >= 0)
                    || part.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(?:\\..*)?")) {
                throw new IllegalArgumentException("Unsafe Skill package path");
            }
        }
        return path;
    }

    public static Map<String, byte[]> archive(byte[] bytes) throws IOException {
        if (bytes.length > MAX_ARCHIVE_BYTES) throw new IllegalArgumentException("Skill archive is too large");
        validateCentralDirectory(bytes);
        Map<String, byte[]> files = new TreeMap<>();
        Set<String> paths = new java.util.HashSet<>();
        int total = 0, count = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (++count > MAX_FILES) throw new IllegalArgumentException("Too many Skill archive entries");
                String key = entry.getName();
                if (entry.isDirectory()) key = key.substring(0, key.length() - 1);
                path(key);
                if (!paths.add(java.text.Normalizer.normalize(key, java.text.Normalizer.Form.NFC).toLowerCase(java.util.Locale.ROOT))) {
                    throw new IllegalArgumentException("Duplicate Skill archive path");
                }
                if (entry.isDirectory()) continue;
                byte[] value = zip.readNBytes(MAX_FILE_BYTES + 1);
                if (value.length > MAX_FILE_BYTES || (long) total + value.length > MAX_PACKAGE_BYTES) {
                    throw new IllegalArgumentException("Skill package exceeds size limits");
                }
                total += value.length;
                if (executableBinary(value)) throw new IllegalArgumentException("Skill archive contains executable binary content");
                files.put(key, value);
            }
        }
        if (files.isEmpty()) throw new IllegalArgumentException("Skill archive is empty");
        // Frozen installer accepts either a flat package or one enclosing directory.
        if (!files.containsKey("SKILL.md")) {
            String prefix = files.keySet().iterator().next().split("/", 2)[0] + "/";
            if (files.keySet().stream().anyMatch(key -> !key.startsWith(prefix))
                    || !files.containsKey(prefix + "SKILL.md")) {
                throw new IllegalArgumentException("Skill archive must contain one root SKILL.md");
            }
            Map<String, byte[]> stripped = new TreeMap<>();
            files.forEach((key, value) -> stripped.put(key.substring(prefix.length()), value));
            files = stripped;
        }
        if (files.keySet().stream().anyMatch(key -> !key.equals("SKILL.md") && key.endsWith("/SKILL.md"))) {
            throw new IllegalArgumentException("Nested SKILL.md is not accepted by the installer");
        }
        return files;
    }

    private static boolean executableBinary(byte[] value) {
        if (value.length >= 2 && value[0] == 'M' && value[1] == 'Z') return true;
        if (value.length < 4) return false;
        int magic = ByteBuffer.wrap(value).getInt();
        return Set.of(0x7f454c46, 0xfeedface, 0xfeedfacf, 0xcefaedfe, 0xcffaedfe,
                0xcafebabe, 0xbebafeca, 0xcafebabf, 0xbfbafeca).contains(magic);
    }

    private static void validateCentralDirectory(byte[] bytes) {
        // JDK ZIP streams do not expose Unix external attributes; read only the central
        // metadata to reject links/encryption/ZIP64 before any filesystem mutation.
        int end = -1;
        for (int i = bytes.length - 22; i >= Math.max(0, bytes.length - 65557); i--) {
            if (u32(bytes, i) == 0x06054b50L && i + 22 + u16(bytes, i + 20) == bytes.length) { end = i; break; }
        }
        if (end < 0 || u16(bytes, end + 4) != 0 || u16(bytes, end + 6) != 0
                || u16(bytes, end + 8) != u16(bytes, end + 10)) {
            throw new IllegalArgumentException("Invalid Skill ZIP archive");
        }
        int count = u16(bytes, end + 10);
        long offset = u32(bytes, end + 16), size = u32(bytes, end + 12);
        if (count == 0 || count > MAX_FILES || offset + size != end || offset > bytes.length) {
            throw new IllegalArgumentException("Invalid or excessive Skill ZIP directory");
        }
        int current = (int) offset;
        for (int i = 0; i < count; i++) {
            if (current + 46 > end || u32(bytes, current) != 0x02014b50L) {
                throw new IllegalArgumentException("Invalid Skill ZIP entry");
            }
            int mode = (int) (u32(bytes, current + 38) >>> 16);
            if ((mode & 0170000) == 0120000 || (u16(bytes, current + 8) & 1) != 0
                    || u32(bytes, current + 20) == 0xffffffffL || u32(bytes, current + 24) == 0xffffffffL) {
                throw new IllegalArgumentException("Skill archive links/encryption/ZIP64 are unsupported");
            }
            current += 46 + u16(bytes, current + 28) + u16(bytes, current + 30) + u16(bytes, current + 32);
        }
        if (current != end) throw new IllegalArgumentException("Invalid Skill ZIP directory length");
    }

    private static int u16(byte[] bytes, int offset) {
        if (offset < 0 || offset + 2 > bytes.length) return -1;
        return Short.toUnsignedInt(ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort());
    }

    private static long u32(byte[] bytes, int offset) {
        if (offset < 0 || offset + 4 > bytes.length) return -1;
        return Integer.toUnsignedLong(ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
    }

    static Map<String, byte[]> directory(Path root) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        long total = 0;
        try (var walk = Files.walk(root)) {
            var iterator = walk.iterator();
            int count = 0;
            while (iterator.hasNext()) {
                Path file = iterator.next();
                if (++count > MAX_FILES + 1) throw new IllegalArgumentException("Too many Skill files");
                if (Files.isSymbolicLink(file)) throw new IllegalArgumentException("Skill package contains a link");
                if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Unsafe Skill file");
                String relative = path(root.relativize(file).toString().replace('\\', '/'));
                long length = Files.size(file);
                if (length > MAX_FILE_BYTES || total + length > MAX_PACKAGE_BYTES) throw new IllegalArgumentException("Skill package is too large");
                byte[] content;
                try (var input = Files.newInputStream(file)) { content = input.readNBytes(MAX_FILE_BYTES + 1); }
                if (content.length != length) throw new IllegalArgumentException("Skill package changed during read");
                files.put(relative, content); total += length;
            }
        }
        return files;
    }

    static String digest(Map<String, byte[]> files) {
        MessageDigest digest = sha();
        new TreeMap<>(files).forEach((path, content) -> {
            byte[] key = path.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(8).putLong(key.length).array()); digest.update(key);
            digest.update(ByteBuffer.allocate(8).putLong(content.length).array()); digest.update(content);
        });
        return HexFormat.of().formatHex(digest.digest());
    }

    static String hash(byte[] content) { return HexFormat.of().formatHex(sha().digest(content)); }
    private static MessageDigest sha() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static byte[] export(String name, Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (var row : exportRows(files)) {
                String relative = row.get("path").toString();
                boolean directory = row.get("type").equals("directory");
                ZipEntry member = new ZipEntry(name(name) + "/" + (relative.equals(".") ? "" : path(relative)) + (directory && !relative.equals(".") ? "/" : ""));
                member.setTime(0); zip.putNextEntry(member);
                if (!directory) zip.write(files.get(relative));
                zip.closeEntry();
                if (bytes.size() > MAX_ARCHIVE_BYTES) throw new IllegalArgumentException("Skill export is too large");
            }
        }
        if (bytes.size() > MAX_ARCHIVE_BYTES) throw new IllegalArgumentException("Skill export is too large");
        return bytes.toByteArray();
    }

    public static Map<String, Object> exportManifest(String name, Map<String, byte[]> files) {
        List<Map<String, Object>> rows = exportRows(files);
        List<Map<String, Object>> blockers = new ArrayList<>(), warnings = new ArrayList<>();
        files.forEach((path, bytes) -> {
            if (!path.equals("SKILL.md") && path.endsWith("/SKILL.md")) blockers.add(Map.of("code", "skill_export_nested_skill", "path", path,
                    "message", "Nested SKILL.md files are not accepted by the installer."));
            if (executableBinary(bytes)) blockers.add(Map.of("code", "skill_export_executable_binary", "path", path,
                    "message", "Executable binary files are not accepted by the installer."));
            for (String component : path.split("/")) {
                String lower = component.toLowerCase(java.util.Locale.ROOT);
                if (Set.of(".env", ".npmrc", ".pypirc", ".netrc", ".git", ".svn", ".hg", "credentials.json", "id_rsa", "id_ed25519").contains(lower)
                        || lower.startsWith(".env.")) {
                    warnings.add(Map.of("code", "skill_export_sensitive_filename", "path", path, "message", "Review this filename for credentials before sharing.")); break;
                }
            }
        });
        if (rows.size() > MAX_FILES || files.values().stream().mapToLong(bytes -> bytes.length).sum() > MAX_ARCHIVE_BYTES) {
            throw new IllegalArgumentException("Skill export exceeds size limits");
        }
        long directoryCount = rows.stream().filter(row -> row.get("type").equals("directory")).count();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("skill_name", name); response.put("revision", blockers.isEmpty() ? exportRevision(name, files, rows) : null); response.put("can_export", blockers.isEmpty());
        response.put("file_count", files.size()); response.put("directory_count", directoryCount);
        response.put("total_bytes", files.values().stream().mapToLong(bytes -> bytes.length).sum());
        response.put("files", rows);
        Map<String, Object> requirements = new LinkedHashMap<>();
        requirements.put("compatibility", null); requirements.put("allowed_tools", null); requirements.put("required_secrets", null);
        try {
            String markdown = new String(files.get("SKILL.md"), StandardCharsets.UTF_8).replace("\r\n", "\n");
            int end = markdown.indexOf("\n---", 4);
            if (!markdown.startsWith("---\n") || end < 0 || end > 1024 * 1024) throw new IllegalArgumentException("Invalid export frontmatter");
            var yaml = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
            var metadata = yaml.readTree(markdown.substring(4, end));
            if (metadata.has("allowed-tools") || metadata.has("required-secrets") || metadata.has("secrets-autonomous")) {
                warnings.add(Map.of("code", "skill_export_platform_declarations", "path", "SKILL.md", "message", "Declared tools and secrets require configuration in the target environment."));
            }
            if (metadata.path("compatibility").isTextual()) requirements.put("compatibility", metadata.path("compatibility").asText());
            var tools = metadata.get("allowed-tools");
            if (tools != null) {
                List<String> allowed = new ArrayList<>();
                if (tools.isArray()) tools.forEach(tool -> { if (tool.isTextual()) allowed.add(tool.asText()); });
                else if (tools.isTextual()) allowed.addAll(portableTools(tools.asText()));
                requirements.put("allowed_tools", allowed);
            }
            var secrets = metadata.get("required-secrets");
            if (secrets != null && secrets.isArray()) {
                List<Map<String, Object>> declared = new ArrayList<>(); Set<String> names = new java.util.HashSet<>();
                for (var secret : secrets) {
                    String declaredName = secret.isTextual() ? secret.asText().strip() : secret.path("name").asText().strip();
                    if (declaredName.matches("[A-Za-z_][A-Za-z0-9_]*") && names.add(declaredName)) {
                        declared.add(Map.of("name", declaredName, "optional", secret.path("optional").asBoolean(false)));
                    } else if (!declaredName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                        warnings.add(Map.of("code", "skill_export_invalid_declaration", "path", "SKILL.md", "message", "Malformed required-secrets declaration omitted."));
                    }
                }
                requirements.put("required_secrets", declared);
            }
        } catch (IOException | RuntimeException invalid) {
            throw new IllegalArgumentException("Skill export frontmatter is unsupported");
        }
        response.put("requirements", requirements); response.put("warnings", warnings); response.put("blockers", blockers);
        return response;
    }

    // Frozen export display normalization only. These strings never grant Tool permissions.
    private static List<String> portableTools(String raw) {
        List<String> tokens = new ArrayList<>(); StringBuilder current = new StringBuilder();
        int depth = 0; char quote = 0; boolean escaped = false;
        for (char value : raw.toCharArray()) {
            if (escaped) { current.append(value); escaped = false; continue; }
            if (value == '\\') { current.append(value); escaped = true; continue; }
            if (quote != 0) { current.append(value); if (value == quote) quote = 0; continue; }
            if (value == '\'' || value == '"') { quote = value; current.append(value); continue; }
            if (Character.isWhitespace(value) && depth == 0) {
                if (!current.isEmpty()) { tokens.add(current.toString()); current.setLength(0); } continue;
            }
            if (value == '(') depth++;
            else if (value == ')' && --depth < 0) throw new IllegalArgumentException("Malformed allowed-tools declaration");
            current.append(value);
        }
        if (quote != 0 || depth != 0) throw new IllegalArgumentException("Malformed allowed-tools declaration");
        if (!current.isEmpty()) tokens.add(current.toString());
        Map<String, String> aliases = Map.of("Bash", "bash", "Edit", "str_replace", "Glob", "glob", "Grep", "grep",
                "Read", "read_file", "WebFetch", "web_fetch", "WebSearch", "web_search", "Write", "write_file");
        return tokens.stream().map(tool -> aliases.getOrDefault(tool, tool)).distinct().toList();
    }

    private static List<Map<String, Object>> exportRows(Map<String, byte[]> files) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> directories = new java.util.TreeSet<>();
        directories.add(".");
        for (var entry : new TreeMap<>(files).entrySet()) {
            Path path = Path.of(entry.getKey()).getParent();
            while (path != null) { directories.add(path.toString().replace('\\', '/')); path = path.getParent(); }
            rows.add(Map.of("path", entry.getKey(), "type", "file", "size", entry.getValue().length, "executable", false));
        }
        directories.forEach(dir -> rows.add(Map.of("path", dir, "type", "directory", "size", 0, "executable", false)));
        rows.sort((left, right) -> java.util.Arrays.compareUnsigned(
                (left.get("path").equals(".") ? "" : left.get("path").toString()).getBytes(StandardCharsets.UTF_8),
                (right.get("path").equals(".") ? "" : right.get("path").toString()).getBytes(StandardCharsets.UTF_8)));
        return rows;
    }

    private static String exportRevision(String name, Map<String, byte[]> files, List<Map<String, Object>> rows) {
        MessageDigest digest = sha();
        field(digest, "deerflow-skill-export-v1".getBytes(StandardCharsets.UTF_8));
        field(digest, name.getBytes(StandardCharsets.UTF_8));
        for (var row : rows) {
            String path = row.get("path").toString();
            field(digest, (path.equals(".") ? "" : path).getBytes(StandardCharsets.UTF_8));
            field(digest, row.get("type").toString().getBytes(StandardCharsets.US_ASCII));
            field(digest, row.get("size").toString().getBytes(StandardCharsets.US_ASCII));
            field(digest, files.containsKey(path) ? sha().digest(files.get(path)) : new byte[0]);
            field(digest, "0".getBytes(StandardCharsets.US_ASCII));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void field(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(8).putLong(value.length).array()); digest.update(value);
    }
}
