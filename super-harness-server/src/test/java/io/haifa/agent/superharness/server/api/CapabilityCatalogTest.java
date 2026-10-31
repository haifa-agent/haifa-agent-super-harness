package io.haifa.agent.superharness.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Frozen capability manifests: bundled bytes, defaults, field order and validation. */
class CapabilityCatalogTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void bundledCatalogMatchesFrozenManifestAndShape() throws Exception {
        byte[] manifest = resource(CapabilityCatalog.MANIFEST_RESOURCE);
        byte[] catalog = resource(CapabilityCatalog.CATALOG_RESOURCE);

        CapabilityCatalog.verify(manifest, catalog);
        List<ObjectNode> entries = CapabilityCatalog.parse(catalog);

        assertThat(entries).hasSize(15);
        assertThat(ids(entries)).containsExactly(
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
        for (ObjectNode entry : entries) {
            assertThat(fieldNames(entry))
                    .as("frozen PluginManifest field order for %s", entry.path("id").asText())
                    .containsExactlyElementsOf(CapabilityCatalog.ENTRY_FIELDS);
        }

        ObjectNode lark = entries.get(0);
        assertThat(lark.path("schema_version").asInt()).isEqualTo(1);
        assertThat(lark.path("id").asText()).isEqualTo("lark");
        assertThat(lark.path("version").asText()).isEqualTo("1");
        assertThat(lark.path("category").asText()).isEqualTo("office");
        assertThat(lark.path("kind").asText()).isEqualTo("cli");
        assertThat(lark.path("adapter").asText()).isEqualTo("lark");
        assertThat(lark.path("source").asText()).isEqualTo("https://github.com/larksuite/cli");
        assertThat(lark.path("icon").asText()).isEqualTo("/images/plugins/lark.ico");
        assertThat(lark.path("name").path("en-US").asText()).isEqualTo("Lark / Feishu");
        assertThat(lark.path("name").path("zh-CN").asText()).isEqualTo("飞书 / Lark");
        assertThat(lark.path("aliases")).extracting(JsonNode::asText).containsExactly("lark", "feishu", "飞书");
        assertThat(lark.path("auth_methods")).extracting(JsonNode::asText).containsExactly("oauth");
        assertThat(lark.path("contributions")).extracting(JsonNode::asText).containsExactly("tools", "skills");
        assertThat(lark.path("config_schema")).isEqualTo(JSON.readTree(
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"));
    }

    @Test
    void pydanticDefaultsApplyToOmittedOptionalFields() throws Exception {
        byte[] minimal = JSON.writeValueAsBytes(JSON.readTree("""
                [{
                  "id": "custom-plugin",
                  "version": "3",
                  "name": {"en-US": "Thing"},
                  "description": {"en-US": "Description"},
                  "setup": {"en-US": "Setup"},
                  "category": "custom",
                  "kind": "native",
                  "adapter": "guide",
                  "source": "https://example.test/plugin"
                }]
                """));

        ObjectNode entry = CapabilityCatalog.parse(minimal).get(0);

        assertThat(fieldNames(entry)).containsExactlyElementsOf(CapabilityCatalog.ENTRY_FIELDS);
        assertThat(entry.path("schema_version").asInt()).isEqualTo(1);
        assertThat(entry.path("icon").isNull()).isTrue();
        assertThat(entry.path("aliases")).isEmpty();
        assertThat(entry.path("auth_methods")).isEmpty();
        assertThat(entry.path("contributions")).isEmpty();
        assertThat(entry.path("config_schema")).isEqualTo(JSON.readTree("{\"type\":\"object\"}"));
    }

    @Test
    void frozenValidationRejectsUnsafeOrDuplicateManifests() throws Exception {
        assertThatThrownBy(() -> CapabilityCatalog.parse(bytes(entryJson("duplicate"))))
                .hasMessageContaining("Duplicate plugin identifier");
        assertThatThrownBy(() -> CapabilityCatalog.parse(bytes(entryJson("http-source"))))
                .hasMessageContaining("must use HTTPS");
        assertThatThrownBy(() -> CapabilityCatalog.parse(bytes(entryJson("traversal-icon"))))
                .hasMessageContaining("bundled plugin assets");
        assertThatThrownBy(() -> CapabilityCatalog.parse(bytes(entryJson("unknown-field"))))
                .hasMessageContaining("Unknown plugin manifest field");
        assertThatThrownBy(() -> CapabilityCatalog.parse(bytes(entryJson("bad-category"))))
                .hasMessageContaining("Unsupported plugin category");

        byte[] manifest = resource(CapabilityCatalog.MANIFEST_RESOURCE);
        byte[] catalog = resource(CapabilityCatalog.CATALOG_RESOURCE);
        catalog[0] = (byte) (catalog[0] == '[' ? '{' : '[');
        assertThatThrownBy(() -> CapabilityCatalog.verify(manifest, catalog))
                .hasMessageContaining("hash mismatch");
    }

    private static String entryJson(String variant) {
        String id = "custom-plugin";
        String source = "https://example.test/plugin";
        String icon = "\"/images/plugins/example.png\"";
        String category = "custom";
        String extra = "";
        if (variant.equals("http-source")) {
            source = "http://example.test/plugin";
        } else if (variant.equals("traversal-icon")) {
            icon = "\"/images/plugins/../secret.png\"";
        } else if (variant.equals("unknown-field")) {
            extra = ",\"installed\": true";
        } else if (variant.equals("bad-category")) {
            category = "unknown";
        }
        String one = "{\"id\":\"%s\",\"version\":\"1\",\"name\":{\"en-US\":\"Thing\"},"
                + "\"description\":{\"en-US\":\"Description\"},\"setup\":{\"en-US\":\"Setup\"},"
                + "\"category\":\"%s\",\"kind\":\"native\",\"adapter\":\"guide\",\"source\":\"%s\","
                + "\"icon\":%s%s}";
        String entry = one.formatted(id, category, source, icon, extra);
        return "[" + entry + (variant.equals("duplicate") ? "," + entry : "") + "]";
    }

    private static byte[] bytes(String text) {
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static List<String> ids(List<ObjectNode> entries) {
        List<String> ids = new ArrayList<>();
        for (ObjectNode entry : entries) {
            ids.add(entry.path("id").asText());
        }
        return ids;
    }

    private static List<String> fieldNames(ObjectNode entry) {
        List<String> names = new ArrayList<>();
        Iterator<String> iterator = entry.fieldNames();
        iterator.forEachRemaining(names::add);
        return names;
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream stream = CapabilityCatalogTest.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("Missing test resource: " + path);
            }
            return stream.readAllBytes();
        }
    }
}
