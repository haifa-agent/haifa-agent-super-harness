package io.haifa.agent.superharness.server.tool.file;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * In-memory "files uploaded by the current Run" holder for {@code list_uploaded_files}.
 *
 * <p>Frozen DeerFlow reads the current Run's upload state from {@code runtime.state.uploaded_files}
 * and excludes those filenames so the tool only reports historical uploads (they are already in
 * {@code <current_uploads>}). SH receives the current message's file references in the Run body, so
 * the Thread record keeps the newest snapshot here. A Thread admits only one active Run, so a
 * volatile field is sufficient and needs no persistence.
 */
public final class CurrentRunUploads {
    private volatile Set<String> filenames = Set.of();

    public void set(Collection<String> names) {
        this.filenames = Set.copyOf(new LinkedHashSet<>(names));
    }

    Set<String> filenames() {
        return filenames;
    }
}
