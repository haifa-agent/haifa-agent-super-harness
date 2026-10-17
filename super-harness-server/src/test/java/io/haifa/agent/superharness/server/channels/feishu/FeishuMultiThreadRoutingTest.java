package io.haifa.agent.superharness.server.channels.feishu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Regression checks for durable Feishu chat pointers and historical reply aliases. */
class FeishuMultiThreadRoutingTest {
    @TempDir Path dataDirectory;

    @Test
    void newConversationRotatesOnlyActivePointerAndRetainsOldReply() {
        var store = new FeishuChatThreadStore(new ObjectMapper(), dataDirectory);
        String first = store.getOrCreate("connection", "chat");
        store.bindAlias("connection", "chat", "old-message", first);
        String second = store.startNew("connection", "chat");
        assertThat(second).isNotEqualTo(first);
        assertThat(store.find("connection", "chat")).contains(second);
        assertThat(store.findAlias("connection", "chat", "old-message")).contains(first);
        assertThat(new FeishuChatThreadStore(new ObjectMapper(), dataDirectory)
                .findAlias("connection", "chat", "old-message")).contains(first);
    }

    @Test
    void aliasIsImmutableAndScopedByConnectionAndChat() {
        var store = new FeishuChatThreadStore(new ObjectMapper(), dataDirectory);
        store.bindAlias("c1", "a", "m1", "thread-1");
        store.bindAlias("c1", "a", "m1", "thread-1");
        assertThat(store.findAlias("c1", "a", "m1")).contains("thread-1");
        assertThat(store.findAlias("c2", "a", "m1")).isEmpty();
        assertThat(store.findAlias("c1", "b", "m1")).isEmpty();
        assertThatThrownBy(() -> store.bindAlias("c1", "a", "m1", "thread-2"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void oldMapFileKeepsWorkingWithoutMigration() {
        var store = new FeishuChatThreadStore(new ObjectMapper(), dataDirectory);
        String original = store.getOrCreate("old", "chat");
        var restarted = new FeishuChatThreadStore(new ObjectMapper(), dataDirectory);
        assertThat(restarted.getOrCreate("old", "chat")).isEqualTo(original);
    }
}
