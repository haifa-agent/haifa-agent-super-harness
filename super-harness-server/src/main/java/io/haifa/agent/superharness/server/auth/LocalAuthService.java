package io.haifa.agent.superharness.server.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.superharness.server.api.DeerFlowBootstrapApi;
import io.haifa.agent.superharness.server.api.dto.AuthMeResponse;
import io.javalin.Javalin;
import io.javalin.http.Context;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import at.favre.lib.crypto.bcrypt.BCrypt;

/**
 * DeerFlow-compatible local session for the auth profile. Disabled unless
 * {@code HAIFA_SUPER_HARNESS_LOCAL_AUTH=1}. Passwords and the JWT secret stay in the
 * auth data-root and are never written to the runtime SQLite database.
 */
public final class LocalAuthService {
    public static final String ENABLED = "HAIFA_SUPER_HARNESS_LOCAL_AUTH";
    public static final String ALLOWED_ORIGINS = "HAIFA_SUPER_HARNESS_AUTH_ALLOWED_ORIGINS";
    public static final String ORIGIN_DENIED = "Cross-site auth request denied.";
    private static final Duration TOKEN_TTL = Duration.ofDays(7);
    private static final int MAX_FAILURES = 5;
    private static final Duration LOCKOUT = Duration.ofSeconds(300);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ThreadLocal<LocalAuthService> PENDING = new ThreadLocal<>();
    private static final Set<String> COMMON_PASSWORDS = Set.of(
            "password", "password1", "12345678", "123456789", "1234567890", "qwerty123",
            "qwertyui", "11111111", "00000000", "abc12345", "iloveyou", "admin123",
            "letmein1", "welcome1", "monkey12", "dragon12", "master12", "login123");

    private final ObjectMapper json;
    private final Path dataDirectory;
    private final Path usersFile;
    private final Path localConfig;
    private final Path preferencesDirectory;
    private final byte[] jwtSecret;
    private final Set<String> allowedOrigins;
    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> preferenceLocks = new ConcurrentHashMap<>();

    private LocalAuthService(ObjectMapper json, Path dataDirectory, Map<String, String> environment) throws IOException {
        this.json = json;
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
        Path auth = this.dataDirectory.resolve("auth");
        Files.createDirectories(auth);
        this.usersFile = auth.resolve("users.json");
        this.localConfig = auth.resolve("local.json");
        this.preferencesDirectory = auth.resolve("preferences");
        Files.createDirectories(preferencesDirectory);
        readAllowRegistration();
        this.jwtSecret = loadOrCreateSecret(auth.resolve("jwt.secret"));
        this.allowedOrigins = parseOrigins(environment.get(ALLOWED_ORIGINS));
    }

    public static void prepare(Path dataDirectory, Map<String, String> environment) throws IOException {
        PENDING.set(open(dataDirectory, environment));
    }

    public static LocalAuthService takePending() {
        LocalAuthService value = PENDING.get();
        PENDING.remove();
        return value;
    }

    public static void discardPending() {
        PENDING.remove();
    }

    public static LocalAuthService open(Path dataDirectory, Map<String, String> environment) throws IOException {
        if (environment == null || !"1".equals(environment.get(ENABLED))) return null;
        return new LocalAuthService(new ObjectMapper(), dataDirectory, environment);
    }

    public void install(Javalin javalin) {
        javalin.before(this::guard);
        javalin.after(ctx -> AccessOwner.clear());
        javalin.post("/api/v1/auth/login/local", this::login);
        javalin.post("/api/v1/auth/logout", this::logout);
        javalin.post("/api/v1/auth/register", this::register);
        javalin.post("/api/v1/auth/initialize", this::initialize);
        javalin.post("/api/v1/auth/change-password", this::changePassword);
        javalin.get("/api/v1/auth/me", this::me);
        javalin.get("/api/v1/auth/setup-status", this::setupStatus);
        javalin.get("/api/v1/auth/providers", ctx -> ctx.json(Map.of("providers", List.of())));
        javalin.get("/api/v1/auth/preferences", this::readPreferences);
        javalin.patch("/api/v1/auth/preferences", this::patchPreferences);
    }

    private void guard(Context ctx) throws Exception {
        String path = ctx.path();
        if ("/health".equals(path)) return;
        boolean exempt = exempt(path);
        if (exempt && rejectsOrigin(ctx)) {
            deny(ctx, 403, ORIGIN_DENIED);
            return;
        }
        if (exempt || publicRead(ctx)) return;
        User user = sessionUser(ctx);
        if (user == null) {
            error(ctx, 401, "not_authenticated", "Not authenticated");
            return;
        }
        if (mutating(ctx) && !csrfMatches(ctx)) {
            deny(ctx, 403, "CSRF validation failed");
            return;
        }
        AccessOwner.bind(user.id, user.systemRole);
        ctx.attribute("localUser", user);
    }

    private void login(Context ctx) throws Exception {
        JsonNode body = body(ctx);
        String email = firstText(body, ctx, "username", "email");
        String password = firstText(body, ctx, "password", "password");
        boolean remember = truthy(firstText(body, ctx, "remember_me", "remember_me"));
        String address = clientAddress(ctx);
        if (locked(address)) {
            error(ctx, 429, "invalid_credentials", "Invalid email or password");
            return;
        }
        User user = findByEmail(normalizeEmail(email));
        if (user == null || !verifyPassword(password, user.passwordHash)) {
            recordFailure(address);
            error(ctx, 401, "invalid_credentials", "Invalid email or password");
            return;
        }
        attempts.remove(address);
        issue(ctx, user, remember, 200);
    }

    private void logout(Context ctx) {
        clearCookie(ctx, "access_token", true, "Lax");
        clearCookie(ctx, "csrf_token", false, "Strict");
        clearCookie(ctx, "deerflow_session_persistent", true, "Lax");
        ctx.status(200).json(Map.of());
    }

    private void register(Context ctx) throws Exception {
        if (!readAllowRegistration()) {
            error(ctx, 403, "registration_disabled", "Registration is disabled");
            return;
        }
        JsonNode body = body(ctx);
        String email = normalizeEmail(text(body, "email"));
        String password = text(body, "password");
        String policy = passwordPolicy(password);
        if (email == null || policy != null) {
            error(ctx, 400, "invalid_credentials", policy == null ? "Invalid email or password" : policy);
            return;
        }
        synchronized (this) {
            if (findByEmail(email) != null) {
                error(ctx, 400, "email_already_exists", "Email already exists");
                return;
            }
            User user = new User(java.util.UUID.randomUUID().toString(), email, hashPassword(password),
                    "user", false, 1);
            save(append(loadUsers(), user));
            issue(ctx, user, truthy(text(body, "remember_me")), 201);
        }
    }

    private void initialize(Context ctx) throws Exception {
        JsonNode body = body(ctx);
        String email = normalizeEmail(text(body, "email"));
        String password = text(body, "password");
        String policy = passwordPolicy(password);
        if (email == null || policy != null) {
            error(ctx, 400, "invalid_credentials", policy == null ? "Invalid email or password" : policy);
            return;
        }
        synchronized (this) {
            if (adminCount(loadUsers()) > 0) {
                error(ctx, 409, "system_already_initialized", "System is already initialized");
                return;
            }
            if (findByEmail(email) != null) {
                error(ctx, 400, "email_already_exists", "Email already exists");
                return;
            }
            User user = new User(java.util.UUID.randomUUID().toString(), email, hashPassword(password),
                    "admin", false, 1);
            save(append(loadUsers(), user));
            issue(ctx, user, truthy(text(body, "remember_me")), 201);
        }
    }

    private void changePassword(Context ctx) throws Exception {
        User current = ctx.attribute("localUser");
        JsonNode body = body(ctx);
        String password = text(body, "current_password");
        String next = text(body, "new_password");
        if (current == null || !verifyPassword(password, current.passwordHash)) {
            error(ctx, 401, "invalid_credentials", "Invalid email or password");
            return;
        }
        String policy = passwordPolicy(next);
        if (policy != null) {
            error(ctx, 400, "invalid_credentials", policy);
            return;
        }
        synchronized (this) {
            List<User> users = loadUsers();
            User updated = null;
            List<User> nextUsers = new ArrayList<>();
            for (User user : users) {
                if (user.id.equals(current.id)) {
                    updated = new User(user.id, user.email, hashPassword(next), user.systemRole,
                            user.needsSetup, user.tokenVersion + 1);
                    nextUsers.add(updated);
                } else {
                    nextUsers.add(user);
                }
            }
            if (updated == null) {
                error(ctx, 401, "user_not_found", "User not found");
                return;
            }
            save(nextUsers);
            issue(ctx, updated, persistent(ctx), 200);
        }
    }

    private void me(Context ctx) {
        User user = ctx.attribute("localUser");
        if (user == null) {
            error(ctx, 401, "not_authenticated", "Not authenticated");
            return;
        }
        ctx.json(response(user));
    }

    private void setupStatus(Context ctx) throws Exception {
        boolean registration = readAllowRegistration();
        boolean needsSetup = adminCount(loadUsers()) == 0;
        ctx.json(Map.of("needs_setup", needsSetup, "registration_enabled", registration));
    }

    private void readPreferences(Context ctx) throws Exception {
        User user = ctx.attribute("localUser");
        if (user == null || fence(ctx, user)) return;
        ctx.json(readPreferenceDocument(user.id));
    }

    private void patchPreferences(Context ctx) throws Exception {
        User user = ctx.attribute("localUser");
        if (user == null || fence(ctx, user)) return;
        JsonNode body = body(ctx);
        if (body == null || !body.isObject()) {
            error(ctx, 422, "invalid_credentials", "Invalid preferences");
            return;
        }
        Path file = preferenceFile(user.id);
        Object lock = preferenceLocks.computeIfAbsent(user.id, ignored -> new Object());
        synchronized (lock) {
            ObjectNode merged = readPreferenceDocument(user.id);
            body.fields().forEachRemaining(field -> merged.set(field.getKey(), field.getValue()));
            writeAtomic(file, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(merged));
            ctx.json(merged);
        }
    }

    private boolean fence(Context ctx, User user) {
        String expected = ctx.header("X-Expected-User-Id");
        if (expected != null && !expected.equals(user.id)) {
            ctx.status(409).json(Map.of("detail", "user mismatch"));
            return true;
        }
        return false;
    }

    private void issue(Context ctx, User user, boolean remember, int status) {
        Instant expires = Instant.now().plus(TOKEN_TTL);
        String token = sign(user.id, user.tokenVersion, expires);
        String csrf = randomToken();
        Integer maxAge = remember ? (int) TOKEN_TTL.toSeconds() : null;
        setCookie(ctx, "access_token", token, true, "Lax", maxAge);
        setCookie(ctx, "csrf_token", csrf, false, "Strict", maxAge);
        setCookie(ctx, "deerflow_session_persistent", remember ? "1" : "0", true, "Lax", maxAge);
        ctx.status(status).json(response(user));
    }

    private AuthMeResponse response(User user) {
        return new AuthMeResponse(user.id, user.email, user.systemRole, user.needsSetup, null,
                DeerFlowBootstrapApi.DEFAULT_PERMISSIONS);
    }

    private User sessionUser(Context ctx) {
        String token = cookie(ctx, "access_token");
        if (token == null) return null;
        Claims claims = verify(token);
        if (claims == null) return null;
        try {
            for (User user : loadUsers()) {
                if (user.id.equals(claims.subject) && user.tokenVersion == claims.version) return user;
            }
        } catch (IOException failure) {
            return null;
        }
        return null;
    }

    private boolean rejectsOrigin(Context ctx) {
        String origin = ctx.header("Origin");
        if (origin == null || origin.isBlank()) return false;
        String normalized = normalizeOrigin(origin);
        return normalized == null || !allowedOrigins.contains(normalized);
    }

    private boolean csrfMatches(Context ctx) {
        String cookie = cookie(ctx, "csrf_token");
        String header = ctx.header("X-CSRF-Token");
        if (cookie == null || header == null) return false;
        byte[] left = cookie.getBytes(StandardCharsets.UTF_8);
        byte[] right = header.getBytes(StandardCharsets.UTF_8);
        return left.length == right.length && MessageDigest.isEqual(left, right);
    }

    private static boolean exempt(String path) {
        return "/api/v1/auth/login/local".equals(path)
                || "/api/v1/auth/logout".equals(path)
                || "/api/v1/auth/register".equals(path)
                || "/api/v1/auth/initialize".equals(path);
    }

    private static boolean publicRead(Context ctx) {
        if (!"GET".equals(ctx.method().name())) return false;
        String path = ctx.path();
        return "/api/v1/auth/setup-status".equals(path) || "/api/v1/auth/providers".equals(path);
    }

    private static boolean mutating(Context ctx) {
        String method = ctx.method().name();
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method) || "DELETE".equals(method);
    }

    boolean readAllowRegistration() throws IOException {
        if (!Files.exists(localConfig)) return true;
        JsonNode root;
        try {
            root = json.readTree(localConfig.toFile());
        } catch (IOException failure) {
            throw new IOException("auth/local.json is malformed");
        }
        if (root == null || !root.isObject()) throw new IOException("auth/local.json is malformed");
        JsonNode value = root.get("allow_registration");
        if (value == null || value.isNull()) return true;
        if (!value.isBoolean()) throw new IOException("auth/local.json is malformed");
        return value.booleanValue();
    }

    private List<User> loadUsers() throws IOException {
        if (!Files.exists(usersFile)) return List.of();
        JsonNode root = json.readTree(usersFile.toFile());
        if (root == null || !root.path("users").isArray()) throw new IOException("auth users file is malformed");
        List<User> users = new ArrayList<>();
        for (JsonNode item : root.path("users")) {
            users.add(new User(
                    item.path("id").asText(),
                    item.path("email").asText(),
                    item.path("password_hash").asText(),
                    item.path("system_role").asText(),
                    item.path("needs_setup").asBoolean(false),
                    item.path("token_version").asInt(1)));
        }
        return List.copyOf(users);
    }

    private void save(List<User> users) throws IOException {
        ObjectNode root = json.createObjectNode();
        ArrayNode array = root.putArray("users");
        for (User user : users) {
            ObjectNode item = array.addObject();
            item.put("id", user.id);
            item.put("email", user.email);
            item.put("password_hash", user.passwordHash);
            item.put("system_role", user.systemRole);
            item.put("needs_setup", user.needsSetup);
            item.put("token_version", user.tokenVersion);
        }
        writeAtomic(usersFile, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
    }

    private static List<User> append(List<User> users, User user) {
        List<User> next = new ArrayList<>(users);
        next.add(user);
        return next;
    }

    private User findByEmail(String email) throws IOException {
        if (email == null) return null;
        for (User user : loadUsers()) {
            if (user.email.equals(email)) return user;
        }
        return null;
    }

    private static int adminCount(List<User> users) {
        int count = 0;
        for (User user : users) {
            if ("admin".equals(user.systemRole)) count++;
        }
        return count;
    }

    private ObjectNode readPreferenceDocument(String userId) throws IOException {
        Path file = preferenceFile(userId);
        if (!Files.exists(file)) return json.createObjectNode();
        JsonNode root = json.readTree(file.toFile());
        if (!root.isObject()) return json.createObjectNode();
        return (ObjectNode) root;
    }

    private Path preferenceFile(String userId) {
        if (userId.indexOf('/') >= 0 || userId.indexOf('\\') >= 0 || userId.contains("..")) {
            throw new IllegalArgumentException("invalid user id");
        }
        return preferencesDirectory.resolve(userId + ".json");
    }

    private String sign(String subject, int version, Instant expires) {
        String header = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        ObjectNode payload = json.createObjectNode();
        payload.put("sub", subject);
        payload.put("ver", version);
        payload.put("iat", Instant.now().getEpochSecond());
        payload.put("exp", expires.getEpochSecond());
        String body;
        try {
            body = b64(json.writeValueAsBytes(payload));
        } catch (IOException failure) {
            throw new IllegalStateException("session token could not be written");
        }
        String signingInput = header + "." + body;
        return signingInput + "." + b64(hmac(signingInput));
    }

    private Claims verify(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) return null;
        byte[] expected = hmac(parts[0] + "." + parts[1]);
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException failure) {
            return null;
        }
        if (expected.length != actual.length || !MessageDigest.isEqual(expected, actual)) return null;
        try {
            JsonNode payload = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
            long exp = payload.path("exp").asLong(0);
            if (exp <= Instant.now().getEpochSecond()) return null;
            String subject = payload.path("sub").asText("");
            int version = payload.path("ver").asInt(-1);
            if (subject.isBlank() || version < 0) return null;
            return new Claims(subject, version);
        } catch (Exception failure) {
            return null;
        }
    }

    private byte[] hmac(String signingInput) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(jwtSecret, "HmacSHA256"));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
        } catch (Exception failure) {
            throw new IllegalStateException("session token could not be signed");
        }
    }

    static String hashPassword(String password) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(password.getBytes(StandardCharsets.UTF_8));
        } catch (Exception failure) {
            throw new IllegalStateException("password could not be hashed");
        }
        String prehash = Base64.getEncoder().encodeToString(digest);
        String bcrypt = BCrypt.withDefaults().hashToString(12, prehash.toCharArray());
        return "$dfv2$" + bcrypt;
    }

    static boolean verifyPassword(String password, String stored) {
        if (password == null || stored == null || !stored.startsWith("$dfv2$")) return false;
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(password.getBytes(StandardCharsets.UTF_8));
        } catch (Exception failure) {
            return false;
        }
        String prehash = Base64.getEncoder().encodeToString(digest);
        return BCrypt.verifyer().verify(prehash.toCharArray(), stored.substring("$dfv2$".length())).verified;
    }

    private static String passwordPolicy(String password) {
        if (password == null || password.length() < 8) return "Password must be at least 8 characters";
        if (COMMON_PASSWORDS.contains(password.toLowerCase(Locale.ROOT))) return "Password is too common";
        return null;
    }

    private static String normalizeEmail(String email) {
        if (email == null) return null;
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        int at = normalized.indexOf('@');
        if (at <= 0 || at != normalized.lastIndexOf('@') || !normalized.substring(at + 1).contains(".")) return null;
        if (normalized.indexOf(' ') >= 0) return null;
        return normalized;
    }

    private boolean locked(String address) {
        Attempt attempt = attempts.get(address);
        return attempt != null && attempt.lockedUntil != null && attempt.lockedUntil.isAfter(Instant.now());
    }

    private void recordFailure(String address) {
        attempts.compute(address, (key, current) -> {
            Instant now = Instant.now();
            if (current == null || (current.lockedUntil != null && current.lockedUntil.isBefore(now))) {
                current = new Attempt(0, null);
            }
            int count = current.count + 1;
            Instant lockedUntil = count >= MAX_FAILURES ? now.plus(LOCKOUT) : null;
            return new Attempt(count, lockedUntil);
        });
    }

    private JsonNode body(Context ctx) throws IOException {
        String type = ctx.contentType() == null ? "" : ctx.contentType().toLowerCase(Locale.ROOT);
        if (type.contains("application/json")) {
            String raw = ctx.body();
            if (raw == null || raw.isBlank()) return json.createObjectNode();
            return json.readTree(raw);
        }
        return null;
    }

    private String firstText(JsonNode body, Context ctx, String formName, String jsonName) {
        if (body != null) return body.hasNonNull(jsonName) ? body.get(jsonName).asText() : null;
        return ctx.formParam(formName);
    }

    private static String text(JsonNode body, String name) {
        if (body == null || !body.hasNonNull(name)) return null;
        return body.get(name).asText();
    }

    private static boolean truthy(String value) {
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    private boolean persistent(Context ctx) {
        return "1".equals(cookie(ctx, "deerflow_session_persistent"));
    }

    private static String cookie(Context ctx, String name) {
        return ctx.cookie(name);
    }

    private static void setCookie(Context ctx, String name, String value, boolean httpOnly, String sameSite, Integer maxAge) {
        ctx.res().addHeader("Set-Cookie", cookieHeader(name, value, httpOnly, sameSite, maxAge));
    }

    private static void clearCookie(Context ctx, String name, boolean httpOnly, String sameSite) {
        ctx.res().addHeader("Set-Cookie", cookieHeader(name, "", httpOnly, sameSite, 0));
    }

    private static String cookieHeader(String name, String value, boolean httpOnly, String sameSite, Integer maxAge) {
        StringBuilder header = new StringBuilder();
        header.append(name).append('=').append(value).append("; Path=/; SameSite=").append(sameSite);
        if (httpOnly) header.append("; HttpOnly");
        if (maxAge != null) header.append("; Max-Age=").append(maxAge);
        return header.toString();
    }

    private static void error(Context ctx, int status, String code, String message) {
        ctx.status(status).json(Map.of("detail", Map.of("code", code, "message", message)));
        ctx.skipRemainingHandlers();
    }

    private static void deny(Context ctx, int status, String detail) {
        ctx.status(status).json(Map.of("detail", detail));
        ctx.skipRemainingHandlers();
    }

    private static String clientAddress(Context ctx) {
        String remote = ctx.req().getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }

    static String normalizeOrigin(String origin) {
        try {
            URI uri = URI.create(origin.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) return null;
            scheme = scheme.toLowerCase(Locale.ROOT);
            host = host.toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            boolean defaultPort = port < 0 || ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
            return defaultPort ? scheme + "://" + host : scheme + "://" + host + ":" + port;
        } catch (IllegalArgumentException failure) {
            return null;
        }
    }

    private static Set<String> parseOrigins(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        java.util.LinkedHashSet<String> origins = new java.util.LinkedHashSet<>();
        for (String item : raw.split(",")) {
            if (item.isBlank()) continue;
            String normalized = normalizeOrigin(item);
            if (normalized != null) origins.add(normalized);
        }
        return Set.copyOf(origins);
    }

    private static byte[] loadOrCreateSecret(Path file) throws IOException {
        if (Files.exists(file)) {
            byte[] existing = Files.readAllBytes(file);
            if (existing.length < 32) throw new IOException("auth jwt secret is invalid");
            return existing;
        }
        byte[] generated = new byte[32];
        RANDOM.nextBytes(generated);
        writeAtomic(file, generated);
        return generated;
    }

    private static void writeAtomic(Path target, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException failure) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String b64(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static String randomToken() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return b64(raw);
    }

    private record User(String id, String email, String passwordHash, String systemRole, boolean needsSetup,
            int tokenVersion) {}

    private record Claims(String subject, int version) {}

    private record Attempt(int count, Instant lockedUntil) {}
}
