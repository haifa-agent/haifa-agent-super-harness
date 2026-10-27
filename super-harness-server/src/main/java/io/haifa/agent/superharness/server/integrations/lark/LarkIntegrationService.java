package io.haifa.agent.superharness.server.integrations.lark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.superharness.server.config.SkillConfigStore;
import io.haifa.agent.superharness.workspace.AtomicMoves;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Product-level Lark/Feishu office integration.
 *
 * <p>Owns only the frozen DeerFlow REST product flow: managed CLI/skill-pack operations, per-user
 * app configuration and OAuth device flows, and flow-generation isolation. Skill discovery,
 * activation, execution, approval, sandboxing and credential injection remain Haifa Agent Runtime
 * semantics; this class never runs a model-visible command and never keeps a second Skill registry.
 *
 * <p>The shared pack and Gateway CLI live once per data root. Credentials live per owner and are
 * never copied from, or shared with, the Feishu IM channel bot credentials.
 */
public final class LarkIntegrationService {
    private static final System.Logger LOG = System.getLogger(LarkIntegrationService.class.getName());
    static final Duration CONFIG_POLL_TIMEOUT = Duration.ofSeconds(45);
    static final Duration AUTH_STATUS_TIMEOUT = Duration.ofSeconds(8);
    static final Duration AUTH_START_TIMEOUT = Duration.ofSeconds(20);
    static final Duration AUTH_COMPLETE_DEFAULT_WAIT = Duration.ofSeconds(45);
    static final int AUTH_COMPLETE_MIN_WAIT_SECONDS = 5;
    static final int AUTH_COMPLETE_MAX_WAIT_SECONDS = 45;
    static final long LATEST_VERSION_TTL_SECONDS = 3600;
    private static final long LATEST_VERSION_TTL_NANOS = Duration.ofSeconds(LATEST_VERSION_TTL_SECONDS).toNanos();
    private static final Pattern SAFE_OWNER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern SAFE_SCOPE = Pattern.compile("[A-Za-z0-9 _.,:/-]{1,1024}");
    private static final Pattern SAFE_DOMAIN = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,31}");
    private static final Pattern GENERATION = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Pattern NUMERIC_VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+");
    private static final int MAX_CLI_DIAGNOSTIC_CHARS = 500;

    /** Gateway CLI probe as the frozen contract reports it. */
    public record CliState(boolean available, String path, String version, String error) {}

    /** Per-user authorization probe. */
    public record AuthState(String status, String message, String user, boolean verified) {}

    /** Frozen {@code LarkIntegrationStatusResponse}. */
    public record Status(
            boolean installed,
            String version,
            String manifestVersion,
            String latestAvailableVersion,
            boolean runtimeVersionMismatch,
            boolean appConfigured,
            String appId,
            String appBrand,
            int skillsExpected,
            int skillsInstalled,
            List<String> installedSkills,
            List<String> enabledSkills,
            String installPath,
            CliState cli,
            AuthState auth,
            String sandboxRuntimeMode,
            boolean sandboxRuntimeReady,
            String sandboxRuntimeDetail) {

        /** Non-admin copy: host paths are administrative information. */
        public Status redacted() {
            return new Status(installed, version, manifestVersion, latestAvailableVersion, runtimeVersionMismatch,
                    appConfigured, appId, appBrand, skillsExpected, skillsInstalled, installedSkills, enabledSkills,
                    "", new CliState(cli.available(), null, cli.version(), cli.error()), auth,
                    sandboxRuntimeMode, sandboxRuntimeReady, sandboxRuntimeDetail);
        }
    }

    public record InstallResult(boolean success, List<String> installedSkills, String message, Status status) {}

    public record ConfigStart(String verificationUrl, String deviceCode, String generation, Integer expiresIn,
            Integer interval, String userCode, String brand) {}

    public record ConfigComplete(boolean success, String message, String generation, Status status) {}

    public record AuthStart(String verificationUrl, String deviceCode, String generation, Integer expiresIn,
            String userCode, String hint) {}

    public record AuthComplete(boolean success, String message, Status status) {}

    private final ObjectMapper json;
    private final Path integrationRoot;
    private final LarkSkillPackInstaller packs;
    private final LarkCliGateway cli;
    private final LarkAppRegistration registration;
    private final LarkArchiveSource archives;
    private final SkillConfigStore skills;
    private final LongSupplier nanoTime;
    private final Consumer<Duration> sleeper;
    private final Map<String, ReentrantLock> userLocks = new ConcurrentHashMap<>();
    private volatile LatestCache latestCache;

    public static LarkIntegrationService production(ObjectMapper json, Path dataDirectory, SkillConfigStore skills) {
        Path root = dataDirectory.toAbsolutePath().normalize().resolve("integrations").resolve("lark");
        return new LarkIntegrationService(json, dataDirectory, skills,
                new LarkSkillPackInstaller(json, root),
                LarkCliProcessGateway.production(root.resolve("gateway-cli")),
                new LarkHttpAppRegistration(json),
                new LarkArchiveSource.GitHub(),
                System::nanoTime,
                duration -> {
                    try {
                        Thread.sleep(Math.max(1, duration.toMillis()));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new LarkTimeoutException("Interrupted while waiting for Lark.", interrupted);
                    }
                });
    }

    public LarkIntegrationService(ObjectMapper json, Path dataDirectory, SkillConfigStore skills,
            LarkSkillPackInstaller packs, LarkCliGateway cli, LarkAppRegistration registration,
            LarkArchiveSource archives, LongSupplier nanoTime, Consumer<Duration> sleeper) {
        this.json = json;
        this.integrationRoot = dataDirectory.toAbsolutePath().normalize().resolve("integrations").resolve("lark");
        this.skills = skills;
        this.packs = packs;
        this.cli = cli;
        this.registration = registration;
        this.archives = archives;
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
    }

    public Path integrationRoot() {
        return integrationRoot;
    }

    public Path userRoot(String ownerId) {
        return integrationRoot.resolve("users").resolve(ownerSegment(ownerId));
    }

    public Path userConfigDirectory(String ownerId) {
        return userRoot(ownerId).resolve("config");
    }

    public Path userDataDirectory(String ownerId) {
        return userRoot(ownerId).resolve("data");
    }

    /**
     * Per-user execution environment that the Host execution provider would need to reach the CLI
     * with this user's authorization. Exposed for the execution-seam probe; it is not itself an
     * execution path and must not be used to bypass the SDK.
     */
    public Map<String, String> userExecutionEnvironment(String ownerId) {
        ensureUserTree(ownerId);
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("LARKSUITE_CLI_CONFIG_DIR", userConfigDirectory(ownerId).toString());
        environment.put("LARKSUITE_CLI_DATA_DIR", userDataDirectory(ownerId).toString());
        environment.put("LARKSUITE_CLI_NO_UPDATE_NOTIFIER", "1");
        environment.put("LARKSUITE_CLI_NO_SKILLS_NOTIFIER", "1");
        cli.managedBinDirectory().ifPresent(bin -> environment.put("PATH", bin));
        return environment;
    }

    // ------------------------------------------------------------------ status

    public Status status(String ownerId) {
        Optional<LarkSkillPackInstaller.Manifest> manifest = packs.manifest();
        Set<String> installed = packs.installedNames();
        AppCredentials app = readAppCredentials(ownerId);
        LarkCliGateway.Probe probe = cli.probe();
        String manifestVersion = manifest.map(LarkSkillPackInstaller.Manifest::version).orElse(null);
        String latest = cachedLatestVersion();
        List<String> enabled = enabledSkills();
        return new Status(
                manifest.isPresent() && installed.contains(LarkSkillCatalog.GUARD_SKILL),
                manifestVersion != null ? manifestVersion : LarkSkillCatalog.FALLBACK_VERSION,
                manifestVersion,
                latest,
                versionsDrifted(manifestVersion, probe.version()),
                app.configured(),
                app.appId(),
                app.brand(),
                LarkSkillCatalog.NAMES.size(),
                installed.size(),
                List.copyOf(new TreeSet<>(installed)),
                enabled,
                packs.packRoot().toString(),
                new CliState(probe.available(), probe.path(), probe.version(), probe.error()),
                probeAuth(ownerId, probe, false),
                "none",
                false,
                "Host execution provider: lark-cli runs on the Gateway host with per-user credentials; "
                        + "no sandbox runtime is used.");
    }

    private List<String> enabledSkills() {
        try {
            return skills.list().stream()
                    .filter(row -> LarkSkillCatalog.NAME_SET.contains(String.valueOf(row.get("name"))))
                    .filter(row -> Boolean.TRUE.equals(row.get("enabled")))
                    .map(row -> String.valueOf(row.get("name")))
                    .sorted()
                    .toList();
        } catch (RuntimeException unavailable) {
            LOG.log(System.Logger.Level.WARNING, "Lark skill enable state is unavailable", unavailable);
            return List.of();
        }
    }

    private String cachedLatestVersion() {
        long now = nanoTime.getAsLong();
        LatestCache cached = latestCache;
        if (cached != null && now - cached.atNanos() < LATEST_VERSION_TTL_NANOS) {
            return cached.version();
        }
        String version;
        try {
            version = LarkCliProcessGateway.normalizeVersionTag(registration.latestCliVersion());
        } catch (RuntimeException unavailable) {
            version = null;
        }
        latestCache = new LatestCache(version, now);
        return version;
    }

    // ----------------------------------------------------------------- install

    public InstallResult install(String ownerId) {
        Path archive = null;
        boolean temporaryArchive = false;
        try {
            String requested = Optional.ofNullable(registration.latestCliVersion())
                    .map(LarkCliProcessGateway::normalizeVersionTag)
                    .orElse(LarkSkillCatalog.FALLBACK_VERSION);
            LarkCliGateway.Probe probe = cli.probe();
            String current = numericVersion(probe.version());
            if (!probe.available() || current == null) {
                try {
                    probe = cli.ensureManaged(requested);
                } catch (IOException failure) {
                    if (!probe.available()) {
                        String detail = failure.getMessage() != null && !failure.getMessage().isBlank()
                                ? failure.getMessage()
                                : probe.error() != null ? probe.error()
                                : "lark-cli is not available on the Gateway";
                        throw new LarkCliUnavailableException(detail, failure);
                    }
                    LOG.log(System.Logger.Level.WARNING,
                            "Could not install the managed lark-cli; using the existing Gateway binary");
                }
            } else if (!requested.equals("v" + current)) {
                try {
                    probe = cli.ensureManaged(requested);
                } catch (IOException failure) {
                    LOG.log(System.Logger.Level.WARNING,
                            "Could not update the managed lark-cli; using the existing Gateway binary");
                }
            }
            String numeric = numericVersion(probe.version());
            String version = numeric != null ? "v" + numeric : LarkSkillCatalog.FALLBACK_VERSION;

            String explicit = System.getenv(LarkSkillCatalog.ARCHIVE_ENV);
            if (explicit == null || explicit.isBlank()) {
                explicit = System.getenv(LarkSkillCatalog.FROZEN_ARCHIVE_ENV);
            }
            if (explicit != null && !explicit.isBlank()) {
                archive = Path.of(explicit);
            } else {
                try {
                    archive = archives.fetch(version);
                } catch (IOException failure) {
                    throw new IllegalStateException("Could not obtain the Lark skill pack archive", failure);
                }
                temporaryArchive = true;
            }
            Path accepted = archive;
            LarkSkillPackInstaller.Installed installed;
            try {
                installed = packs.install(accepted, version, skills::reload);
            } catch (IOException failure) {
                throw new IllegalStateException("Lark integration install failed", failure);
            }
            String message = "Installed " + installed.names().size() + " Lark/Feishu skills.";
            if (installed.contentChanged()) {
                message += " Skill content changed since the previous install.";
            }
            return new InstallResult(true, List.copyOf(installed.names()), message, status(ownerId));
        } finally {
            if (temporaryArchive && archive != null) {
                try {
                    Files.deleteIfExists(archive);
                } catch (IOException ignored) {
                    // Best-effort temporary archive cleanup.
                }
            }
        }
    }

    // ---------------------------------------------------------- app registration

    public ConfigStart startConfig(String ownerId, String brand) {
        String parsedBrand = normalizeBrand(brand);
        String generation = withUserLock(ownerId, () -> advanceGenerationLocked(ownerId));
        LarkAppRegistration.Begin begin = registration.begin(parsedBrand);
        String verificationUrl = verificationUrl(parsedBrand, begin.userCode());
        return new ConfigStart(verificationUrl, begin.deviceCode(), generation, begin.expiresIn(), begin.interval(),
                begin.userCode(), parsedBrand);
    }

    public ConfigComplete completeConfig(String ownerId, String deviceCode, String generation, String brand,
            Integer interval, Integer expiresIn) {
        String code = deviceCode == null ? "" : deviceCode.strip();
        if (code.isEmpty()) {
            throw new IllegalArgumentException("device_code is required.");
        }
        String parsedBrand = normalizeBrand(brand);
        return withUserLock(ownerId, () -> {
            String current = requireGenerationLocked(ownerId, generation);
            LarkAppRegistration.Poll approved = pollAppRegistration(code, parsedBrand, interval, expiresIn);
            if (blank(approved.appSecret()) && "lark".equals(approved.tenantBrand())) {
                // Frozen behaviour: the Feishu accounts host can omit client_secret for a Lark
                // tenant; polling the Lark accounts host with the same device code completes it.
                approved = pollAppRegistration(code, "lark", interval, expiresIn);
            }
            String appId = approved.appId() == null ? "" : approved.appId().strip();
            String appSecret = approved.appSecret() == null ? "" : approved.appSecret().strip();
            String finalBrand = approved.tenantBrand() != null ? approved.tenantBrand() : parsedBrand;
            if (appId.isEmpty() || appSecret.isEmpty()) {
                throw new IllegalArgumentException(
                        "Lark app registration succeeded but did not return app credentials.");
            }
            requireGenerationLocked(ownerId, current);
            replaceCredentialsLocked(ownerId, appId, appSecret, finalBrand);
            return new ConfigComplete(true, "Lark/Feishu connection setup completed.", current, status(ownerId));
        });
    }

    public ConfigComplete switchCredentials(String ownerId, String appId, String appSecret, String brand) {
        String id = appId == null ? "" : appId.strip();
        String secret = appSecret == null ? "" : appSecret.strip();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("app_id is required.");
        }
        if (secret.isEmpty()) {
            throw new IllegalArgumentException("app_secret is required.");
        }
        String parsedBrand = brand == null ? "feishu" : brand.strip().toLowerCase(Locale.ROOT);
        if (!parsedBrand.equals("feishu") && !parsedBrand.equals("lark")) {
            throw new IllegalArgumentException("brand must be feishu or lark.");
        }
        return withUserLock(ownerId, () -> {
            validateCredentialsWithCli(ownerId, id, secret, parsedBrand);
            String generation = advanceGenerationLocked(ownerId);
            replaceCredentialsLocked(ownerId, id, secret, parsedBrand);
            return new ConfigComplete(true, "Lark/Feishu app switched. Reconnect to authorize the new app.",
                    generation, status(ownerId));
        });
    }

    // ------------------------------------------------------------ browser auth

    public AuthStart startAuth(String ownerId, boolean recommend, List<String> domains, String scope,
            String generation) {
        String checkedScope = scope == null || scope.isBlank() ? null : scope.strip();
        if (checkedScope != null && !SAFE_SCOPE.matcher(checkedScope).matches()) {
            throw new IllegalArgumentException("scope contains unsupported characters.");
        }
        List<String> checkedDomains = new ArrayList<>();
        for (String domain : domains == null ? List.<String>of() : domains) {
            if (domain == null || domain.isBlank()) {
                continue;
            }
            String stripped = domain.strip();
            if (!SAFE_DOMAIN.matcher(stripped).matches()) {
                throw new IllegalArgumentException("domains contain an unsupported value.");
            }
            checkedDomains.add(stripped);
        }
        return withUserLock(ownerId, () -> {
            String current;
            if (generation == null) {
                current = advanceGenerationLocked(ownerId);
            } else {
                current = requireGenerationLocked(ownerId, generation);
            }
            String path = requireCliPath();
            List<String> argv = new ArrayList<>(List.of(path, "auth", "login", "--no-wait", "--json"));
            if (recommend) {
                argv.add("--recommend");
            }
            if (checkedScope != null) {
                argv.add("--scope");
                argv.add(checkedScope);
            }
            for (String domain : checkedDomains) {
                argv.add("--domain");
                argv.add(domain);
            }
            JsonNode data = runJson(ownerId, argv, AUTH_START_TIMEOUT, false);
            String verificationUrl = text(data, "verification_url");
            if (verificationUrl.isBlank()) {
                verificationUrl = text(data, "verification_uri_complete");
            }
            String deviceCode = text(data, "device_code");
            if (verificationUrl.isBlank() || deviceCode.isBlank()) {
                throw new IllegalArgumentException("lark-cli did not return a verification_url and device_code.");
            }
            return new AuthStart(verificationUrl, deviceCode, current, integerOrNull(data, "expires_in"),
                    blank(text(data, "user_code")) ? null : text(data, "user_code"),
                    blank(text(data, "hint")) ? null : text(data, "hint"));
        });
    }

    public AuthComplete completeAuth(String ownerId, String deviceCode, String generation, int waitTimeoutSeconds) {
        String code = deviceCode == null ? "" : deviceCode.strip();
        if (code.isEmpty()) {
            throw new IllegalArgumentException("device_code is required.");
        }
        if (waitTimeoutSeconds < AUTH_COMPLETE_MIN_WAIT_SECONDS
                || waitTimeoutSeconds > AUTH_COMPLETE_MAX_WAIT_SECONDS) {
            throw new IllegalArgumentException("wait_timeout_seconds must be between "
                    + AUTH_COMPLETE_MIN_WAIT_SECONDS + " and " + AUTH_COMPLETE_MAX_WAIT_SECONDS + ".");
        }
        return withUserLock(ownerId, () -> {
            requireGenerationLocked(ownerId, generation);
            String path = requireCliPath();
            runJson(ownerId, List.of(path, "auth", "login", "--device-code", code, "--json"),
                    Duration.ofSeconds(waitTimeoutSeconds), true);
            Status verified = status(ownerId);
            AuthState auth = probeAuth(ownerId, cli.probe(), true);
            Status withVerification = new Status(verified.installed(), verified.version(),
                    verified.manifestVersion(), verified.latestAvailableVersion(),
                    verified.runtimeVersionMismatch(), verified.appConfigured(), verified.appId(),
                    verified.appBrand(), verified.skillsExpected(), verified.skillsInstalled(),
                    verified.installedSkills(), verified.enabledSkills(), verified.installPath(),
                    verified.cli(), auth, verified.sandboxRuntimeMode(), verified.sandboxRuntimeReady(),
                    verified.sandboxRuntimeDetail());
            boolean success = "authenticated".equals(auth.status());
            String message = success ? "Lark/Feishu authorization completed."
                    : (blank(auth.message()) ? "Lark/Feishu authorization status is still pending." : auth.message());
            return new AuthComplete(success, message, withVerification);
        });
    }

    // --------------------------------------------------------------- internals

    private LarkAppRegistration.Poll pollAppRegistration(String deviceCode, String brand, Integer interval,
            Integer expiresIn) {
        long deadline = nanoTime.getAsLong()
                + Math.min(Math.max(expiresIn == null ? 1 : expiresIn, 1), CONFIG_POLL_TIMEOUT.toSeconds())
                * 1_000_000_000L;
        long pollSeconds = Math.max(1, Math.min(interval == null ? 5 : interval, 10));
        String lastDetail = "authorization_pending";
        while (nanoTime.getAsLong() < deadline) {
            LarkAppRegistration.PollOutcome outcome = registration.pollOnce(deviceCode, brand);
            switch (outcome.kind()) {
                case APPROVED -> {
                    return outcome.approved();
                }
                case PENDING -> {
                    long remaining = (deadline - nanoTime.getAsLong()) / 1_000_000_000L;
                    if (remaining <= 0) {
                        break;
                    }
                    sleeper.accept(Duration.ofSeconds(Math.min(pollSeconds, remaining)));
                }
                case SLOW_DOWN -> {
                    pollSeconds = Math.min(pollSeconds + 5, 30);
                    long remaining = (deadline - nanoTime.getAsLong()) / 1_000_000_000L;
                    if (remaining <= 0) {
                        break;
                    }
                    sleeper.accept(Duration.ofSeconds(Math.min(pollSeconds, remaining)));
                }
                case ERROR -> throw new IllegalArgumentException(
                        blank(outcome.description()) ? "Lark app registration is still pending."
                                : outcome.description());
            }
            lastDetail = outcome.description() == null ? lastDetail : outcome.description();
        }
        throw new LarkTimeoutException("Lark app registration is still pending: " + lastDetail);
    }

    private AuthState probeAuth(String ownerId, LarkCliGateway.Probe probe, boolean verify) {
        if (!probe.available()) {
            return new AuthState("unavailable", "lark-cli is not installed on the Gateway", null, false);
        }
        if (!readAppCredentials(ownerId).configured()) {
            return new AuthState("not_configured", "Lark app is not configured", null, false);
        }
        String path = probe.path();
        if (path == null) {
            return new AuthState("unavailable", "lark-cli is not installed on the Gateway", null, false);
        }
        List<String> argv = verify
                ? List.of(path, "auth", "status", "--json", "--verify")
                : List.of(path, "auth", "status", "--json");
        LarkCliGateway.ExecResult result;
        try {
            result = cli.run(argv, cliEnvironment(ownerId), AUTH_STATUS_TIMEOUT, null);
        } catch (LarkTimeoutException timeout) {
            return new AuthState("error", "lark-cli auth status timed out", null, false);
        } catch (IOException | RuntimeException failure) {
            return new AuthState("error", failure.getClass().getSimpleName(), null, false);
        }
        String raw = !result.stdout().isBlank() ? result.stdout() : result.stderr();
        String text = raw.strip();
        JsonNode data = parseJsonObject(text);
        if (result.exitCode() != 0) {
            String message = data != null ? authErrorMessage(data) : null;
            return new AuthState("not_authorized",
                    blank(message) ? (blank(text) ? "Lark user authorization is not configured" : clipped(text))
                            : message,
                    null, false);
        }
        String user = null;
        if (data != null) {
            JsonNode identities = data.get("identities");
            if (identities != null && identities.isObject()) {
                JsonNode userInfo = identities.get("user");
                if (userInfo != null && userInfo.isObject()) {
                    user = firstText(userInfo, "userName", "openId");
                }
            }
            if (user == null) {
                String userName = text(data, "userName");
                user = userName.isBlank() ? null : userName;
            }
        }
        if (verify) {
            return new AuthState("authenticated", "Lark/Feishu authorization is live-verified.", user, true);
        }
        return new AuthState("authenticated",
                "Lark/Feishu credentials are configured locally but not live-verified.", user, false);
    }

    private void validateCredentialsWithCli(String ownerId, String appId, String appSecret, String brand) {
        Path temporary = null;
        try {
            ensureUserTree(ownerId);
            temporary = Files.createTempDirectory(userRoot(ownerId), ".validating-lark-app-");
            harden(temporary);
            Path config = Files.createDirectories(temporary.resolve("config"));
            Path data = Files.createDirectories(temporary.resolve("data"));
            harden(config);
            harden(data);
            String path = requireCliPath();
            LarkCliGateway.ExecResult result = cli.run(
                    List.of(path, "config", "init", "--app-id", appId, "--app-secret-stdin", "--brand", brand),
                    directoriesEnvironment(config, data), Duration.ofSeconds(15), appSecret + "\n");
            if (result.exitCode() != 0) {
                String raw = !result.stderr().isBlank() ? result.stderr() : result.stdout();
                JsonNode parsed = parseJsonObject(raw.strip());
                String message = parsed != null ? authErrorMessage(parsed) : null;
                throw new IllegalArgumentException(blank(message)
                        ? (blank(raw) ? "lark-cli config init exited with code " + result.exitCode() : clipped(raw))
                        : message);
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("Could not validate the Lark app credentials.", failure);
        } finally {
            if (temporary != null) {
                deleteTree(temporary);
            }
        }
    }

    private void replaceCredentialsLocked(String ownerId, String appId, String appSecret, String brand) {
        ensureUserTree(ownerId);
        Path snapshot = null;
        try {
            snapshot = Files.createTempDirectory(userRoot(ownerId), ".switching-lark-app-");
            harden(snapshot);
            for (String name : List.of("config", "data")) {
                Path target = snapshot.resolve(name);
                Files.createDirectories(target);
                harden(target);
                copyTree(Path.of(userRoot(ownerId).toString(), name), target);
            }
            clearDirectory(userDataDirectory(ownerId));
            String path = requireCliPath();
            LarkCliGateway.ExecResult result = cli.run(
                    List.of(path, "config", "init", "--app-id", appId, "--app-secret-stdin", "--brand", brand),
                    cliEnvironment(ownerId), Duration.ofSeconds(15), appSecret + "\n");
            if (result.exitCode() != 0) {
                String raw = !result.stderr().isBlank() ? result.stderr() : result.stdout();
                JsonNode parsed = parseJsonObject(raw.strip());
                String message = parsed != null ? authErrorMessage(parsed) : null;
                throw new IllegalArgumentException(blank(message)
                        ? (blank(raw) ? "lark-cli config init exited with code " + result.exitCode() : clipped(raw))
                        : message);
            }
            revokeAuthorization(snapshot);
        } catch (IOException failure) {
            restoreQuietly(ownerId, snapshot, failure);
            throw new IllegalStateException("Lark CLI credential update failed", failure);
        } catch (RuntimeException failure) {
            restoreQuietly(ownerId, snapshot, failure);
            throw failure;
        } finally {
            if (snapshot != null) {
                deleteTree(snapshot);
            }
            ensureUserTree(ownerId);
        }
    }

    private void revokeAuthorization(Path snapshot) throws IOException {
        if (!hasRegularFile(snapshot.resolve("data"))) {
            return;
        }
        String path = requireCliPath();
        LarkCliGateway.ExecResult result = cli.run(List.of(path, "auth", "logout", "--json"),
                directoriesEnvironment(snapshot.resolve("config"), snapshot.resolve("data")),
                Duration.ofSeconds(15), null);
        if (result.exitCode() != 0) {
            String raw = !result.stderr().isBlank() ? result.stderr() : result.stdout();
            JsonNode parsed = parseJsonObject(raw.strip());
            String message = parsed != null ? authErrorMessage(parsed) : null;
            throw new IllegalArgumentException(blank(message)
                    ? (blank(raw) ? "lark-cli auth logout exited with code " + result.exitCode() : clipped(raw))
                    : message);
        }
    }

    private void restoreQuietly(String ownerId, Path snapshot, Throwable failure) {
        if (snapshot == null) {
            return;
        }
        try {
            restoreCredentials(ownerId, snapshot);
        } catch (IOException restoreFailure) {
            failure.addSuppressed(restoreFailure);
        }
    }

    private void restoreCredentials(String ownerId, Path snapshot) throws IOException {
        for (String name : List.of("config", "data")) {
            Path target = userRoot(ownerId).resolve(name);
            clearDirectory(target);
            copyTree(snapshot.resolve(name), target);
        }
    }

    private void clearDirectory(Path directory) throws IOException {
        rejectSymlink(directory);
        Files.createDirectories(directory);
        try (var children = Files.list(directory)) {
            for (Path child : children.toList()) {
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(child)) {
                    deleteTree(child);
                } else {
                    Files.deleteIfExists(child);
                }
            }
        }
    }

    private void copyTree(Path source, Path destination) throws IOException {
        if (!Files.isDirectory(source)) {
            return;
        }
        rejectSymlink(source);
        try (var walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("Lark CLI credential path must not be a symlink: " + path);
                }
                Path target = destination.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private boolean hasRegularFile(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (var walk = Files.walk(directory)) {
            return walk.anyMatch(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS));
        }
    }

    private void ensureUserTree(String ownerId) {
        try {
            Path users = integrationRoot.resolve("users");
            Files.createDirectories(users);
            rejectSymlink(users);
            Path root = userRoot(ownerId);
            Files.createDirectories(root);
            rejectSymlink(root);
            Path config = Files.createDirectories(userConfigDirectory(ownerId));
            Path data = Files.createDirectories(userDataDirectory(ownerId));
            harden(root);
            harden(config);
            harden(data);
        } catch (IOException failure) {
            throw new IllegalStateException("Lark CLI credential directory is unavailable", failure);
        }
    }

    private void harden(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("Lark CLI credential path must not be a symlink: " + path);
        }
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("acl")) {
            AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (view != null) {
                var lookup = path.getFileSystem().getUserPrincipalLookupService();
                var owner = lookup.lookupPrincipalByName(System.getProperty("user.name", ""));
                view.setOwner(owner);
                AclEntry allow = AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(owner)
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                        .build();
                view.setAcl(List.of(allow));
                return;
            }
        }
        try {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.setPosixFilePermissions(path, Set.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
            } else {
                Files.setPosixFilePermissions(path, Set.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            }
        } catch (UnsupportedOperationException unsupported) {
            // The platform has neither ACL nor POSIX modes; the data root boundary still applies.
        }
    }

    private static void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("Lark CLI credential path must not be a symlink: " + path);
        }
    }

    private <T> T withUserLock(String ownerId, LockedWork<T> work) {
        ReentrantLock lock = userLocks.computeIfAbsent(ownerId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            Path locks = Files.createDirectories(integrationRoot.resolve(".locks"));
            Path lockFile = locks.resolve(ownerSegment(ownerId) + ".lock");
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
                    FileLock ignored = channel.lock()) {
                return work.run();
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Lark integration state is unavailable", failure);
        } finally {
            lock.unlock();
        }
    }

    private String advanceGenerationLocked(String ownerId) {
        ensureUserTree(ownerId);
        String generation = UUID.randomUUID().toString().replace("-", "");
        writeGeneration(ownerId, generation);
        return generation;
    }

    private String requireGenerationLocked(String ownerId, String generation) {
        String expected = generation == null ? "" : generation.strip();
        if (!GENERATION.matcher(expected).matches() || !expected.equals(readGeneration(ownerId))) {
            throw new LarkFlowSupersededException();
        }
        return expected;
    }

    private void writeGeneration(String ownerId, String generation) {
        try {
            ensureUserTree(ownerId);
            Path target = userRoot(ownerId).resolve(".flow.json");
            Path temporary = Files.createTempFile(userRoot(ownerId), ".flow-", ".tmp");
            try {
                ObjectNode node = json.createObjectNode();
                node.put("generation", generation);
                Files.writeString(temporary, json.writeValueAsString(node) + "\n", StandardCharsets.UTF_8);
                harden(temporary);
                AtomicMoves.replace(temporary, target);
            } finally {
                AtomicMoves.deleteQuietly(temporary);
            }
            harden(target);
        } catch (IOException failure) {
            throw new IllegalStateException("Lark integration flow state is unavailable", failure);
        }
    }

    private String readGeneration(String ownerId) {
        Path path = userRoot(ownerId).resolve(".flow.json");
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            JsonNode node = json.readTree(path.toFile());
            return node != null && node.path("generation").isTextual() ? node.path("generation").asText() : null;
        } catch (IOException invalid) {
            return null;
        }
    }

    private AppCredentials readAppCredentials(String ownerId) {
        Path path = userConfigDirectory(ownerId).resolve("config.json");
        if (!Files.isRegularFile(path)) {
            return AppCredentials.NONE;
        }
        try {
            JsonNode node = json.readTree(path.toFile());
            if (node == null || !node.isObject()) {
                return AppCredentials.NONE;
            }
            JsonNode apps = node.path("apps");
            if (!apps.isArray() || apps.isEmpty()) {
                return AppCredentials.NONE;
            }
            JsonNode app = null;
            String current = node.path("currentApp").asText("");
            if (!current.isBlank()) {
                for (JsonNode candidate : apps) {
                    if (candidate.isObject() && (current.equals(candidate.path("name").asText())
                            || current.equals(candidate.path("appId").asText()))) {
                        app = candidate;
                        break;
                    }
                }
            }
            if (app == null && apps.get(0).isObject()) {
                app = apps.get(0);
            }
            if (app == null) {
                return AppCredentials.NONE;
            }
            String appId = app.path("appId").asText("").strip();
            String appSecret = app.path("appSecret").asText("");
            String brand = app.path("brand").asText("feishu").strip();
            return new AppCredentials(!appId.isEmpty() && !appSecret.isEmpty(), appId.isEmpty() ? null : appId,
                    brand.isEmpty() ? "feishu" : brand);
        } catch (IOException invalid) {
            return AppCredentials.NONE;
        }
    }

    private JsonNode runJson(String ownerId, List<String> argv, Duration timeout, boolean allowEmpty) {
        LarkCliGateway.ExecResult result;
        try {
            result = cli.run(argv, cliEnvironment(ownerId), timeout, null);
        } catch (LarkTimeoutException timeoutFailure) {
            throw new LarkTimeoutException(
                    "Timed out waiting for Lark/Feishu authorization. Complete authorization in the browser, then try again.",
                    timeoutFailure);
        } catch (IOException failure) {
            throw new IllegalStateException("lark-cli could not be executed", failure);
        } finally {
            ensureUserTree(ownerId);
        }
        String raw = !result.stdout().isBlank() ? result.stdout() : result.stderr();
        String text = raw.strip();
        JsonNode parsed = parseJsonObject(text);
        if (result.exitCode() != 0) {
            String message = parsed != null ? authErrorMessage(parsed) : null;
            throw new IllegalArgumentException(blank(message)
                    ? (blank(text) ? "lark-cli exited with code " + result.exitCode() : clipped(text))
                    : message);
        }
        if (text.isEmpty() && allowEmpty) {
            return json.createObjectNode();
        }
        if (parsed == null) {
            if (allowEmpty) {
                return json.createObjectNode();
            }
            throw new IllegalArgumentException(blank(text) ? "lark-cli did not return JSON output." : clipped(text));
        }
        return parsed;
    }

    private Map<String, String> cliEnvironment(String ownerId) {
        ensureUserTree(ownerId);
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("LARKSUITE_CLI_CONFIG_DIR", userConfigDirectory(ownerId).toString());
        environment.put("LARKSUITE_CLI_DATA_DIR", userDataDirectory(ownerId).toString());
        environment.put("LARKSUITE_CLI_NO_UPDATE_NOTIFIER", "1");
        environment.put("LARKSUITE_CLI_NO_SKILLS_NOTIFIER", "1");
        return environment;
    }

    private static Map<String, String> directoriesEnvironment(Path config, Path data) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("LARKSUITE_CLI_CONFIG_DIR", config.toString());
        environment.put("LARKSUITE_CLI_DATA_DIR", data.toString());
        environment.put("LARKSUITE_CLI_NO_UPDATE_NOTIFIER", "1");
        environment.put("LARKSUITE_CLI_NO_SKILLS_NOTIFIER", "1");
        return environment;
    }

    private String requireCliPath() {
        LarkCliGateway.Probe probe = cli.probe();
        if (!probe.available() || probe.path() == null) {
            throw new LarkCliUnavailableException("lark-cli is not installed on the Gateway. Install the managed "
                    + "Lark integration as an admin, or rebuild the Gateway image with "
                    + LarkSkillCatalog.NPM_PACKAGE + " installed.");
        }
        return probe.path();
    }

    private String verificationUrl(String brand, String userCode) {
        String runtimeVersion = numericVersion(cli.probe().version());
        String version = runtimeVersion != null ? "v" + runtimeVersion : LarkSkillCatalog.FALLBACK_VERSION;
        return LarkHttpAppRegistration.openUrl(brand) + "/page/cli?user_code="
                + java.net.URLEncoder.encode(userCode, StandardCharsets.UTF_8)
                + "&lpv=" + java.net.URLEncoder.encode(version, StandardCharsets.UTF_8)
                + "&ocv=" + java.net.URLEncoder.encode(version, StandardCharsets.UTF_8)
                + "&from=cli";
    }

    static String normalizeBrand(String brand) {
        return brand != null && brand.strip().toLowerCase(Locale.ROOT).equals("lark") ? "lark" : "feishu";
    }

    static String ownerSegment(String ownerId) {
        String value = ownerId == null || ownerId.isBlank() ? "default" : ownerId;
        if (SAFE_OWNER.matcher(value).matches()) {
            return value;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return "u-" + HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static boolean versionsDrifted(String manifestVersion, String cliVersion) {
        String left = numericVersion(manifestVersion);
        String right = numericVersion(cliVersion);
        return left != null && right != null && !left.equals(right);
    }

    static String numericVersion(String value) {
        if (value == null) {
            return null;
        }
        var matcher = NUMERIC_VERSION.matcher(value);
        return matcher.find() ? matcher.group() : null;
    }

    private JsonNode parseJsonObject(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            JsonNode node = json.readTree(text);
            return node != null && node.isObject() ? node : null;
        } catch (IOException invalid) {
            return null;
        }
    }

    private static String authErrorMessage(JsonNode data) {
        if (data == null) {
            return null;
        }
        JsonNode error = data.get("error");
        if (error != null && error.isObject()) {
            String value = firstText(error, "message", "hint", "type");
            if (value != null) {
                return value;
            }
        }
        return firstText(data, "message", "msg", "hint");
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull() && !value.asText("").isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static Integer integerOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.canConvertToInt() ? value.asInt() : null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String clipped(String value) {
        String single = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').strip();
        return single.length() > MAX_CLI_DIAGNOSTIC_CHARS
                ? single.substring(0, MAX_CLI_DIAGNOSTIC_CHARS) + "..."
                : single;
    }

    private static void deleteTree(Path path) {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(candidate -> {
                try {
                    Files.deleteIfExists(candidate);
                } catch (IOException ignored) {
                    // Best-effort private staging cleanup.
                }
            });
        } catch (IOException ignored) {
            // Best-effort private staging cleanup.
        }
    }

    private record LatestCache(String version, long atNanos) {}

    private record AppCredentials(boolean configured, String appId, String brand) {
        static final AppCredentials NONE = new AppCredentials(false, null, null);
    }

    @FunctionalInterface
    private interface LockedWork<T> {
        T run();
    }
}
