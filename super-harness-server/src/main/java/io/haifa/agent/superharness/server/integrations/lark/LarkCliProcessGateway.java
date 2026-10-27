package io.haifa.agent.superharness.server.integrations.lark;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Default {@link LarkCliGateway}: structured {@link ProcessBuilder} argv, bounded output and bounded
 * wall clock. User-influenced arguments reach the native official executable directly. Windows
 * shell shims are never executed; npm uses its fixed JavaScript entry through native Node.
 */
public final class LarkCliProcessGateway implements LarkCliGateway {
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);
    static final Duration NPM_INSTALL_TIMEOUT = Duration.ofSeconds(180);
    static final Duration DESTROY_GRACE = Duration.ofSeconds(5);
    static final int MAXIMUM_OUTPUT_BYTES = 256 * 1024;

    /** Narrow child environment allowlist; the Gateway process env is never forwarded wholesale. */
    static final List<String> ENVIRONMENT_ALLOWLIST = List.of(
            "PATH", "PATHEXT", "SystemRoot", "SYSTEMROOT", "windir", "ComSpec", "COMSPEC",
            "TEMP", "TMP", "TMPDIR", "HOME", "USERPROFILE", "APPDATA", "LOCALAPPDATA",
            "NUMBER_OF_PROCESSORS", "PROCESSOR_ARCHITECTURE", "LANG", "LC_ALL");

    private final Path gatewayCliDirectory;
    private final Map<String, String> processEnvironment;

    public LarkCliProcessGateway(Path gatewayCliDirectory, Map<String, String> processEnvironment) {
        this.gatewayCliDirectory = gatewayCliDirectory.toAbsolutePath().normalize();
        this.processEnvironment = Map.copyOf(processEnvironment);
    }

    /** Product environment used by the running Gateway. */
    public static LarkCliProcessGateway production(Path gatewayCliDirectory) {
        return new LarkCliProcessGateway(gatewayCliDirectory, System.getenv());
    }

    @Override
    public Optional<String> managedBinDirectory() {
        return resolveManaged().map(path -> Path.of(path).getParent().toString());
    }

    @Override
    public Probe probe() {
        Optional<String> path = resolve();
        if (path.isEmpty()) {
            return new Probe(false, null, null, "lark-cli is not installed on the Gateway");
        }
        return probeAt(path.orElseThrow());
    }

    @Override
    public Probe ensureManaged(String requestedVersion) throws IOException {
        String normalized = normalizeVersionTag(requestedVersion);
        if (normalized == null) {
            throw new IllegalArgumentException("Invalid Lark CLI npm version: " + requestedVersion);
        }
        List<String> npm = npmCommand();
        Files.createDirectories(gatewayCliDirectory);
        List<String> argv = new ArrayList<>(npm);
        argv.addAll(List.of("install", "--prefix", gatewayCliDirectory.toString(),
                "--no-audit", "--no-fund", LarkSkillCatalog.NPM_PACKAGE + "@" + normalized.substring(1)));
        Map<String, String> environment = childEnvironment(Map.of(
                "npm_config_update_notifier", "false",
                "npm_config_cache", gatewayCliDirectory.resolve(".npm-cache").toString(),
                "npm_config_userconfig", gatewayCliDirectory.resolve(".npmrc").toString(),
                "npm_config_globalconfig", gatewayCliDirectory.resolve(".npm-globalrc").toString()));
        ExecResult result = run(argv, environment, NPM_INSTALL_TIMEOUT, null);
        if (result.exitCode() != 0) {
            String detail = !result.stderr().isBlank() ? result.stderr() : result.stdout();
            throw new IOException("npm install " + LarkSkillCatalog.NPM_PACKAGE + "@" + normalized.substring(1)
                    + " failed (exit " + result.exitCode() + ")");
        }
        Optional<String> path = resolveManaged();
        if (path.isEmpty()) {
            throw new IOException("Managed lark-cli install completed, but no lark-cli binary was found.");
        }
        Probe probe = probeAt(path.orElseThrow());
        if (!probe.available()) {
            throw new IOException(probe.error() == null ? "Managed lark-cli install did not produce a runnable CLI."
                    : probe.error());
        }
        return probe;
    }

    @Override
    public ExecResult run(List<String> argv, Map<String, String> environment, Duration timeout, String stdin)
            throws IOException {
        if (argv.isEmpty()) {
            throw new IllegalArgumentException("argv must not be empty");
        }
        String executable = argv.getFirst().toLowerCase(Locale.ROOT);
        if (executable.endsWith(".cmd") || executable.endsWith(".bat")) {
            throw new IOException("Shell wrappers are not supported for Lark CLI operations");
        }
        List<String> command = new ArrayList<>();
        command.add(argv.getFirst());
        for (String argument : argv.subList(1, argv.size())) {
            command.add(isWindows() ? windowsNativeArgument(argument) : argument);
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().clear();
        builder.environment().putAll(childEnvironment(environment));
        builder.redirectErrorStream(false);
        Process process = builder.start();
        try (OutputStream input = process.getOutputStream()) {
            if (stdin != null) {
                input.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // A CLI that closes stdin early is not itself a failure; its exit code decides.
        }
        BoundedReader out = BoundedReader.start(process.getInputStream(), MAXIMUM_OUTPUT_BYTES);
        BoundedReader err = BoundedReader.start(process.getErrorStream(), MAXIMUM_OUTPUT_BYTES);
        boolean finished;
        try {
            finished = process.waitFor(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            terminate(process);
            throw new IOException("Interrupted while waiting for lark-cli", interrupted);
        }
        if (!finished) {
            terminate(process);
            throw new LarkTimeoutException("Timed out waiting for " + executableName(argv) + ".");
        }
        return new ExecResult(process.exitValue(), out.await(), err.await());
    }

    private Probe probeAt(String path) {
        try {
            ExecResult result = run(List.of(path, "--version"), childEnvironment(Map.of()), PROBE_TIMEOUT, null);
            String output = !result.stdout().isBlank() ? result.stdout() : result.stderr();
            String text = output.strip();
            if (result.exitCode() != 0) {
                return new Probe(false, path, null,
                        text.isEmpty() ? "exit code " + result.exitCode() : boundedForDiagnostic(text));
            }
            return new Probe(true, path, text.isEmpty() ? null : text, null);
        } catch (LarkTimeoutException timeout) {
            return new Probe(false, path, null, "lark-cli --version timed out");
        } catch (IOException | RuntimeException failure) {
            return new Probe(false, path, null, failure.getClass().getSimpleName());
        }
    }

    private Optional<String> resolve() {
        Optional<String> managed = resolveManaged();
        if (managed.isPresent()) {
            return managed;
        }
        return which("lark-cli");
    }

    private Optional<String> resolveManaged() {
        // Official @larksuite/cli scripts/run.js delegates to this installed native payload.
        Path nativeFile = gatewayCliDirectory.resolve("node_modules/@larksuite/cli/bin")
                .resolve(isWindows() ? "lark-cli.exe" : "lark-cli");
        return Files.isRegularFile(nativeFile) ? Optional.of(nativeFile.toString()) : Optional.empty();
    }

    private List<String> npmCommand() throws IOException {
        String npm = which("npm").orElse(null);
        if (npm != null) return List.of(npm);
        if (isWindows()) {
            String node = which("node").orElseThrow(() -> new IOException("Node.js is not available on the Gateway"));
            // npm's fixed distribution entry point; no shell string or user configuration is used.
            Path entry = Path.of(node).getParent().resolve("node_modules/npm/bin/npm-cli.js");
            if (Files.isRegularFile(entry)) return List.of(node, entry.toString());
        }
        throw new IOException("npm is not available on the Gateway; cannot install managed @larksuite/cli.");
    }

    private Optional<String> which(String executable) {
        String path = processEnvironment.getOrDefault("PATH",
                processEnvironment.getOrDefault("Path", ""));
        List<String> names = executableNames(executable);
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            Path directory;
            try {
                directory = Path.of(entry);
            } catch (RuntimeException invalid) {
                continue;
            }
            for (String name : names) {
                Path candidate = directory.resolve(name);
                if (Files.isRegularFile(candidate)) {
                    return Optional.of(candidate.toString());
                }
            }
        }
        return Optional.empty();
    }

    private static List<String> executableNames(String executable) {
        if (isWindows()) {
            return List.of(executable + ".exe");
        }
        return List.of(executable);
    }

    private static String windowsNativeArgument(String value) {
        // ProcessBuilder handles whitespace/trailing separators, but its legacy Windows quoting
        // does not protect embedded quotes. Encode those using the native CRT argv convention.
        if (!value.contains("\"")) return value;
        StringBuilder encoded = new StringBuilder();
        int slashes = 0;
        for (char character : value.toCharArray()) {
            if (character == '\\') { slashes++; continue; }
            encoded.append("\\".repeat(character == '"' ? slashes * 2 + 1 : slashes));
            encoded.append(character);
            slashes = 0;
        }
        encoded.append("\\".repeat(slashes));
        return encoded.toString();
    }

    public Map<String, String> childEnvironment(Map<String, String> overlay) {
        java.util.LinkedHashMap<String, String> environment = new java.util.LinkedHashMap<>();
        for (String key : ENVIRONMENT_ALLOWLIST) {
            String value = processEnvironment.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(key))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
            if (value != null) {
                environment.put(key, value);
            }
        }
        managedBinDirectory().ifPresent(bin -> {
            String existing = environment.getOrDefault("PATH", "");
            environment.put("PATH", existing.isBlank() ? bin : bin + java.io.File.pathSeparator + existing);
        });
        environment.putAll(overlay);
        return environment;
    }

    private static void terminate(Process process) {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(DESTROY_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(DESTROY_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String executableName(List<String> argv) {
        String executable = argv.get(0);
        int separator = Math.max(executable.lastIndexOf('/'), executable.lastIndexOf('\\'));
        return separator >= 0 ? executable.substring(separator + 1) : executable;
    }

    static String normalizeVersionTag(String value) {
        String text = value == null ? "" : value.strip();
        if (text.matches("v?\\d+\\.\\d+\\.\\d+")) {
            return text.startsWith("v") ? text : "v" + text;
        }
        return null;
    }

    private static String boundedForDiagnostic(String value) {
        String single = value.replace('\r', ' ').replace('\n', ' ').strip();
        return single.length() > 500 ? single.substring(0, 500) + "..." : single;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** Drains one process stream without ever buffering more than the declared bound. */
    private static final class BoundedReader {
        private final ByteArrayOutputStream retained = new ByteArrayOutputStream();
        private final Thread thread;
        private volatile IOException failure;

        private BoundedReader(InputStream stream, int limit) {
            this.thread = Thread.ofVirtual().start(() -> {
                byte[] buffer = new byte[8192];
                long total = 0;
                try (InputStream input = stream) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        int remaining = (int) Math.max(0, limit - total);
                        if (remaining > 0) {
                            retained.write(buffer, 0, Math.min(read, remaining));
                        }
                        total += read;
                    }
                } catch (IOException failure) {
                    this.failure = failure;
                }
            });
        }

        static BoundedReader start(InputStream stream, int limit) {
            return new BoundedReader(stream, limit);
        }

        String await() throws IOException {
            try {
                thread.join(DESTROY_GRACE.multipliedBy(2).toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while reading lark-cli output", interrupted);
            }
            if (failure != null) {
                throw failure;
            }
            return retained.toString(StandardCharsets.UTF_8);
        }
    }
}
