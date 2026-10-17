package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The diagnostic contract of a swallowed Run admission failure: the frozen response stays the generic
 * {@code Run could not start}, so the bounded log line is the only place the failure survives.
 */
class RunAdmissionDiagnosticTest {

    @Test
    void carriesTheFrozenRouteAndTheFailureClass() {
        String message = DeerFlowConversationService.admissionFailureMessage(
                new IllegalStateException("bridge assembly refused"));

        assertThat(message)
                .isEqualTo("Run admission failure on " + DeerFlowConversationService.RUN_STREAM_ROUTE
                        + ": " + IllegalStateException.class.getName());
    }

    @Test
    void recordsTheCauseClassesAndNeverTheMessages() {
        var failure = new IllegalStateException("outer detail",
                new java.io.IOException(
                        "trusted script runtime executable does not exist as an exact file"));

        String message = DeerFlowConversationService.admissionFailureMessage(failure);

        assertThat(message).contains(java.io.IOException.class.getName());
        // Admission fails before the request is validated, so a message may echo client text or a
        // provider body; the line must never carry one.
        assertThat(message).doesNotContain("outer detail").doesNotContain("exact file");
    }

    @Test
    void boundsTheCauseChain() {
        Throwable nested = new IllegalStateException("deepest");
        for (int depth = 0; depth < 6; depth++) {
            nested = new IllegalStateException("layer " + depth, nested);
        }

        String message = DeerFlowConversationService.admissionFailureMessage(
                new IllegalStateException("root", nested));

        assertThat(message.split(" <- ", -1).length).isBetween(2, 4); // the cause chain is bounded
        assertThat(message).doesNotContain("layer").doesNotContain("deepest");
    }

    @Test
    void staysSingleLineEvenWhenAnExceptionMessageIsNot() {
        String message = DeerFlowConversationService.admissionFailureMessage(
                new IllegalStateException("first\r\nsecond"));

        assertThat(message).doesNotContain("\n").doesNotContain("\r");
    }

    @Test
    void keepsTheClassWhenTheFailureHasNoCause() {
        assertThat(DeerFlowConversationService.admissionFailureMessage(new IllegalStateException()))
                .isEqualTo("Run admission failure on " + DeerFlowConversationService.RUN_STREAM_ROUTE
                        + ": " + IllegalStateException.class.getName());
    }
}
