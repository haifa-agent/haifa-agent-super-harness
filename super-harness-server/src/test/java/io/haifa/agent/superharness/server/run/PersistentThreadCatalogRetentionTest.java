package io.haifa.agent.superharness.server.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PersistentThreadCatalogRetentionTest {
    private static final Instant CREATED_AT = Instant.parse("2026-10-17T00:00:00Z");

    private static PersistentThreadCatalog.InteractionDisplay approval(int sequence, String answered) {
        return new PersistentThreadCatalog.InteractionDisplay(
                "run-" + sequence, "request-" + sequence, sequence, CREATED_AT,
                "tool-call-" + sequence, "Approval required", "Approve tool " + sequence, "context",
                List.of("approve", "reject"), answered);
    }

    private static PersistentThreadCatalog.ClarificationDisplay clarification(int sequence, String answered) {
        return new PersistentThreadCatalog.ClarificationDisplay(
                "run-" + sequence, "clarification-" + sequence, sequence, CREATED_AT,
                "tool-call-" + sequence, answered, answered == null ? null : "approve",
                answered == null ? null : "approve", answered == null ? null : "run-" + sequence + "-answer");
    }

    @Test
    void dropsTheOldestAnsweredDisplaysSoTheWaitingCardFits() {
        List<PersistentThreadCatalog.InteractionDisplay> displays = new ArrayList<>();
        for (int sequence = 1; sequence <= 64; sequence++) {
            displays.add(approval(sequence, "approve"));
        }
        displays.add(approval(65, null));

        List<PersistentThreadCatalog.InteractionDisplay> retained =
                PersistentThreadCatalog.retainDisplaysWithinCapacity(displays);

        assertThat(retained).hasSize(64);
        assertThat(retained.get(0).requestSequence()).isEqualTo(2L);
        assertThat(retained.get(retained.size() - 1).requestSequence()).isEqualTo(65L);
        assertThat(retained.get(retained.size() - 1).answeredOption()).isNull();
        assertThat(retained).extracting(PersistentThreadCatalog.InteractionDisplay::requestSequence)
                .doesNotHaveDuplicates();
        PersistentThreadCatalog.validateDisplays(retained);
    }

    @Test
    void neverDropsUnansweredDisplaysAndRejectsAnOverfullWaitingSet() {
        List<PersistentThreadCatalog.InteractionDisplay> displays = new ArrayList<>();
        for (int sequence = 1; sequence <= 65; sequence++) {
            displays.add(approval(sequence, null));
        }

        assertThatThrownBy(() -> PersistentThreadCatalog.retainDisplaysWithinCapacity(displays))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too many interaction displays");
    }

    @Test
    void clarificationRetentionDropsTheOldestAnsweredCards() {
        List<PersistentThreadCatalog.ClarificationDisplay> displays = new ArrayList<>();
        for (int sequence = 1; sequence <= 64; sequence++) {
            displays.add(clarification(sequence, "option"));
        }
        displays.add(clarification(65, null));

        List<PersistentThreadCatalog.ClarificationDisplay> retained =
                PersistentThreadCatalog.retainClarificationsWithinCapacity(displays);

        assertThat(retained).hasSize(64);
        assertThat(retained.get(0).requestSequence()).isEqualTo(2L);
        assertThat(retained.get(retained.size() - 1).answeredKind()).isNull();
        PersistentThreadCatalog.validateClarificationDisplays(retained);
    }
}