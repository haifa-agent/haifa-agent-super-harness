package io.haifa.agent.superharness.server.tool.file;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/** Verified facts about one saved original: SHA-256, byte size and strict UTF-8 status. */
public record UploadFileFacts(String sha256, long size, String encoding, Optional<String> text) {
    static final String UTF8 = "UTF-8";
    static final String UTF8_BOM = "UTF-8 with BOM";
    static final String NOT_UTF8 = "not valid UTF-8";

    public static UploadFileFacts of(byte[] bytes) {
        Optional<String> text;
        try {
            text = Optional.of(StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString());
        } catch (CharacterCodingException malformed) {
            text = Optional.empty();
        }
        String encoding = text.isEmpty()
                ? NOT_UTF8
                : bytes.length >= 3 && (bytes[0] & 0xff) == 0xEF && (bytes[1] & 0xff) == 0xBB
                        && (bytes[2] & 0xff) == 0xBF ? UTF8_BOM : UTF8;
        return new UploadFileFacts(sha256(bytes), bytes.length, encoding, text);
    }

    public int lineCount() {
        String value = text.orElse("");
        if (value.isEmpty()) return 0;
        long breaks = value.chars().filter(character -> character == '\n').count();
        return Math.toIntExact(value.endsWith("\n") ? breaks : breaks + 1);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
