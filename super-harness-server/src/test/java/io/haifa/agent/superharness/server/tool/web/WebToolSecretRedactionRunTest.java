package io.haifa.agent.superharness.server.tool.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.superharness.server.api.DeerFlowBootstrapApi;
import io.haifa.agent.superharness.server.config.LocalServerConfiguration;
import io.haifa.agent.superharness.server.testsupport.LocalProbeModelTestSupport;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression for the W11 P1 finding that a provider can reflect request material back to the caller.
 *
 * <p>A loopback provider answers {@code 402} with a body that echoes the received
 * {@code Authorization} header. The reflected text must never reach the run stream or the persisted
 * thread state: {@link ToolSecretRedactor} removes the whole {@code Bearer <token>} sequence at the
 * tool boundary, before the text can become a tool result.
 *
 * <p>The canary is generated locally. This test needs no real provider credential and therefore runs
 * in the normal gate instead of being skipped when {@code DEEPSEEK_TOOL_API_KEY} is absent.
 *
 * <p>Scope note: SDK 0.1.3 exposes no public seam to declare a credential requirement on a
 * {@link JavaTool}, so the SDK still cannot inject {@code DEEPSEEK_TOOL_API_KEY} into
 * {@link JavaToolContext#credentials()} for this tool and cannot register it with its own secret
 * redaction. The tool therefore resolves the key from {@link WebToolConfig}, and the redaction above
 * is what enforces the no-leak guarantee. Do not bridge this locally by hand-assembling a
 * {@code ToolRegistration}: a hand-built definition is not identity-equal to the SDK's own Java tool
 * assembly and breaks the frozen tool catalog on resume.
 */
class WebToolSecretRedactionRunTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static HttpResponse<String> request(URI base, String method, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(20));
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void reflectedAuthorizationHeaderNeverReachesTheStreamOrThreadState(@TempDir Path dataDir) throws Exception {
        String canary = "sk-synthetic-canary-" + UUID.randomUUID();
        AtomicBoolean providerReceivedCanary = new AtomicBoolean();
        AtomicInteger toolCalls = new AtomicInteger();

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
            environment.put("DEEPSEEK_TOOL_API_KEY", canary);

            ProductionWebFetchTool production = new ProductionWebFetchTool(WebToolConfig.fromEnvironment(environment));
            JavaTool<ProductionWebFetchTool.Input, ProductionWebFetchTool.Output> observed = new JavaTool<>() {
                @Override
                public JavaToolSpec<ProductionWebFetchTool.Input, ProductionWebFetchTool.Output> spec() {
                    return production.spec();
                }

                @Override
                public ProductionWebFetchTool.Output invoke(
                        ProductionWebFetchTool.Input input, JavaToolContext context) {
                    toolCalls.incrementAndGet();
                    return production.invoke(input, context);
                }

                @Override
                public String summarize(ProductionWebFetchTool.Output output) {
                    return production.summarize(output);
                }
            };

            var model = new WebToolApprovalRunTest.ToolCallingModel("web_fetch", Map.of("url", "https://example.com"));
            var binding = LocalProbeModelTestSupport.binding(model);

            try (var api = DeerFlowBootstrapApi.createForInteractionTests(
                    new LocalServerConfiguration("local", InetAddress.getLoopbackAddress(), 0),
                    dataDir,
                    new SecretKeySpec(new byte[32], "AES"),
                    ignored -> binding,
                    List.of(observed))) {
                api.start();
                URI base = URI.create("http://127.0.0.1:" + api.address().getPort());

                HttpResponse<String> createThread =
                        request(base, "POST", "/api/threads", "{\"thread_id\":\"secret-redaction-test\"}");
                assertThat(createThread.statusCode()).isEqualTo(200);

                String body = "{\"assistant_id\":\"lead_agent\",\"input\":{\"messages\":[{\"id\":\"h\","
                        + "\"type\":\"human\",\"content\":\"run\"}]},\"stream_mode\":[\"messages-tuple\","
                        + "\"updates\",\"custom\"],\"on_disconnect\":\"continue\",\"multitask_strategy\":\"reject\"}";
                var stream = request(base, "POST", "/api/threads/secret-redaction-test/runs/stream", body);
                var state = request(base, "GET", "/api/threads/secret-redaction-test/state", null);

                if (toolCalls.get() == 0) {
                    var tree = JSON.readTree(state.body());
                    String requestId = "";
                    for (var message : tree.path("values").path("messages")) {
                        var input = message.path("artifact").path("human_input");
                        if (input.path("source").asText().equals("haifa_interaction")) {
                            requestId = input.path("request_id").asText();
                        }
                    }
                    assertThat(requestId).isNotEmpty();

                    var reply = JSON.createObjectNode();
                    reply.put("assistant_id", "lead_agent");
                    reply.put("on_disconnect", "continue");
                    reply.putArray("stream_mode").add("messages-tuple").add("updates").add("custom");
                    var human = reply.putObject("input").putArray("messages").addObject();
                    human.put("id", "h-approve");
                    human.put("type", "human");
                    human.put("content", "Approve the synthetic loopback fixture");
                    var additional = human.putObject("additional_kwargs");
                    additional.put("hide_from_ui", true);
                    additional.putObject("human_input_response").put("version", 1).put("kind", "human_input_response")
                            .put("source", "haifa_interaction").put("request_id", requestId).put("response_kind", "option")
                            .put("option_id", "approve").put("value", "approve");

                    stream = request(base, "POST", "/api/threads/secret-redaction-test/runs/stream", reply.toString());
                    state = request(base, "GET", "/api/threads/secret-redaction-test/state", null);
                }

                assertThat(stream.statusCode()).isEqualTo(200);
                assertThat(state.statusCode()).isEqualTo(200);
                assertThat(toolCalls.get()).as("approved web_fetch must run exactly once").isEqualTo(1);
                assertThat(providerReceivedCanary.get())
                        .as("the loopback provider must have received the configured key")
                        .isTrue();

                // Core security assertion: the reflected secret must not survive into either surface.
                assertThat(stream.body()).as("canary must never leak into the run stream").doesNotContain(canary);
                assertThat(state.body()).as("canary must never leak into thread state").doesNotContain(canary);
                assertThat(stream.body()).doesNotContain("Bearer ");
                assertThat(state.body()).doesNotContain("Bearer ");
                assertThat(state.body())
                        .as("the redaction marker proves the provider text was rewritten, not simply dropped")
                        .contains(ToolSecretRedactor.REDACTED);
            }
        } finally {
            provider.stop(0);
        }
    }
}
