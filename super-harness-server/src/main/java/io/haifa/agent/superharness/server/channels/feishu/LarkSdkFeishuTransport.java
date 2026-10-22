package io.haifa.agent.superharness.server.channels.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.Client;
import com.lark.oapi.core.response.RawResponse;
import com.lark.oapi.core.utils.Jsons;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.event.cardcallback.P2CardActionTriggerHandler;
import com.lark.oapi.event.cardcallback.model.CallBackAction;
import com.lark.oapi.event.cardcallback.model.CallBackCard;
import com.lark.oapi.event.cardcallback.model.CallBackContext;
import com.lark.oapi.event.cardcallback.model.CallBackOperator;
import com.lark.oapi.event.cardcallback.model.CallBackToast;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerData;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.ReplyMessageReq;
import com.lark.oapi.service.im.v1.model.ReplyMessageReqBody;
import com.lark.oapi.service.im.v1.model.ReplyMessageResp;
import com.lark.oapi.service.im.v1.model.UserId;
import com.lark.oapi.service.im.v1.model.CreateMessageReactionReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReactionReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageReactionResp;
import com.lark.oapi.service.im.v1.model.DeleteMessageReactionReq;
import com.lark.oapi.service.im.v1.model.DeleteMessageReactionResp;
import com.lark.oapi.service.im.v1.model.Emoji;
import com.lark.oapi.service.im.v1.model.GetMessageResourceReq;
import com.lark.oapi.service.im.v1.model.GetMessageResourceResp;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Official {@code com.larksuite.oapi:oapi-sdk} WebSocket long-connection transport. No public
 * webhook route is registered.
 */
public final class LarkSdkFeishuTransport implements FeishuTransport {
    private static final System.Logger LOG = System.getLogger(LarkSdkFeishuTransport.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Bounded attempts for a reply: one initial send plus at most one duplicate-safe retry. */
    private static final int REPLY_MAX_ATTEMPTS = 2;
    private static final long REPLY_RETRY_BACKOFF_MILLIS = 300L;
    private static final Pattern CARD_ERROR_CODE = Pattern.compile("\\bErrCode:\\s*([0-9]{1,9})(?![0-9])");
    private static final Pattern CARD_ERROR_PROPERTY = Pattern.compile(
            "\\bproperty:\\s*(label|label_position|input_type|required|options|width|name|tag|form_action_type|behaviors|rows|auto_resize)(?![a-zA-Z0-9_])");

    private final Object lock = new Object();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread wsThread;
    private com.lark.oapi.ws.Client wsClient;
    private Client apiClient;
    private String domain = FeishuOpenDomain.DEFAULT;
    private volatile CardActionHandler cardActions;

    @Override
    public void bindCardActions(CardActionHandler handler) {
        this.cardActions = handler;
    }

    @Override
    public void start(String appId, String appSecret, String domain, InboundHandler handler) {
        synchronized (lock) {
            stopLocked();
            String resolvedDomain = FeishuOpenDomain.normalizeOrThrow(domain);
            if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
                throw new IllegalArgumentException("Feishu app_id and app_secret are required");
            }
            this.domain = resolvedDomain;
            EventDispatcher dispatcher = EventDispatcher.newBuilder("", "")
                    .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                        @Override
                        public void handle(P2MessageReceiveV1 event) {
                            deliver(event, handler);
                        }
                    })
                    .onP2CardActionTrigger(new P2CardActionTriggerHandler() {
                        @Override
                        public P2CardActionTriggerResponse handle(P2CardActionTrigger event) {
                            return respondToCard(event);
                        }
                    })
                    .build();
            com.lark.oapi.ws.Client client = new com.lark.oapi.ws.Client.Builder(appId, appSecret)
                    .eventHandler(dispatcher)
                    .autoReconnect(true)
                    .domain(resolvedDomain)
                    .build();
            this.wsClient = client;
            this.apiClient = Client.newBuilder(appId, appSecret)
                    .openBaseUrl(resolvedDomain)
                    .build();
            Thread thread = new Thread(
                    () -> {
                        try {
                            client.start();
                        } catch (Throwable failure) {
                            LOG.log(System.Logger.Level.WARNING, "Feishu WebSocket ended", failure);
                        } finally {
                            running.set(false);
                        }
                    },
                    "feishu-ws");
            thread.setDaemon(true);
            this.wsThread = thread;
            running.set(true);
            thread.start();
            LOG.log(System.Logger.Level.INFO, "Feishu channel started (domain={0})", resolvedDomain);
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            stopLocked();
        }
    }

    private void stopLocked() {
        running.set(false);
        com.lark.oapi.ws.Client client = wsClient;
        wsClient = null;
        apiClient = null;
        if (client != null) {
            tryDisconnect(client);
        }
        Thread thread = wsThread;
        wsThread = null;
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
            try {
                thread.join(5_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        LOG.log(System.Logger.Level.INFO, "Feishu channel stopped");
    }

    @Override
    public boolean running() {
        return running.get();
    }

    @Override
    public void replyCard(String messageId, String text) throws Exception {
        replyCardId(messageId, text);
    }

    @Override
    public Optional<String> replyCardId(String messageId, String text) throws Exception {
        return replyInteractiveId(messageId, cardPayloadMap(text == null ? "" : text));
    }

    @Override
    public void replyInteractive(String messageId, Map<String, Object> card) throws Exception {
        replyInteractiveId(messageId, card);
    }

    @Override
    public Optional<String> replyInteractiveId(String messageId, Map<String, Object> card) throws Exception {
        Client api;
        synchronized (lock) {
            api = apiClient;
        }
        if (api == null || messageId == null || messageId.isBlank() || card == null) {
            return Optional.empty();
        }
        String content = Jsons.DEFAULT.toJson(card);
        ReplyMessageReq request = ReplyMessageReq.newBuilder()
                .messageId(messageId)
                .replyMessageReqBody(ReplyMessageReqBody.newBuilder()
                        .msgType("interactive")
                        .content(content)
                        .build())
                .build();
        return sendReplyValidated(api, request, "interactive", cardVersion(card));
    }

    @Override
    public void replyText(String messageId, String text) throws Exception {
        replyTextId(messageId, text);
    }

    @Override
    public Optional<String> replyTextId(String messageId, String text) throws Exception {
        Client api;
        synchronized (lock) {
            api = apiClient;
        }
        if (api == null || messageId == null || messageId.isBlank()) {
            return Optional.empty();
        }
        ReplyMessageReq request = ReplyMessageReq.newBuilder()
                .messageId(messageId)
                .replyMessageReqBody(ReplyMessageReqBody.newBuilder()
                        .msgType("text")
                        .content(Jsons.DEFAULT.toJson(Map.of("text", text == null ? "" : text)))
                        .build())
                .build();
        return sendReplyValidated(api, request, "text", "text");
    }

    @Override
    public Optional<String> uploadImage(byte[] bytes, String filename) {
        Client api;
        synchronized (lock) {
            api = apiClient;
        }
        if (api == null || bytes == null || bytes.length == 0) {
            return Optional.empty();
        }
        java.nio.file.Path temp = null;
        try {
            int dot = filename == null ? -1 : filename.lastIndexOf('.');
            String suffix = dot > 0 ? filename.substring(dot) : ".png";
            temp = java.nio.file.Files.createTempFile("feishu-image-", suffix);
            java.nio.file.Files.write(temp, bytes);
            com.lark.oapi.service.im.v1.model.CreateImageResp response = api.im().image().create(
                    com.lark.oapi.service.im.v1.model.CreateImageReq.newBuilder()
                            .createImageReqBody(com.lark.oapi.service.im.v1.model.CreateImageReqBody
                                    .newBuilder()
                                    .imageType("message")
                                    .image(temp.toFile())
                                    .build())
                            .build());
            if (response == null || !response.success() || response.getData() == null) {
                LOG.log(
                        System.Logger.Level.WARNING,
                        "Feishu image upload failed code={0}",
                        response == null ? -1 : response.getCode());
                return Optional.empty();
            }
            String imageKey = response.getData().getImageKey();
            return imageKey == null || imageKey.isBlank() ? Optional.empty() : Optional.of(imageKey);
        } catch (Exception failure) {
            LOG.log(System.Logger.Level.WARNING, "Feishu image upload raised", failure);
            return Optional.empty();
        } finally {
            if (temp != null) {
                try {
                    java.nio.file.Files.deleteIfExists(temp);
                } catch (java.io.IOException ignored) {
                    // Temp cleanup is best effort; the image is already uploaded.
                }
            }
        }
    }

    @Override
    public void replyImage(String messageId, String imageKey) throws Exception {
        Client api;
        synchronized (lock) {
            api = apiClient;
        }
        if (api == null
                || messageId == null
                || messageId.isBlank()
                || imageKey == null
                || imageKey.isBlank()) {
            return;
        }
        ReplyMessageReq request = ReplyMessageReq.newBuilder()
                .messageId(messageId)
                .replyMessageReqBody(ReplyMessageReqBody.newBuilder()
                        .msgType("image")
                        .content("{\"image_key\":\"" + imageKey + "\"}")
                        .build())
                .build();
        sendReplyValidated(api, request, "image", "image");
    }

    /**
     * Sends a reply and refuses to treat an unsuccessful provider response as delivered. Bounded,
     * duplicate-safe retries run only for rate limiting and transient transport errors; a rejected
     * payload or missing permission fails immediately. Never logs card or message content.
     */
    private Optional<String> sendReplyValidated(
            Client api, ReplyMessageReq request, String messageType, String cardVersion) throws Exception {
        ensureIdempotencyKey(request);
        FeishuDeliveryException last = null;
        for (int attempt = 1; attempt <= REPLY_MAX_ATTEMPTS; attempt++) {
            ReplyMessageResp response;
            try {
                response = api.im().message().reply(request);
            } catch (Exception transportFailure) {
                last = new FeishuDeliveryException(
                        FeishuDeliveryException.Failure.TRANSPORT, -1, "", messageType, cardVersion, "transport error");
                LOG.log(
                        System.Logger.Level.WARNING,
                        "feishu.reply.transport messageType={0} attempt={1} of={2}",
                        messageType,
                        attempt,
                        REPLY_MAX_ATTEMPTS);
                if (attempt < REPLY_MAX_ATTEMPTS) {
                    backoff();
                    continue;
                }
                throw last;
            }
            FeishuDeliveryException failure = validateReply(response, messageType, cardVersion);
            if (failure == null) {
                String providerMessageId = response.getData().getMessageId();
                LOG.log(
                        System.Logger.Level.DEBUG,
                        "feishu.reply.delivered messageType={0} cardVersion={1} providerMessageId={2}",
                        messageType,
                        cardVersion,
                        providerMessageId);
                return Optional.ofNullable(providerMessageId).filter(id -> !id.isBlank());
            }
            last = failure;
            LOG.log(
                    System.Logger.Level.WARNING,
                    "feishu.reply.rejected messageType={0} failure={1} code={2} logId={3} attempt={4} of={5} cardErrorCode={6} invalidProperty={7}",
                    messageType,
                    failure.failure(),
                    failure.code(),
                    failure.logId(),
                    attempt,
                    REPLY_MAX_ATTEMPTS,
                    cardErrorCode(response == null ? null : response.getMsg()),
                    cardErrorProperty(response == null ? null : response.getMsg()));
            if (failure.retryable() && attempt < REPLY_MAX_ATTEMPTS) {
                backoff();
                continue;
            }
            throw failure;
        }
        if (last != null) {
            throw last;
        }
        return Optional.empty();
    }

    /**
     * Assigns one stable idempotency key per logical reply. A bounded retry reuses the same key so
     * the provider can deduplicate the resend instead of posting a duplicate message.
     */
    static void ensureIdempotencyKey(ReplyMessageReq request) {
        ReplyMessageReqBody body = request == null ? null : request.getReplyMessageReqBody();
        if (body != null && (body.getUuid() == null || body.getUuid().isBlank())) {
            body.setUuid(UUID.randomUUID().toString());
        }
    }

    /**
     * Returns {@code null} when the provider confirmed delivery (success status plus a provider
     * message id), otherwise a value-free failure describing the rejection class.
     */
    static FeishuDeliveryException validateReply(
            ReplyMessageResp response, String messageType, String cardVersion) {
        if (response == null) {
            return new FeishuDeliveryException(
                    FeishuDeliveryException.Failure.TRANSPORT, -1, "", messageType, cardVersion, "null response");
        }
        String logId = response.getError() == null ? "" : response.getError().getLogId();
        if (!response.success()) {
            return new FeishuDeliveryException(
                    classifyFailure(response.getCode(), httpStatus(response)),
                    response.getCode(),
                    logId,
                    messageType,
                    cardVersion,
                    "provider rejected reply");
        }
        String providerMessageId = response.getData() == null ? "" : response.getData().getMessageId();
        if (providerMessageId == null || providerMessageId.isBlank()) {
            return new FeishuDeliveryException(
                    FeishuDeliveryException.Failure.UNKNOWN,
                    response.getCode(),
                    logId,
                    messageType,
                    cardVersion,
                    "reply missing provider message id");
        }
        return null;
    }

    /** Only numeric subcodes and known schema keys may leave the provider's error message. */
    static int cardErrorCode(String message) {
        var matcher = CARD_ERROR_CODE.matcher(boundedErrorMessage(message));
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    static String cardErrorProperty(String message) {
        var matcher = CARD_ERROR_PROPERTY.matcher(boundedErrorMessage(message));
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String boundedErrorMessage(String message) {
        return message == null ? "" : message.substring(0, Math.min(message.length(), 2048));
    }

    /**
     * Classifies a provider rejection so retries and diagnostics stay bounded and distinct. Business
     * codes are checked before the generic HTTP status so a rate-limit or invalid-parameter
     * rejection that arrives with HTTP 400 is not misclassified.
     */
    static FeishuDeliveryException.Failure classifyFailure(int code, int httpStatus) {
        if (code == 99991400 || code == 99991401) {
            return FeishuDeliveryException.Failure.RATE_LIMIT;
        }
        if (code == 200830 || code == 230001 || code == 230002 || code == 230006 || code == 230009 || code == 230099) {
            return FeishuDeliveryException.Failure.INVALID;
        }
        if (httpStatus == 429) {
            return FeishuDeliveryException.Failure.RATE_LIMIT;
        }
        if (httpStatus == 401 || httpStatus == 403) {
            return FeishuDeliveryException.Failure.PERMISSION;
        }
        if (httpStatus >= 500) {
            return FeishuDeliveryException.Failure.TRANSPORT;
        }
        if (httpStatus == 400) {
            return FeishuDeliveryException.Failure.INVALID;
        }
        return FeishuDeliveryException.Failure.UNKNOWN;
    }

    private static int httpStatus(ReplyMessageResp response) {
        RawResponse raw = response.getRawResponse();
        return raw == null ? 0 : raw.getStatusCode();
    }

    private static void backoff() {
        try {
            Thread.sleep(REPLY_RETRY_BACKOFF_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Card JSON version marker used for diagnostics only; never logs card content. */
    private static String cardVersion(Map<String, Object> card) {
        Object schema = card.get("schema");
        return schema == null ? "1.0" : String.valueOf(schema);
    }

    @Override
    public Optional<String> addReaction(String messageId, String emojiType) {
        Client api;
        synchronized (lock) {
            api = apiClient;
        }
        if (api == null
                || messageId == null
                || messageId.isBlank()
                || emojiType == null
                || emojiType.isBlank()) {
            return Optional.empty();
        }
        try {
            CreateMessageReactionResp response = api.im()
                    .messageReaction()
                    .create(CreateMessageReactionReq.newBuilder()
                            .messageId(messageId)
                            .createMessageReactionReqBody(CreateMessageReactionReqBody.newBuilder()
                                    .reactionType(Emoji.newBuilder().emojiType(emojiType).build())
                                    .build())
                            .build());
            if (response == null || !response.success() || response.getData() == null) {
                LOG.log(
                        System.Logger.Level.WARNING,
                        "Feishu add reaction failed messageId={0} emoji={1} code={2}",
                        messageId,
                        emojiType,
                        response == null ? -1 : response.getCode());
                return Optional.empty();
            }
            String reactionId = response.getData().getReactionId();
            if (reactionId == null || reactionId.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(reactionId);
        } catch (Exception failure) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    "Feishu add reaction raised messageId=" + messageId + " emoji=" + emojiType,
                    failure);
            return Optional.empty();
        }
    }

    @Override
    public boolean deleteReaction(String messageId, String reactionId) {
        Client api;
        synchronized (lock) {
            api = apiClient;
        }
        if (api == null
                || messageId == null
                || messageId.isBlank()
                || reactionId == null
                || reactionId.isBlank()) {
            return false;
        }
        try {
            DeleteMessageReactionResp response = api.im()
                    .messageReaction()
                    .delete(DeleteMessageReactionReq.newBuilder()
                            .messageId(messageId)
                            .reactionId(reactionId)
                            .build());
            if (response == null || !response.success()) {
                LOG.log(
                        System.Logger.Level.WARNING,
                        "Feishu delete reaction failed messageId={0} reactionId={1} code={2}",
                        messageId,
                        reactionId,
                        response == null ? -1 : response.getCode());
                return false;
            }
            return true;
        } catch (Exception failure) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    "Feishu delete reaction raised messageId=" + messageId,
                    failure);
            return false;
        }
    }

    @Override
    public byte[] downloadResource(String messageId, String resourceKey, String resourceType) throws Exception {
        Client api;
        synchronized (lock) {
            api = apiClient;
        }
        if (api == null
                || messageId == null
                || messageId.isBlank()
                || resourceKey == null
                || resourceKey.isBlank()) {
            return new byte[0];
        }
        String type = resourceType == null || resourceType.isBlank() ? "file" : resourceType;
        GetMessageResourceResp response = api.im()
                .messageResource()
                .get(GetMessageResourceReq.newBuilder()
                        .messageId(messageId)
                        .fileKey(resourceKey)
                        .type(type)
                        .build());
        if (response == null || !response.success()) {
            throw new IllegalStateException("Feishu media download failed");
        }
        Object data = response.getData();
        if (data instanceof ByteArrayOutputStream stream) {
            return stream.toByteArray();
        }
        return new byte[0];
    }

    public String domain() {
        return domain;
    }

    private static void tryDisconnect(com.lark.oapi.ws.Client client) {
        try {
            Method disconnect = com.lark.oapi.ws.Client.class.getDeclaredMethod("disconnect");
            disconnect.setAccessible(true);
            disconnect.invoke(client);
        } catch (ReflectiveOperationException ignored) {
            // Protected disconnect; interrupt+join is the DeerFlow-parity fallback.
        }
    }

    private static void deliver(P2MessageReceiveV1 event, InboundHandler handler) {
        if (handler == null || event == null || event.getEvent() == null) {
            return;
        }
        EventMessage message = event.getEvent().getMessage();
        EventSender sender = event.getEvent().getSender();
        if (message == null) {
            return;
        }
        String openId = "";
        if (sender != null) {
            UserId senderId = sender.getSenderId();
            if (senderId != null && senderId.getOpenId() != null) {
                openId = senderId.getOpenId();
            }
        }
        String messageType = message.getMessageType() == null || message.getMessageType().isBlank()
                ? "text"
                : message.getMessageType().trim().toLowerCase(Locale.ROOT);
        ParsedContent parsed = parseContent(messageType, message.getContent());
        // Always deliver: unsupported / media-only messages must reach the worker so it can reply
        // instead of looking hung (Phase 1 essential).
        handler.onMessage(new FeishuInboundMessage(
                message.getChatId(),
                message.getMessageId(),
                openId,
                parsed.text(),
                message.getChatType(),
                messageType,
                parsed.attachments(),
                null,
                message.getRootId(),
                message.getParentId(),
                message.getThreadId()));
    }

    private P2CardActionTriggerResponse respondToCard(P2CardActionTrigger event) {
        FeishuInboundMessage message = cardMessage(event);
        String toast = FeishuInteraction.INVALID;
        CardActionHandler handler = cardActions;
        if (handler != null) {
            try {
                toast = handler.toastFor(message);
            } catch (RuntimeException failure) {
                LOG.log(System.Logger.Level.WARNING, "Feishu card action was rejected", failure);
                toast = FeishuInteraction.INVALID;
            }
        }
        return callbackResponse(toast, FeishuInteraction.settledCard(message, toast));
    }

    static P2CardActionTriggerResponse toastResponse(String content) {
        return callbackResponse(content, null);
    }

    static P2CardActionTriggerResponse callbackResponse(String content, Map<String, Object> card) {
        CallBackToast toast = new CallBackToast();
        toast.setType("info");
        toast.setContent(content == null || content.isBlank() ? FeishuInteraction.PROCESSING : content);
        P2CardActionTriggerResponse response = new P2CardActionTriggerResponse();
        response.setToast(toast);
        if (card != null) {
            CallBackCard update = new CallBackCard();
            update.setType("raw");
            update.setData(card);
            response.setCard(update);
        }
        return response;
    }

    static FeishuInboundMessage cardMessage(P2CardActionTrigger event) {
        P2CardActionTriggerData data = event == null ? null : event.getEvent();
        CallBackContext context = data == null ? null : data.getContext();
        CallBackOperator operator = data == null ? null : data.getOperator();
        CallBackAction action = data == null ? null : data.getAction();
        Map<String, Object> value = action == null ? null : action.getValue();
        String requestId = textValue(value, "request_id");
        String actionName = action == null || action.getName() == null ? "" : action.getName().trim();
        Map<String, Object> formValues = action == null || action.getFormValue() == null
                ? Map.of()
                : Map.copyOf(action.getFormValue());
        String typed = firstText(
                textValue(formValues, "other"),
                action == null || action.getInputValue() == null ? "" : action.getInputValue().trim());
        boolean other = FeishuInteraction.OTHER_OPTION.equals(textValue(value, "action"))
                || "other_submit".equals(actionName);
        boolean formSubmit = FeishuInteraction.FORM_SUBMIT.equals(textValue(value, "action"))
                || FeishuInteraction.FORM_SUBMIT.equals(textValue(value, "option_id"))
                || "form_submit".equals(actionName);
        String optionId = other
                ? FeishuInteraction.OTHER_OPTION
                : formSubmit
                        ? FeishuInteraction.FORM_SUBMIT
                        : firstText(textValue(value, "option_id"), textValue(value, "action"));
        String submitted = other ? typed : firstText(textValue(value, "value"), optionId);
        return new FeishuInboundMessage(
                context == null ? "" : context.getOpenChatId(),
                context == null ? "" : context.getOpenMessageId(),
                operator == null ? "" : operator.getOpenId(),
                "",
                "",
                new FeishuInboundMessage.CardAction(
                        textValue(value, "source"),
                        requestId,
                        optionId,
                        submitted,
                        textValue(value, "card_schema"),
                        formValues));
    }

    /** Package-visible for unit assertions on inbound content shaping. */
    record ParsedContent(String text, List<FeishuInboundMessage.Attachment> attachments) {}

    static ParsedContent parseContent(String messageType, String contentJson) {
        String type = messageType == null ? "text" : messageType;
        if (contentJson == null || contentJson.isBlank()) {
            return new ParsedContent("", List.of());
        }
        try {
            JsonNode content = JSON.readTree(contentJson);
            if ("image".equals(type)) {
                String key = content.path("image_key").asText("").trim();
                List<FeishuInboundMessage.Attachment> attachments = key.isEmpty()
                        ? List.of()
                        : List.of(new FeishuInboundMessage.Attachment("image", key, "image.jpg"));
                return new ParsedContent("", attachments);
            }
            if ("file".equals(type) || "media".equals(type)) {
                String key = content.path("file_key").asText("").trim();
                String name = content.path("file_name").asText("").trim();
                if (name.isEmpty()) {
                    name = "file.bin";
                }
                List<FeishuInboundMessage.Attachment> attachments = key.isEmpty()
                        ? List.of()
                        : List.of(new FeishuInboundMessage.Attachment("file", key, name));
                return new ParsedContent("", attachments);
            }
            if ("post".equals(type)) {
                return new ParsedContent(extractPostText(content), List.of());
            }
            if (content.hasNonNull("text")) {
                return new ParsedContent(content.get("text").asText("").trim(), List.of());
            }
            return new ParsedContent("", List.of());
        } catch (Exception ignored) {
            return new ParsedContent("text".equals(type) ? contentJson.trim() : "", List.of());
        }
    }

    /** Best-effort plain text from a Feishu post/rich-text payload. */
    private static String extractPostText(JsonNode content) {
        StringBuilder builder = new StringBuilder();
        collectPostText(content, builder);
        return builder.toString().trim();
    }

    private static void collectPostText(JsonNode node, StringBuilder builder) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            appendWord(builder, node.asText());
            return;
        }
        if (node.isObject()) {
            if (node.hasNonNull("text")) {
                appendWord(builder, node.get("text").asText());
            }
            node.fields().forEachRemaining(entry -> {
                if (!"text".equals(entry.getKey())) {
                    collectPostText(entry.getValue(), builder);
                }
            });
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectPostText(child, builder);
            }
        }
    }

    private static void appendWord(StringBuilder builder, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append(' ');
        }
        builder.append(text.trim());
    }

    private static String textValue(Map<String, Object> value, String key) {
        if (value == null || key == null) {
            return "";
        }
        Object raw = value.get(key);
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    private static String firstText(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? (fallback == null ? "" : fallback) : preferred;
    }

    /**
     * DeerFlow interactive card: Card JSON 2.0 presentation so code fences, headings, tables,
     * and emphasis render properly in Feishu desktop and mobile clients.
     */
    static Object cardPayload(String text) {
        return cardPayloadMap(text);
    }

    private static Map<String, Object> cardPayloadMap(String text) {
        return FeishuCardPresenter.cardPayload(text == null ? "" : text);
    }
}
