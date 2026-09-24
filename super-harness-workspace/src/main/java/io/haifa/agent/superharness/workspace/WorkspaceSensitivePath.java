package io.haifa.agent.superharness.workspace;

import java.util.Locale;

/** Shared DeerFlow-compatible classifier used before workspace content is read or hashed. */
public final class WorkspaceSensitivePath {
    private WorkspaceSensitivePath() {}

    public static boolean matches(String path) {
        String normalized = LogicalWorkspacePath.normalize(path).toLowerCase(Locale.ROOT);
        for (String segment : normalized.split("/")) {
            if (segment.equals(".env") || segment.startsWith(".env.")
                    || segment.endsWith(".key") || segment.endsWith(".pem")
                    || containsSensitiveToken(segment)) {
                return true;
            }
        }
        return containsSensitiveToken(normalized);
    }

    private static boolean containsSensitiveToken(String value) {
        return value.contains("api_key") || value.contains("apikey")
                || value.contains("credential") || value.contains("password")
                || value.contains("private_key") || value.contains("secret")
                || value.contains("token");
    }
}
