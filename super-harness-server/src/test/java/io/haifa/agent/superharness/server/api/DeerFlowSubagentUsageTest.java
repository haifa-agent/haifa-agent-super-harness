package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DeerFlowSubagentUsageTest {
    @Test void knownZeroAndExactBrowserIntegerBoundaryArePreserved() {
        assertThat(DeerFlowConversationService.sumUsageTokens(0, 0)).hasValue(0);
        assertThat(DeerFlowConversationService.sumUsageTokens(17, 24)).hasValue(41);
        assertThat(DeerFlowConversationService.sumUsageTokens(9_007_199_254_740_990L, 1))
                .hasValue(9_007_199_254_740_991L);
    }

    @Test void invalidSourceAndUnrepresentableSumsStayUnknown() {
        assertThat(DeerFlowConversationService.sumUsageTokens(-1, 1)).isEmpty();
        assertThat(DeerFlowConversationService.sumUsageTokens(9_007_199_254_740_992L, 0)).isEmpty();
        assertThat(DeerFlowConversationService.sumUsageTokens(9_007_199_254_740_991L, 1)).isEmpty();
        assertThat(DeerFlowConversationService.sumUsageTokens(Long.MAX_VALUE, Long.MAX_VALUE)).isEmpty();
    }
}
