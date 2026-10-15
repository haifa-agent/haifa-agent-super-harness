package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The diagnostic contract of a swallowed Run admission failure. The frozen response stays the generic
 * {@code Run could not start}, so this bounded server-side line is the only place the failure survives:
 * an admission failure that never reached the Runtime has no durable Run record to inspect later.
 */
class RunAdmissionDiagnosticTest {

    @Test
    void carriesTheFrozenRouteAndTheFailureClass() {
        String message = DeerFlowThreadRunApi.admissionFailureMessage(new IllegalStateException(
                "trusted script runtime executable does not exist as an exact file"));

        assertThat(message)
                .contains(DeerFlowThreadRunApi.RUN_STREAM_ROUTE)
                .contains(IllegalStateException.class.getName())
                .contains("trusted script runtime executable does not exist as an exact file");
    }

    @Test
    void boundsTheReasonToOneLine() {
        String message = DeerFlowThreadRunApi.admissionFailureMessage(
                new IllegalStateException("first\r\nsecond " + "x".repeat(500)));

        // CR/LF collapsed so a client-influenced reason cannot forge extra log lines, and truncated
        // so one admission failure cannot flood the log with a provider body.
        assertThat(message).doesNotContain("\n").doesNotContain("\r");
        assertThat(message).contains("first  second");
        assertThat(message).endsWith("...)");
        assertThat(message.length()).isLessThan(400);
    }

    @Test
    void keepsTheClassWhenTheFailureCarriesNoReason() {
        assertThat(DeerFlowThreadRunApi.admissionFailureMessage(new IllegalStateException()))
                .isEqualTo("Run admission failure on " + DeerFlowThreadRunApi.RUN_STREAM_ROUTE
                        + ": " + IllegalStateException.class.getName());
    }
}
