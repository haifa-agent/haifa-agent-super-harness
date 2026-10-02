package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Diagnostic formatting controls; real restart cases collect the actual SDK throwing site. */
class InteractionFailureDiagnosticTest {
    private static final String PRIVATE_TEXT = "synthetic-private-SOUL-and-arguments";

    @Test
    void usesFirstHaifaFrameFromDeepestCauseWithoutMessagesOrFilePaths() {
        var deepest = new IllegalStateException(PRIVATE_TEXT);
        deepest.setStackTrace(new StackTraceElement[] {
                frame("java.util.Objects", "requireNonNull", 1),
                frame("io.haifa.agent.store.sqlite.SqliteInteractionPort", "fromRecordRow", 433),
                frame("io.haifa.agent.store.sqlite.SqliteInteractionPort", "execute", 611)
        });
        var wrapper = new RuntimeException(PRIVATE_TEXT, deepest);
        wrapper.setStackTrace(new StackTraceElement[] {
                frame("io.haifa.agent.superharness.server.api.DeerFlowThreadRunApi", "handle", 1)
        });

        String site = DeerFlowThreadRunApi.interactionFailureSourceSite(wrapper);
        assertThat(site).isEqualTo("io.haifa.agent.store.sqlite.SqliteInteractionPort#fromRecordRow:433")
                .doesNotContain(PRIVATE_TEXT, "execute", "handle", "C:\\", ".java");
    }

    @Test
    void missingHaifaFrameOnDeepestCauseDoesNotAttributeAnOuterWrapper() {
        var deepest = new IllegalStateException(PRIVATE_TEXT);
        deepest.setStackTrace(new StackTraceElement[] {frame("java.lang.Thread", "run", 10)});
        var wrapper = new RuntimeException(PRIVATE_TEXT, deepest);
        wrapper.setStackTrace(new StackTraceElement[] {
                frame("io.haifa.agent.store.sqlite.SqliteInteractionPort", "execute", 611)
        });
        assertThat(DeerFlowThreadRunApi.interactionFailureSourceSite(wrapper)).isEqualTo("unavailable");
    }

    @Test
    void malformedFrameIdentifiersDoNotInjectDiagnosticText() {
        var failure = new IllegalStateException(PRIVATE_TEXT);
        failure.setStackTrace(new StackTraceElement[] {
                frame("io.haifa.agent.Bad\n" + PRIVATE_TEXT, "execute", 1),
                frame("io.haifa.agent.store.sqlite.SqliteInteractionPort", "execute\n" + PRIVATE_TEXT, 2),
                frame("io.haifa.agent.store.sqlite.SqliteInteractionPort", "<init>", -1)
        });
        assertThat(DeerFlowThreadRunApi.interactionFailureSourceSite(failure))
                .isEqualTo("io.haifa.agent.store.sqlite.SqliteInteractionPort#<init>:-1")
                .doesNotContain(PRIVATE_TEXT, "\n");
    }

    @Test
    void cyclicOverdeepEmptyAndAbsentCausesRemainBoundedAndUnattributed() {
        var first = new IllegalStateException(PRIVATE_TEXT);
        var second = new IllegalStateException(PRIVATE_TEXT);
        first.initCause(second);
        second.initCause(first);
        assertThat(DeerFlowThreadRunApi.interactionFailureSourceSite(first)).isEqualTo("unavailable");

        Throwable deep = new IllegalStateException(PRIVATE_TEXT);
        deep.setStackTrace(new StackTraceElement[] {frame("io.haifa.agent.core.run.AgentRun", "restore", 1)});
        for (int i = 0; i < 32; i++) deep = new RuntimeException(PRIVATE_TEXT, deep);
        assertThat(DeerFlowThreadRunApi.interactionFailureSourceSite(deep)).isEqualTo("unavailable");
        var empty = new IllegalStateException(PRIVATE_TEXT);
        empty.setStackTrace(new StackTraceElement[0]);
        assertThat(DeerFlowThreadRunApi.interactionFailureSourceSite(empty)).isEqualTo("unavailable");
        assertThat(DeerFlowThreadRunApi.interactionFailureSourceSite(null)).isEqualTo("unavailable");
    }

    private static StackTraceElement frame(String type, String method, int line) {
        return new StackTraceElement(type, method, "C:\\private\\" + PRIVATE_TEXT + ".java", line);
    }
}
