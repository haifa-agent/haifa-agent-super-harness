package io.haifa.agent.superharness.server.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.memory.api.MemoryActor;
import io.haifa.agent.memory.api.MemoryContextRequest;
import io.haifa.agent.memory.api.MemoryDraft;
import io.haifa.agent.memory.api.MemoryKind;
import io.haifa.agent.memory.api.MemoryOperationException;
import io.haifa.agent.memory.api.MemoryQuery;
import io.haifa.agent.memory.api.MemoryScope;
import io.haifa.agent.sdk.api.SdkCaller;
import io.haifa.agent.sdk.product.ProductMemoryPolicy;
import io.haifa.agent.store.sqlite.SqliteSdkContributions;
import io.haifa.agent.store.sqlite.SqliteStoreConfiguration;
import io.haifa.agent.superharness.server.auth.AccountSdkIdentity;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DurableMemoryAcceptanceTest {
    @TempDir Path directory;

    @Test
    void sqliteRejectsOlderAndEqualWritesAfterDeletionAndClearIncludingRestart() throws Exception {
        var caller = AccountSdkIdentity.forOwner("memory-owner-a");
        var actor = new MemoryActor(caller.tenant(), caller.principal());
        var scope = MemoryScope.user(caller.tenant(), caller.principal());
        var fixed = Instant.parse("2026-11-22T12:00:00Z");
        var configuration = SqliteStoreConfiguration.defaults(directory.resolve("memory.sqlite"));
        var key = new SecretKeySpec(new byte[32], "AES");
        var sqlite = SqliteSdkContributions.initializeWithKey(configuration, Clock.fixed(fixed, ZoneOffset.UTC), key);
        try {
            var service = sqlite.memory(ProductMemoryPolicy.safeDefault()).service();
            var initial = service.put(draft(scope, "remove-me", fixed.minusSeconds(10)), actor);
            var updated = service.update(initial.id(), initial.revision(), "Winner", actor);
            assertThat(updated.revision()).isGreaterThan(initial.revision());
            assertThatThrownBy(() -> service.update(initial.id(), initial.revision(), "Loser", actor))
                    .isInstanceOfSatisfying(MemoryOperationException.class,
                            failure -> assertThat(failure.code()).isEqualTo("MEMORY_REVISION_STALE"));
            service.delete(initial.id(), updated.revision(), actor);
            for (Instant time : java.util.List.of(fixed.minusSeconds(1), fixed)) {
                assertThatThrownBy(() -> service.put(draft(scope, "remove-me", time), actor))
                        .isInstanceOfSatisfying(MemoryOperationException.class,
                                failure -> assertThat(failure.code()).isEqualTo("MEMORY_WRITE_STALE"));
            }
            assertThat(service.find(initial.id(), actor)).isEmpty();
            service.clear(scope, actor);
            assertThatThrownBy(() -> service.put(draft(scope, "not-yet-inserted", fixed), actor))
                    .isInstanceOfSatisfying(MemoryOperationException.class,
                            failure -> assertThat(failure.code()).isEqualTo("MEMORY_WRITE_STALE"));
        } finally {
            sqlite.persistence().close();
        }

        var reopened = SqliteSdkContributions.initializeWithKey(
                configuration, Clock.fixed(fixed.plusSeconds(1), ZoneOffset.UTC), key);
        try {
            var service = reopened.memory(ProductMemoryPolicy.safeDefault()).service();
            for (String subject : java.util.List.of("remove-me", "not-yet-inserted")) {
                assertThatThrownBy(() -> service.put(draft(scope, subject, fixed.minusSeconds(1)), actor))
                        .isInstanceOfSatisfying(MemoryOperationException.class,
                                failure -> assertThat(failure.code()).isEqualTo("MEMORY_WRITE_STALE"));
            }
            assertThat(service.list(MemoryQuery.all(scope, 10), actor).items()).isEmpty();
            assertThat(service.put(draft(scope, "fresh-explicit-write", fixed.plusSeconds(1)), actor)).isNotNull();
        } finally {
            reopened.persistence().close();
        }
    }

    @Test
    void recallRemainsBoundToUserScopeAndSdkBudget() throws Exception {
        var a = AccountSdkIdentity.forOwner("memory-owner-a");
        var b = AccountSdkIdentity.forOwner("memory-owner-b");
        var scope = MemoryScope.user(a.tenant(), a.principal());
        var alice = new MemoryActor(a.tenant(), a.principal());
        var key = new SecretKeySpec(new byte[32], "AES");
        try (var foundation = new DurableHaifaFoundation(directory, key, true)) {
            var service = foundation.memories().orElseThrow();
            var retriever = foundation.accountMemory(a).orElseThrow().retriever();
            service.put(draft(scope, "short", Instant.now()), alice);
            service.put(new MemoryDraft(scope, MemoryKind.FACT, "over-budget", "A bounded paragraph of ordinary test prose. ".repeat(32),
                    Optional.empty(), Optional.empty()), alice);
            service.put(draft(MemoryScope.agent(a.tenant(), a.principal(), "agent-1"), "agent", Instant.now()), alice);
            service.put(draft(MemoryScope.session(a.tenant(), a.principal(), "session-1"), "session", Instant.now()), alice);
            var snippets = retriever.contextFor(new MemoryContextRequest(
                    a.tenant(), a.principal(), "run-1", "session-1", "agent-1", "test", 16)).snippets();
            assertThat(snippets).isNotEmpty().allSatisfy(snippet -> {
                assertThat(snippet.scope()).isEqualTo(scope);
                assertThat(snippet.text()).isEqualTo("a test fact");
            });
            assertThat(snippets.stream().mapToInt(snippet -> snippet.estimatedTokens()).sum()).isLessThanOrEqualTo(16);
            assertThat(retriever.contextFor(new MemoryContextRequest(
                    b.tenant(), b.principal(), "run-2", "session-1", "agent-1", "test", 16)).snippets()).isEmpty();
            assertThat(foundation.accountMemory(SdkCaller.defaultPublicUser())).isEmpty();
        }
    }

    private static MemoryDraft draft(MemoryScope scope, String subject, Instant observedAt) {
        return new MemoryDraft(scope, MemoryKind.FACT, subject, "a test fact",
                Optional.empty(), Optional.of(observedAt));
    }
}
