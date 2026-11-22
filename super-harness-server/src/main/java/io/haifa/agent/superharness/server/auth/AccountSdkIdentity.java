package io.haifa.agent.superharness.server.auth;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.sdk.api.SdkCaller;
import io.haifa.agent.sdk.tool.JavaToolContext;
import java.util.Map;

/** Maps a server-verified product owner to the identity shared by the SDK and its Tools. */
public final class AccountSdkIdentity {
    public static final String BIND_NEW_THREADS = "HAIFA_SUPER_HARNESS_ACCOUNT_SDK_BIND_NEW_THREADS";
    private static final TenantRef TENANT = new TenantRef("super-harness");

    private AccountSdkIdentity() {}

    public static boolean bindNewThreads(Map<String, String> environment) {
        return switch (environment.getOrDefault(BIND_NEW_THREADS, "false")) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(BIND_NEW_THREADS + " must be true or false");
        };
    }

    public static SdkCaller forOwner(String ownerId) {
        if (ownerId == null || ownerId.isBlank() || AccessOwner.DEFAULT.equals(ownerId)) {
            throw new IllegalArgumentException("A product account is required");
        }
        return new SdkCaller(TENANT, new PrincipalRef(ownerId, "user"));
    }

    public static boolean matches(SdkCaller caller, JavaToolContext context) {
        return caller.tenant().equals(context.tenant()) && caller.principal().equals(context.principal());
    }
}
