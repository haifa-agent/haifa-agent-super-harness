package io.haifa.agent.superharness.workspace;

import io.haifa.agent.project.workspace.WorkspaceId;

public record WorkspaceBinding(String ownerId, String threadId, WorkspaceId workspaceId) {}
