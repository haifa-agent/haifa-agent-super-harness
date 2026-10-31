package io.haifa.agent.superharness.server.testsupport;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Native child fixture for the Lark execution environment tests.
 *
 * <p>Runs as a real operating-system process through the SDK execution platform, so tests can
 * observe the actual launch, the owner overlay reaching the child, and process exit after timeout
 * or cancellation without inventing a product execution engine.
 */
public final class LarkExecutionFixture {
    private LarkExecutionFixture() {}

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "";
        switch (mode) {
            case "env" -> {
                Path marker = Path.of(args[1]);
                StringBuilder text = new StringBuilder();
                text.append("OWNER_CONFIG=")
                        .append(nullToEmpty(System.getenv("LARKSUITE_CLI_CONFIG_DIR")))
                        .append('\n');
                text.append("OWNER_DATA=")
                        .append(nullToEmpty(System.getenv("LARKSUITE_CLI_DATA_DIR")))
                        .append('\n');
                text.append("NO_UPDATE_NOTIFIER=")
                        .append(nullToEmpty(System.getenv("LARKSUITE_CLI_NO_UPDATE_NOTIFIER")))
                        .append('\n');
                String path = System.getenv("PATH");
                text.append("PATH_FIRST=")
                        .append(path == null || path.isBlank()
                                ? ""
                                : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))[0])
                        .append('\n');
                text.append("PID=").append(ProcessHandle.current().pid()).append('\n');
                text.append("HOME_PRESENT=")
                        .append(System.getenv("HOME") != null || System.getenv("USERPROFILE") != null)
                        .append('\n');
                write(marker, text.toString());
            }
            case "sleep" -> {
                Path marker = Path.of(args[1]);
                write(marker, "PID=" + ProcessHandle.current().pid() + "\n");
                Thread.sleep(Long.parseLong(args[2]));
            }
            case "output" -> {
                int bytes = Integer.parseInt(args[1]);
                OutputStream stdout = System.out;
                byte[] chunk = new byte[256];
                java.util.Arrays.fill(chunk, (byte) 'x');
                int remaining = bytes;
                while (remaining > 0) {
                    int length = Math.min(remaining, chunk.length);
                    stdout.write(chunk, 0, length);
                    remaining -= length;
                }
                stdout.flush();
            }
            default -> {
                System.err.println("unknown fixture mode: " + mode);
                System.exit(2);
            }
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void write(Path marker, String text) throws IOException {
        Files.createDirectories(marker.toAbsolutePath().getParent());
        Files.writeString(marker, text, StandardCharsets.UTF_8);
    }
}
