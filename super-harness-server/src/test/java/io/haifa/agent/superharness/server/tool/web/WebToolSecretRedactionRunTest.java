package io.haifa.agent.superharness.server.tool.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.sdk.tool.JavaToolContext;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Regression for the W11 P1 review finding that a provider can reflect request material back to the
 * caller.
 *
 * <p>The tool now declares {@code DEEPSEEK_TOOL_API_KEY} through
 * {@code JavaToolSpec.Builder.credentialRequirements(...)}, so the SDK resolves the value through the
 * Credential broker, injects it into {@link JavaToolContext#credentials()} and registers it for scoped
 * redaction. The transport-level guarantee is still enforced at the tool boundary: a loopback provider
 * answers {@code 402} with a body that echoes the received {@code Authorization} header, and the
 * resulting tool result must not carry either the secret or the header text.
 *
 * <p>Both cases are hermetic: the canary is generated locally, the provider is a loopback server, and
 * no real credential or model is involved.
 */
class WebToolSecretRedactionRunTest {

    @Test
    void declaresTheProviderCredentialThroughTheSdkSeam() {
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put("HAIFA_SUPER_HARNESS_WEB_FETCH_PROVIDER", "deepseek");
        environment.put("HAIFA_SUPER_HARNESS_WEB_SEARCH_PROVIDER", "deepseek");
        environment.remove(ProductionWebFetchTool.JINA_API_KEY_CREDENTIAL);
        environment.remove(ProductionWebFetchTool.DEEPSEEK_TOOL_API_KEY_CREDENTIAL);

        WebToolConfig config = WebToolConfig.fromEnvironment(environment);

        assertThat(new ProductionWebFetchTool(config).spec().credentialRequirements())
                .as("web_fetch must declare the provider credential so the SDK resolves and redacts it")
                .extracting(requirement -> requirement.credentialId())
                .containsExactly(ProductionWebFetchTool.DEEPSEEK_TOOL_API_KEY_CREDENTIAL);
        assertThat(new ProductionWebSearchTool(config).spec().credentialRequirements())
                .as("web_search must declare the provider credential so the SDK resolves and redacts it")
                .extracting(requirement -> requirement.credentialId())
                .containsExactly(ProductionWebFetchTool.DEEPSEEK_TOOL_API_KEY_CREDENTIAL);
    }

    @Test
    void reflectedAuthorizationHeaderIsRedactedAtTheToolBoundary() throws Exception {
        String canary = "sk-synthetic-canary-" + UUID.randomUUID();
        AtomicBoolean providerReceivedCanary = new AtomicBoolean();

        HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/fetch", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            providerReceivedCanary.set(("Bearer " + canary).equals(authorization));
            byte[] bytes = ("{\"error\":\"synthetic reflected authorization: " + authorization + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(402, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        provider.start();

        try {
            Map<String, String> environment = new HashMap<>(System.getenv());
            environment.put("HAIFA_SUPER_HARNESS_WEB_FETCH_PROVIDER", "deepseek");
            environment.put(
                    "HAIFA_SUPER_HARNESS_DEEPSEEK_FETCH_URL",
                    "http://127.0.0.1:" + provider.getAddress().getPort() + "/fetch");
            environment.put(ProductionWebFetchTool.DEEPSEEK_TOOL_API_KEY_CREDENTIAL, canary);

            ProductionWebFetchTool tool = new ProductionWebFetchTool(WebToolConfig.fromEnvironment(environment));
            ProductionWebFetchTool.Output output = tool.invoke(
                    new ProductionWebFetchTool.Input("https://example.com"),
                    context(Map.of(ProductionWebFetchTool.DEEPSEEK_TOOL_API_KEY_CREDENTIAL, canary)));

            assertThat(providerReceivedCanary.get())
                    .as("the tool must send the injected credential to the provider")
                    .isTrue();
            assertThat(output.error()).startsWith("Error: DeepSeek API returned status 402");
            assertThat(output.error()).doesNotContain(canary);
            assertThat(output.error()).doesNotContain("Bearer");
            assertThat(output.error())
                    .as("the redaction marker proves the provider text was rewritten, not simply dropped")
                    .contains(ToolSecretRedactor.REDACTED);
        } finally {
            provider.stop(0);
        }
    }

    private static JavaToolContext context(Map<String, String> credentials) {
        return new JavaToolContext(
                new AgentRunId("redaction-run"),
                Optional.empty(),
                new ToolCallId("redaction-call"),
                new TenantRef("test-tenant"),
                new PrincipalRef(ProductionWebFetchTool.SDK_LOCAL_PRINCIPAL, "user"),
                Instant.now().plusSeconds(30),
                Optional.empty(),
                () -> false,
                credentials);
    }

}
