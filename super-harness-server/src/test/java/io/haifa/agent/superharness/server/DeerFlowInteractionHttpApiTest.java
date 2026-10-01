package io.haifa.agent.superharness.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import io.haifa.agent.runtime.api.RuntimeApiErrorCode;
import io.haifa.agent.runtime.api.RuntimeContractException;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionConsequenceView;
import io.haifa.agent.runtime.api.InteractionInputContract;
import io.haifa.agent.runtime.api.InteractionKind;
import io.haifa.agent.runtime.api.InteractionRequestId;
import io.haifa.agent.runtime.api.InteractionRequesterView;
import io.haifa.agent.runtime.api.InteractionState;
import io.haifa.agent.runtime.api.InteractionTargetView;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.store.sqlite.SqliteStoreException;
import io.haifa.agent.store.sqlite.SqliteStoreFailure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeerFlowInteractionHttpApiTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final AtomicInteger toolInvocations = new AtomicInteger();
    private final SecretKey continuationKey = new SecretKeySpec(new byte[32], "AES");
    private HaifaPublicInteractionSpikeTest.ApprovalProbeModel model =
            new HaifaPublicInteractionSpikeTest.ApprovalProbeModel();
    @TempDir
    Path dataDirectory;
    private DeerFlowBootstrapApi api;
    private URI baseUri;

    @BeforeEach
    void startServer() throws Exception {
        var binding = new HaifaRunBridge.ModelBinding(
                "interaction-probe",
                "1.0.0",
                model,
                HaifaPublicInteractionSpikeTest.modelSnapshot());
        api = DeerFlowBootstrapApi.createForInteractionTests(
                new LocalServerConfiguration("local", InetAddress.getByName("127.0.0.1"), 0),
                dataDirectory,
                continuationKey,
                ignored -> binding,
                List.of(new HaifaPublicInteractionSpikeTest.MutatingProbeTool(toolInvocations)));
        api.start();
        baseUri = URI.create("http://127.0.0.1:" + api.address().getPort());
    }

    @AfterEach
    void stopServer() {
        api.close();
    }

    @Test
    void frozenCardApprovesSameRunReloadsAndDeduplicatesTheHiddenReply() throws Exception {
        createThread("interaction-thread");

        HttpResponse<String> requested = postJson(
                "/api/threads/interaction-thread/runs/stream", ordinaryRunBody("request approval"));
        assertThat(requested.statusCode()).isEqualTo(200);
        String location = requested.headers().firstValue("content-location").orElseThrow();
        String runId = location.replaceFirst(".*/", "");
        List<JsonNode> messageEvents = messageEvents(requested.body());
        JsonNode toolCallMessage = messageEvents.stream()
                .filter(message -> !message.path("tool_calls").isEmpty())
                .findFirst()
                .orElseThrow();
        JsonNode card = humanInputCard(requested.body());
        JsonNode request = card.path("artifact").path("human_input");
        String requestId = request.path("request_id").asText();
        String toolCallId = toolCallMessage.path("tool_calls").get(0).path("id").asText();

        assertThat(card.path("type").asText()).isEqualTo("tool");
        assertThat(card.path("name").asText()).isEqualTo("ask_clarification");
        assertThat(card.path("tool_call_id").asText()).isEqualTo(card.path("id").asText());
        assertThat(card.path("tool_call_id").asText()).isNotEqualTo(toolCallId);
        assertThat(request.path("tool_call_id").asText()).isEqualTo(toolCallId);
        assertThat(messageEvents.indexOf(toolCallMessage)).isLessThan(messageEvents.indexOf(card));
        assertThat(messageEvents.stream().map(message -> message.path("id").asText()).toList())
                .doesNotHaveDuplicates();
        assertThat(request.path("version").asInt()).isEqualTo(1);
        assertThat(request.path("source").asText()).isEqualTo("haifa_interaction");
        assertThat(request.path("input_mode").asText()).isEqualTo("single_choice");
        assertThat(request.path("options").findValuesAsText("id"))
                .containsExactly("approve", "reject");
        assertThat(requested.body()).contains("event: end\ndata: null\n\n");
        assertThat(toolInvocations).hasValue(0);
        assertThat(getJson(location).path("status").asText()).isEqualTo("interrupted");

        // The frozen UI reloads history while the card waits; the human input must precede it
        // or the card is rendered as already answered.
        HttpResponse<String> pendingHistory =
                postJson("/api/threads/interaction-thread/history", "{\"limit\":10}");
        assertThat(pendingHistory.statusCode()).isEqualTo(200);
        JsonNode pendingMessages = json.readTree(pendingHistory.body())
                .get(0).path("values").path("messages");
        assertThat(pendingMessages).hasSize(3);
        JsonNode pendingToolCall = messageWithToolCalls(pendingMessages);
        JsonNode pendingCard = messageWithHumanInput(pendingMessages);
        assertThat(pendingMessages.get(0).path("type").asText()).isEqualTo("human");
        assertThat(pendingToolCall.path("type").asText()).isEqualTo("ai");
        assertThat(pendingToolCall.path("tool_calls").get(0).path("name").asText())
                .isEqualTo("mutating_probe");
        assertThat(pendingCard.path("type").asText()).isEqualTo("tool");
        assertThat(pendingCard.path("tool_call_id").asText()).isEqualTo(pendingCard.path("id").asText());
        assertThat(pendingCard.path("artifact").path("human_input").path("tool_call_id").asText())
                .isEqualTo(pendingToolCall.path("tool_calls").get(0).path("id").asText());
        assertThat(indexOfMessage(pendingMessages, pendingToolCall))
                .isLessThan(indexOfMessage(pendingMessages, pendingCard));
        assertThat(pendingMessages.get(0).path("content").asText()).isEqualTo("request approval");
        assertThat(pendingCard.path("artifact").path("human_input").path("request_id").asText())
                .isEqualTo(requestId);

        String responseBody = hiddenResponseBody(requestId, "approve", "approve");
        HttpResponse<String> resumed =
                postJson("/api/threads/interaction-thread/runs/stream", responseBody);
        assertThat(resumed.statusCode()).as(resumed.body()).isEqualTo(200);
        assertThat(resumed.headers().firstValue("content-location")).contains(location);
        assertThat(resumed.body()).contains("event: messages");
        assertThat(resumed.body()).contains("interaction resolved");
        assertThat(resumed.body()).contains("event: end");
        assertThat(toolInvocations).hasValue(1);
        assertThat(getJson(location).path("status").asText()).isEqualTo("success");

        JsonNode page = getJson("/api/threads/interaction-thread/messages/page?limit=20");
        assertThat(page.path("data")).hasSize(4);
        assertThat(page.path("data").findValuesAsText("request_id"))
                .containsExactly(requestId, requestId);
        assertThat(page.path("data").get(1).path("content").path("artifact").path("human_input")
                        .path("request_id")
                        .asText())
                .isEqualTo(requestId);
        assertThat(page.path("data").get(2).path("content").path("additional_kwargs")
                        .path("human_input_response")
                        .path("option_id")
                        .asText())
                .isEqualTo("approve");

        HttpResponse<String> duplicate =
                postJson("/api/threads/interaction-thread/runs/stream", responseBody);
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(duplicate.body()).contains("Interaction response already accepted");
        assertThat(toolInvocations).hasValue(1);

        HttpResponse<String> conflicting = postJson(
                "/api/threads/interaction-thread/runs/stream",
                hiddenResponseBody(requestId, "reject", "reject"));
        assertThat(conflicting.statusCode()).isEqualTo(409);
        assertThat(conflicting.body()).contains("Interaction already has a different response");

        System.out.println("W05_INTERACTION_HTTP_EVIDENCE thread=interaction-thread run="
                + runId + " request=" + requestId + " duplicate=acknowledged");
    }

    @Test
    void twoSequentialApprovalsKeepDistinctCardsAndSurviveGatewayRestart() throws Exception {
        api.close();
        model = new HaifaPublicInteractionSpikeTest.ApprovalProbeModel(2);
        startServer();
        createThread("sequential-thread");

        HttpResponse<String> firstStream = postJson(
                "/api/threads/sequential-thread/runs/stream", ordinaryRunBody("approve two steps"));
        assertThat(firstStream.statusCode()).as(firstStream.body()).isEqualTo(200);
        String location = firstStream.headers().firstValue("content-location").orElseThrow();
        String firstId = humanInputCard(firstStream.body())
                .path("artifact").path("human_input").path("request_id").asText();
        assertThat(toolInvocations).hasValue(0);

        HttpResponse<String> secondStream = postJson(
                "/api/threads/sequential-thread/runs/stream",
                hiddenResponseBody(firstId, "approve", "approve"));
        assertThat(secondStream.statusCode()).as(secondStream.body()).isEqualTo(200);
        assertThat(secondStream.headers().firstValue("content-location")).contains(location);
        String secondId = humanInputCard(secondStream.body())
                .path("artifact").path("human_input").path("request_id").asText();
        assertThat(secondId).isNotEqualTo(firstId);
        assertThat(toolInvocations).hasValue(1);
        assertThat(getJson(location).path("status").asText()).isEqualTo("interrupted");
        JsonNode liveMessages = getJson("/api/threads/sequential-thread/state")
                .path("values").path("messages");
        assertThat(liveMessages.findValuesAsText("request_id"))
                .containsExactly(firstId, firstId, secondId);
        JsonNode livePage = getJson("/api/threads/sequential-thread/messages/page?limit=20")
                .path("data");
        assertThat(livePage.findValuesAsText("seq")).doesNotHaveDuplicates();
        assertThat(livePage.findValuesAsText("request_id"))
                .containsExactly(firstId, firstId, secondId);

        api.close();
        startServer();
        JsonNode restoredMessages = getJson("/api/threads/sequential-thread/state")
                .path("values").path("messages");
        assertThat(restoredMessages.findValuesAsText("request_id"))
                .containsExactly(firstId, firstId, secondId);
        JsonNode restoredPage = getJson("/api/threads/sequential-thread/messages/page?limit=20")
                .path("data");
        assertThat(restoredPage.findValuesAsText("seq")).doesNotHaveDuplicates();
        assertThat(restoredPage.findValuesAsText("request_id"))
                .containsExactly(firstId, firstId, secondId);
        assertThat(postJson("/api/threads/sequential-thread/runs/stream",
                hiddenResponseBody(firstId, "approve", "approve")).statusCode()).isEqualTo(409);
        assertThat(postJson("/api/threads/sequential-thread/runs/stream",
                hiddenResponseBody("not-current", "approve", "approve")).statusCode()).isEqualTo(409);
        assertThat(toolInvocations).hasValue(1);

        HttpResponse<String> completed = postJson(
                "/api/threads/sequential-thread/runs/stream",
                hiddenResponseBody(secondId, "approve", "approve"));
        assertThat(completed.statusCode()).as(completed.body()).isEqualTo(200);
        assertThat(completed.headers().firstValue("content-location")).contains(location);
        assertThat(completed.body()).contains("interaction resolved", "event: end");
        assertThat(getJson(location).path("status").asText()).isEqualTo("success");
        assertThat(toolInvocations).hasValue(2);
        assertThat(postJson("/api/threads/sequential-thread/runs/stream",
                hiddenResponseBody(secondId, "approve", "approve")).statusCode()).isEqualTo(409);
        assertThat(toolInvocations).hasValue(2);
        JsonNode finalPage = getJson("/api/threads/sequential-thread/messages/page?limit=20")
                .path("data");
        assertThat(finalPage.findValuesAsText("seq")).doesNotHaveDuplicates();
        assertThat(finalPage.findValuesAsText("request_id"))
                .containsExactly(firstId, firstId, secondId, secondId);
        System.out.println("W16_SEQUENTIAL_INTERACTION_HTTP_EVIDENCE run="
                + location.substring(location.lastIndexOf('/') + 1)
                + " first=" + firstId + " second=" + secondId
                + " side_effects=" + toolInvocations.get() + " restart=true");
    }

    @Test
    void missingSavedCardAndCatalogFailureNeverSubmitTheApproval() throws Exception {
        createThread("missing-card-thread");
        HttpResponse<String> pending = postJson(
                "/api/threads/missing-card-thread/runs/stream", ordinaryRunBody("approve once"));
        String requestId = humanInputCard(pending.body())
                .path("artifact").path("human_input").path("request_id").asText();
        String location = pending.headers().firstValue("content-location").orElseThrow();
        api.close();
        Path catalog = dataDirectory.resolve("owners/default/threads.json");
        ObjectNode catalogJson = (ObjectNode) json.readTree(catalog.toFile());
        for (JsonNode thread : catalogJson.path("threads")) {
            if ("missing-card-thread".equals(thread.path("thread_id").asText())) {
                ((ObjectNode) thread).putArray("interaction_displays");
            }
        }
        json.writerWithDefaultPrettyPrinter().writeValue(catalog.toFile(), catalogJson);
        startServer();
        Path backup = makeCatalogUnavailable(catalog);
        try {
            HttpResponse<String> blocked = postJson(
                    "/api/threads/missing-card-thread/runs/stream",
                    hiddenResponseBody(requestId, "approve", "approve"));
            assertThat(blocked.statusCode()).as(blocked.body()).isEqualTo(503);
            assertThat(blocked.body()).contains("approval was not submitted");
            assertThat(toolInvocations).hasValue(0);
            assertThat(getJson(location).path("status").asText()).isEqualTo("interrupted");
        } finally {
            restoreCatalog(catalog, backup);
        }
        HttpResponse<String> resumed = postJson(
                "/api/threads/missing-card-thread/runs/stream",
                hiddenResponseBody(requestId, "approve", "approve"));
        assertThat(resumed.statusCode()).as(resumed.body()).isEqualTo(200);
        assertThat(toolInvocations).hasValue(1);
    }

    @Test
    void catalogFailureAfterSdkAcceptReportsAcceptedAndDoesNotReplaySideEffect() throws Exception {
        createThread("accepted-with-display-fault");
        HttpResponse<String> pending = postJson(
                "/api/threads/accepted-with-display-fault/runs/stream",
                ordinaryRunBody("approve once"));
        String requestId = humanInputCard(pending.body())
                .path("artifact").path("human_input").path("request_id").asText();
        Path catalog = dataDirectory.resolve("owners/default/threads.json");
        // Freeze the unanswered presentation before submitting approval; an
        // asynchronous title observer may persist the answered state later.
        Path staleCatalog = catalog.resolveSibling("threads.json.pre-response");
        Files.copy(catalog, staleCatalog, StandardCopyOption.REPLACE_EXISTING);
        Path backup = makeCatalogUnavailable(catalog);
        try {
            HttpResponse<String> accepted = postJson(
                    "/api/threads/accepted-with-display-fault/runs/stream",
                    hiddenResponseBody(requestId, "approve", "approve"));
            assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
            assertThat(accepted.headers().firstValue("X-Haifa-Interaction-Presentation"))
                    .contains("accepted-not-persisted");
            assertThat(accepted.body()).contains("interaction resolved", "event: end");
            assertThat(toolInvocations).hasValue(1);
        } finally {
            restoreCatalog(catalog, backup);
        }
        assertThat(postJson("/api/threads/accepted-with-display-fault/runs/stream",
                hiddenResponseBody(requestId, "approve", "approve")).statusCode()).isEqualTo(409);
        assertThat(toolInvocations).hasValue(1);
        // Simulate the exact crash boundary: the SDK commit survived, while the
        // catalog still contains the pre-response unanswered presentation.
        api.close();
        Files.copy(staleCatalog, catalog, StandardCopyOption.REPLACE_EXISTING);
        startServer();
        assertThat(getJson("/api/threads/accepted-with-display-fault/state")
                .path("values").path("messages").findValuesAsText("request_id"))
                .doesNotContain(requestId);
        assertThat(toolInvocations).hasValue(1);
    }

    private Path makeCatalogUnavailable(Path catalog) throws Exception {
        Path backup = catalog.resolveSibling("threads.json.fault-backup");
        Files.move(catalog, backup, StandardCopyOption.REPLACE_EXISTING);
        Files.createDirectory(catalog);
        return backup;
    }

    private void restoreCatalog(Path catalog, Path backup) throws Exception {
        Files.delete(catalog);
        Files.move(backup, catalog, StandardCopyOption.REPLACE_EXISTING);
    }

    @Test
    void malformedMismatchedAndCrossThreadResponsesFailClosed() throws Exception {
        createThread("owner-thread");
        HttpResponse<String> requested =
                postJson("/api/threads/owner-thread/runs/stream", ordinaryRunBody("request approval"));
        String requestId = humanInputCard(requested.body())
                .path("artifact")
                .path("human_input")
                .path("request_id")
                .asText();

        HttpResponse<String> malformed = postJson(
                "/api/threads/owner-thread/runs/stream",
                hiddenResponseBody(requestId, "approve", ""));
        assertThat(malformed.statusCode()).isEqualTo(422);
        assertThat(malformed.body()).contains("Invalid human_input_response");

        HttpResponse<String> mismatched = postJson(
                "/api/threads/owner-thread/runs/stream",
                hiddenResponseBody("another-request", "approve", "approve"));
        assertThat(mismatched.statusCode()).isEqualTo(409);
        assertThat(mismatched.body()).contains("Thread has no matching Interaction");

        createThread("other-thread");
        HttpResponse<String> crossThread = postJson(
                "/api/threads/other-thread/runs/stream",
                hiddenResponseBody(requestId, "approve", "approve"));
        assertThat(crossThread.statusCode()).isEqualTo(409);
        assertThat(crossThread.body()).contains("Thread has no matching Interaction");
        assertThat(toolInvocations).hasValue(0);
    }

    @Test
    void frozenCardRejectionResumesWithoutExecutingTheTool() throws Exception {
        createThread("rejected-thread");
        HttpResponse<String> requested = postJson(
                "/api/threads/rejected-thread/runs/stream", ordinaryRunBody("request approval"));
        String location = requested.headers().firstValue("content-location").orElseThrow();
        String requestId = humanInputCard(requested.body())
                .path("artifact")
                .path("human_input")
                .path("request_id")
                .asText();

        HttpResponse<String> rejected = postJson(
                "/api/threads/rejected-thread/runs/stream",
                hiddenResponseBody(requestId, "reject", "reject"));
        assertThat(rejected.statusCode()).isEqualTo(200);
        assertThat(rejected.body()).contains("event: end");
        assertThat(toolInvocations).hasValue(0);
        JsonNode rejectedRun = getJson(location);
        assertThat(rejectedRun.path("status").asText()).isEqualTo("success");
        assertThat(rejectedRun.path("diagnostics").path("interactions").findValuesAsText("request_id"))
                .contains(requestId);
        assertThat(rejectedRun.path("diagnostics").path("interactions").findValuesAsText("action"))
                .contains("reject");
        assertThat(rejectedRun.path("diagnostics").path("thread_run_id").asText())
                .isEqualTo(rejectedRun.path("run_id").asText());
    }

    @Test
    void pendingInteractionSurvivesServerRestartAndResumesTheSameRun() throws Exception {
        createThread("restart-interaction-thread");
        HttpResponse<String> requested = postJson(
                "/api/threads/restart-interaction-thread/runs/stream", ordinaryRunBody("request approval"));
        assertThat(requested.statusCode()).isEqualTo(200);
        String location = requested.headers().firstValue("content-location").orElseThrow();
        String requestId = humanInputCard(requested.body())
                .path("artifact").path("human_input").path("request_id").asText();
        assertThat(toolInvocations).hasValue(0);

        api.close();
        startServer();
        JsonNode reloadedMessages = getJson("/api/threads/restart-interaction-thread/state")
                .path("values").path("messages");
        assertThat(reloadedMessages).hasSize(3);
        JsonNode reloadedToolCall = messageWithToolCalls(reloadedMessages);
        JsonNode reloadedCard = messageWithHumanInput(reloadedMessages);
        assertThat(reloadedToolCall.path("tool_calls").get(0).path("name").asText())
                .isEqualTo("mutating_probe");
        assertThat(reloadedCard.path("tool_call_id").asText()).isEqualTo(reloadedCard.path("id").asText());
        assertThat(reloadedCard.path("artifact").path("human_input").path("tool_call_id").asText())
                .isEqualTo(reloadedToolCall.path("tool_calls").get(0).path("id").asText());
        assertThat(reloadedCard.path("artifact").path("human_input")
                .path("request_id").asText()).isEqualTo(requestId);
        JsonNode reloadedPage = getJson("/api/threads/restart-interaction-thread/messages/page?limit=20")
                .path("data");
        assertThat(reloadedPage).hasSize(2);
        assertThat(pageContentWithHumanInput(reloadedPage).path("artifact").path("human_input")
                .path("request_id").asText()).isEqualTo(requestId);
        assertThat(toolInvocations).hasValue(0);
        HttpResponse<String> competingRun = postJson(
                "/api/threads/restart-interaction-thread/runs/stream", ordinaryRunBody("competing request"));
        assertThat(competingRun.statusCode()).as(competingRun.body()).isEqualTo(409);
        HttpResponse<String> staleReply = postJson(
                "/api/threads/restart-interaction-thread/runs/stream",
                hiddenResponseBody("stale-request", "approve", "approve"));
        assertThat(staleReply.statusCode()).as(staleReply.body()).isEqualTo(409);
        assertThat(toolInvocations).hasValue(0);

        HttpResponse<String> resumed = postJson(
                "/api/threads/restart-interaction-thread/runs/stream",
                hiddenResponseBody(requestId, "approve", "approve"));
        assertThat(resumed.statusCode()).as(resumed.body()).isEqualTo(200);
        assertThat(resumed.headers().firstValue("content-location")).contains(location);
        assertThat(resumed.body()).contains("interaction resolved", "event: end");
        assertThat(toolInvocations).hasValue(1);
        assertThat(getJson(location).path("status").asText()).isEqualTo("success");
        HttpResponse<String> duplicate = postJson(
                "/api/threads/restart-interaction-thread/runs/stream",
                hiddenResponseBody(requestId, "approve", "approve"));
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(toolInvocations).hasValue(1);
        System.out.println("W05_RESTART_INTERACTION_EVIDENCE run="
                + location.substring(location.lastIndexOf('/') + 1)
                + " request=" + requestId + " tool_calls=" + toolInvocations.get()
                + " competing_run=409 stale_reply=409 duplicate=409");
    }

    @Test
    void interactionFailureClassificationKeepsUnexpectedSdkFailuresOutOfConflictResponsesAndLogs() {
        RuntimeContractException stale = new RuntimeContractException(
                RuntimeApiErrorCode.INTERACTION_REVISION_CONFLICT, "safe conflict");
        assertThat(DeerFlowThreadRunApi.interactionFailureStatus(stale)).isEqualTo(409);
        assertThat(DeerFlowThreadRunApi.interactionFailureReason(stale))
                .isEqualTo("INTERACTION_REVISION_CONFLICT");

        RuntimeContractException denied = new RuntimeContractException(
                RuntimeApiErrorCode.APPROVAL_AUTHORITY_DENIED, "safe denial");
        assertThat(DeerFlowThreadRunApi.interactionFailureStatus(denied)).isEqualTo(403);

        IllegalStateException stillActive = new IllegalStateException(
                "previous execution attempt did not pause before resume");
        assertThat(DeerFlowThreadRunApi.interactionFailureStatus(stillActive)).isEqualTo(500);
        assertThat(DeerFlowThreadRunApi.interactionFailureReason(stillActive))
                .isEqualTo("PREVIOUS_ATTEMPT_NOT_PAUSED");

        IllegalStateException unexpected = new IllegalStateException("provider response: sensitive content");
        assertThat(DeerFlowThreadRunApi.interactionFailureStatus(unexpected)).isEqualTo(500);
        assertThat(DeerFlowThreadRunApi.interactionFailureReason(unexpected))
                .isEqualTo("IllegalStateException");

        SqliteStoreException sqliteBusy = new SqliteStoreException(
                SqliteStoreFailure.DATABASE_BUSY,
                "private SQL and user content",
                new SQLException("private SQL and user content", "SQLITE_BUSY", 5));
        assertThat(DeerFlowThreadRunApi.interactionFailureStatus(sqliteBusy)).isEqualTo(500);
        assertThat(DeerFlowThreadRunApi.interactionFailureReason(sqliteBusy))
                .isEqualTo("SQLITE_DATABASE_BUSY");
        assertThat(DeerFlowThreadRunApi.interactionFailureCauses(sqliteBusy))
                .isEqualTo("SqliteStoreException[DATABASE_BUSY]>SQLException[primary=5,extended=5]");
    }

    @Test
    void interactionResponseTimeUsesSdkMillisecondPrecision() {
        var sameMillisecondLater = Instant.parse("2026-09-26T12:00:00.123999999Z");
        var sdkReceivedAt = Instant.parse("2026-09-26T12:00:00.123Z");
        var requestId = new InteractionRequestId("approval-request-1");
        var pending = new InteractionView(
                requestId,
                new AgentRunId("run-1"),
                new AgentSessionId("session-1"),
                1,
                InteractionKind.APPROVAL,
                InteractionState.PENDING,
                "Approve tool",
                "Approve this tool?",
                List.of(InteractionAction.APPROVE, InteractionAction.REJECT),
                InteractionInputContract.NONE,
                new InteractionTargetView("tool", "tool-1", Optional.empty(), Optional.empty(), "Tool"),
                new InteractionRequesterView("human", "Operator"),
                sdkReceivedAt,
                Optional.empty(),
                new InteractionConsequenceView("Approve", "Reject", "Expire"));
        var wireReply = new DeerFlowInteractionHttpProjection.FrozenResponse(
                DeerFlowInteractionHttpProjection.SOURCE,
                requestId.value(),
                "option",
                "approve",
                "approve");

        assertThat(sdkReceivedAt).isBefore(sameMillisecondLater);
        assertThat(DeerFlowInteractionHttpProjection.submission(pending, wireReply, sameMillisecondLater)
                        .respondedAt())
                .isEqualTo(sdkReceivedAt);
        assertThat(DeerFlowInteractionHttpProjection.sdkMillisecondTime(sdkReceivedAt))
                .isEqualTo(sdkReceivedAt);
    }

    private String ordinaryRunBody(String prompt) {
        return """
                {"assistant_id":"lead_agent","input":{"messages":[{"id":"human-1","type":"human","content":[{"type":"text","text":"%s"}],"additional_kwargs":{}}]},"stream_mode":["messages-tuple","updates","custom"],"on_disconnect":"continue","multitask_strategy":"reject"}
                """
                .formatted(prompt);
    }

    private String hiddenResponseBody(String requestId, String optionId, String value) {
        return """
                {"assistant_id":"lead_agent","input":{"messages":[{"id":"hidden-response","type":"human","content":[{"type":"text","text":"For your clarification, my answer is: %s"}],"additional_kwargs":{"hide_from_ui":true,"human_input_response":{"version":1,"kind":"human_input_response","source":"haifa_interaction","request_id":"%s","response_kind":"option","option_id":"%s","value":"%s"}}}]},"stream_mode":["messages-tuple","updates","custom"],"on_disconnect":"continue","multitask_strategy":"reject"}
                """
                .formatted(value, requestId, optionId, value);
    }

    private void createThread(String threadId) throws Exception {
        assertThat(postJson("/api/threads", "{\"thread_id\":\"" + threadId + "\"}")
                        .statusCode())
                .isEqualTo(200);
    }

    private HttpResponse<String> postJson(String path, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(baseUri.resolve(path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(baseUri.resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private List<JsonNode> messageEvents(String transcript) throws Exception {
        Matcher matcher = Pattern.compile("event: messages\\ndata: ([^\\n]+)\\n")
                .matcher(transcript);
        List<JsonNode> messages = new ArrayList<>();
        while (matcher.find()) {
            JsonNode tuple = json.readTree(matcher.group(1));
            if (tuple.isArray() && !tuple.isEmpty()) messages.add(tuple.get(0));
        }
        assertThat(messages).as("SSE transcript: %s", transcript).isNotEmpty();
        return List.copyOf(messages);
    }

    private JsonNode humanInputCard(String transcript) throws Exception {
        return messageEvents(transcript).stream()
                .filter(message -> message.path("artifact").path("human_input").hasNonNull("request_id"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing human input card: " + transcript));
    }

    private static JsonNode messageWithToolCalls(JsonNode messages) {
        for (JsonNode message : messages) {
            if (!message.path("tool_calls").isEmpty()) return message;
        }
        throw new AssertionError("missing tool call message: " + messages);
    }

    private static JsonNode messageWithHumanInput(JsonNode messages) {
        for (JsonNode message : messages) {
            if (message.path("artifact").path("human_input").hasNonNull("request_id")) return message;
        }
        throw new AssertionError("missing human input message: " + messages);
    }

    private static JsonNode pageContentWithHumanInput(JsonNode page) {
        for (JsonNode item : page) {
            JsonNode content = item.path("content");
            if (content.path("artifact").path("human_input").hasNonNull("request_id")) return content;
        }
        throw new AssertionError("missing human input page item: " + page);
    }

    private static int indexOfMessage(JsonNode messages, JsonNode selected) {
        for (int index = 0; index < messages.size(); index++) {
            if (messages.get(index).equals(selected)) return index;
        }
        throw new AssertionError("message not present: " + selected);
    }
}
