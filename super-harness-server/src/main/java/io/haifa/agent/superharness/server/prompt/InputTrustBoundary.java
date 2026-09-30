package io.haifa.agent.superharness.server.prompt;

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

    private InputTrustBoundary() {}

    /** Neutralize forged markers and wrap non-blank text; already-wrapped text is returned stable. */
    public static String wrap(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        if (text.startsWith(BEGIN) && text.endsWith(END)) {
            String inner = text.substring(BEGIN.length(), text.length() - END.length());
            String neutralized = neutralize(inner);
            return neutralized.equals(inner) ? text : BEGIN + neutralized + END;
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
        int begin = text.indexOf(BEGIN);
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
        inner = inner.replace(NEUTRALIZED_BEGIN, BEGIN).replace(NEUTRALIZED_END, END);
        return text.substring(0, begin) + inner + text.substring(end + END.length());
    }

    private static String neutralize(String text) {
        return text.replace(BEGIN, NEUTRALIZED_BEGIN).replace(END, NEUTRALIZED_END);
    }
}
