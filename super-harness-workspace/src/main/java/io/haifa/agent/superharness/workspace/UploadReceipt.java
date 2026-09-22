package io.haifa.agent.superharness.workspace;

public record UploadReceipt(String logicalPath, String originalFilename, String sha256, long byteCount,
                            String mediaType, boolean deduplicated) {}
