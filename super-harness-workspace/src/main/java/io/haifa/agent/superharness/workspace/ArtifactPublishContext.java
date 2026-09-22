package io.haifa.agent.superharness.workspace;

public record ArtifactPublishContext(String projectId, String runId, String sessionId, String executionRef,
                                     String exportPolicy) {
    public ArtifactPublishContext {
        projectId = requireText(projectId, "projectId");
        runId = requireText(runId, "runId");
        sessionId = requireText(sessionId, "sessionId");
        executionRef = requireText(executionRef, "executionRef");
        exportPolicy = requireText(exportPolicy, "exportPolicy");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
