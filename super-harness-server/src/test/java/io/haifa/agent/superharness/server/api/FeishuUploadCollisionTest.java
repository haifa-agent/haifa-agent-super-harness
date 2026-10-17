package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.superharness.server.auth.ThreadOwners;
import io.haifa.agent.superharness.server.channels.feishu.InProcessFeishuAgentBridge;
import io.haifa.agent.superharness.workspace.RestrictedWorkspaceService;
import io.haifa.agent.superharness.workspace.UploadReceipt;
import io.haifa.agent.superharness.workspace.WorkspaceBinding;
import io.haifa.agent.superharness.workspace.WorkspaceLimits;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P1 regression: colliding Feishu attachment names must surface the stored basename in
 * {@code additional_kwargs.files} so {@link DeerFlowUploadContext} reads the new bytes.
 */
class FeishuUploadCollisionTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path dataDirectory;

    @Test
    void collidingImageJpgUsesStoredBasenameSoModelInputSeesSecondHash() {
        RestrictedWorkspaceService workspace =
                new RestrictedWorkspaceService(dataDirectory.resolve("workspace"), WorkspaceLimits.defaults());
        String threadId = "thread-feishu-collision";
        String owner = "owner-feishu";
        ThreadOwners.remember(threadId, owner);
        WorkspaceBinding binding = workspace.bind(owner, threadId);

        byte[] firstBytes = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01};
        byte[] secondBytes = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x02, 0x03};
        UploadReceipt first =
                workspace.upload(owner, binding.workspaceId(), "image.jpg", firstBytes, "image/jpeg");
        UploadReceipt second =
                workspace.upload(owner, binding.workspaceId(), "image.jpg", secondBytes, "image/jpeg");

        String firstName = InProcessFeishuAgentBridge.storedUploadFilename(first.logicalPath());
        String secondName = InProcessFeishuAgentBridge.storedUploadFilename(second.logicalPath());
        assertThat(firstName).isEqualTo("image.jpg");
        assertThat(secondName).matches("image__[0-9a-f]{12}\\.jpg");
        assertThat(secondName).isNotEqualTo(firstName);
        // originalFilename stays image.jpg even after collision — that is the bug if used as files[].filename
        assertThat(second.originalFilename()).isEqualTo("image.jpg");

        ArrayNode messages = json.createArrayNode();
        ObjectNode human = messages.addObject();
        human.put("type", "human");
        human.put("content", "请查看附件");
        ObjectNode file = human.putObject("additional_kwargs").putArray("files").addObject();
        file.put("filename", secondName);
        file.put("path", second.logicalPath());
        file.put("size", second.byteCount());
        file.put("status", "uploaded");

        String modelInput = new DeerFlowUploadContext(workspace).modelInput(threadId, messages, "请查看附件");
        assertThat(modelInput).contains(second.sha256());
        assertThat(modelInput).doesNotContain(first.sha256());
        assertThat(modelInput).contains(secondName);
    }
}
