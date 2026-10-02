package io.haifa.agent.superharness.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.jna.Memory;
import com.sun.jna.platform.win32.IPHlpAPI;
import com.sun.jna.ptr.IntByReference;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.crypto.spec.SecretKeySpec;

/** Production Gateway child with a bounded, exact-process and exact-port lifecycle. */
final class GatewayProcessFixture implements AutoCloseable {
    private static final IPHlpAPI NATIVE_TCP = nativeTcpApi();
    final ObjectMapper json = new ObjectMapper();
    HttpClient http = newClient();
    final Path evidence;
    final Path data;
    URI base;
    Process gateway;
    final List<Long> pids = new ArrayList<>();
    final List<Integer> ports = new ArrayList<>();
    private final List<Process> ownedProcesses = new ArrayList<>();
    private int sequence;

    GatewayProcessFixture(String scenario) throws Exception {
        Path external = Path.of(System.getProperty("super.harness.soul.restart.evidence.dir",
                System.getenv().getOrDefault("ENG_ACCEPTANCE_EVIDENCE_ROOT", "../local-tmp/acceptance-fixtures/evidence")));
        evidence = Files.createDirectories(external.toAbsolutePath().normalize().resolve(scenario + "-" + UUID.randomUUID()));
        data = Files.createDirectories(evidence.resolve("data"));
        if (NATIVE_TCP != null) {
            long started = System.nanoTime();
            try {
                nativeTcpRows(0);
                proof("native-tcp-preflight", Map.of("available", true,
                        "elapsed_us", (System.nanoTime() - started) / 1_000));
            } catch (Exception | LinkageError failure) {
                proof("native-tcp-preflight", Map.of("available", false, "error_class", failure.getClass().getName()));
            }
        }
    }

    void start() throws Exception {
        require(gateway == null, "Previous production Gateway must stop before restart");
        int configuredPort = Integer.parseInt(System.getenv().getOrDefault("ENG_ACCEPTANCE_GATEWAY_PORT", "0"));
        try (var probe = new java.net.ServerSocket()) {
            probe.bind(new InetSocketAddress("127.0.0.1", configuredPort));
            proof("port-before-start-" + (sequence + 1), Map.of("configured_port", configuredPort, "bind", "PASS"));
        }
        ClientClosure priorClient = closeClient(System.nanoTime() + Duration.ofSeconds(5).toNanos());
        proof("client-before-start-" + (sequence + 1), priorClient);
        require(priorClient.terminated(), "Previous fixture HTTP client did not terminate before restart");
        http = newClient();
        Path log = evidence.resolve("gateway-" + (++sequence) + ".raw.log");
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path argumentFile = evidence.resolve("gateway-" + sequence + ".args.txt");
        Files.write(argumentFile, List.of("-Xmx128m", "-XX:+UseSerialGC", "-cp",
                "\"" + cp.replace('\\', '/').replace("\"", "\\\"") + "\"", SuperHarnessServer.class.getName()), StandardCharsets.UTF_8);
        ProcessBuilder builder = new ProcessBuilder(java, "@" + argumentFile).redirectErrorStream(true).redirectOutput(log.toFile());
        Map<String, String> env = builder.environment();
        env.put("SUPER_HARNESS_PROFILE", "local");
        env.put("DEER_FLOW_AUTH_DISABLED", "1");
        env.put("SUPER_HARNESS_BIND_ADDRESS", "127.0.0.1");
        env.put("SUPER_HARNESS_PORT", Integer.toString(configuredPort));
        env.put("SUPER_HARNESS_DATA_DIR", data.toString());
        env.put("HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF", "env://M3_PROCESS_KEY");
        env.put("M3_PROCESS_KEY", Base64.getEncoder().encodeToString(new SecretKeySpec(new byte[32], "AES").getEncoded()));
        gateway = builder.start();
        ownedProcesses.add(gateway);
        pids.add(gateway.pid());
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        var banner = Pattern.compile("Super Harness local API listening on http://127\\.0\\.0\\.1:(\\d+)");
        while (System.nanoTime() < deadline) {
            require(gateway.isAlive(), "Production Gateway exited before readiness; inspect original log");
            if (Files.exists(log)) {
                var match = banner.matcher(Files.readString(log));
                if (match.find()) {
                    int port = Integer.parseInt(match.group(1));
                    base = URI.create("http://127.0.0.1:" + port);
                    ports.add(port);
                    require(send("GET", "/health", null).statusCode() == 200, "Actual Gateway health");
                    require(System.nanoTime() <= deadline, "Gateway readiness deadline exceeded");
                    return;
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Production Gateway did not become ready within 10 seconds; inspect original log");
    }

    HttpResponse<String> send(String method, String route, String body) throws Exception {
        return send(method, route, body, null);
    }

    HttpResponse<String> send(String method, String route, String body, String cursor) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(route)).timeout(Duration.ofSeconds(10));
        if (cursor != null) request.header("Last-Event-ID", cursor);
        request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        // A SOUL tool_call legitimately contains synthetic soul arguments. This is a derived
        // digest of the actual response, deliberately not described as a raw SSE/JSON original.
        proof("http-" + (++sequence), Map.of("method", method, "route", route, "status", response.statusCode(),
                "bytes", response.body().getBytes(StandardCharsets.UTF_8).length,
                "response_sha256", hash(response.body()), "evidence_kind", "derived-response-digest"));
        return response;
    }

    JsonNode get(String route) throws Exception {
        var response = send("GET", route, null);
        require(response.statusCode() == 200, "Expected actual HTTP 200");
        return json.readTree(response.body());
    }

    void proof(String name, Object values) throws Exception {
        Files.writeString(evidence.resolve(name + ".json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(values),
                StandardCharsets.UTF_8);
    }

    void configureModel(int providerPort) throws Exception {
        String body = json.writeValueAsString(Map.of("config", Map.of(
                "name", "m3-fixture", "display_name", "M3 fixture", "provider", "openai-compatible",
                "model", "m3-fixture-model", "base_url", "http://127.0.0.1:" + providerPort + "/v1",
                "enabled", true, "supports_vision", false, "context_window", 32768, "max_tokens", 1024,
                "api_key", "synthetic-local-fixture")));
        require(send("PUT", "/api/managed-models", body).statusCode() == 200, "Synthetic local model configuration");
    }

    String runBody(String agent, boolean bootstrap, String text) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("assistant_id", "lead_agent");
        body.putObject("input").putArray("messages").addObject().put("type", "human").put("content", text);
        body.putObject("context").put("model_name", "m3-fixture").put("agent_name", agent)
                .put("is_bootstrap", bootstrap).put("mode", "flash").put("subagent_enabled", false);
        body.set("stream_mode", json.valueToTree(List.of("messages-tuple", "updates", "custom")));
        body.put("on_disconnect", "continue").put("multitask_strategy", "reject");
        return json.writeValueAsString(body);
    }

    String approval(String requestId) { return SseEvents.PendingApproval.reply(requestId, "approve"); }

    JsonNode card(String sse) { return SseEvents.read(sse).card(); }

    JsonNode event(String sse, String name) { return SseEvents.read(sse).event(name); }

    static String location(HttpResponse<String> response) {
        require(response.statusCode() == 200, "Actual Run stream must be accepted");
        return response.headers().firstValue("content-location").orElseThrow();
    }

    void stop() throws Exception {
        long started = System.nanoTime();
        long deadline = started + Duration.ofSeconds(40).toNanos();
        Process current = gateway;
        gateway = null;
        long forcedDeadline = 0;
        try {
            ClientClosure clientClosure = closeClient(deadline);
            proof("client-stop-" + sequence + "-" + (current == null ? "no-process" : current.pid()), clientClosure);
            if (current == null) {
                require(clientClosure.terminated(), "Fixture HTTP client exceeded its five-second termination bound");
                return;
            }
            long processStarted = System.nanoTime();
            current.destroy();
            boolean exited = current.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            proof("process-stop-" + sequence, Map.of("gateway_pid", current.pid(), "exited", exited,
                    "process_elapsed_us", (System.nanoTime() - processStarted) / 1_000,
                    "total_stop_elapsed_us", (System.nanoTime() - started) / 1_000,
                    "client_terminated", clientClosure.terminated()));
            if (!exited) {
                current.destroyForcibly();
                forcedDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                require(current.waitFor(Math.max(0, forcedDeadline - System.nanoTime()), TimeUnit.NANOSECONDS),
                        "Exact Gateway cleanup failed");
                throw new AssertionError("Exact Gateway and client exceeded the original 40-second stop limit");
            }
            require(clientClosure.terminated(), "Fixture HTTP client exceeded its five-second termination bound");
            require(!current.isAlive(), "Exact production Gateway must stop");
            if (base != null) {
                awaitExactPortRelease(current, base.getPort(), started, deadline);
            }
        } finally {
            // Client termination or evidence I/O failure must never leave this owned child alive.
            if (current != null && current.isAlive()) {
                current.destroyForcibly();
                if (forcedDeadline == 0) forcedDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                require(current.waitFor(Math.max(0, forcedDeadline - System.nanoTime()), TimeUnit.NANOSECONDS),
                        "Exact owned Gateway final cleanup failed");
            }
        }
    }

    /** Observe actual same-port binding only within the original client/process stop deadline. */
    private void awaitExactPortRelease(Process stopped, int port, long stopStarted, long deadline) throws Exception {
        long releaseStarted = System.nanoTime();
        java.net.BindException firstError = null;
        long lastBindFailedAt = releaseStarted;
        int attempts = 0;
        while (System.nanoTime() < deadline) {
            attempts++;
            try (var probe = new java.net.ServerSocket()) {
                probe.bind(new InetSocketAddress("127.0.0.1", port));
                long boundAt = System.nanoTime();
                if (boundAt > deadline) break;
                proofPortRelease(stopped, port, attempts, firstError, stopStarted, releaseStarted, boundAt, true);
                return;
            } catch (java.net.BindException failure) {
                lastBindFailedAt = System.nanoTime();
                if (firstError == null) {
                    firstError = failure;
                    cleanupDiagnostic(stopped, port, lastBindFailedAt, "first", false);
                }
            }
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) TimeUnit.NANOSECONDS.sleep(Math.min(Duration.ofMillis(10).toNanos(), remaining));
        }
        long failedAt = System.nanoTime();
        proofPortRelease(stopped, port, attempts, firstError, stopStarted, releaseStarted, failedAt, false);
        // The acceptance deadline has already failed; this bounded evidence read cannot grant a late PASS.
        cleanupDiagnostic(stopped, port, lastBindFailedAt, "timeout", true);
        if (firstError != null) throw firstError;
        throw new java.net.BindException("Exact Gateway port was not released within the original stop deadline");
    }

    private void proofPortRelease(Process stopped, int port, int attempts, java.net.BindException firstError,
            long stopStarted, long releaseStarted, long observedAt, boolean released) throws Exception {
        proof("port-release-" + sequence, Map.of("gateway_pid", stopped.pid(), "gateway_pids", List.copyOf(pids),
                "port", port, "attempts", attempts, "first_bind_error", firstError == null ? "NONE" : firstError.getClass().getName(),
                "release_success", released, "process_alive", stopped.isAlive(), "stop_budget_ms", 40_000,
                "total_stop_elapsed_us", (observedAt - stopStarted) / 1_000,
                "release_wait_elapsed_us", (observedAt - releaseStarted) / 1_000));
    }

    private ClientClosure closeClient(long deadline) throws InterruptedException {
        HttpClient current = http;
        http = null;
        if (current == null) return new ClientClosure(false, true, 0);
        long started = System.nanoTime();
        current.shutdownNow();
        long remaining = Math.max(0, deadline - System.nanoTime());
        long allowance = Math.min(Duration.ofSeconds(5).toNanos(), remaining);
        boolean terminated = current.awaitTermination(Duration.ofNanos(allowance));
        return new ClientClosure(true, terminated && current.isTerminated(), (System.nanoTime() - started) / 1_000);
    }

    private record ClientClosure(boolean present, boolean terminated, long elapsedUs) {}

    private static HttpClient newClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** Only public REST projections of SDK codes; response bodies remain in memory. */
    void runDiagnostic(String location, String phase, Object providerResponses) throws Exception {
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("phase", phase);
        safe.put("provider_response_diagnostics", providerResponses);
        try {
            JsonNode run = get(location);
            JsonNode diagnostics = run.path("diagnostics");
            safe.put("run_id", run.path("run_id").asText());
            safe.put("status", safeCode(run.path("status")));
            safe.put("sdk_status", safeCode(diagnostics.path("sdk_status")));
            safe.put("error_code", safeCode(diagnostics.path("error_code")));
            safe.put("stop_reason", safeCode(diagnostics.path("stop_reason")));
            List<Map<String, Object>> models = new ArrayList<>();
            for (JsonNode model : diagnostics.path("model_calls")) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("status", safeCode(model.path("status")));
                item.put("finish_reason", safeCode(model.path("finish_reason")));
                item.put("reason_code", safeCode(model.path("reason_code")));
                models.add(item);
            }
            safe.put("model_calls", models);
            List<Map<String, Object>> interactions = new ArrayList<>();
            for (JsonNode interaction : diagnostics.path("interactions")) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("request_id", safeCode(interaction.path("request_id")));
                item.put("kind", safeCode(interaction.path("kind")));
                item.put("state", safeCode(interaction.path("state")));
                interactions.add(item);
            }
            safe.put("interactions", interactions);
        } catch (Exception failure) {
            safe.put("diagnostic_read_error_class", failure.getClass().getName());
        }
        proof("run-diagnostic-" + (++sequence), safe);
    }

    private static String safeCode(JsonNode value) {
        String text = value.isTextual() ? value.asText() : "";
        return text.matches("[A-Za-z0-9_.:-]{1,128}") ? text : null;
    }

    private void cleanupDiagnostic(Process stopped, int port, long bindFailedAt, String phase, boolean externalObserver) {
        try {
            // Capture before evidence I/O or starting the much slower external PowerShell observer.
            if (NATIVE_TCP != null) {
                long captureStarted = System.nanoTime();
                String captureTime = java.time.Instant.now().toString();
                try {
                    List<Map<String, Object>> rows = nativeTcpRows(port);
                    long captureFinished = System.nanoTime();
                    proof("cleanup-native-tcp-" + sequence + "-" + phase, Map.of("gateway_pid", stopped.pid(), "port", port,
                            "process_alive", stopped.isAlive(), "rows", rows,
                            "observed_at", captureTime,
                            "bind_to_capture_start_us", (captureStarted - bindFailedAt) / 1_000,
                            "native_capture_us", (captureFinished - captureStarted) / 1_000));
                } catch (Exception | LinkageError failure) {
                    proof("cleanup-native-tcp-" + sequence + "-" + phase, Map.of("gateway_pid", stopped.pid(), "port", port,
                            "available", false, "error_class", failure.getClass().getName()));
                }
            }
            proof("cleanup-bind-failure-" + sequence + "-" + phase, Map.of("gateway_pid", stopped.pid(),
                    "process_alive", stopped.isAlive(), "port", port,
                    "observed_at", java.time.Instant.now().toString(), "bind_error_class", "java.net.BindException"));
            if (!externalObserver || !System.getProperty("os.name").startsWith("Windows")) return;
            String command = "$ErrorActionPreference='Stop'; $items=@(Get-NetTCPConnection -LocalPort " + port
                    + " -ErrorAction SilentlyContinue | ForEach-Object { [ordered]@{local_address=$_.LocalAddress;"
                    + "local_port=$_.LocalPort;remote_port=$_.RemotePort;state=$_.State.ToString();owning_pid=$_.OwningProcess} });"
                    + "[ordered]@{observed_at=[DateTimeOffset]::Now.ToString('o');gateway_pid=" + stopped.pid()
                    + ";pid_exists=[bool](Get-Process -Id " + stopped.pid()
                    + " -ErrorAction SilentlyContinue);connections=$items} | ConvertTo-Json -Depth 5";
            Process observer = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command)
                    .redirectOutput(evidence.resolve("cleanup-tcp-" + sequence + "-" + phase + ".raw.json").toFile())
                    .redirectError(evidence.resolve("cleanup-tcp-" + sequence + "-" + phase + ".stderr.raw.log").toFile()).start();
            boolean completed = observer.waitFor(3, TimeUnit.SECONDS);
            if (!completed) observer.destroyForcibly();
            proof("cleanup-observer-" + sequence + "-" + phase, Map.of("completed", completed,
                    "exit_code", completed ? observer.exitValue() : -1));
        } catch (Exception diagnosticFailure) {
            System.err.println("M3_CLEANUP_DIAGNOSTIC_FAILED error_class=" + diagnosticFailure.getClass().getName());
        }
    }

    private static IPHlpAPI nativeTcpApi() {
        if (!System.getProperty("os.name").startsWith("Windows")) return null;
        try { return IPHlpAPI.INSTANCE; }
        catch (LinkageError failure) {
            System.err.println("M3_NATIVE_TCP_UNAVAILABLE error_class=" + failure.getClass().getName());
            return null;
        }
    }

    private static List<Map<String, Object>> nativeTcpRows(int port) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int family : new int[] {2, 23}) {
            IntByReference size = new IntByReference();
            int sizing = NATIVE_TCP.GetExtendedTcpTable(null, size, false, family, 5, 0);
            if (sizing != 122 && sizing != 0) throw new IllegalStateException("Native TCP table sizing failed");
            try (Memory buffer = new Memory(size.getValue())) {
                if (NATIVE_TCP.GetExtendedTcpTable(buffer, size, false, family, 5, 0) != 0) {
                    throw new IllegalStateException("Native TCP table read failed");
                }
                if (family == 2) {
                    var table = new IPHlpAPI.MIB_TCPTABLE_OWNER_PID(buffer);
                    for (var row : table.table) addTcpRow(rows, port, family, row.dwLocalPort, row.dwRemotePort,
                            row.dwState, row.dwOwningPid);
                } else {
                    var table = new IPHlpAPI.MIB_TCP6TABLE_OWNER_PID(buffer);
                    for (var row : table.table) addTcpRow(rows, port, family, row.dwLocalPort, row.dwRemotePort,
                            row.State, row.dwOwningPid);
                }
            }
        }
        return rows;
    }

    private static void addTcpRow(List<Map<String, Object>> rows, int targetPort, int family,
            int encodedLocalPort, int encodedRemotePort, int state, int pid) {
        int localPort = Short.toUnsignedInt(Short.reverseBytes((short) encodedLocalPort));
        int remotePort = Short.toUnsignedInt(Short.reverseBytes((short) encodedRemotePort));
        if (targetPort == 0 || (localPort != targetPort && remotePort != targetPort)) return;
        String stateName = switch (state) {
            case 1 -> "CLOSED"; case 2 -> "LISTEN"; case 3 -> "SYN_SENT"; case 4 -> "SYN_RCVD";
            case 5 -> "ESTABLISHED"; case 6 -> "FIN_WAIT1"; case 7 -> "FIN_WAIT2"; case 8 -> "CLOSE_WAIT";
            case 9 -> "CLOSING"; case 10 -> "LAST_ACK"; case 11 -> "TIME_WAIT"; case 12 -> "DELETE_TCB";
            default -> "UNKNOWN";
        };
        rows.add(Map.of("family", family, "local_port", localPort, "remote_port", remotePort,
                "state", stateName, "state_code", state, "pid", Integer.toUnsignedLong(pid)));
    }

    static String hash(String text) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    static void require(boolean value, String safe) { if (!value) throw new AssertionError(safe); }

    @Override public void close() throws Exception {
        try { stop(); }
        finally { proof("lifecycle", Map.of("gateway_pids", pids, "ports", ports,
                "processes_stopped", ownedProcesses.stream().noneMatch(Process::isAlive))); }
    }
}
