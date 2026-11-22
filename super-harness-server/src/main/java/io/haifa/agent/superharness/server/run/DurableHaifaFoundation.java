package io.haifa.agent.superharness.server.run;

import io.haifa.agent.memory.api.MemoryContext;
import io.haifa.agent.memory.api.MemoryScope;
import io.haifa.agent.memory.api.MemoryService;
import io.haifa.agent.sdk.api.SdkCaller;
import io.haifa.agent.sdk.contribution.MemoryPlatformContribution;
import io.haifa.agent.sdk.conversation.ConversationStore;
import io.haifa.agent.sdk.product.ProductMemoryPolicy;
import io.haifa.agent.sdk.spi.SdkConversationContribution;
import io.haifa.agent.sdk.spi.SdkPersistenceContribution;
import io.haifa.agent.store.sqlite.SqliteSdkContributions;
import io.haifa.agent.store.sqlite.SqliteStoreConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.SecretKey;

/** API-owned SQLite foundation shared by per-model public SDK assemblies. */
public final class DurableHaifaFoundation implements AutoCloseable {
    public static final String MEMORY_ENABLED = "HAIFA_SUPER_HARNESS_MEMORY_ENABLED";

    private final SqliteSdkContributions sqlite;
    private final SdkPersistenceContribution persistence;
    private final SdkConversationContribution conversation;
    private final Optional<MemoryPlatformContribution> memory;
    private final AtomicBoolean closed = new AtomicBoolean();

    public DurableHaifaFoundation(Path dataDirectory, SecretKey continuationKey) throws IOException {
        this(dataDirectory, continuationKey, false);
    }

    public DurableHaifaFoundation(Path dataDirectory, SecretKey continuationKey, boolean memoryEnabled)
            throws IOException {
        Path durableDirectory = dataDirectory.toAbsolutePath().normalize().resolve("runtime");
        Files.createDirectories(durableDirectory);
        this.sqlite = SqliteSdkContributions.initializeWithKey(
                SqliteStoreConfiguration.defaults(durableDirectory.resolve("haifa-agent.sqlite")),
                Clock.systemUTC(),
                continuationKey);
        this.persistence = sqlite.borrowedPersistence();
        this.memory = memoryEnabled ? Optional.of(sqlite.memory(ProductMemoryPolicy.safeDefault())) : Optional.empty();
        this.conversation = new SdkConversationContribution() {
            @Override
            public ConversationStore conversationStore() {
                return sqlite.conversation().conversationStore();
            }
        };
    }

    public SdkPersistenceContribution persistence() {
        requireOpen();
        return persistence;
    }

    public SdkConversationContribution conversation() {
        requireOpen();
        return conversation;
    }

    public Optional<MemoryService> memories() {
        requireOpen();
        return memory.map(MemoryPlatformContribution::service);
    }

    public Optional<MemoryPlatformContribution> accountMemory(SdkCaller caller) {
        requireOpen();
        if (SdkCaller.defaultPublicUser().equals(caller)) return Optional.empty();
        MemoryScope scope = MemoryScope.user(caller.tenant(), caller.principal());
        return memory.map(platform -> new MemoryPlatformContribution(platform.service(), request -> {
            var context = platform.retriever().contextFor(request);
            return new MemoryContext(context.snippets().stream()
                    .filter(snippet -> scope.equals(snippet.scope()))
                    .toList(), context.policyVersion(), context.queryDigest());
        }, platform.policy()).withRecallWhen(request ->
                scope.tenant().equals(request.tenant()) && scope.owner().equals(request.owner())));
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
