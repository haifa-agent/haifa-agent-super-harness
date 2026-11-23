package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.memory.api.MemoryActor;
import io.haifa.agent.memory.api.MemoryKind;
import io.haifa.agent.memory.api.MemoryOperationException;
import io.haifa.agent.memory.api.MemoryQuery;
import io.haifa.agent.memory.api.MemoryScope;
import io.haifa.agent.superharness.server.auth.AccountSdkIdentity;
import io.haifa.agent.superharness.server.run.DurableHaifaFoundation;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeerFlowMemoryApiTest {
    @Test
    void generatedSubjectDoesNotRejectOrdinaryContentOrExemptSensitiveContent(@TempDir Path data) throws Exception {
        UUID id = UUID.fromString("facecafe-1234-4123-8123-123456789abc");
        String ordinary = "Daily report first line: use tag ALPHA3745B547";
        var caller = AccountSdkIdentity.forOwner("memory-subject-regression");
        var actor = new MemoryActor(caller.tenant(), caller.principal());
        var scope = MemoryScope.user(actor.tenant(), actor.principal());
        var now = Instant.now();

        try (var foundation = new DurableHaifaFoundation(data, new SecretKeySpec(new byte[32], "AES"), true)) {
            var service = foundation.memories().orElseThrow();
            var saved = service.put(DeerFlowMemoryApi.manualFact(scope, MemoryKind.FACT, id, ordinary, now), actor);
            assertThat(saved.content()).isEqualTo(ordinary);

            for (String sensitive : new String[]{"My test password is demo-only", "Test card: 4242 4242 4242 4242"}) {
                var draft = DeerFlowMemoryApi.manualFact(scope, MemoryKind.FACT, id, sensitive, now);
                assertThatThrownBy(() -> service.put(draft, actor))
                        .isInstanceOfSatisfying(MemoryOperationException.class,
                                failure -> assertThat(failure.code()).isEqualTo("MEMORY_CONTENT_SENSITIVE"));
            }
            assertThat(service.list(MemoryQuery.all(scope, 10), actor).items()).containsExactly(saved);
        }
    }
}
