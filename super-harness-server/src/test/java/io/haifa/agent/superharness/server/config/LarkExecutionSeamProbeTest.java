package io.haifa.agent.superharness.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.credential.api.CredentialBroker;
import io.haifa.agent.execution.api.EnvironmentLeaseResolver;
import io.haifa.agent.execution.api.ExecutionEnvironmentRef;
import io.haifa.agent.execution.api.ResolvedExecutionEnvironment;
import io.haifa.agent.execution.core.tool.ExecutionToolConfiguration;
import io.haifa.agent.starter.HostExecutionPlatforms;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Feasibility probe for W20-task-5: can the published SDK deliver per-user {@code lark-cli}
 * config/data directories to {@code execution_run}?
 *
 * <p>This test asserts only public API facts. It deliberately does not construct a second execution
 * runtime, and it fails if the published SDK later adds a seam so the product can be rewired.
 */
class LarkExecutionSeamProbeTest {
    @Test
    void hostStarterExposesNoPerUserEnvironmentOrLeaseSeam() {
        List<String> components = Arrays.stream(HostExecutionPlatforms.HostExecutionSpec.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertThat(components).containsExactly(
                "workspaceRoot", "dataRoot", "defaultTimeout", "maximumTimeout",
                "maximumOutputBytes", "maximumOutputLines");
        assertThat(components)
                .as("a per-user CLI env/lease seam would appear as a HostExecutionSpec component")
                .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("env"))
                .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("lease"))
                .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("resolver"));

        List<String> factoryParameters = Arrays.stream(HostExecutionPlatforms.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("hostGuarded"))
                .map(Method::getParameterTypes)
                .flatMap(types -> Arrays.stream(types).map(Class::getSimpleName))
                .toList();
        assertThat(factoryParameters)
                .containsExactly("HostExecutionSpec", "TenantRef", "PrincipalRef")
                .doesNotContain("EnvironmentLeaseResolver", "ExecutionEnvironmentRef");
    }

    @Test
    void brokerSeamExistsButOnlyOpaqueLeaseRefsReachIt() {
        assertThat(EnvironmentLeaseResolver.class.isInterface()).isTrue();
        assertThat(EnvironmentLeaseResolver.class.getDeclaredMethods())
                .extracting(Method::getName)
                .containsExactly("resolve");
        assertThat(Arrays.stream(ExecutionToolConfiguration.class.getRecordComponents())
                .map(RecordComponent::getName))
                .contains("environmentRef", "sandboxProfileRef");
        assertThat(ExecutionEnvironmentRef.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("leaseRefs");
        assertThat(ResolvedExecutionEnvironment.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("values", "sensitiveNames");
    }

    @Test
    void credentialBrokerCarriesNoPrincipalOrRunContext() {
        assertThat(Arrays.stream(CredentialBroker.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("getSecret"))
                .map(Method::getParameterTypes)
                .flatMap(types -> Arrays.stream(types).map(Class::getSimpleName)))
                .containsExactly("String");
        assertThat(CredentialBroker.class.getDeclaredMethods())
                .extracting(Method::getName)
                .doesNotContain("resolveForPrincipal", "resolveForRun", "environment", "environmentFor");
    }

    @Test
    void productCanComputeThePerUserEnvironmentItCannotAttach() {
        Map<String, String> alice = Map.of(
                "LARKSUITE_CLI_CONFIG_DIR", "D:/data/integrations/lark/users/alice/config",
                "LARKSUITE_CLI_DATA_DIR", "D:/data/integrations/lark/users/alice/data");
        Map<String, String> bob = Map.of(
                "LARKSUITE_CLI_CONFIG_DIR", "D:/data/integrations/lark/users/bob/config",
                "LARKSUITE_CLI_DATA_DIR", "D:/data/integrations/lark/users/bob/data");
        assertThat(alice.get("LARKSUITE_CLI_CONFIG_DIR"))
                .isNotEqualTo(bob.get("LARKSUITE_CLI_CONFIG_DIR"));
        assertThat(ExecutionEnvironmentRef.empty().leaseRefs()).isEmpty();
    }
}
