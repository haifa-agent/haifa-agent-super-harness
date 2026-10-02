package io.haifa.agent.superharness.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.haifa.agent.superharness.server.tool.file.UploadFileFacts;
import io.haifa.agent.superharness.workspace.RestrictedWorkspaceService;
import io.haifa.agent.superharness.workspace.WorkspaceBinding;
import io.haifa.agent.superharness.workspace.WorkspaceException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Run-input half of frozen DeerFlow's {@code UploadsMiddleware}.
 *
 * <p>The frozen UI names the files of the current message in the last human message's
 * {@code additional_kwargs.files}. Only files that exist in this Thread's own uploads are listed
 * with their verified SHA-256, size and UTF-8 status; the contents are never inlined, the model
 * reads them with {@code read_file}. Unlike the frozen middleware, which silently drops entries
 * missing on disk, references that cannot be read are stated explicitly so the model does not
 * guess. Client-supplied paths are ignored and invalid filenames are counted, never echoed.
 */
final class DeerFlowUploadContext {
    static final int MAX_FILES_PER_CONTEXT = 10;
    private static final String UPLOADS = "/mnt/user-data/uploads/";

    private final RestrictedWorkspaceService workspace;

    DeerFlowUploadContext(RestrictedWorkspaceService workspace) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
    }

    /** Returns the text submitted to the model: the user text, prefixed by a bounded upload block. */
    String modelInput(String threadId, JsonNode messages, String userText) {
        JsonNode files = lastHumanFiles(messages);
        if (files == null || !files.isArray() || files.isEmpty()) {
            return userText;
        }
        Set<String> filenames = new LinkedHashSet<>();
        int rejected = 0;
        for (JsonNode file : files) {
            JsonNode filename = file.path("filename");
            if (filename.isTextual() && validFilename(filename.textValue())) {
                filenames.add(filename.textValue());
            } else {
                rejected++;
            }
        }
        List<String> readable = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        int listed = 0;
        int omitted = 0;
        WorkspaceBinding binding = null;
        for (String filename : filenames) {
            if (listed == MAX_FILES_PER_CONTEXT) {
                omitted++;
                continue;
            }
            listed++;
            String path = UPLOADS + filename;
            try {
                if (binding == null) {
                    binding = workspace.bind(DeerFlowBootstrapApi.DEFAULT_USER_ID, threadId);
                }
                UploadFileFacts facts = UploadFileFacts.of(
                        workspace.readVerifiedBytes(binding.ownerId(), binding.workspaceId(), path));
                readable.add("- " + filename + " (" + displaySize(facts.size()) + ")\n"
                        + "  Path: " + path + "\n"
                        + "  SHA-256: " + facts.sha256() + "\n"
                        + "  Bytes: " + facts.size() + "\n"
                        + "  Encoding: " + facts.encoding()
                        + (facts.text().isPresent() ? " (" + facts.lineCount() + " lines)" : "")
                        + "\n");
            } catch (WorkspaceException failure) {
                unavailable.add("- " + filename + ": unavailable, " + reason(failure) + " (" + path + ")");
            }
        }
        StringBuilder block = new StringBuilder("<current_uploads>\n")
                .append("The following files were uploaded in this message:\n\n");
        if (readable.isEmpty()) {
            block.append("(empty)\n\n");
        }
        readable.forEach(entry -> block.append(entry).append('\n'));
        if (!unavailable.isEmpty()) {
            block.append("These referenced files cannot be read by this Run; say so instead of guessing:\n");
            unavailable.forEach(entry -> block.append(entry).append('\n'));
            block.append('\n');
        }
        if (omitted > 0) {
            block.append("... (").append(omitted).append(" more file(s) from this message omitted from this context.)\n\n");
        }
        if (rejected > 0) {
            block.append(rejected).append(" file reference(s) rejected (invalid filename).\n\n");
        }
        block.append("To work with these files:\n")
                .append("- Read the saved original with `read_file` before answering; use start_line/end_line for large files.\n")
                .append("- Only report values that `read_file` returned. A file that is not valid UTF-8 cannot be read as text.\n")
                .append("</current_uploads>");
        return block + "\n\n" + userText;
    }

    /**
     * Filenames referenced by the newest human message's {@code additional_kwargs.files}.
     * {@code list_uploaded_files} uses this to exclude the current Run's uploads.
     */
    static Set<String> currentUploadFilenames(JsonNode messages) {
        Set<String> filenames = new LinkedHashSet<>();
        JsonNode files = lastHumanFiles(messages);
        if (files == null || !files.isArray()) {
            return filenames;
        }
        for (JsonNode file : files) {
            JsonNode filename = file.path("filename");
            if (filename.isTextual() && validFilename(filename.textValue())) {
                filenames.add(filename.textValue());
            }
        }
        return filenames;
    }

    private static JsonNode lastHumanFiles(JsonNode messages) {
        if (messages == null || !messages.isArray()) {
            return null;
        }
        JsonNode result = null;
        for (JsonNode message : messages) {
            String type = message.path("type").asText(message.path("role").asText());
            if (type.equals("human") || type.equals("user")) {
                result = message.path("additional_kwargs").get("files");
            }
        }
        return result;
    }

    private static boolean validFilename(String filename) {
        return !filename.isBlank()
                && filename.length() <= 255
                && filename.equals(filename.trim())
                && !filename.equals(".")
                && !filename.equals("..")
                && filename.chars().noneMatch(character -> character < 32 || "/\\<>:\"|?*".indexOf(character) >= 0);
    }

    private static String reason(WorkspaceException failure) {
        return switch (failure.error()) {
            case FILE_NOT_FOUND, WORKSPACE_NOT_FOUND -> "not found in this conversation's uploads";
            case FILE_TOO_LARGE -> "exceeds the readable size limit";
            case INVALID_PATH, PATH_ESCAPE -> "not a readable upload path";
            default -> "the saved original could not be verified";
        };
    }

    private static String displaySize(long bytes) {
        double kilobytes = bytes / 1024.0;
        return kilobytes < 1024
                ? String.format(Locale.ROOT, "%.1f KB", kilobytes)
                : String.format(Locale.ROOT, "%.1f MB", kilobytes / 1024.0);
    }
}
