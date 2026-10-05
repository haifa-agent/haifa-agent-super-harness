package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.javalin.Javalin;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class JavalinSseSemanticTest {

    @Test
    void javalinHttpExchangePreservesSseStreamingSemantics() throws Exception {
        CountDownLatch firstFrameFlushed = new CountDownLatch(1);
        CountDownLatch clientReadFirstFrame = new CountDownLatch(1);

        Javalin app = Javalin.create(config -> {
            config.showJavalinBanner = false;
        });

        app.post("/test-sse", ctx -> {
            try (JavalinHttpExchange exchange = new JavalinHttpExchange(ctx)) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                exchange.getResponseHeaders().set("Connection", "keep-alive");
                exchange.getResponseHeaders().set("X-Accel-Buffering", "no");

                // In JDK HttpServer semantics, responseLength == 0 specifies chunked transfer encoding
                exchange.sendResponseHeaders(200, 0);

                try (OutputStream out = exchange.getResponseBody()) {
                    // Frame 1
                    out.write("event: metadata\ndata: {\"run_id\":\"run-1\"}\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    firstFrameFlushed.countDown();

                    // Wait until client has observed frame 1 before sending frame 2,
                    // proving that chunked streaming flushes immediately rather than buffering until handler exit.
                    boolean read = clientReadFirstFrame.await(5, TimeUnit.SECONDS);
                    assertThat(read).isTrue();

                    // Frame 2
                    out.write("event: messages\ndata: {\"content\":\"hello\"}\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();

                    // Frame 3
                    out.write("event: end\ndata: null\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
        });

        app.start("127.0.0.1", 0);
        int port = app.port();

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/test-sse"))
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .header("Accept", "text/event-stream")
                    .build();

            Future<List<String>> readTask = executor.submit(() -> {
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.headers().firstValue("content-type"))
                        .hasValueSatisfying(ct -> assertThat(ct).contains("text/event-stream"));
                assertThat(response.headers().firstValue("cache-control"))
                        .hasValue("no-cache");
                // Chunked streaming response must NOT have a Content-Length header
                assertThat(response.headers().firstValue("content-length")).isEmpty();

                List<String> receivedLines = new ArrayList<>();
                try (InputStream in = response.body();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        receivedLines.add(line);
                        if ("event: metadata".equals(line)) {
                            // Notify the server handler that client observed the first frame
                            clientReadFirstFrame.countDown();
                        }
                    }
                }
                return receivedLines;
            });

            List<String> receivedLines;
            try {
                receivedLines = readTask.get(5, TimeUnit.SECONDS);
            } catch (Exception timeoutOrError) {
                readTask.cancel(true);
                throw timeoutOrError;
            }

            assertThat(receivedLines).contains(
                    "event: metadata",
                    "data: {\"run_id\":\"run-1\"}",
                    "event: messages",
                    "data: {\"content\":\"hello\"}",
                    "event: end",
                    "data: null"
            );
        } finally {
            executor.shutdownNow();
            app.stop();
        }
    }
}
