package io.haifa.agent.superharness.server.integrations.lark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.superharness.server.config.SkillConfigStore;
import io.haifa.agent.superharness.server.testsupport.LarkIntegrationFakes;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LarkInfrastructureTest {
    @TempDir Path data;

    @Test void realNativeChildReceivesMetacharactersVerbatimAndPreservesRuntimePath() throws Exception {
        var gateway = new LarkCliProcessGateway(data.resolve("gateway-cli"), Map.of("Path", "safe-inherited-runtime-path"));
        String argument = "app&cd.>shell-metacharacter-executed.txt %PATH% !x! \"quoted\"";
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        var result = gateway.run(List.of(javaExecutable.toString(), "-cp", System.getProperty("java.class.path"),
                Argv.class.getName(), argument), Map.of(), Duration.ofSeconds(10), null);
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(new String(java.util.Base64.getDecoder().decode(result.stdout().strip()), StandardCharsets.UTF_8)).isEqualTo(argument);
        assertThat(gateway.childEnvironment(Map.of()).get("PATH")).isEqualTo("safe-inherited-runtime-path");
        assertThatThrownBy(() -> gateway.run(List.of("untrusted.cmd", argument), Map.of(), Duration.ofSeconds(1), null))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("Shell wrappers");
    }

    public static class Argv {
        public static void main(String[] args) {
            System.out.print(java.util.Base64.getEncoder().encodeToString(args[0].getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test void boundedArchiveCancelsBeforeWritingOverLimit() throws Exception {
        Path target = data.resolve("download.zip");
        var subscriber = new LarkArchiveSource.BoundedArchive(target, 8);
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {
            public void request(long n) {}
            public void cancel() { cancelled.set(true); }
        });
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[6])));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[6])));
        assertThat(cancelled).isTrue();
        assertThat(Files.size(target)).isEqualTo(6);
        assertThatThrownBy(() -> subscriber.getBody().toCompletableFuture().join()).hasCauseInstanceOf(java.io.IOException.class);
    }

    @Test void initialReloadFailureRemovesPackAndReloadsEmptyCatalog() throws Exception {
        var json = new ObjectMapper();
        var store = new SkillConfigStore(json, data, SkillConfigStore.repositoryRootFromWorkingDirectory());
        var installer = new LarkSkillPackInstaller(json, data.resolve("integrations/lark"));
        Path archive = LarkIntegrationFakes.archive(data.resolve("fixture.zip"), "v1.0.65", LarkSkillCatalog.NAMES, null);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        assertThatThrownBy(() -> installer.install(archive, "v1.0.65", () -> {
            store.reload();
            if (calls.getAndIncrement() == 0) throw new java.io.IOException("controlled initial publication failure");
        })).isInstanceOf(java.io.IOException.class);
        assertThat(installer.packRoot()).doesNotExist();
        assertThat(calls).hasValue(2);
        assertThat(store.list().stream().filter(row -> "integration-managed".equals(row.get("source")))).isEmpty();
    }
}
