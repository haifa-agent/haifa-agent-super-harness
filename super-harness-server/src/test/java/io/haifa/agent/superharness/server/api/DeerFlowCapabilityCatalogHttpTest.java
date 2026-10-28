package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.superharness.server.testsupport.GatewayFixture;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Real registered route: the frozen capability catalog is served without installation state. */
class DeerFlowCapabilityCatalogHttpTest {
    private final ObjectMapper json = GatewayFixture.JSON;
    private Path data;
    private Path evidence;
    private GatewayFixture gateway;
    private GatewayFixture.HttpEvidence http;

    @BeforeEach
    void start() throws Exception {
        data = GatewayFixture.dataDirectory();
        evidence = GatewayFixture.evidenceDirectory();
        http = new GatewayFixture.HttpEvidence(evidence, Duration.ofSeconds(30));
        gateway = GatewayFixture.production(data, Integer.getInteger("w20.catalog.port", 0), Map.of(), evidence);
    }

    @AfterEach
    void stop() throws Exception {
        try {
            if (gateway != null) gateway.close();
        } finally {
            if (http != null) http.close();
        }
    }

    @Test
    void catalogReturnsEveryFrozenEntryWithoutInstallationState() throws Exception {
        HttpResponse<String> first = http.request(
                gateway.base, "GET", "/api/capabilities/catalog", null, "catalog-1.raw.json");
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(first.headers().firstValue("content-type").orElse(""))
                .startsWith("application/json");
        assertThat(first.headers().firstValue("cache-control")).contains("no-store");

        JsonNode catalog = json.readTree(first.body());
        assertThat(catalog.isArray()).isTrue();
        assertThat(catalog).hasSize(15);
        assertThat(ids(catalog)).containsExactly(
                "lark",
                "dingtalk",
                "wecom",
                "tencent-docs",
                "notion",
                "openviking",
                "web-search",
                "web-fetch",
                "exa",
                "firecrawl",
                "database",
                "hubspot",
                "github",
                "atlassian",
                "browser");
        for (JsonNode entry : catalog) {
            assertThat(fieldNames(entry)).containsExactlyElementsOf(CapabilityCatalog.ENTRY_FIELDS);
        }

        JsonNode lark = catalog.get(0);
        assertThat(lark.path("id").asText()).isEqualTo("lark");
        assertThat(lark.path("category").asText()).isEqualTo("office");
        assertThat(lark.path("kind").asText()).isEqualTo("cli");
        assertThat(lark.path("adapter").asText()).isEqualTo("lark");
        assertThat(lark.path("source").asText()).isEqualTo("https://github.com/larksuite/cli");
        assertThat(lark.path("icon").asText()).isEqualTo("/images/plugins/lark.ico");
        assertThat(lark.path("name").path("zh-CN").asText()).isEqualTo("飞书 / Lark");
        assertThat(lark.path("auth_methods")).extracting(JsonNode::asText).containsExactly("oauth");
        assertThat(lark.path("contributions")).extracting(JsonNode::asText).containsExactly("tools", "skills");

        // A fresh read returns the same bytes: the response carries no per-user or per-run state.
        HttpResponse<String> second = http.request(
                gateway.base, "GET", "/api/capabilities/catalog", null, "catalog-2.raw.json");
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(first.body()).doesNotContain(data.toAbsolutePath().toString());
        assertThat(first.body()).doesNotContain("env://");
        assertThat(first.body()).doesNotContain("GATEWAY_FIXTURE_CONTINUATION_KEY");
        assertThat(first.body()).doesNotContain("auth_status");
        assertThat(first.body()).doesNotContain("\"installed\"");

        HttpResponse<String> rejected = http.request(
                gateway.base, "POST", "/api/capabilities/catalog", "{}", "catalog-post.raw.json");
        assertThat(rejected.statusCode()).isEqualTo(405);
        assertThat(rejected.headers().firstValue("allow")).contains("GET");
    }

    private static List<String> ids(JsonNode catalog) {
        List<String> ids = new ArrayList<>();
        catalog.forEach(entry -> ids.add(entry.path("id").asText()));
        return ids;
    }

    private static List<String> fieldNames(JsonNode entry) {
        List<String> names = new ArrayList<>();
        Iterator<String> iterator = entry.fieldNames();
        iterator.forEachRemaining(names::add);
        return names;
    }
}
