package io.haifa.agent.superharness.server.prompt;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure request-scope user-input framing, the same boundary the adapted lead-agent template declares.
 *
 * <p>Only the two plain-text boundary markers are handled (frozen
 * {@code input_sanitization_middleware.py:139-221}): a forged marker inside untrusted text is
 * neutralized to a look-alike inert form before the whole text is wrapped. The full blocked-tag
 * denylist is intentionally out of scope for this slice and registered as a known difference.
 */
public final class InputTrustBoundary {
    static final String BEGIN = "--- BEGIN USER INPUT ---";
    static final String END = "--- END USER INPUT ---";
    static final String NEUTRALIZED_BEGIN = "[BEGIN USER INPUT]";
    static final String NEUTRALIZED_END = "[END USER INPUT]";
    private static final Pattern RAW_TOKEN = Pattern.compile("(\\\\*)(" + Pattern.quote(BEGIN) + "|"
            + Pattern.quote(END) + "|" + Pattern.quote(NEUTRALIZED_BEGIN) + "|"
            + Pattern.quote(NEUTRALIZED_END) + ")");
    private static final Pattern DISPLAY_TOKEN = Pattern.compile("(\\\\*)(" + Pattern.quote(NEUTRALIZED_BEGIN)
            + "|" + Pattern.quote(NEUTRALIZED_END) + ")");

    private InputTrustBoundary() {}

    /** Frame raw user text; even a complete user-supplied frame is untrusted. */
    public static String wrap(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        return BEGIN + "\n" + neutralize(text) + "\n" + END;
    }

    /**
     * Reverse {@link #wrap} for display projections so the frozen UI never shows the markers.
     * Upload context may precede the wrapped segment; only the boundary segment is removed.
     */
    public static String unwrapForDisplay(String text) {
        if (text == null) {
            return null;
        }
        // An upload filename in the trusted prefix may itself contain a marker. Raw user
        // markers are neutralized, so the final BEGIN is the actual outer frame.
        int begin = text.lastIndexOf(BEGIN);
        if (begin < 0) {
            return text;
        }
        int end = text.lastIndexOf(END);
        if (end < begin) {
            return text;
        }
        String inner = text.substring(begin + BEGIN.length(), end);
        if (inner.startsWith("\n")) {
            inner = inner.substring(1);
        }
        if (inner.endsWith("\n")) {
            inner = inner.substring(0, inner.length() - 1);
        }
        inner = restore(inner);
        return text.substring(0, begin) + inner + text.substring(end + END.length());
    }

    private static String neutralize(String text) {
        // Only token-adjacent backslashes are encoded. Ordinary paths and code stay byte-faithful.
        Matcher matcher = RAW_TOKEN.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int previous = 0;
        while (matcher.find()) {
            out.append(text, previous, matcher.start());
            String token = matcher.group(2);
            boolean forged = token.equals(BEGIN) || token.equals(END);
            int slashes = matcher.group(1).length() * 2 + (forged ? 0 : 1);
            out.append("\\".repeat(slashes));
            out.append(token.equals(BEGIN) ? NEUTRALIZED_BEGIN : token.equals(END) ? NEUTRALIZED_END : token);
            previous = matcher.end();
        }
        return out.append(text, previous, text.length()).toString();
    }

    private static String restore(String text) {
        Matcher matcher = DISPLAY_TOKEN.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int previous = 0;
        while (matcher.find()) {
            out.append(text, previous, matcher.start());
            int slashes = matcher.group(1).length();
            out.append("\\".repeat(slashes / 2));
            String token = matcher.group(2);
            out.append(slashes % 2 == 1 ? token : token.equals(NEUTRALIZED_BEGIN) ? BEGIN : END);
            previous = matcher.end();
        }
        return out.append(text, previous, text.length()).toString();
    }
}
