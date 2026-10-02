package io.haifa.agent.superharness.server.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersistentThreadCatalogTest {
    @Test
    void rollsBackMemoryWhenMetadataSerializationFails(@TempDir Path dataDirectory) throws Exception {
        PersistentThreadCatalog catalog = new PersistentThreadCatalog(new ObjectMapper(), dataDirectory);
        Object recursive = new Object() {
            @SuppressWarnings("unused")
            public Object getSelf() {
                return this;
            }
        };
        PersistentThreadCatalog.Entry invalid = new PersistentThreadCatalog.Entry(
                "thread-invalid-metadata",
                Map.of("recursive", recursive),
                Instant.parse("2026-09-24T00:00:00Z"),
                Instant.parse("2026-09-24T00:00:01Z"),
                Optional.empty(),
                Optional.empty());

        assertThatThrownBy(() -> catalog.save(invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThat(catalog.find(invalid.threadId())).isEmpty();
        assertThat(catalog.list()).isEmpty();
    }
}
