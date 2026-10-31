package io.haifa.agent.superharness.server.integrations.lark;

/**
 * A delayed configuration or authorization flow is no longer the current one for this user.
 *
 * <p>Mirrors frozen DeerFlow {@code LarkFlowSupersededError}: the HTTP projection maps it to
 * {@code 409} so a late browser callback cannot overwrite a newer flow. The generation is a
 * server-owned per-user fact; a client-side flow counter cannot stand in for it.
 */
public final class LarkFlowSupersededException extends RuntimeException {
    static final String DETAIL = "This Lark integration flow was superseded by a newer action.";

    public LarkFlowSupersededException() {
        super(DETAIL);
    }
}
