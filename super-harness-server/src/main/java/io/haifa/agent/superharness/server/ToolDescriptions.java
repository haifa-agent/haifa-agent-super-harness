package io.haifa.agent.superharness.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Loads the frozen DeerFlow model-visible tool descriptions that were imported verbatim to
 * {@code upstream/deerflow/tool-descriptions/<tool>.txt} (ledger: {@code manifest.json}).
 *
 * <p>Loading the resource at spec-construction time keeps the Java {@code description} byte-equal
 * to the imported original, so the description-hash oracle cannot silently drift.
 */
final class ToolDescriptions {
    private static final String ROOT = "upstream/deerflow/tool-descriptions/";

    private ToolDescriptions() {}

    static String load(String tool) {
        String resource = ROOT + tool + ".txt";
        try (InputStream stream = ToolDescriptions.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("missing imported tool description: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to read imported tool description: " + resource, exception);
        }
    }
}
