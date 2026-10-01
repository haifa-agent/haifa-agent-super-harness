package io.haifa.agent.superharness.server;

import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.runtime.api.ToolCallView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Read-only, bounded transport projection of public SDK facts for one caller-visible Run. */
final class RunDiagnosticProjection {
    private RunDiagnosticProjection() {}

    static Facts from(
            AgentRunSnapshot snapshot,
            List<AgentRunEvent> events,
            List<ToolCallView> toolCalls,
            Optional<InteractionView> pendingInteraction,
            boolean eventsComplete,
            String incompleteReason) {
        Map<String, RunEventPayloads.ModelLifecycle> modelsByCall = new LinkedHashMap<>();
        Map<String, Map<String, Object>> interactionsById = new LinkedHashMap<>();
        boolean failedModelCall = false;
        for (AgentRunEvent event : events) {
            if (!event.runId().equals(snapshot.runId())) {
                throw new IllegalStateException("Diagnostic event belongs to a different Run");
            }
            if (event.payload() instanceof RunEventPayloads.ModelLifecycle model) {
                modelsByCall.put(model.modelCallId(), model);
                if ("FAILED".equals(model.status())) failedModelCall = true;
            } else if (event.payload() instanceof RunEventPayloads.InteractionLifecycle interaction) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("request_id", interaction.requestId());
                item.put("run_id", snapshot.runId().value());
                item.put("kind", interaction.kind());
                item.put("state", interaction.state());
                String action = Optional.ofNullable(interaction.actionOrReason())
                        .orElse("").toLowerCase(Locale.ROOT);
                item.put("action", List.of("approve", "reject").contains(action) ? action : null);
                interactionsById.put(interaction.requestId(), item);
            }
        }
        pendingInteraction.ifPresent(pending -> {
            if (!pending.runId().equals(snapshot.runId())) {
                throw new IllegalStateException("Pending Interaction belongs to a different Run");
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("request_id", pending.requestId().value());
            item.put("run_id", snapshot.runId().value());
            item.put("kind", pending.kind().value());
            item.put("state", pending.state().name());
            item.put("action", null);
            interactionsById.put(pending.requestId().value(), item);
        });

        long eventInput = 0;
        long eventOutput = 0;
        int succeededCalls = 0;
        Map<String, long[]> usageByModel = new LinkedHashMap<>();
        List<Map<String, Object>> modelCalls = new ArrayList<>();
        for (RunEventPayloads.ModelLifecycle model : modelsByCall.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("model_call_id", model.modelCallId());
            item.put("run_id", snapshot.runId().value());
            item.put("provider_id", model.providerId());
            item.put("model_id", model.modelId());
            item.put("configuration_digest", null); // Not retained in durable public Run facts.
            item.put("iteration", model.iteration());
            item.put("attempt", model.attempt());
            item.put("status", model.status());
            item.put("finish_reason", safeCode(model.finishReason()));
            item.put("reason_code", safeCode(model.reasonCode()));
            if ("SUCCEEDED".equals(model.status())) {
                succeededCalls++;
                eventInput = Math.addExact(eventInput, model.inputTokens());
                eventOutput = Math.addExact(eventOutput, model.outputTokens());
                // Length-prefix the provider: even IDs containing '/' or ':' cannot collide.
                String modelKey = model.providerId().length() + ":" + model.providerId() + model.modelId();
                long[] byModel = usageByModel.computeIfAbsent(modelKey, ignored -> new long[2]);
                byModel[0] = Math.addExact(byModel[0], model.inputTokens());
                byModel[1] = Math.addExact(byModel[1], model.outputTokens());
                item.put("input_tokens", model.inputTokens());
                item.put("output_tokens", model.outputTokens());
            } else {
                item.put("input_tokens", null);
                item.put("output_tokens", null);
            }
            modelCalls.add(item);
        }
        boolean usageKnown = eventsComplete
                && snapshot.status().isTerminal()
                && !failedModelCall
                && succeededCalls > 0
                && succeededCalls == modelsByCall.size()
                && succeededCalls == snapshot.usage().modelCalls()
                && eventInput == snapshot.usage().inputTokens()
                && eventOutput == snapshot.usage().outputTokens();

        List<Map<String, Object>> tools = new ArrayList<>();
        List<Map<String, Object>> artifacts = new ArrayList<>();
        for (ToolCallView call : toolCalls) {
            if (!call.runId().equals(snapshot.runId())) {
                throw new IllegalStateException("Diagnostic ToolCall belongs to a different Run");
            }
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("tool_call_id", call.id().value());
            tool.put("run_id", snapshot.runId().value());
            tool.put("name", call.toolName());
            tool.put("status", call.status().name());
            tool.put("result_successful", call.result().map(value -> value.successful()).orElse(null));
            tool.put("error_code", call.error().map(value -> safeCode(value.wireCode())).orElse(null));
            tool.put("actual_source", null); // The public ToolCallView has no MCP/native origin.
            tool.put("requested_at", call.requestedAt().toString());
            tool.put("started_at", call.startedAt().map(Object::toString).orElse(null));
            tool.put("completed_at", call.completedAt().map(Object::toString).orElse(null));
            tools.add(tool);
            call.result().ifPresent(result -> {
                Map<String, Object> values = result.structuredData().values();
                result.artifacts().forEach(reference -> {
                    if (artifacts.stream().anyMatch(value -> reference.artifactId().equals(value.get("artifact_id")))) {
                        return;
                    }
                    Map<String, Object> artifact = artifactIdentity(
                            snapshot.runId().value(), call.id().value(), reference.artifactId(),
                            reference.artifactType(), reference.version());
                    boolean matchesPublishedFile = "write_file".equals(call.toolName())
                            && reference.artifactId().equals(values.get("artifactId"));
                    artifact.put("path", matchesPublishedFile ? filePath(values.get("path")) : null);
                    artifact.put("sha256", matchesPublishedFile ? fileHash(values.get("artifactSha256")) : null);
                    artifacts.add(artifact);
                });
                // Production write_file publishes through SH's Artifact service and returns its
                // durable identity in Tool structuredData; the SDK result may carry no ArtifactRef.
                if (result.successful() && call.status() == ToolCallStatus.COMPLETED
                        && "write_file".equals(call.toolName())
                        && snapshot.runId().value().equals(values.get("runId"))
                        && call.id().value().equals(values.get("artifactExecutionRef"))
                        && values.get("artifactId") instanceof String artifactId && !artifactId.isBlank()
                        && filePath(values.get("path")) != null
                        && fileHash(values.get("artifactSha256")) != null
                        && artifacts.stream().noneMatch(value -> artifactId.equals(value.get("artifact_id")))) {
                    Object version = values.get("artifactVersion");
                    Map<String, Object> artifact = artifactIdentity(
                            snapshot.runId().value(), call.id().value(), artifactId,
                            values.get("artifactType") instanceof String type ? type : null,
                            version instanceof Number number ? number.longValue() : null);
                    artifact.put("path", filePath(values.get("path")));
                    artifact.put("sha256", fileHash(values.get("artifactSha256")));
                    artifacts.add(artifact);
                }
            });
        }

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("availability", usageKnown ? "known" : "unknown");
        usage.put("input_tokens", usageKnown ? snapshot.usage().inputTokens() : null);
        usage.put("output_tokens", usageKnown ? snapshot.usage().outputTokens() : null);
        usage.put("total_tokens", usageKnown
                ? Math.addExact(snapshot.usage().inputTokens(), snapshot.usage().outputTokens()) : null);
        usage.put("llm_call_count", usageKnown ? snapshot.usage().modelCalls() : null);
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("thread_run_id", snapshot.runId().value());
        diagnostics.put("run_attempt", null); // SDK publishes ModelCall attempt, not a Run attempt.
        diagnostics.put("projection_complete", eventsComplete);
        diagnostics.put("incomplete_reason", incompleteReason);
        diagnostics.put("sdk_status", snapshot.status().name());
        diagnostics.put("started_at", null); // Snapshot has last update, not an exact start time.
        diagnostics.put("ended_at", null); // Last update is not guaranteed to be termination time.
        diagnostics.put("last_updated_at", snapshot.updatedAt().toString());
        diagnostics.put("stop_reason", snapshot.terminationReason().map(value -> safeCode(value.code())).orElse(null));
        diagnostics.put("error_code", snapshot.error().map(value -> safeCode(value.code().wireCode())).orElse(null));
        diagnostics.put("diagnostic_id", snapshot.error().flatMap(value -> value.optionalDiagnosticId())
                .map(RunDiagnosticProjection::safeCode).orElse(null));
        diagnostics.put("duration_ms", snapshot.status().isTerminal() && snapshot.usage().wallTimeMillis() > 0
                ? snapshot.usage().wallTimeMillis() : null);
        boolean summaryOnly = "run_list_summary".equals(incompleteReason);
        diagnostics.put("model_calls", summaryOnly ? null : List.copyOf(modelCalls));
        diagnostics.put("tool_calls", summaryOnly ? null : List.copyOf(tools));
        diagnostics.put("interactions", summaryOnly ? null : List.copyOf(interactionsById.values()));
        diagnostics.put("artifacts", summaryOnly ? null : List.copyOf(artifacts));
        diagnostics.put("usage", usage);
        return new Facts(diagnostics, usageKnown, Map.copyOf(usageByModel));
    }

    private static String safeCode(String value) {
        return value != null && value.matches("[A-Za-z0-9_.:-]{1,128}") ? value : null;
    }

    private static Map<String, Object> artifactIdentity(
            String runId, String toolCallId, String artifactId, String type, Object version) {
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("artifact_id", artifactId);
        artifact.put("run_id", runId);
        artifact.put("tool_call_id", toolCallId);
        artifact.put("type", type);
        artifact.put("version", version);
        return artifact;
    }

    private static String filePath(Object value) {
        if (!(value instanceof String path)
                || !(path.startsWith("/mnt/user-data/workspace/")
                        || path.startsWith("/mnt/user-data/outputs/"))
                || path.indexOf('\\') >= 0) {
            return null;
        }
        for (String segment : path.substring("/mnt/user-data/".length()).split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return null;
            }
        }
        return path;
    }

    private static String fileHash(Object value) {
        return value instanceof String hash && hash.matches("sha256:[a-fA-F0-9]{64}") ? hash : null;
    }

    record Facts(Map<String, Object> values, boolean usageKnown, Map<String, long[]> usageByModel) {}
}
