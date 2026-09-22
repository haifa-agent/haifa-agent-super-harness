package io.haifa.agent.superharness.workspace;

public record ProjectDocumentContent(
        String documentId, byte[] bytes, String sha256, long totalBytes, long offset, boolean truncated) {

    public ProjectDocumentContent {
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
