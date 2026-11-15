package io.haifa.agent.superharness.server.tool.poster;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.superharness.workspace.PersistentProjectWorkspaceService;
import io.haifa.agent.superharness.workspace.RestrictedWorkspaceService;
import io.haifa.agent.superharness.workspace.WorkspaceChangeLimits;
import io.haifa.agent.superharness.workspace.WorkspaceLimits;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PosterRenderToolTest {
    @TempDir Path root;

    @Test
    void renderingAndEditingSkipAbandonedVersionsWithoutOverwritingThem() throws Exception {
        var limits = WorkspaceLimits.defaults();
        var bindings = new PersistentProjectWorkspaceService(root, limits, WorkspaceChangeLimits.defaults());
        var workspace = new RestrictedWorkspaceService(root, limits);
        var binding = bindings.bindThreadWorkspace("default", "poster-retry");
        var context = new JavaToolContext(new AgentRunId("poster-retry-run"), Optional.empty(),
                new ToolCallId("poster-retry-call"), new TenantRef("public"), new PrincipalRef("default-public-user", "user"),
                Instant.now().plusSeconds(60), Optional.empty(), () -> false, Map.of());
        var render = new PosterRenderTool("poster-retry", bindings, workspace);
        var edit = new PosterEditTool(render);
        var empty = Optional.<String>empty();
        byte[] abandoned = new byte[]{1, 2, 3};
        String orphanA = "workspace/FDE-A稿-v1.json";
        String orphanB = "outputs/FDE-B稿-v3.png";
        byte[] visual = PosterArtwork.png(new BufferedImage(360, 360, BufferedImage.TYPE_INT_RGB));
        workspace.createWorkingFile(binding.ownerId(), binding.workspaceId(), "outputs/poster-visual.png", visual);
        workspace.createWorkingFile(binding.ownerId(), binding.workspaceId(), orphanA, abandoned);

        var drafts = render.invoke(new PosterRenderTool.Input(empty, Optional.of("outputs/poster-visual.png"),
                Optional.of("FDE"), empty, empty, Optional.of("2026-12-12"), empty, empty, Optional.empty()), context);
        assertThat(drafts.error()).isEmpty();
        assertThat(drafts.files()).containsExactlyElementsOf(List.of("/mnt/user-data/outputs/FDE-A-B对比-v2.png",
                "/mnt/user-data/outputs/FDE-A稿-v2.png", "/mnt/user-data/outputs/FDE-B稿-v2.png"));
        assertThat(edit.invoke(new PosterEditTool.Input(Optional.of("B"), empty, empty, empty, empty, empty),
                context).currentPoster()).endsWith("FDE-B稿-v2.png");
        workspace.createWorkingFile(binding.ownerId(), binding.workspaceId(), orphanB, abandoned);

        var revised = edit.invoke(new PosterEditTool.Input(empty, empty, empty, empty,
                Optional.of("2026-12-19"), empty), context);
        assertThat(revised.error()).isEmpty();
        assertThat(revised.currentPoster()).endsWith("FDE-B稿-v4.png");
        assertThat(revised.startDate()).isEqualTo("2026-12-19");
        assertThat(workspace.readVerifiedBytes(binding.ownerId(), binding.workspaceId(), orphanA)).isEqualTo(abandoned);
        assertThat(workspace.readVerifiedBytes(binding.ownerId(), binding.workspaceId(), orphanB)).isEqualTo(abandoned);
    }
}
