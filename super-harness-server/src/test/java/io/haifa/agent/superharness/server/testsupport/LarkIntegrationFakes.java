package io.haifa.agent.superharness.server.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.superharness.server.integrations.lark.LarkAppRegistration;
import io.haifa.agent.superharness.server.integrations.lark.LarkArchiveSource;
import io.haifa.agent.superharness.server.integrations.lark.LarkCliGateway;
import io.haifa.agent.superharness.server.integrations.lark.LarkSkillCatalog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Deterministic fakes for the Lark integration boundaries. They are offline test doubles only and
 * never stand in for a real OAuth or office-write PASS.
 */
public final class LarkIntegrationFakes {
    private LarkIntegrationFakes() {}

    /** One recorded CLI invocation. */
    public record Call(List<String> argv, Map<String, String> environment, String stdin) {
        public String command() {
            return argv.size() > 1 ? argv.get(0) + " " + argv.get(1) : String.join(" ", argv);
        }
    }

    /** In-memory CLI that writes the same config/data files the real CLI would. */
    public static final class FakeCli implements LarkCliGateway {
        private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean available;
        private volatile String path = "C:/fake/lark-cli.exe";
        private volatile String version = "1.0.65";
        private volatile int authStatusExit;
        private volatile String authStatusJson = "{\"identities\":{\"user\":{\"userName\":\"Fake User\"}}}";
        private volatile boolean failConfigInit;
        private final AtomicLong probes = new AtomicLong();

        public FakeCli(boolean available) {
            this.available = available;
        }

        public List<Call> calls() {
            return List.copyOf(calls);
        }

        public void makeAvailable(String version) {
            this.available = true;
            this.version = version;
        }

        public void authStatus(int exit, String json) {
            this.authStatusExit = exit;
            this.authStatusJson = json;
        }

        public void failConfigInit(boolean fail) {
            this.failConfigInit = fail;
        }

        /** How many times {@link #probe()} ran, so caching behavior is observable. */
        public long probeCount() {
            return probes.get();
        }

        @Override
        public Probe probe() {
            probes.incrementAndGet();
            return available ? new Probe(true, path, version, null)
                    : new Probe(false, null, null, "lark-cli is not installed on the Gateway");
        }

        @Override
        public Probe ensureManaged(String requestedVersion) {
            available = true;
            version = requestedVersion + " (fake)";
            return probe();
        }

        @Override
        public ExecResult run(List<String> argv, Map<String, String> environment, Duration timeout, String stdin)
                throws IOException {
            calls.add(new Call(List.copyOf(argv), Map.copyOf(environment), stdin));
            String second = argv.size() > 1 ? argv.get(1) : "";
            String third = argv.size() > 2 ? argv.get(2) : "";
            if (second.equals("config") && third.equals("init")) {
                if (failConfigInit) {
                    return new ExecResult(1, "", "{\"error\":\"invalid app credentials\"}");
                }
                String appId = valueAfter(argv, "--app-id");
                String brand = valueAfter(argv, "--brand");
                Path config = Path.of(environment.get("LARKSUITE_CLI_CONFIG_DIR"));
                Files.createDirectories(config);
                var node = new ObjectMapper().createObjectNode();
                var apps = node.putArray("apps");
                var app = apps.addObject();
                app.put("name", "app-" + appId);
                app.put("appId", appId);
                app.put("appSecret", stdin == null ? "" : stdin.strip());
                app.put("brand", brand);
                node.put("currentApp", "app-" + appId);
                Files.writeString(config.resolve("config.json"),
                        new ObjectMapper().writeValueAsString(node), StandardCharsets.UTF_8);
                return new ExecResult(0, "", "");
            }
            if (second.equals("auth") && third.equals("status")) {
                return new ExecResult(authStatusExit, authStatusJson, "");
            }
            if (second.equals("auth") && third.equals("login")) {
                if (argv.contains("--device-code")) {
                    Path data = Path.of(environment.get("LARKSUITE_CLI_DATA_DIR"));
                    Files.createDirectories(data);
                    Files.writeString(data.resolve("token.json"), "{\"token\":\"fake\"}", StandardCharsets.UTF_8);
                    return new ExecResult(0, "", "");
                }
                return new ExecResult(0,
                        "{\"verification_url\":\"https://open.feishu.cn/page/cli?user_code=ABC\","
                                + "\"device_code\":\"device-abc\",\"expires_in\":600,\"user_code\":\"ABC\","
                                + "\"hint\":\"Open the link\"}",
                        "");
            }
            if (second.equals("auth") && third.equals("logout")) {
                return new ExecResult(0, "", "");
            }
            return new ExecResult(0, "", "");
        }

        @Override
        public Optional<String> managedBinDirectory() {
            return Optional.of("C:/fake/bin");
        }

        private static String valueAfter(List<String> argv, String flag) {
            int index = argv.indexOf(flag);
            return index >= 0 && index + 1 < argv.size() ? argv.get(index + 1) : "";
        }
    }

    /** Scripted app-registration flow. */
    public static final class FakeRegistration implements LarkAppRegistration {
        private final Deque<PollOutcome> outcomes = new ArrayDeque<>();
        private volatile String latest = "v1.0.65";
        private volatile Begin begin = new Begin("USER-CODE", "DEVICE-CODE", 600, 5);
        private volatile boolean upstreamUnavailable;

        /** Simulates an unreachable accounts host, which is not a client input error. */
        public void upstreamUnavailable(boolean unavailable) {
            this.upstreamUnavailable = unavailable;
        }

        public void approved(String appId, String appSecret, String tenantBrand) {
            outcomes.add(PollOutcome.approved(new Poll(appId, appSecret, tenantBrand)));
        }

        public void pending(int times) {
            for (int index = 0; index < times; index++) {
                outcomes.add(PollOutcome.pending());
            }
        }

        public void slowDown() {
            outcomes.add(PollOutcome.slowDown());
        }

        public void error(String error, String description) {
            outcomes.add(PollOutcome.error(error, description));
        }

        public void clear() {
            outcomes.clear();
        }

        public void latest(String version) {
            this.latest = version;
        }

        public void begin(String userCode, String deviceCode) {
            this.begin = new Begin(userCode, deviceCode, 600, 5);
        }

        @Override
        public Begin begin(String brand) {
            if (upstreamUnavailable) {
                throw new io.haifa.agent.superharness.server.integrations.lark
                        .LarkUpstreamUnavailableException("Lark app registration request failed: ConnectException");
            }
            return begin;
        }

        @Override
        public PollOutcome pollOnce(String deviceCode, String brand) {
            PollOutcome outcome = outcomes.poll();
            return outcome == null ? PollOutcome.pending() : outcome;
        }

        @Override
        public String latestCliVersion() {
            return latest;
        }
    }

    /** Archive source that serves one pre-built zip. */
    public static final class FakeArchives implements LarkArchiveSource {
        private volatile Path archive;

        public FakeArchives(Path archive) {
            this.archive = archive;
        }

        public void archive(Path archive) {
            this.archive = archive;
        }

        @Override
        public Path fetch(String version) throws IOException {
            // The caller owns deletion of the returned temporary copy, like the HTTPS source.
            Path copy = Files.createTempFile("haifa-lark-skills-fake-", ".zip");
            Files.copy(archive, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return copy;
        }
    }

    /** Monotonic virtual clock advanced by the fake sleeper. */
    public static final class VirtualClock {
        private final AtomicLong nanos = new AtomicLong(1);

        public long nanoTime() {
            return nanos.get();
        }

        public void sleep(Duration duration) {
            nanos.addAndGet(Math.max(1, duration.toNanos()));
        }
    }

    /** Builds an archive shaped like the official {@code cli-<version>/skills/<name>} tree. */
    public static Path archive(Path target, String version, List<String> names, String extraGuidance)
            throws IOException {
        Files.createDirectories(target.getParent());
        String tag = version.startsWith("v") ? version.substring(1) : version;
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            zip.putNextEntry(new ZipEntry("cli-" + tag + "/LICENSE"));
            zip.write("MIT License - controlled test fixture".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (String name : names) {
                String body = """
                        ---
                        name: %s
                        description: Fake official Lark skill %s
                        ---

                        # %s

                        Use the official lark-cli for %s.
                        """.formatted(name, name, name, name);
                if (extraGuidance != null && name.equals(LarkSkillCatalog.GUARD_SKILL)) {
                    body += "\n" + extraGuidance + "\n";
                }
                zip.putNextEntry(new ZipEntry("cli-" + tag + "/skills/" + name + "/SKILL.md"));
                zip.write(body.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return target;
    }

    /** Convenience: all 27 skill names. */
    public static List<String> allNames() {
        return LarkSkillCatalog.NAMES;
    }

    /** Environment map view for assertions. */
    public static Map<String, String> environment(Call call) {
        return new LinkedHashMap<>(call.environment());
    }
}
