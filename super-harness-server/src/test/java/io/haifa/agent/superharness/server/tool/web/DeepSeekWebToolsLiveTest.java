package io.haifa.agent.superharness.server.tool.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.sdk.tool.JavaToolContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Live validation of DeepSeek WebSearch and WebFetch tool providers against the official
 * DeepSeek API endpoints using the real {@code DEEPSEEK_TOOL_API_KEY} environment variable.
 */
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_TOOL_API_KEY", matches = ".+")
public class DeepSeekWebToolsLiveTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static String apiKey;
    private static WebToolConfig config;

    @BeforeAll
    static void setUp() {
        apiKey = System.getenv("DEEPSEEK_TOOL_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getProperty("DEEPSEEK_TOOL_API_KEY");
        }
        assertThat(apiKey).isNotBlank();

        Map<String, String> env = Map.of(
                "HAIFA_SUPER_HARNESS_WEB_SEARCH_PROVIDER", "deepseek",
                "HAIFA_SUPER_HARNESS_WEB_FETCH_PROVIDER", "deepseek",
                "DEEPSEEK_TOOL_API_KEY", apiKey);
        config = WebToolConfig.fromEnvironment(env);
    }

    private JavaToolContext mockContext(String key) {
        return new JavaToolContext(
                new AgentRunId("live-test-run"),
                Optional.empty(),
                new ToolCallId("live-call-1"),
                new TenantRef("test-tenant"),
                new PrincipalRef("default-public-user", "user"),
                Instant.now().plusSeconds(30),
                Optional.empty(),
                () -> false,
                Map.of(WebToolConfig.DEEPSEEK_TOOL_API_KEY_CREDENTIAL, key));
    }

    @Test
    void liveSearchDirectDeepSeekClient() throws Exception {
        DeepSeekWebClient client = new DeepSeekWebClient(config);
        List<DeepSeekWebClient.SearchResultItem> results = client.search(
                "haifa agent super harness", 3, Optional.empty(), apiKey);

        assertThat(results).isNotEmpty();
        System.out.println("LIVE_SEARCH_RESULTS_COUNT=" + results.size());
        for (int i = 0; i < results.size(); i++) {
            var item = results.get(i);
            assertThat(item.title()).isNotBlank();
            assertThat(item.url()).isNotBlank();
            assertThat(item.url()).startsWith("http");
            System.out.println("  [" + (i + 1) + "] " + item.title() + " -> " + item.url());
        }
    }

    @Test
    void liveFetchDirectDeepSeekClient() throws Exception {
        DeepSeekWebClient client = new DeepSeekWebClient(config);
        String content = client.fetch("https://example.com", apiKey);

        assertThat(content).isNotBlank();
        assertThat(content.toLowerCase()).contains("example");
        System.out.println("LIVE_FETCH_CONTENT_LENGTH=" + content.length());
        System.out.println("LIVE_FETCH_CONTENT_PREVIEW=" + content.substring(0, Math.min(120, content.length())));
    }

    @Test
    void liveWebSearchToolInvocation() throws Exception {
        ProductionWebSearchTool tool = new ProductionWebSearchTool(config);
        JavaToolContext context = mockContext(apiKey);

        ProductionWebSearchTool.Output output = tool.invoke(
                new ProductionWebSearchTool.Input("haifa agent", Optional.of(3), Optional.empty()),
                context);

        assertThat(output).isNotNull();
        assertThat(output.result()).isNotBlank();
        assertThat(output.result()).doesNotContain(apiKey);

        JsonNode root = JSON.readTree(output.result());
        assertThat(root.path("total_results").asInt()).isGreaterThan(0);
        assertThat(root.path("results").isArray()).isTrue();
        assertThat(root.path("results").size()).isGreaterThan(0);

        JsonNode first = root.path("results").get(0);
        assertThat(first.path("title").asText()).isNotBlank();
        assertThat(first.path("url").asText()).startsWith("http");
        System.out.println("LIVE_TOOL_SEARCH_PASS total_results=" + root.path("total_results").asInt());
    }

    @Test
    void liveWebFetchToolInvocation() {
        ProductionWebFetchTool tool = new ProductionWebFetchTool(config);
        JavaToolContext context = mockContext(apiKey);

        ProductionWebFetchTool.Output output = tool.invoke(
                new ProductionWebFetchTool.Input("https://example.com"),
                context);

        assertThat(output).isNotNull();
        assertThat(output.error()).isEmpty();
        assertThat(output.content()).isNotBlank();
        assertThat(output.content().length()).isLessThanOrEqualTo(4096);
        assertThat(output.content()).doesNotContain(apiKey);
        assertThat(output.content().toLowerCase()).contains("example");
        System.out.println("LIVE_TOOL_FETCH_PASS content_length=" + output.content().length());
    }
}
