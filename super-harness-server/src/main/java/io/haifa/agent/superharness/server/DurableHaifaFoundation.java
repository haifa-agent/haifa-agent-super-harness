package io.haifa.agent.superharness.server;

import io.haifa.agent.sdk.conversation.ConversationStore;
import io.haifa.agent.sdk.spi.SdkConversationContribution;
import io.haifa.agent.sdk.spi.SdkPersistenceContribution;
import io.haifa.agent.store.sqlite.SqliteSdkContributions;
import io.haifa.agent.store.sqlite.SqliteStoreConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.SecretKey;

/** API-owned SQLite foundation shared by per-model public SDK assemblies. */
final class DurableHaifaFoundation implements AutoCloseable {
    private final SqliteSdkContributions sqlite;
    private final SdkPersistenceContribution persistence;
    private final SdkConversationContribution conversation;
    private final AtomicBoolean closed = new AtomicBoolean();

    DurableHaifaFoundation(Path dataDirectory, SecretKey continuationKey) throws IOException {
        Path durableDirectory = dataDirectory.toAbsolutePath().normalize().resolve("runtime");
        Files.createDirectories(durableDirectory);
        this.sqlite = SqliteSdkContributions.initializeWithKey(
                SqliteStoreConfiguration.defaults(durableDirectory.resolve("haifa-agent.sqlite")),
                Clock.systemUTC(),
                continuationKey);
        this.persistence = sqlite.borrowedPersistence();
        this.conversation = new SdkConversationContribution() {
            @Override
            public ConversationStore conversationStore() {
                return sqlite.conversation().conversationStore();
            }
        };
    }

    SdkPersistenceContribution persistence() {
        requireOpen();
        return persistence;
    }

    SdkConversationContribution conversation() {
        requireOpen();
        return conversation;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            sqlite.persistence().close();
        }
    }

    private void requireOpen() {
        if (closed.get()) throw new IllegalStateException("durable Haifa foundation is closed");
    }
}
