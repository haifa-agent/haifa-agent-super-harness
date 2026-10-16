package io.haifa.agent.superharness.server.channels.feishu;

import java.util.Locale;
import java.util.Set;

/**
 * Allowlisted Feishu/Lark Open Platform base URLs. Fail closed for any other host.
 */
public final class FeishuOpenDomain {
    public static final String FEISHU_CN = "https://open.feishu.cn";
    public static final String LARK_SUITE = "https://open.larksuite.com";
    public static final String DEFAULT = FEISHU_CN;

    private static final Set<String> ALLOWED = Set.of(FEISHU_CN, LARK_SUITE);

    private FeishuOpenDomain() {}

    /** Blank → default; otherwise must be one of the two Open Platform bases (no trailing slash). */
    public static String normalizeOrThrow(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        String trimmed = raw.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!ALLOWED.contains(lower)) {
            throw new IllegalArgumentException(
                    "Unsupported Feishu domain. Use https://open.feishu.cn or https://open.larksuite.com.");
        }
        // Preserve canonical casing from the allowlist.
        return ALLOWED.contains(trimmed) ? trimmed : lower;
    }

    public static boolean isAllowed(String raw) {
        try {
            normalizeOrThrow(raw);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
