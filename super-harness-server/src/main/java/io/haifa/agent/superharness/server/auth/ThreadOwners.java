package io.haifa.agent.superharness.server.auth;

import java.util.concurrent.ConcurrentHashMap;

/** Thread id to the owner saved when the thread was created. */
public final class ThreadOwners {
    private static final ConcurrentHashMap<String, String> OWNERS = new ConcurrentHashMap<>();

    private ThreadOwners() {}

    public static void remember(String threadId, String ownerId) {
        if (threadId == null || threadId.isBlank()) return;
        OWNERS.put(threadId, ownerId == null || ownerId.isBlank() ? AccessOwner.DEFAULT : ownerId);
    }

    public static String of(String threadId) {
        if (threadId == null) return AccessOwner.DEFAULT;
        return OWNERS.getOrDefault(threadId, AccessOwner.DEFAULT);
    }

    /** Forgets a deleted thread so a recycled id cannot inherit the previous owner. */
    public static void forget(String threadId) {
        if (threadId == null || threadId.isBlank()) return;
        OWNERS.remove(threadId);
    }
}
