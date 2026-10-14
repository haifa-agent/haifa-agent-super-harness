package io.haifa.agent.superharness.server.auth;

import java.util.function.Supplier;

/**
 * Request-scoped product identity. Unset means the auth-disabled principal {@code default}.
 * Run and tool work that may leave the request thread must use {@link ThreadOwners}.
 */
public final class AccessOwner {
    public static final String DEFAULT = "default";

    private static final ThreadLocal<String> ID = new ThreadLocal<>();
    private static final ThreadLocal<String> ROLE = new ThreadLocal<>();

    private AccessOwner() {}

    public static String current() {
        String value = ID.get();
        return value == null || value.isBlank() ? DEFAULT : value;
    }

    /** Unbound callers keep the auth-disabled administrator behavior. */
    public static boolean admin() {
        String role = ROLE.get();
        return role == null || role.isBlank() || "admin".equals(role);
    }

    public static void bind(String ownerId, String systemRole) {
        ID.set(ownerId);
        ROLE.set(systemRole);
    }

    public static void clear() {
        ID.remove();
        ROLE.remove();
    }

    public static <T> T call(String ownerId, Supplier<T> work) {
        String previousId = ID.get();
        String previousRole = ROLE.get();
        ID.set(ownerId);
        try {
            return work.get();
        } finally {
            if (previousId == null) ID.remove();
            else ID.set(previousId);
            if (previousRole == null) ROLE.remove();
            else ROLE.set(previousRole);
        }
    }
}
