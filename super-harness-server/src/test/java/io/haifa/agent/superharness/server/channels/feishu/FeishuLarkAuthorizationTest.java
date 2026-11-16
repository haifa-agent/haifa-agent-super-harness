package io.haifa.agent.superharness.server.channels.feishu;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.superharness.server.api.DeerFlowBootstrapApi;
import io.haifa.agent.superharness.server.config.ChannelConnectionStore;
import io.haifa.agent.superharness.server.config.ExecutionProviderConfig;
import io.haifa.agent.superharness.server.config.LocalServerConfiguration;
import io.haifa.agent.superharness.server.config.SkillConfigStore;
import io.haifa.agent.superharness.server.integrations.lark.LarkIntegrationService;
import io.haifa.agent.superharness.server.integrations.lark.LarkSkillPackInstaller;
import io.haifa.agent.superharness.server.testsupport.ControlledModel;
import io.haifa.agent.superharness.server.testsupport.LarkExecutionFixture;
import io.haifa.agent.superharness.server.testsupport.LarkIntegrationFakes;
import io.haifa.agent.superharness.server.testsupport.LocalProbeModelTestSupport;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class FeishuLarkAuthorizationTest {
    private static final String OWNER = "office-owner";
    private static final String CHAT = "oc_calendar";
    private static final String OPEN_ID = "ou_calendar";
    private static final String APP_ID = "cli_office";
    private static final String WEB = "https://office.example.test";
    private static final List<String> SCOPES = List.of("calendar:calendar.event:read", "calendar:calendar.free_busy:read");
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path data;

    @Test
    void neverPrefillsBotScopesOrAmbiguousPlatformScopeAlternatives() {
        String result = """
                Command exited (exit 3)
                {"ok":false,"error":{"subtype":"missing_scope","identity":"%s",%s
                "missing_scopes":["wiki:node:read","wiki:wiki:readonly"]}}
                """;
        assertThat(FeishuLarkAuthorization.missingScopes(result.formatted("bot", ""))).isEmpty();
        assertThat(FeishuLarkAuthorization.missingScopes(result.formatted("user", "\"code\":99991679,")))
                .isEmpty();
        assertThat(FeishuLarkAuthorization.missingScopes(result.formatted("user", "")))
                .containsExactly("wiki:node:read", "wiki:wiki:readonly");
    }

    @Test
    @Timeout(100)
    void calendarAuthorizationReturnsToOriginalBotThreadAndWriteStillRequiresApproval() throws Exception {
        var cli = new LarkIntegrationFakes.FakeCli(true);
        var skills = new SkillConfigStore(json, data, SkillConfigStore.repositoryRootFromWorkingDirectory());
        var lark = new LarkIntegrationService(json, data, skills,
                new LarkSkillPackInstaller(json, data.resolve("integrations/lark")), cli,
                new LarkIntegrationFakes.FakeRegistration(),
                new LarkIntegrationFakes.FakeArchives(data.resolve("archive-unused.zip")), System::nanoTime, delay -> {});
        lark.switchCredentials(OWNER, APP_ID, "fixture-value", "feishu");
        cli.authStatus(0, verifiedGrant("docx:document:readonly"));
        var connections = new ChannelConnectionStore(json, data);
        var threadMap = new FeishuChatThreadStore(json, data);
        var transport = new RecordingFeishuTransport();
        Path writeMarker = data.resolve("calendar-write-probe");
        var model = LocalProbeModelTestSupport.binding(calendarModel(writeMarker));
        try (var api = DeerFlowBootstrapApi.createForTests(
                     new LocalServerConfiguration("local", InetAddress.getLoopbackAddress(), 0), data,
                     Map.of(ExecutionProviderConfig.PROVIDER_ENVIRONMENT_KEY, "host-guarded"), ignored -> model);
             var worker = new FeishuChannelWorker(connections, threadMap,
                     new InProcessFeishuAgentBridge(json, api.threadRunApi().conversations()), () -> transport, lark, WEB)) {
            worker.startOrRestart("cli_chat", "fixture-value", FeishuOpenDomain.DEFAULT);
            String code = ChannelConnectionStore.newBindingCode();
            connections.createOauthStateWithinCap(OWNER, "feishu", code, Instant.now().plusSeconds(60),
                    ChannelConnectionStore.MAX_PENDING_CONNECT_CODES_PER_PROVIDER);
            connections.bindFeishuFromConnectCode(code, OPEN_ID, CHAT, Map.of());

            worker.handleInbound(inbound("om_original", "查询 2026-11-16 北京时间日程及 16:00–17:00 忙闲"));
            assertThat(transport.interactiveReplies()).hasSize(1);
            worker.handleInbound(callback(transport, "approve"));
            assertThat(transport.interactiveReplies()).hasSize(2);
            worker.handleInbound(callback(transport, "approve"));

            String connectionId = connections.findConnectionByExternalIdentity("feishu", OPEN_ID, CHAT)
                    .orElseThrow().path("id").asText();
            String threadId = threadMap.find(connectionId, CHAT).orElseThrow();
            assertThat(transport.interactiveReplies()).hasSize(3);
            JsonNode card = currentCard(transport);
            URI page = URI.create(card.findValue("default_url").asText());
            assertThat(page.getScheme()).isEqualTo("https");
            assertThat(page.getHost()).isEqualTo("office.example.test");
            assertThat(page.getPath()).isEqualTo("/connect/feishu");
            assertThat(URLDecoder.decode(page.getRawQuery(), StandardCharsets.UTF_8))
                    .isEqualTo("lark_scope=" + String.join(" ", SCOPES));
            var resume = callback(transport, "resume");
            var wrongUser = new FeishuInboundMessage(CHAT, resume.messageId(), "ou_other", "", "p2p",
                    resume.cardAction());
            assertThat(worker.acceptCardAction(wrongUser)).isEqualTo(FeishuInteraction.FORBIDDEN);
            worker.handleInbound(resume);
            assertThat(transport.replies().getLast().text()).isEqualTo(FeishuLarkAuthorization.NOT_AUTHORIZED);

            var authorization = lark.startAuth(OWNER, false, List.of(), String.join(" ", SCOPES), null);
            cli.authStatus(0, verifiedGrant(String.join(" ", SCOPES)));
            assertThat(lark.completeAuth(OWNER, authorization.deviceCode(), authorization.generation(), 8).success())
                    .isTrue();
            assertThat(cli.calls()).anyMatch(call ->
                    call.argv().containsAll(List.of("login", "--scope", String.join(" ", SCOPES))));

            worker.handleInbound(resume);
            assertThat(transport.interactiveReplies()).hasSize(4);
            worker.handleInbound(callback(transport, "approve"));
            assertThat(transport.interactiveReplies()).hasSize(5);
            worker.handleInbound(callback(transport, "approve"));
            assertThat(transport.replies()).anyMatch(reply -> reply.text().contains("CALENDAR_QUERY_OK"));
            assertThat(threadMap.find(connectionId, CHAT)).contains(threadId);
            worker.handleInbound(resume);
            assertThat(transport.replies().getLast().text()).isEqualTo(FeishuLarkAuthorization.STALE);

            worker.handleInbound(inbound("om_write", "calendar-write: 创建周会"));
            assertThat(transport.interactiveReplies()).hasSize(6);
            assertThat(currentCard(transport).findValuesAsText("source")).contains("haifa_interaction");
            assertThat(Files.exists(writeMarker)).isFalse();
            worker.handleInbound(callback(transport, "reject"));
            assertThat(Files.exists(writeMarker)).isFalse();
        }
    }

    private ControlledModel calendarModel(Path marker) throws Exception {
        String fixturePath = Path.of(LarkExecutionFixture.class.getProtectionDomain().getCodeSource().getLocation()
                .toURI()).toString();
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        String javaPath = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
        String command = (windows ? "& " : "") + quote(javaPath, windows) + " -cp " + quote(fixturePath, windows)
                + " " + LarkExecutionFixture.class.getName() + " ";
        return new ControlledModel((request, ignored) -> {
            var messages = request.messages();
            int user = -1;
            for (int i = 0; i < messages.size(); i++) {
                if (messages.get(i).role() == ModelMessageRole.USER) user = i;
            }
            if (user < 0 || request.tools().isEmpty()) return ControlledModel.stop(request);
            boolean write = messages.get(user).content().contains("calendar-write:");
            var results = messages.subList(user + 1, messages.size()).stream()
                    .filter(message -> message.role() == ModelMessageRole.TOOL).toList();
            if (results.size() >= (write ? 1 : 2)) {
                boolean completed = results.stream().anyMatch(message -> message.content().contains("\"events\":0"))
                        && results.stream().anyMatch(message -> message.content().contains("\"busy\":null"));
                return ControlledModel.response(request, request.callId().value(),
                        completed ? "CALENDAR_QUERY_OK" : "当前请求未执行完毕。", List.of(),
                        ModelFinishReason.STOP, ModelUsage.unpriced(1, 1));
            }
            String commandMode = write ? "env " + quote(marker.toString(), windows)
                    : results.isEmpty() ? "calendar-agenda" : "calendar-freebusy";
            return ControlledModel.call(request, "execution_run", Map.of("mode", "COMMAND",
                    "purpose", write ? "Calendar write approval probe" : "Read agenda and free/busy",
                    "content", command + commandMode,
                    "timeoutMillis", 30_000));
        });
    }

    private static String quote(String text, boolean windows) {
        return "'" + text.replace("'", windows ? "''" : "'\"'\"'") + "'";
    }

    private static String verifiedGrant(String scope) {
        return """
                {"appId":"cli_office","identities":{"user":{"userName":"Office User","verified":true,"scope":"%s"}}}
                """.formatted(scope);
    }

    private JsonNode currentCard(RecordingFeishuTransport transport) {
        return json.valueToTree(transport.interactiveReplies().getLast().card());
    }

    private FeishuInboundMessage callback(RecordingFeishuTransport transport, String option) {
        JsonNode value = currentCard(transport).findValues("value").stream()
                .filter(item -> option.equals(item.path("option_id").asText())).findFirst().orElseThrow();
        return new FeishuInboundMessage(CHAT, "reply-interactive-" + transport.interactiveReplies().size(),
                OPEN_ID, "", "p2p", new FeishuInboundMessage.CardAction(
                        value.path("source").asText(), value.path("request_id").asText(),
                        option, value.path("value").asText()));
    }

    private static FeishuInboundMessage inbound(String id, String text) {
        return new FeishuInboundMessage(CHAT, id, OPEN_ID, text, "p2p");
    }
}
