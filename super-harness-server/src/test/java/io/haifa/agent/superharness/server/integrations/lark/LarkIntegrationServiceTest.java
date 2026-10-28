package io.haifa.agent.superharness.server.integrations.lark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.superharness.server.config.ExecutionProvider;
import io.haifa.agent.superharness.server.config.SkillConfigStore;
import io.haifa.agent.superharness.server.testsupport.LarkIntegrationFakes;
import io.haifa.agent.superharness.server.testsupport.LarkIntegrationFakes.FakeArchives;
import io.haifa.agent.superharness.server.testsupport.LarkIntegrationFakes.FakeCli;
import io.haifa.agent.superharness.server.testsupport.LarkIntegrationFakes.FakeRegistration;
import io.haifa.agent.superharness.server.testsupport.LarkIntegrationFakes.VirtualClock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Offline contract and safety coverage for the Lark office integration product flow. */
class LarkIntegrationServiceTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path dataDirectory;

    private SkillConfigStore skills;
    private FakeCli cli;
    private FakeRegistration registration;
    private FakeArchives archives;
    private VirtualClock clock;
    private LarkIntegrationService service;
    private Path archive;

    @BeforeEach
    void setUp() throws Exception {
        skills = new SkillConfigStore(json, dataDirectory, SkillConfigStore.repositoryRootFromWorkingDirectory());
        cli = new FakeCli(false);
        registration = new FakeRegistration();
        archive = LarkIntegrationFakes.archive(dataDirectory.resolve("fixture-v1.0.65.zip"), "v1.0.65",
                LarkIntegrationFakes.allNames(), null);
        archives = new FakeArchives(archive);
        clock = new VirtualClock();
        service = service(skills, cli, registration, archives, clock);
    }

    private LarkIntegrationService service(SkillConfigStore store, FakeCli gateway,
            FakeRegistration registration, FakeArchives archives, VirtualClock clock) {
        return service(store, gateway, registration, archives, clock, ExecutionProvider.DISABLED);
    }

    private LarkIntegrationService service(SkillConfigStore store, FakeCli gateway,
            FakeRegistration registration, FakeArchives archives, VirtualClock clock, ExecutionProvider provider) {
        Path root = dataDirectory.resolve("integrations").resolve("lark");
        return new LarkIntegrationService(json, dataDirectory, store, new LarkSkillPackInstaller(json, root),
                gateway, registration, archives, clock::nanoTime, clock::sleep, provider);
    }

    @Test
    void statusBeforeInstallIsTruthfulAndRedacted() {
        LarkIntegrationService.Status status = service.status("alice");
        assertThat(status.installed()).isFalse();
        assertThat(status.version()).isEqualTo(LarkSkillCatalog.FALLBACK_VERSION);
        assertThat(status.manifestVersion()).isNull();
        assertThat(status.skillsExpected()).isEqualTo(27);
        assertThat(status.skillsInstalled()).isZero();
        assertThat(status.installedSkills()).isEmpty();
        assertThat(status.enabledSkills()).isEmpty();
        assertThat(status.cli().available()).isFalse();
        assertThat(status.cli().error()).isNotBlank();
        assertThat(status.auth().status()).isEqualTo("unavailable");
        assertThat(status.sandboxRuntimeMode()).isEqualTo("none");
        assertThat(status.sandboxRuntimeReady()).isFalse();
        assertThat(status.installPath()).isEqualTo(service.integrationRoot().resolve("skills").toString());

        LarkIntegrationService.Status redacted = status.redacted();
        assertThat(redacted.installPath()).isEmpty();
        assertThat(redacted.cli().path()).isNull();
        assertThat(redacted.cli().available()).isFalse();
    }

    @Test
    void installMaterializesExactTwentySevenSkillsAndKeepsUserAuthorization() throws Exception {
        Files.createDirectories(service.userDataDirectory("alice"));
        Files.writeString(service.userDataDirectory("alice").resolve("token.json"), "{\"keep\":true}");

        LarkIntegrationService.InstallResult first = service.install("alice");
        assertThat(first.success()).isTrue();
        assertThat(first.installedSkills()).hasSize(27)
                .containsExactlyInAnyOrderElementsOf(LarkSkillCatalog.NAMES);
        assertThat(first.installedSkills()).doesNotHaveDuplicates();
        assertThat(first.message()).isEqualTo("Installed 27 Lark/Feishu skills.");
        assertThat(first.status().installed()).isTrue();
        assertThat(first.status().manifestVersion()).isEqualTo("v1.0.65");
        assertThat(first.status().skillsInstalled()).isEqualTo(27);
        assertThat(Files.readString(service.userDataDirectory("alice").resolve("token.json")))
                .contains("keep");

        Set<String> registered = skills.list().stream()
                .map(row -> String.valueOf(row.get("name")))
                .filter(LarkSkillCatalog.NAME_SET::contains)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(registered).hasSize(27).containsExactlyInAnyOrderElementsOf(LarkSkillCatalog.NAMES);
        assertThat(skills.list().stream()
                .filter(row -> LarkSkillCatalog.NAME_SET.contains(String.valueOf(row.get("name"))))
                .allMatch(row -> Boolean.TRUE.equals(row.get("enabled")))).isTrue();

        LarkIntegrationService.InstallResult second = service.install("alice");
        assertThat(second.success()).isTrue();
        assertThat(second.message()).isEqualTo("Installed 27 Lark/Feishu skills.");
        assertThat(second.status().skillsInstalled()).isEqualTo(27);
        assertThat(Files.readString(service.userDataDirectory("alice").resolve("token.json")))
                .contains("keep");
    }

    @Test
    void reinstallDetectsContentChangeAndCorruptArchiveRestoresPreviousPack() throws Exception {
        service.install("alice");
        String firstDigest = service.status("alice").version();

        Path changed = LarkIntegrationFakes.archive(dataDirectory.resolve("fixture-changed.zip"), "v1.0.65",
                LarkIntegrationFakes.allNames(), "<!-- changed guidance -->");
        archives.archive(changed);
        LarkIntegrationService.InstallResult reinstalled = service.install("alice");
        assertThat(reinstalled.message())
                .isEqualTo("Installed 27 Lark/Feishu skills. Skill content changed since the previous install.");
        assertThat(reinstalled.status().version()).isEqualTo(firstDigest);

        Path corrupt = LarkIntegrationFakes.archive(dataDirectory.resolve("fixture-corrupt.zip"), "v1.0.65",
                LarkIntegrationFakes.allNames().subList(0, 26), null);
        archives.archive(corrupt);
        assertThatThrownBy(() -> service.install("alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing required skills");

        LarkIntegrationService.Status after = service.status("alice");
        assertThat(after.installed()).isTrue();
        assertThat(after.skillsInstalled()).isEqualTo(27);
        assertThat(after.manifestVersion()).isEqualTo("v1.0.65");
        assertThat(service.userExecutionEnvironment("alice").get("LARKSUITE_CLI_CONFIG_DIR"))
                .endsWith("users" + java.io.File.separator + "alice" + java.io.File.separator + "config");
    }

    @Test
    void installAlignsPackVersionToRuntimeCliAndReportsDrift() throws Exception {
        cli.makeAvailable("1.0.66 (lark-cli)");
        registration.latest("v1.0.66");
        Path aligned = LarkIntegrationFakes.archive(dataDirectory.resolve("fixture-1.0.66.zip"), "v1.0.66",
                LarkIntegrationFakes.allNames(), null);
        archives.archive(aligned);

        LarkIntegrationService.InstallResult result = service.install("alice");
        assertThat(result.status().manifestVersion()).isEqualTo("v1.0.66");
        assertThat(result.status().runtimeVersionMismatch()).isFalse();

        cli.makeAvailable("1.0.67 (lark-cli)");
        LarkIntegrationService.Status drifted = service.status("alice");
        assertThat(drifted.runtimeVersionMismatch()).isTrue();
        assertThat(drifted.latestAvailableVersion()).isEqualTo("v1.0.66");
    }

    @Test
    void generationIsPerUserAndLateFlowsAreSuperseded() {
        cli.makeAvailable("1.0.65");
        LarkIntegrationService.ConfigStart first = service.startConfig("alice", "feishu");
        assertThat(first.generation()).isNotBlank();
        assertThat(first.deviceCode()).isEqualTo("DEVICE-CODE");
        assertThat(first.brand()).isEqualTo("feishu");

        assertThatThrownBy(() -> service.completeConfig("bob", "DEVICE-CODE", first.generation(), "feishu", 5, 600))
                .isInstanceOf(LarkFlowSupersededException.class);

        LarkIntegrationService.ConfigStart second = service.startConfig("alice", "feishu");
        assertThat(second.generation()).isNotEqualTo(first.generation());
        assertThatThrownBy(() -> service.completeConfig("alice", "DEVICE-CODE", first.generation(), "feishu", 5, 600))
                .isInstanceOf(LarkFlowSupersededException.class);

        registration.approved("cli_alice", "secret-alice", "feishu");
        LarkIntegrationService.ConfigComplete completed = service.completeConfig(
                "alice", "DEVICE-CODE", second.generation(), "feishu", 5, 600);
        assertThat(completed.success()).isTrue();
        assertThat(completed.message()).isEqualTo("Lark/Feishu connection setup completed.");
        assertThat(completed.status().appConfigured()).isTrue();
        assertThat(completed.status().appId()).isEqualTo("cli_alice");
        assertThat(completed.status().auth().status()).isEqualTo("authenticated");

        assertThat(service.status("bob").appConfigured()).isFalse();
        assertThat(service.userConfigDirectory("bob")).doesNotExist();
        assertThat(completed.toString()).doesNotContain("secret-alice");
    }

    @Test
    void configCompleteTimesOutAndSurfacesTerminalErrors() {
        registration.pending(1_000);
        LarkIntegrationService.ConfigStart pending = service.startConfig("alice", "feishu");
        assertThatThrownBy(() -> service.completeConfig("alice", "DEVICE-CODE", pending.generation(), "feishu", 5, 600))
                .isInstanceOf(LarkTimeoutException.class)
                .hasMessageContaining("still pending");

        registration.clear();
        registration.error("access_denied", "The user denied the request.");
        LarkIntegrationService.ConfigStart denied = service.startConfig("alice", "feishu");
        assertThatThrownBy(() -> service.completeConfig("alice", "DEVICE-CODE", denied.generation(), "feishu", 5, 600))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("denied");
    }

    @Test
    void switchCredentialsValidatesRevokesOldAuthorizationAndNeverPutsSecretInArgv() throws Exception {
        cli.makeAvailable("1.0.65");
        service.startConfig("alice", "feishu");
        String current = service.status("alice").version();
        assertThat(current).isNotBlank();

        Files.createDirectories(service.userConfigDirectory("alice"));
        Files.writeString(service.userConfigDirectory("alice").resolve("config.json"),
                "{\"apps\":[{\"name\":\"old\",\"appId\":\"cli_old\",\"appSecret\":\"old-secret\",\"brand\":\"feishu\"}],"
                        + "\"currentApp\":\"old\"}",
                StandardCharsets.UTF_8);
        Files.createDirectories(service.userDataDirectory("alice"));
        Files.writeString(service.userDataDirectory("alice").resolve("token.json"), "{\"token\":\"old\"}");

        LarkIntegrationService.ConfigComplete switched = service.switchCredentials(
                "alice", "cli_new", "brand-new-secret", "feishu");
        assertThat(switched.success()).isTrue();
        assertThat(switched.message()).isEqualTo("Lark/Feishu app switched. Reconnect to authorize the new app.");
        assertThat(switched.status().appId()).isEqualTo("cli_new");
        assertThat(switched.toString()).doesNotContain("brand-new-secret");
        assertThat(Files.readString(service.userConfigDirectory("alice").resolve("config.json")))
                .doesNotContain("old-secret");
        assertThat(service.userDataDirectory("alice").resolve("token.json")).doesNotExist();

        List<LarkIntegrationFakes.Call> calls = cli.calls();
        assertThat(calls).anyMatch(call -> call.argv().contains("logout"));
        assertThat(calls).allMatch(call -> !String.join("\u0000", call.argv()).contains("brand-new-secret"));
        assertThat(calls).anyMatch(call -> "brand-new-secret\n".equals(call.stdin()));
        assertThat(calls).allMatch(call -> call.environment().getOrDefault("LARKSUITE_CLI_CONFIG_DIR", "")
                .contains("alice"));
    }

    @Test
    void authStartUsesStructuredArgvAndRejectsUnsafeScopeOrDomain() throws Exception {
        cli.makeAvailable("1.0.65");
        Files.createDirectories(service.userConfigDirectory("alice"));
        Files.writeString(service.userConfigDirectory("alice").resolve("config.json"),
                "{\"apps\":[{\"name\":\"app\",\"appId\":\"cli_alice\",\"appSecret\":\"fake-secret\","
                        + "\"brand\":\"feishu\"}],\"currentApp\":\"app\"}");
        LarkIntegrationService.AuthStart started = service.startAuth("alice", true,
                List.of("calendar", "docs"), "docx:document im:message", null);
        assertThat(started.generation()).isNotBlank();
        assertThat(started.verificationUrl()).contains("open.feishu.cn");
        assertThat(started.deviceCode()).isEqualTo("device-abc");
        assertThat(started.hint()).isEqualTo("Open the link");

        List<String> argv = cli.calls().stream()
                .filter(call -> call.argv().contains("login"))
                .findFirst().orElseThrow().argv();
        assertThat(argv).containsSequence("--domain", "calendar");
        assertThat(argv).containsSequence("--domain", "docs");
        assertThat(argv).containsSequence("--scope", "docx:document im:message");
        assertThat(argv).contains("--recommend");

        assertThatThrownBy(() -> service.startAuth("alice", false, List.of(), "docx:document; rm -rf /", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope");
        assertThatThrownBy(() -> service.startAuth("alice", false, List.of("docs\"&calc"), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("domains");
        assertThatThrownBy(() -> service.startAuth("alice", false, List.of(), null, "bad generation!"))
                .isInstanceOf(LarkFlowSupersededException.class);

        LarkIntegrationService.AuthComplete completed = service.completeAuth(
                "alice", started.deviceCode(), started.generation(), 45);
        assertThat(completed.success()).isTrue();
        assertThat(completed.message()).isEqualTo("Lark/Feishu authorization completed.");
        assertThat(completed.status().auth().verified()).isTrue();
    }

    @Test
    void authCompleteWaitBoundsAreEnforced() {
        cli.makeAvailable("1.0.65");
        LarkIntegrationService.AuthStart started = service.startAuth("alice", false, List.of(), null, null);
        assertThatThrownBy(() -> service.completeAuth("alice", started.deviceCode(), started.generation(), 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 5 and 45");
        assertThatThrownBy(() -> service.completeAuth("alice", started.deviceCode(), started.generation(), 46))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 5 and 45");
    }

    @Test
    void executionEnvironmentSeparatesUsersAndNeverTouchesTheImChannelSecrets() {
        var alice = service.userExecutionEnvironment("alice");
        var bob = service.userExecutionEnvironment("bob");
        assertThat(alice.get("LARKSUITE_CLI_CONFIG_DIR")).isNotEqualTo(bob.get("LARKSUITE_CLI_CONFIG_DIR"));
        assertThat(alice).containsKeys("LARKSUITE_CLI_CONFIG_DIR", "LARKSUITE_CLI_DATA_DIR",
                "LARKSUITE_CLI_NO_UPDATE_NOTIFIER", "LARKSUITE_CLI_NO_SKILLS_NOTIFIER");
        assertThat(alice.values()).noneMatch(value -> value.contains("feishu-secret")
                || value.contains("channel"));
    }

    @Test
    void statusDistinguishesDisabledHostAndDockerExecutionProviders() {
        var disabled = service(skills, cli, registration, archives, clock, ExecutionProvider.DISABLED).status("alice");
        var host = service(skills, cli, registration, archives, clock, ExecutionProvider.HOST_GUARDED).status("alice");
        var docker = service(skills, cli, registration, archives, clock, ExecutionProvider.DOCKER_AIO).status("alice");

        assertThat(disabled.sandboxRuntimeMode()).isEqualTo("none");
        assertThat(disabled.sandboxRuntimeReady()).isFalse();
        assertThat(disabled.sandboxRuntimeDetail()).contains("disabled");

        assertThat(host.sandboxRuntimeMode()).isEqualTo("none");
        assertThat(host.sandboxRuntimeReady()).isFalse();
        assertThat(host.sandboxRuntimeDetail())
                .contains("Host guarded")
                .contains("shared")
                .contains("no OS tenant isolation");

        assertThat(docker.sandboxRuntimeMode()).isEqualTo("gateway-download");
        assertThat(docker.sandboxRuntimeReady()).isFalse();
        assertThat(docker.sandboxRuntimeDetail()).contains("not provisioned");
    }

    @Test
    void hostRunOverlayMatchesGatewayDirectoriesAndPrependsTheManagedCli() throws Exception {
        Path config = service.userConfigDirectory("alice");
        Path data = service.userDataDirectory("alice");
        Path managed = dataDirectory.resolve("integrations").resolve("lark").resolve("gateway-cli")
                .resolve("node_modules").resolve("@larksuite").resolve("cli").resolve("bin")
                .resolve(cliExecutableName());
        Files.createDirectories(managed.getParent());
        Files.writeString(managed, "");

        var overlay = LarkIntegrationService.hostExecutionEnvironment(dataDirectory, "alice");
        var gatewayOverlay = service.userExecutionEnvironment("alice");

        assertThat(overlay.get("LARKSUITE_CLI_CONFIG_DIR"))
                .isEqualTo(gatewayOverlay.get("LARKSUITE_CLI_CONFIG_DIR"))
                .isEqualTo(config.toString());
        assertThat(overlay.get("LARKSUITE_CLI_DATA_DIR"))
                .isEqualTo(gatewayOverlay.get("LARKSUITE_CLI_DATA_DIR"))
                .isEqualTo(data.toString());
        assertThat(overlay.get("LARKSUITE_CLI_NO_UPDATE_NOTIFIER")).isEqualTo("1");
        assertThat(overlay).doesNotContainKeys("HOME", "TEMP", "TMP", "SystemRoot", "USERPROFILE");
        assertThat(overlay.get("PATH").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))[0])
                .isEqualTo(managed.getParent().toString());
    }

    @Test
    void optionalExpiryUsesFrozenFortyFiveSecondBudget() {
        cli.makeAvailable("1.0.65");
        for (Integer expiry : java.util.Arrays.asList(null, 0)) {
            registration.pending(1); registration.approved("cli_alice", "fixture-secret", "feishu");
            var start = service.startConfig("alice", "feishu");
            long before = clock.nanoTime();
            assertThat(service.completeConfig("alice", start.deviceCode(), start.generation(), "feishu", null, expiry).success()).isTrue();
            assertThat(clock.nanoTime() - before).isEqualTo(java.time.Duration.ofSeconds(5).toNanos());
        }
        var start = service.startConfig("alice", "feishu"); long before = clock.nanoTime();
        assertThatThrownBy(() -> service.completeConfig("alice", start.deviceCode(), start.generation(), "feishu", null, null))
                .isInstanceOf(LarkTimeoutException.class);
        assertThat(clock.nanoTime() - before).isEqualTo(java.time.Duration.ofSeconds(45).toNanos());
    }

    @Test
    void stalledOldPollDoesNotBlockNewFlowAndCannotOverwriteNewCredentials() throws Exception {
        cli.makeAvailable("1.0.65");
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        LarkAppRegistration stalled = new LarkAppRegistration() {
            public Begin begin(String brand) { return registration.begin(brand); }
            public String latestCliVersion() { return "v1.0.65"; }
            public PollOutcome pollOnce(String code, String brand) {
                entered.countDown();
                try { if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("poll release deadline"); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                return PollOutcome.approved(new Poll("cli_old", "old-secret", "feishu"));
            }
        };
        var concurrent = new LarkIntegrationService(json, dataDirectory, skills,
                new LarkSkillPackInstaller(json, service.integrationRoot()), cli, stalled, archives, clock::nanoTime, clock::sleep);
        var first = concurrent.startConfig("alice", "feishu");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var old = executor.submit(() -> concurrent.completeConfig("alice", first.deviceCode(), first.generation(), "feishu", 5, 600));
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var switched = executor.submit(() -> concurrent.switchCredentials("alice", "cli_new", "new-secret", "feishu"));
            assertThat(switched.get(3, java.util.concurrent.TimeUnit.SECONDS).status().appId()).isEqualTo("cli_new");
            release.countDown();
            assertThatThrownBy(() -> old.get(3, java.util.concurrent.TimeUnit.SECONDS))
                    .hasCauseInstanceOf(LarkFlowSupersededException.class);
            assertThat(concurrent.status("alice").appId()).isEqualTo("cli_new");
        } finally { release.countDown(); }
    }

    @Test
    void existingOwnerResourcesRejectCrossedDeviceCodesAndSurviveRestart() throws Exception {
        cli.makeAvailable("1.0.65");
        service.switchCredentials("alice", "cli_alice", "secret-a", "feishu");
        service.switchCredentials("bob", "cli_bob", "secret-b", "feishu");
        byte[] victim = Files.readAllBytes(service.userConfigDirectory("bob").resolve("config.json"));
        registration.begin("A", "device-a"); var alice = service.startConfig("alice", "feishu");
        registration.begin("B", "device-b"); var bob = service.startConfig("bob", "feishu");
        assertThatThrownBy(() -> service.completeConfig("alice", bob.deviceCode(), alice.generation(), "feishu", 5, 600))
                .isInstanceOf(LarkFlowSupersededException.class);
        assertThatThrownBy(() -> service.completeAuth("alice", alice.deviceCode(), alice.generation(), 5))
                .isInstanceOf(LarkFlowSupersededException.class);
        assertThat(Files.readAllBytes(service.userConfigDirectory("bob").resolve("config.json"))).isEqualTo(victim);
        assertThat(Files.readString(service.userRoot("alice").resolve(".flow.json"))).doesNotContain("device-a");
        var restarted = service(new SkillConfigStore(json, dataDirectory, SkillConfigStore.repositoryRootFromWorkingDirectory()),
                cli, registration, archives, clock);
        registration.approved("cli_alice_new", "new-secret-a", "feishu");
        assertThat(restarted.completeConfig("alice", alice.deviceCode(), alice.generation(), "feishu", 5, 600).success()).isTrue();
        assertThat(restarted.status("bob").appId()).isEqualTo("cli_bob");
    }

    @Test
    void sharedCliPreparationAndPackPublicationAreSerializedAcrossServiceInstances() throws Exception {
        var active = new java.util.concurrent.atomic.AtomicInteger();
        var maximum = new java.util.concurrent.atomic.AtomicInteger();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var work = new ArrayList<Callable<LarkIntegrationService.InstallResult>>();
            for (int index = 0; index < 4; index++) {
                FakeCli local = new FakeCli(false);
                LarkCliGateway guarded = new LarkCliGateway() {
                    public Probe probe() { return local.probe(); }
                    public Probe ensureManaged(String version) throws java.io.IOException {
                        int inFlight = active.incrementAndGet(); maximum.accumulateAndGet(inFlight, Math::max);
                        calls.incrementAndGet();
                        try {
                            Thread.sleep(40);
                            return local.ensureManaged(version);
                        } catch (InterruptedException failure) { throw new java.io.IOException(failure); }
                        finally { active.decrementAndGet(); }
                    }
                    public ExecResult run(List<String> argv, java.util.Map<String, String> env, java.time.Duration timeout, String stdin)
                            throws java.io.IOException { return local.run(argv, env, timeout, stdin); }
                    public java.util.Optional<String> managedBinDirectory() { return local.managedBinDirectory(); }
                };
                var other = new LarkIntegrationService(json, dataDirectory, skills,
                        new LarkSkillPackInstaller(json, service.integrationRoot()), guarded, registration, archives, clock::nanoTime, clock::sleep);
                work.add(() -> other.install("alice"));
            }
            for (var result : executor.invokeAll(work)) assertThat(result.get().status().skillsInstalled()).isEqualTo(27);
        }
        assertThat(calls).hasValue(4); assertThat(maximum).hasValue(1);
    }

    @Test
    void concurrentInstallsRemainConsistent() throws Exception {
        archives.archive(LarkIntegrationFakes.archive(dataDirectory.resolve("fixture-concurrent.zip"), "v1.0.65",
                LarkIntegrationFakes.allNames(), null));
        var pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<LarkIntegrationService.InstallResult>> work = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                work.add(() -> service.install("alice"));
            }
            var results = pool.invokeAll(work);
            for (var result : results) {
                assertThat(result.get().success()).isTrue();
                assertThat(result.get().installedSkills()).hasSize(27);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(service.status("alice").installed()).isTrue();
        assertThat(service.status("alice").skillsInstalled()).isEqualTo(27);
        assertThat(service.status("alice").manifestVersion()).isEqualTo("v1.0.65");
    }

    private static String cliExecutableName() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        return windows ? "lark-cli.exe" : "lark-cli";
    }
}
