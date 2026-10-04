package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.superharness.server.config.SubagentCatalogStore;
import io.haifa.agent.superharness.server.testsupport.GatewayFixture;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** W08 catalog oracles exercise registered production routes and real file publication. */
class DeerFlowSubagentCatalogApiTest {
    private final ObjectMapper json = GatewayFixture.JSON;
    private Path data;
    private Path evidence;
    private GatewayFixture gateway;
    private GatewayFixture.HttpEvidence http;
    private int sequence;

    @BeforeEach
    void start(org.junit.jupiter.api.TestInfo testInfo) throws Exception {
        data = GatewayFixture.dataDirectory();
        evidence = GatewayFixture.evidenceDirectory();
        http = new GatewayFixture.HttpEvidence(evidence, Duration.ofSeconds(10));
        http.save("case-binding.json", Map.of("case", testInfo.getTestMethod().orElseThrow().getName(),
                "dataRoot", data.toAbsolutePath().toString(), "evidence", evidence.toAbsolutePath().toString()));
        gateway = production();
    }

    @AfterEach
    void stop() throws Exception {
        try { if (gateway != null) gateway.close(); } finally { if (http != null) http.close(); }
    }

    private GatewayFixture production() throws Exception {
        return GatewayFixture.production(data, Integer.getInteger("w08.catalog.port", 30618),
                Map.of("HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF", "env://W08_CATALOG_TEST_KEY",
                        "W08_CATALOG_TEST_KEY", Base64.getEncoder().encodeToString(new byte[32])), evidence);
    }

    @Test
    void oracle1EmptyCatalogContainsFrozenGeneralPurpose() throws Exception {
        JsonNode list = body(request("GET", "", null)).path("subagents");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("name").asText()).isEqualTo("general-purpose");
        assertThat(list.get(0).path("source").asText()).isEqualTo("builtin");
        assertThat(list.get(0).path("editable").asBoolean()).isFalse();
        assertThat(list.get(0).path("max_turns").asInt()).isEqualTo(150);
        assertThat(list.get(0).path("timeout_seconds").asInt()).isEqualTo(900);
        assertThat(list.get(0).path("config_overrides")).isEmpty();
        assertThat(Files.exists(directory())).isFalse();
    }

    @Test
    void oracle2CreateNormalizesAndPersistsDefinition() throws Exception {
        ObjectNode input = definition("My-Worker");
        input.put("description", "  worker description  ").put("system_prompt", "  worker instructions  ");
        input.putArray("tools").add(" read_file ").add("read_file");
        input.putNull("disallowed_tools");
        input.put("unexpected", "ignored");
        HttpResponse<String> response = request("POST", "", input);
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode saved = body(response);
        assertThat(saved.path("name").asText()).isEqualTo("my-worker");
        assertThat(saved.path("source").asText()).isEqualTo("managed");
        assertThat(saved.path("editable").asBoolean()).isTrue();
        assertThat(saved.path("tools")).hasSize(1);
        assertThat(strings(saved.path("disallowed_tools"))).containsExactly("ask_clarification", "present_files", "task");
        assertThat(saved.path("description").asText()).isEqualTo("worker description");
        assertThat(saved.has("unexpected")).isFalse();
        assertThat(directory().resolve("my-worker.json")).exists();
        assertThat(json.readTree(Files.readString(directory().resolve("my-worker.json"))).path("system_prompt").asText())
                .isEqualTo("worker instructions");
    }

    @Test
    void oracle3DuplicateCreateIsConflictWithoutReplacement() throws Exception {
        assertThat(request("POST", "", definition("worker")).statusCode()).isEqualTo(201);
        byte[] before = Files.readAllBytes(directory().resolve("worker.json"));
        HttpResponse<String> duplicate = request("POST", "", definition("WORKER").put("description", "replacement"));
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(body(duplicate).path("detail").asText()).isEqualTo("Managed subagent 'worker' already exists");
        assertThat(Files.readAllBytes(directory().resolve("worker.json"))).isEqualTo(before);
    }

    @Test
    void oracle4BuiltinNameIsReserved() throws Exception {
        HttpResponse<String> response = request("POST", "", definition("GENERAL-PURPOSE"));
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(body(response).path("detail").asText()).isEqualTo(
                "Subagent name 'general-purpose' is reserved by a built-in or config.yaml definition.");
    }

    @Test
    void oracle5InvalidFieldsHaveStructuredLocations() throws Exception {
        for (String field : List.of("name", "description", "system_prompt", "max_turns")) {
            ObjectNode invalid = definition("worker");
            if (field.equals("name")) invalid.put(field, "bad_name");
            else if (field.equals("max_turns")) invalid.put(field, 0);
            else invalid.put(field, "  ");
            HttpResponse<String> response = request("POST", "", invalid);
            assertThat(response.statusCode()).isEqualTo(422);
            assertThat(body(response).path("detail").get(0).path("loc").get(1).asText()).isEqualTo(field);
        }
    }

    @Test
    void oracle6UnknownModelUsesFrozenStringDetail() throws Exception {
        HttpResponse<String> response = request("POST", "", definition("worker").put("model", "missing"));
        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(body(response).path("detail").asText()).isEqualTo(
                "Unknown model 'missing'. Use 'inherit' or a configured model name.");
    }

    @Test
    void oracle7PartialUpdateKeepsFieldsAndListsDisabled() throws Exception {
        assertThat(body(request("GET", "", null)).path("subagents")).hasSize(1);
        JsonNode saved = body(request("POST", "", definition("worker")));
        HttpResponse<String> response = request("PUT", "/worker", json.createObjectNode().put("enabled", false));
        assertThat(response.statusCode()).isEqualTo(200);
        ObjectNode expected = saved.deepCopy();
        expected.put("enabled", false);
        assertThat(body(response)).isEqualTo(expected);
        assertThat(item("worker").path("enabled").asBoolean()).isFalse();
        assertThat(request("DELETE", "/worker", null).statusCode()).isEqualTo(204);
        assertThat(body(request("GET", "", null)).path("subagents")).hasSize(1);
        http.save("crud-smoke-result.json", Map.of("scope", "list-create-patch-list-delete-list",
                "status", "PASS", "modelCalls", 0));
    }

    @Test
    void oracle8MissingAndBuiltinTargetsAre404() throws Exception {
        for (String name : List.of("general-purpose", "missing")) {
            for (String method : List.of("PUT", "DELETE")) {
                HttpResponse<String> response = request(method, "/" + name, method.equals("PUT") ? json.createObjectNode() : null);
                assertThat(response.statusCode()).isEqualTo(404);
                assertThat(body(response).path("detail").asText()).isEqualTo("Managed subagent '" + name + "' not found");
            }
        }
    }

    @Test
    void oracle9DeleteHasNoBodyAndCannotRepeat() throws Exception {
        request("POST", "", definition("worker"));
        HttpResponse<String> response = request("DELETE", "/worker", null);
        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        assertThat(request("DELETE", "/worker", null).statusCode()).isEqualTo(404);
        assertThat(directory().resolve("worker.json")).doesNotExist();
    }

    @Test
    void oracle10RestartReadsSameDataRoot() throws Exception {
        JsonNode saved = body(request("POST", "", definition("worker")));
        gateway.close();
        gateway = null;
        gateway = production();
        assertThat(item("worker")).isEqualTo(saved);
    }

    @Test
    void oracle11CorruptEntryDoesNotHideValidEntries() throws Exception {
        JsonNode saved = body(request("POST", "", definition("worker")));
        Files.writeString(directory().resolve("corrupt.json"), "{broken JSON");
        Files.writeString(directory().resolve("invalid.json"), "{\"name\":\"invalid\"}");
        assertThat(body(request("GET", "", null)).path("subagents")).hasSize(2);
        assertThat(item("worker")).isEqualTo(saved);
        gateway.close();
        gateway = null;
        gateway = production();
        assertThat(item("worker")).isEqualTo(saved);
    }

    @Test
    void oracle12RoleParameterizedHandlerRedactsAndRejectsWrites() throws Exception {
        request("POST", "", definition("worker"));
        var store = new SubagentCatalogStore(json, data);
        assertThat(store.catalog(false)).allSatisfy(item -> assertThat(item.path("system_prompt").isNull()).isTrue());
        assertThat(store.catalog(true)).allSatisfy(item -> assertThat(item.path("system_prompt").isTextual()).isTrue());
        assertThatThrownBy(() -> DeerFlowSubagentCatalogApi.requireAdmin(false))
                .isInstanceOfSatisfying(SubagentCatalogStore.CatalogException.class, error -> {
                    assertThat(error.status()).isEqualTo(403);
                    assertThat(error.detail()).isEqualTo(DeerFlowSubagentCatalogApi.ADMIN_REQUIRED);
                });
        DeerFlowSubagentCatalogApi.requireAdmin(true);
        // Exercise the handler with a parameterized role, never a forged identity header.
        // This fixture is not a production non-admin principal or isolation acceptance.
        var roleServer = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 35618), 0);
        try {
            roleServer.createContext("/api/subagents", new DeerFlowSubagentCatalogApi(json, store,
                    ignored -> false, ignored -> false));
            roleServer.start();
            var roleBase = java.net.URI.create("http://127.0.0.1:35618");
            var list = http.request(roleBase, "GET", "/api/subagents", null, "role-param-read.http");
            assertThat(list.statusCode()).isEqualTo(200);
            assertThat(body(list).path("subagents")).allSatisfy(item -> assertThat(item.path("system_prompt").isNull()).isTrue());
            for (String method : List.of("POST", "PUT", "DELETE")) {
                String path = "/api/subagents" + (method.equals("POST") ? "" : "/worker");
                var denied = http.request(roleBase, method, path, method.equals("DELETE") ? null : definition("worker").toString(),
                        "role-param-" + method.toLowerCase() + ".http");
                assertThat(denied.statusCode()).isEqualTo(403);
                assertThat(body(denied).path("detail").asText()).isEqualTo(DeerFlowSubagentCatalogApi.ADMIN_REQUIRED);
            }
            assertThat(store.catalog(true)).hasSize(2);
        } finally {
            roleServer.stop(0);
            GatewayFixture.portReleased(35618);
        }
        Files.writeString(evidence.resolve("oracle12-identity-limit.txt"),
                "Role-parameterized checks PASS; production non-admin HTTP NOT_RUN: local identity is admin only.\n");
    }

    @Test
    void oracle13RuntimeSnapshotFiltersAndFingerprintsNormalizedContent() throws Exception {
        request("POST", "", definition("z-worker"));
        request("POST", "", definition("a-worker").put("enabled", false));
        ObjectNode conflict = definition("general-purpose");
        Files.writeString(directory().resolve("general-purpose.json"), conflict.toString());
        assertThat(item("general-purpose", "managed").path("conflict").asBoolean()).isTrue();
        var first = gateway.api.subagentCatalogSnapshot();
        assertThat(first.definitions()).extracting(n -> n.path("name").asText()).containsExactly("general-purpose", "z-worker");
        assertThat(first.fingerprint()).matches("[0-9a-f]{64}");
        JsonNode original = json.readTree(directory().resolve("z-worker.json").toFile());
        ObjectNode reordered = json.createObjectNode();
        List<String> names = new ArrayList<>();
        original.fieldNames().forEachRemaining(names::add);
        java.util.Collections.reverse(names);
        for (String name : names) reordered.set(name, original.get(name));
        Files.delete(directory().resolve("z-worker.json"));
        Files.writeString(directory().resolve("z-worker.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(reordered));
        assertThat(gateway.api.subagentCatalogSnapshot().fingerprint()).isEqualTo(first.fingerprint());
        assertThat(first.definitions().get(1).path("tools").isNull()).isTrue();
        ObjectNode emptyTools = json.createObjectNode();
        emptyTools.putArray("tools");
        request("PUT", "/z-worker", emptyTools);
        var empty = gateway.api.subagentCatalogSnapshot();
        assertThat(empty.fingerprint()).isNotEqualTo(first.fingerprint());
        assertThat(empty.definitions().get(1).path("tools").isArray()).isTrue();
        request("PUT", "/z-worker", json.createObjectNode().putNull("tools"));
        assertThat(gateway.api.subagentCatalogSnapshot().fingerprint()).isEqualTo(first.fingerprint());
    }

    @Test
    void concurrentCreatesPublishExactlyOneCompleteFile() throws Exception {
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var barrier = new java.util.concurrent.CountDownLatch(1);
            var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int index = 0; index < 8; index++) {
                final int requestIndex = index;
                futures.add(executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    var response = gateway.client.send(gateway.builder("POST", "/api/subagents", definition("worker")).build(),
                            HttpResponse.BodyHandlers.ofString());
                    Files.writeString(evidence.resolve("concurrent-create-" + requestIndex + ".response.http"), response.body());
                    return response.statusCode();
                }));
            }
            barrier.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (var future : futures) statuses.add(future.get(10, TimeUnit.SECONDS));
            http.save("concurrent-create-statuses.json", statuses);
            assertThat(statuses.stream().filter(n -> n == 201).count()).isEqualTo(1);
            assertThat(statuses.stream().filter(n -> n == 409).count()).isEqualTo(7);
            assertThat(item("worker").path("system_prompt").asText()).isEqualTo("worker instructions");
            try (var files = Files.list(directory())) {
                assertThat(files.map(p -> p.getFileName().toString()).toList()).containsExactly("worker.json");
            }
        }
    }

    @Test
    void malformedBodiesAndExplicitNullsNeverReplaceValidFile() throws Exception {
        request("POST", "", definition("worker"));
        byte[] before = Files.readAllBytes(directory().resolve("worker.json"));
        for (String field : List.of("description", "system_prompt", "enabled", "model", "max_turns", "timeout_seconds")) {
            HttpResponse<String> response = request("PUT", "/worker", json.createObjectNode().putNull(field));
            assertThat(response.statusCode()).as(field).isEqualTo(422);
        }
        assertThat(Files.readAllBytes(directory().resolve("worker.json"))).isEqualTo(before);
        ObjectNode customDeny = json.createObjectNode();
        customDeny.putArray("disallowed_tools").add("web_fetch");
        request("PUT", "/worker", customDeny);
        var resetDenied = request("PUT", "/worker", json.createObjectNode().putNull("disallowed_tools"));
        assertThat(resetDenied.statusCode()).isEqualTo(200);
        assertThat(strings(body(resetDenied).path("disallowed_tools"))).containsExactly("ask_clarification", "present_files", "task");
        for (String body : List.of("{", "[]", "null", "{} {}")) {
            HttpResponse<String> response = http.request(gateway.base, "POST", "/api/subagents", body, "malformed-" + (++sequence) + ".http");
            assertThat(response.statusCode()).isEqualTo(422);
            assertThat(json.readTree(response.body()).path("detail").isArray()).isTrue();
        }
        assertThat(request("PUT", "/bad_name", json.createObjectNode()).statusCode()).isEqualTo(422);
        assertThat(request("DELETE", "/bad_name", null).statusCode()).isEqualTo(422);
    }

    @Test
    void concurrentUpdatesMergeAndDeleteCannotResurrectDefinition() throws Exception {
        request("POST", "", definition("worker"));
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var barrier = new java.util.concurrent.CountDownLatch(1);
            List<ObjectNode> patches = List.of(json.createObjectNode().put("display_name", "concurrent display"),
                    json.createObjectNode().put("description", "concurrent description"));
            var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (ObjectNode patch : patches) {
                futures.add(executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    var response = gateway.client.send(gateway.builder("PUT", "/api/subagents/worker", patch).build(),
                            HttpResponse.BodyHandlers.ofString());
                    String field = patch.fieldNames().next();
                    Files.writeString(evidence.resolve("concurrent-update-" + field + ".request.json"), patch.toString());
                    Files.writeString(evidence.resolve("concurrent-update-" + field + ".response.http"), response.body());
                    return response.statusCode();
                }));
            }
            barrier.countDown();
            for (var future : futures) assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            JsonNode merged = item("worker");
            assertThat(merged.path("display_name").asText()).isEqualTo("concurrent display");
            assertThat(merged.path("description").asText()).isEqualTo("concurrent description");
            var deleteBarrier = new java.util.concurrent.CountDownLatch(1);
            var update = executor.submit(() -> {
                deleteBarrier.await(5, TimeUnit.SECONDS);
                var response = gateway.client.send(gateway.builder("PUT", "/api/subagents/worker", json.createObjectNode().put("enabled", false)).build(),
                        HttpResponse.BodyHandlers.ofString());
                Files.writeString(evidence.resolve("concurrent-delete-race-update.response.http"), response.body());
                return response.statusCode();
            });
            var delete = executor.submit(() -> {
                deleteBarrier.await(5, TimeUnit.SECONDS);
                var response = gateway.client.send(gateway.builder("DELETE", "/api/subagents/worker", null).build(),
                        HttpResponse.BodyHandlers.ofString());
                Files.writeString(evidence.resolve("concurrent-delete-race-delete.response.http"), response.body());
                return response.statusCode();
            });
            deleteBarrier.countDown();
            int updateStatus = update.get(10, TimeUnit.SECONDS);
            int deleteStatus = delete.get(10, TimeUnit.SECONDS);
            http.save("concurrent-update-delete-statuses.json", Map.of("update", updateStatus, "delete", deleteStatus));
            assertThat(updateStatus).isIn(200, 404);
            assertThat(deleteStatus).isEqualTo(204);
            assertThat(directory().resolve("worker.json")).doesNotExist();
            assertThat(body(request("GET", "", null)).path("subagents")).hasSize(1);
        }
    }

    @Test
    void writeFailureIsJson500AndLeavesNoPartialDefinition() throws Exception {
        Files.createDirectories(directory().getParent());
        Files.writeString(directory(), "blocked-directory");
        HttpResponse<String> response = request("POST", "", definition("worker"));
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(body(response).path("detail").asText()).isEqualTo("Subagent configuration store is unavailable");
        assertThat(Files.readString(directory())).isEqualTo("blocked-directory");
    }

    private ObjectNode definition(String name) {
        return json.createObjectNode().put("name", name).put("description", "worker description")
                .put("system_prompt", "worker instructions");
    }

    private HttpResponse<String> request(String method, String suffix, JsonNode body) throws Exception {
        String name = "catalog-" + (++sequence);
        if (body != null) Files.writeString(evidence.resolve(name + ".request.json"), body.toString());
        return http.request(gateway.base, method, "/api/subagents" + suffix,
                body == null ? null : body.toString(), name + ".response.http");
    }

    private JsonNode body(HttpResponse<String> response) throws Exception { return json.readTree(response.body()); }
    private Path directory() { return data.resolve("owners/default/subagents"); }
    private JsonNode item(String name) throws Exception { return item(name, "managed"); }
    private JsonNode item(String name, String source) throws Exception {
        for (JsonNode item : body(request("GET", "", null)).path("subagents")) {
            if (item.path("name").asText().equals(name) && item.path("source").asText().equals(source)) return item;
        }
        throw new AssertionError("Missing catalog entry " + name);
    }
    private static List<String> strings(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(value -> result.add(value.asText()));
        return result;
    }
}
