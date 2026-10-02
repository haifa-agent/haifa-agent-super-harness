package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.auth.localmodel.InMemoryWindowsCredentialManagerClient;
import io.haifa.agent.auth.localmodel.WindowsCredentialManagerClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContinuationKeyConfigurationTest {
    @TempDir
    Path dataDirectory;

    @Test
    void provisionsOsCredentialOnlyBeforeTheRuntimeDatabaseExistsAndReusesItAfterRestart() {
        InMemoryWindowsCredentialManagerClient credentials = new InMemoryWindowsCredentialManagerClient();

        byte[] first = DeerFlowBootstrapApi.continuationKey(Map.of(), dataDirectory, credentials)
                .getEncoded();
        byte[] reopened = DeerFlowBootstrapApi.continuationKey(Map.of(), dataDirectory, credentials)
                .getEncoded();

        assertThat(first).hasSize(32).containsExactly(reopened);
        assertThat(credentials.snapshot())
                .containsOnlyKeys("haifa:os:" + DeerFlowBootstrapApi.defaultOsCredentialName(dataDirectory));
    }

    @Test
    void explicitEnvironmentReferenceTakesPriorityAndDoesNotTouchTheOsStore() {
        InMemoryWindowsCredentialManagerClient credentials = new InMemoryWindowsCredentialManagerClient();
        byte[] configured = new byte[32];
        java.util.Arrays.fill(configured, (byte) 7);

        byte[] resolved = DeerFlowBootstrapApi.continuationKey(
                        Map.of(
                                DeerFlowBootstrapApi.CONTINUATION_KEY_REFERENCE_ENVIRONMENT,
                                "env://HARNESS_TEST_KEY",
                                "HARNESS_TEST_KEY",
                                Base64.getEncoder().encodeToString(configured)),
                        dataDirectory,
                        credentials)
                .getEncoded();

        assertThat(resolved).containsExactly(configured);
        assertThat(credentials.snapshot()).isEmpty();
    }

    @Test
    void explicitOsReferenceUsesTheExistingCredentialWithoutOverwritingIt() {
        InMemoryWindowsCredentialManagerClient credentials = new InMemoryWindowsCredentialManagerClient();
        byte[] configured = new byte[32];
        java.util.Arrays.fill(configured, (byte) 11);
        credentials.write("haifa:os:CUSTOM_HARNESS_KEY", Base64.getEncoder().encodeToString(configured));

        byte[] resolved = DeerFlowBootstrapApi.continuationKey(
                        Map.of(
                                DeerFlowBootstrapApi.CONTINUATION_KEY_REFERENCE_ENVIRONMENT,
                                "os://CUSTOM_HARNESS_KEY"),
                        dataDirectory,
                        credentials)
                .getEncoded();

        assertThat(resolved).containsExactly(configured);
        assertThat(credentials.snapshot()).containsOnlyKeys("haifa:os:CUSTOM_HARNESS_KEY");
    }

    @Test
    void missingExplicitOsReferenceFailsClosedForAFreshDatabase() {
        InMemoryWindowsCredentialManagerClient credentials = new InMemoryWindowsCredentialManagerClient();

        assertThatThrownBy(() -> DeerFlowBootstrapApi.continuationKey(
                        Map.of(
                                DeerFlowBootstrapApi.CONTINUATION_KEY_REFERENCE_ENVIRONMENT,
                                "os://MISSPELLED_CUSTOM_KEY"),
                        dataDirectory,
                        credentials))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("continuation key credential is unavailable");
        assertThat(credentials.snapshot()).isEmpty();
        assertThat(dataDirectory.resolve("runtime/haifa-agent.sqlite")).doesNotExist();
    }

    @Test
    void defaultOsCredentialTargetsAreScopedByCanonicalDataRoot() {
        Path first = dataDirectory.resolve("slot-one");
        Path second = dataDirectory.resolve("slot-two");

        assertThat(DeerFlowBootstrapApi.defaultOsCredentialName(first))
                .startsWith("HAIFA_SUPER_HARNESS_CONTINUATION_KEY_")
                .isNotEqualTo(DeerFlowBootstrapApi.defaultOsCredentialName(second));
        assertThat(DeerFlowBootstrapApi.defaultOsCredentialName(first))
                .isEqualTo(DeerFlowBootstrapApi.defaultOsCredentialName(first.toAbsolutePath()));
    }

    @Test
    void existingRuntimeDatabaseWithoutItsCredentialFailsClosed() throws Exception {
        Path runtime = Files.createDirectories(dataDirectory.resolve("runtime"));
        Files.createFile(runtime.resolve("haifa-agent.sqlite"));

        assertThatThrownBy(() -> DeerFlowBootstrapApi.continuationKey(
                        Map.of(), dataDirectory, new InMemoryWindowsCredentialManagerClient()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("continuation key credential is unavailable for the existing Runtime database");
    }

    @Test
    void explicitEnvironmentReferenceWorksWhenTheOsCredentialStoreIsUnavailable() {
        byte[] configured = new byte[32];

        byte[] resolved = DeerFlowBootstrapApi.continuationKey(
                        Map.of(
                                DeerFlowBootstrapApi.CONTINUATION_KEY_REFERENCE_ENVIRONMENT,
                                "env://HARNESS_TEST_KEY",
                                "HARNESS_TEST_KEY",
                                Base64.getEncoder().encodeToString(configured)),
                        dataDirectory,
                        new UnavailableCredentials())
                .getEncoded();

        assertThat(resolved).containsExactly(configured);
    }

    @Test
    void unavailableOsCredentialStoreFailsClearlyWithoutSecretMaterial() {
        assertThatThrownBy(() -> DeerFlowBootstrapApi.continuationKey(
                        Map.of(), dataDirectory, new UnavailableCredentials()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OS credential store is unavailable")
                .hasMessageNotContaining("Base64");
    }

    private static final class UnavailableCredentials implements WindowsCredentialManagerClient {
        @Override
        public Optional<String> read(String targetName) {
            throw new IllegalStateException("OS credential store is unavailable");
        }

        @Override
        public void write(String targetName, String secret) {
            throw new IllegalStateException("OS credential store is unavailable");
        }

        @Override
        public boolean delete(String targetName) {
            throw new IllegalStateException("OS credential store is unavailable");
        }

        @Override
        public List<String> listTargets(String prefixFilter) {
            throw new IllegalStateException("OS credential store is unavailable");
        }
    }
}
