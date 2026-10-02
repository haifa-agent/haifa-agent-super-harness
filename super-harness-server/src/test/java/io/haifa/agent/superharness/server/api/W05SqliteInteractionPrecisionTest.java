package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.haifa.agent.common.time.SystemTimeProvider;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.model.api.ModelAdapterCoordinate;
import io.haifa.agent.policy.api.PolicyPresets;
import io.haifa.agent.policy.core.DefaultPolicyDecisionService;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseReceiptStatus;
import io.haifa.agent.runtime.api.InteractionResponseSubmission;
import io.haifa.agent.runtime.api.InteractionState;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.runtime.api.RuntimeApiErrorCode;
import io.haifa.agent.runtime.api.RuntimeContractException;
import io.haifa.agent.sdk.api.AgentMetadata;
import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.sdk.api.HaifaAgents;
import io.haifa.agent.sdk.api.SdkCallerProvider;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.product.ProductId;
import io.haifa.agent.sdk.product.ProductProfile;
import io.haifa.agent.sdk.product.ProductRunProfileRef;
import io.haifa.agent.sdk.product.ProductVersion;
import io.haifa.agent.store.sqlite.SqliteSdkContributions;
import io.haifa.agent.store.sqlite.SqliteStoreConfiguration;
import io.haifa.agent.store.sqlite.SqliteStoreException;
import io.haifa.agent.store.sqlite.SqliteStoreFailure;
import io.haifa.agent.superharness.server.run.HaifaPublicInteractionSpikeTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deterministic public-SDK/SQLite proof for the frozen approval-card time boundary. */
class W05SqliteInteractionPrecisionTest {
    private static final Instant BASE = Instant.parse("2026-09-27T00:00:00.123Z");

    @TempDir Path dataDirectory;

    @BeforeEach
    void useAssignedDataRootWhenProvided() throws IOException {
        String assignedRoot = System.getProperty("w05.fault.dataRoot");
        if (assignedRoot != null && !assignedRoot.isBlank()) {
            Path root = Path.of(assignedRoot).toAbsolutePath().normalize();
            Files.createDirectories(root);
            dataDirectory = Files.createTempDirectory(root, "precision-");
        }
    }

    @Test
    void sameMillisecondNanosecondsRejectBeforeProjectionAndCommitOnceAfterProjection() throws Exception {
        var clock = new ControlledClock(BASE);
        var calls = new AtomicInteger();
        var model = new HaifaPublicInteractionSpikeTest.ApprovalProbeModel();
        try (var fixture = open(clock, model, calls)) {
            var waiting = startPending(fixture.agent(), "same-millisecond");
            Instant rawRespondedAt = BASE.plusNanos(500_000);
            var raw = rawSubmission(waiting.pending(), rawRespondedAt);

            assertTimeOrderingFailure(fixture.agent(), waiting, raw, calls);

            var projected = projectedSubmission(waiting.pending(), rawRespondedAt);
            assertThat(projected.respondedAt()).isEqualTo(BASE);
            assertThat(fixture.agent().runs().respond(projected).status())
                    .isIn(InteractionResponseReceiptStatus.NEWLY_ACCEPTED,
                            InteractionResponseReceiptStatus.ACCEPTED_PENDING_APPLICATION,
                            InteractionResponseReceiptStatus.APPLIED);
            assertCompletedOnce(fixture.agent(), waiting.runId(), calls);
            assertThat(fixture.agent().runs().respond(projected).status())
                    .isEqualTo(InteractionResponseReceiptStatus.DUPLICATE);
            System.out.println("W05_SQLITE_TIME_EVIDENCE scenario=same-ms run=" + waiting.runId().value()
                    + " request=" + waiting.pending().requestId().value()
                    + " raw_nanos=500000 sdk_millis=123 failure=TRANSACTION_FAILED>IllegalArgumentException"
                    + " rollback=pending success=ACCEPTED_COMPLETED duplicate=DUPLICATE tool_calls=1");
        }
    }

    @Test
    void crossMillisecondFutureRejectsUntilSdkClockPassesTheResponseTime() throws Exception {
        var clock = new ControlledClock(BASE);
        var calls = new AtomicInteger();
        var model = new HaifaPublicInteractionSpikeTest.ApprovalProbeModel();
        try (var fixture = open(clock, model, calls)) {
            var waiting = startPending(fixture.agent(), "cross-millisecond");
            Instant respondedAt = BASE.plusMillis(1);
            var projected = projectedSubmission(waiting.pending(), respondedAt);
            assertThat(projected.respondedAt()).isEqualTo(respondedAt);

            assertTimeOrderingFailure(fixture.agent(), waiting, projected, calls);
            clock.set(BASE.plusMillis(2));
            assertThat(fixture.agent().runs().respond(projected).status())
                    .isIn(InteractionResponseReceiptStatus.NEWLY_ACCEPTED,
                            InteractionResponseReceiptStatus.ACCEPTED_PENDING_APPLICATION,
                            InteractionResponseReceiptStatus.APPLIED);
            assertCompletedOnce(fixture.agent(), waiting.runId(), calls);
            System.out.println("W05_SQLITE_TIME_EVIDENCE scenario=cross-ms run=" + waiting.runId().value()
                    + " request=" + waiting.pending().requestId().value()
                    + " received_before=123 responded=124 received_after=125"
                    + " failure=TRANSACTION_FAILED>IllegalArgumentException success=ACCEPTED_COMPLETED tool_calls=1");
        }
    }

    @Test
    void restartReadsTheSamePendingInteractionAndAcceptsMillisecondProjectedReply() throws Exception {
        var clock = new ControlledClock(BASE);
        var calls = new AtomicInteger();
        var model = new HaifaPublicInteractionSpikeTest.ApprovalProbeModel();
        Waiting waiting;
        try (var first = open(clock, model, calls)) {
            waiting = startPending(first.agent(), "restart-roundtrip");
            assertThat(first.agent().runs().find(waiting.runId()).orElseThrow().status())
                    .isEqualTo(AgentRunStatus.WAITING_APPROVAL);
        }
        try (var reopened = open(clock, model, calls)) {
            var pending = reopened.agent().runs().pendingInteraction(waiting.runId()).orElseThrow();
            assertThat(pending.requestId()).isEqualTo(waiting.pending().requestId());
            assertThat(pending.revision()).isEqualTo(waiting.pending().revision());
            assertThat(pending.state()).isEqualTo(InteractionState.PENDING);
            assertThat(reopened.agent().runs().find(waiting.runId()).orElseThrow().status())
                    .isEqualTo(AgentRunStatus.WAITING_APPROVAL);

            var projected = projectedSubmission(pending, BASE.plusNanos(500_000));
            assertThat(projected.respondedAt()).isEqualTo(BASE);
            assertThat(reopened.agent().runs().respond(projected).status())
                    .isIn(InteractionResponseReceiptStatus.NEWLY_ACCEPTED,
                            InteractionResponseReceiptStatus.ACCEPTED_PENDING_APPLICATION,
                            InteractionResponseReceiptStatus.APPLIED);
            assertCompletedOnce(reopened.agent(), waiting.runId(), calls);
            assertThat(reopened.agent().runs().respond(projected).status())
                    .isEqualTo(InteractionResponseReceiptStatus.DUPLICATE);
            System.out.println("W05_SQLITE_TIME_EVIDENCE scenario=restart run=" + waiting.runId().value()
                    + " request=" + pending.requestId().value()
                    + " reloaded=PENDING success=ACCEPTED_COMPLETED duplicate=DUPLICATE tool_calls=1");
        }
    }

    @Test
    void writerLockRejectsWithoutRecordingAndSameReplySucceedsAfterRelease() throws Exception {
        var clock = new ControlledClock(BASE);
        var calls = new AtomicInteger();
        var model = new HaifaPublicInteractionSpikeTest.ApprovalProbeModel();
        try (var fixture = open(clock, model, calls)) {
            var waiting = startPending(fixture.agent(), "writer-lock");
            var projected = projectedSubmission(waiting.pending(), BASE.plusNanos(500_000));
            Throwable locked;
            try (var holder = DriverManager.getConnection("jdbc:sqlite:" + dataDirectory.resolve("runtime.sqlite"));
                    var statement = holder.createStatement()) {
                statement.execute("BEGIN IMMEDIATE");
                try {
                    locked = catchThrowable(() -> fixture.agent().runs().respond(projected));
                } finally {
                    statement.execute("ROLLBACK");
                }
            }
            assertThat(locked).isInstanceOf(SqliteStoreException.class);
            var sqliteFailure = (SqliteStoreException) locked;
            assertThat(sqliteFailure.failure()).isEqualTo(SqliteStoreFailure.DATABASE_BUSY);
            assertThat(sqliteFailure.getCause()).isInstanceOf(SQLException.class);
            int sqliteCode = ((SQLException) sqliteFailure.getCause()).getErrorCode();
            assertThat(sqliteCode & 0xff).isEqualTo(5);
            assertThat(DeerFlowThreadRunApi.interactionFailureStatus(sqliteFailure)).isEqualTo(500);
            assertThat(DeerFlowThreadRunApi.interactionFailureReason(sqliteFailure))
                    .isEqualTo("SQLITE_DATABASE_BUSY");
            assertThat(fixture.agent().runs().find(waiting.runId()).orElseThrow().status())
                    .isEqualTo(AgentRunStatus.WAITING_APPROVAL);
            assertThat(fixture.agent().runs().pendingInteraction(waiting.runId())).contains(waiting.pending());
            assertThat(calls).hasValue(0);
            assertThat(fixture.agent().runs().respond(projected).status())
                    .isIn(InteractionResponseReceiptStatus.NEWLY_ACCEPTED,
                            InteractionResponseReceiptStatus.ACCEPTED_PENDING_APPLICATION,
                            InteractionResponseReceiptStatus.APPLIED);
            assertCompletedOnce(fixture.agent(), waiting.runId(), calls);
            System.out.println("W05_SQLITE_FAULT_EVIDENCE scenario=writer-lock run=" + waiting.runId().value()
                    + " request=" + waiting.pending().requestId().value()
                    + " failure=DATABASE_BUSY sqlite_primary=" + (sqliteCode & 0xff)
                    + " sqlite_extended=" + sqliteCode
                    + " http_mapping=500 rollback=pending recovered=COMPLETED tool_calls=1");
        }
    }

    @Test
    // Diagnostic current-state lock, not the desired HTTP contract. When SDK exception
    // propagation is fixed, replace the 500 expectation with the reviewed 409 contract.
    void nestedRevisionConflictCurrentlyWrapsAsTransactionFailure() throws Exception {
        var clock = new ControlledClock(BASE);
        var calls = new AtomicInteger();
        var model = new HaifaPublicInteractionSpikeTest.ApprovalProbeModel();
        try (var fixture = open(clock, model, calls)) {
            var waiting = startPending(fixture.agent(), "revision-conflict");
            var valid = projectedSubmission(waiting.pending(), BASE.plusNanos(500_000));
            var stale = new InteractionResponseSubmission(
                    new InteractionResponseId("w05-stale-" + waiting.pending().requestId().value()),
                    waiting.pending().requestId(),
                    waiting.runId(),
                    waiting.pending().revision() + 1,
                    InteractionAction.APPROVE,
                    List.of(),
                    "w05-stale-" + waiting.pending().requestId().value(),
                    BASE);
            Throwable refused = catchThrowable(() -> fixture.agent().runs().respond(stale));
            assertThat(refused).isInstanceOf(SqliteStoreException.class);
            var sqliteFailure = (SqliteStoreException) refused;
            assertThat(sqliteFailure.failure()).isEqualTo(SqliteStoreFailure.TRANSACTION_FAILED);
            assertThat(sqliteFailure.getCause()).isInstanceOf(RuntimeContractException.class);
            assertThat(((RuntimeContractException) sqliteFailure.getCause()).code())
                    .isEqualTo(RuntimeApiErrorCode.INTERACTION_REVISION_CONFLICT);
            assertThat(DeerFlowThreadRunApi.interactionFailureStatus(sqliteFailure)).isEqualTo(500);
            assertThat(fixture.agent().runs().find(waiting.runId()).orElseThrow().status())
                    .isEqualTo(AgentRunStatus.WAITING_APPROVAL);
            assertThat(fixture.agent().runs().pendingInteraction(waiting.runId())).contains(waiting.pending());
            assertThat(calls).hasValue(0);
            assertThat(fixture.agent().runs().respond(valid).status())
                    .isIn(InteractionResponseReceiptStatus.NEWLY_ACCEPTED,
                            InteractionResponseReceiptStatus.ACCEPTED_PENDING_APPLICATION,
                            InteractionResponseReceiptStatus.APPLIED);
            assertCompletedOnce(fixture.agent(), waiting.runId(), calls);
            System.out.println("W05_SQLITE_FAULT_EVIDENCE scenario=revision-conflict run=" + waiting.runId().value()
                    + " request=" + waiting.pending().requestId().value()
                    + " failure=TRANSACTION_FAILED>INTERACTION_REVISION_CONFLICT"
                    + " mapped_http=500"
                    + " rollback=pending recovered=COMPLETED tool_calls=1");
        }
    }

    private Fixture open(
            ControlledClock clock,
            HaifaPublicInteractionSpikeTest.ApprovalProbeModel model,
            AtomicInteger calls) {
        var snapshot = HaifaPublicInteractionSpikeTest.modelSnapshot();
        var sqlite = SqliteSdkContributions.initializeWithKey(
                new SqliteStoreConfiguration(
                        dataDirectory.resolve("runtime.sqlite"),
                        100,
                        SqliteStoreConfiguration.DEFAULT_MAXIMUM_PAYLOAD_BYTES),
                clock,
                new SecretKeySpec(new byte[32], "AES"));
        try {
            ProductProfile profile = ProductProfile.create(
                    new ProductId("haifa-super-harness"),
                    new ProductVersion("1.0.0"),
                    new AgentDefinitionId("haifa-super-harness-agent"),
                    new AgentDefinitionVersion(1, 0, 0),
                    "Return a concise answer to the local user's request.",
                    new ProductRunProfileRef(snapshot.modelId().value(), "1.0.0"),
                    new AgentRunBudget(65_536, 8_192, 65_536, 16, 16, 0, "USD", 100),
                    new AgentRunLimits(16, 0, 1, 120_000, 60_000, 16, 16, 0),
                    Set.of("mutating_probe"),
                    Set.of());
            var models = new ModelContribution(
                    Map.of(ModelAdapterCoordinate.from(snapshot), model),
                    snapshot,
                    Map.of(snapshot.modelId().value(), snapshot));
            var agent = HaifaAgents.builder(profile)
                    .metadata(new AgentMetadata("W05 deterministic SQLite precision test"))
                    .callerProvider(SdkCallerProvider.defaultPublicUser())
                    .timeProvider(new SystemTimeProvider(clock))
                    .model(models)
                    .persistence(sqlite.borrowedPersistence())
                    .conversation(() -> sqlite.conversation().conversationStore())
                    .tool(new HaifaPublicInteractionSpikeTest.MutatingProbeTool(calls))
                    .policy(new PolicyPlatformContribution(
                            PolicyPresets.standardApproval(), new DefaultPolicyDecisionService()))
                    .build();
            return new Fixture(sqlite, agent);
        } catch (RuntimeException failure) {
            sqlite.persistence().close();
            throw failure;
        }
    }

    private static Waiting startPending(HaifaAgent agent, String id) throws InterruptedException {
        var started = agent.conversations()
                .start(new StartConversationCommand("w05-sqlite-time-" + id, id, "perform the change"));
        AgentRunId runId = started.runId();
        var waiting = new CountDownLatch(1);
        try (var subscription = agent.runs().subscribe(
                runId,
                RunEventCursor.beforeFirst(runId),
                event -> {
                    if (event.payload() instanceof RunEventPayloads.RunLifecycle run
                            && run.status().equals(AgentRunStatus.WAITING_APPROVAL.name())) {
                        waiting.countDown();
                    }
                })) {
            boolean observedWaiting = waiting.await(10, TimeUnit.SECONDS);
            var snapshot = agent.runs().find(runId).orElseThrow();
            assertThat(observedWaiting).as("Run must reach WAITING_APPROVAL; run=%s status=%s error=%s data=%s",
                    runId.value(), snapshot.status(), snapshot.error(), System.getProperty("w05.fault.dataRoot", "JUnit TempDir"))
                    .isTrue();
        }
        var pending = agent.runs().pendingInteraction(runId).orElseThrow();
        assertThat(pending.state()).isEqualTo(InteractionState.PENDING);
        return new Waiting(runId, pending);
    }

    private static InteractionResponseSubmission rawSubmission(InteractionView pending, Instant respondedAt) {
        String stableId = "deerflow:" + pending.requestId().value() + ":approve";
        return new InteractionResponseSubmission(
                new InteractionResponseId(stableId),
                pending.requestId(),
                pending.runId(),
                pending.revision(),
                InteractionAction.APPROVE,
                List.of(),
                stableId,
                respondedAt);
    }

    private static InteractionResponseSubmission projectedSubmission(InteractionView pending, Instant respondedAt) {
        return DeerFlowInteractionHttpProjection.submission(
                pending,
                new DeerFlowInteractionHttpProjection.FrozenResponse(
                        DeerFlowInteractionHttpProjection.SOURCE,
                        pending.requestId().value(),
                        "option",
                        "approve",
                        "approve"),
                respondedAt);
    }

    private static void assertTimeOrderingFailure(
            HaifaAgent agent, Waiting waiting, InteractionResponseSubmission submission, AtomicInteger calls) {
        Throwable thrown = catchThrowable(() -> agent.runs().respond(submission));
        assertThat(thrown).isInstanceOf(SqliteStoreException.class);
        var sqliteFailure = (SqliteStoreException) thrown;
        assertThat(sqliteFailure.failure()).isEqualTo(SqliteStoreFailure.TRANSACTION_FAILED);
        assertThat(sqliteFailure.getCause())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("receivedAt must not precede respondedAt");
        assertThat(agent.runs().find(waiting.runId()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.WAITING_APPROVAL);
        assertThat(agent.runs().pendingInteraction(waiting.runId()))
                .contains(waiting.pending());
        assertThat(calls).hasValue(0);
    }

    private static void assertCompletedOnce(HaifaAgent agent, AgentRunId runId, AtomicInteger calls)
            throws InterruptedException {
        assertThat(agent.runs().await(runId, Duration.ofSeconds(10)).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(agent.runs().pendingInteraction(runId)).isEmpty();
        assertThat(calls).hasValue(1);
    }

    private record Waiting(AgentRunId runId, InteractionView pending) {}

    private record Fixture(SqliteSdkContributions sqlite, HaifaAgent agent) implements AutoCloseable {
        @Override
        public void close() {
            agent.close();
            sqlite.persistence().close();
        }
    }

    private static final class ControlledClock extends Clock {
        private final AtomicReference<Instant> now;

        private ControlledClock(Instant now) {
            this.now = new AtomicReference<>(now);
        }

        private void set(Instant value) {
            now.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("UTC clock only");
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
