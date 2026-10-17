package io.haifa.agent.superharness.server.config;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Product approval level of this Gateway process.
 *
 * <p>The level is a product decision, not a Runtime policy owner: the SDK still evaluates every
 * Tool request, and this record only says which policy data the product declares. It mirrors the
 * upstream Coding Agent CLI switch ({@code --approval ask|auto|deny}).
 *
 * <p>The switch is read from the process environment with the same shape as the other Super
 * Harness switches ({@code HAIFA_SUPER_HARNESS_*}). A missing, blank or unrecognised value keeps
 * {@link Mode#ASK}, so a typo can never widen approval. {@link Mode#AUTO} is a trust expansion for
 * a trusted local host: it removes the product side-effect approval rules and lets the SDK execute
 * {@code approvalRequirement=ALWAYS} Tools, but it does not disable credential reauthentication,
 * workspace bounds, the broker or the sandbox.
 *
 * <p>Switching the level takes effect for Runs built after the Gateway restart; a pending
 * Interaction keeps the level it was raised under.
 */
public record ApprovalPolicyConfig(Mode mode) {

    /** Product approval levels; the names match the environment values. */
    public enum Mode {
        ASK,
        AUTO,
        DENY
    }

    /** Environment variable that selects the level; any unrecognised value keeps {@link Mode#ASK}. */
    public static final String ENVIRONMENT_KEY = "HAIFA_SUPER_HARNESS_APPROVAL_MODE";

    /** Fail-closed product default: every governed Tool call still requires approval. */
    public static final Mode DEFAULT_MODE = Mode.ASK;

    public ApprovalPolicyConfig {
        Objects.requireNonNull(mode, "mode must not be null");
    }

    /** Resolves the level declared by the process environment. */
    public static ApprovalPolicyConfig fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment must not be null");
        return new ApprovalPolicyConfig(parse(environment.get(ENVIRONMENT_KEY)));
    }

    /** Parses one declared value; anything other than {@code auto}/{@code deny} keeps {@code ask}. */
    public static Mode parse(String rawValue) {
        if (rawValue == null) {
            return DEFAULT_MODE;
        }
        return switch (rawValue.strip().toLowerCase(Locale.ROOT)) {
            case "auto" -> Mode.AUTO;
            case "deny" -> Mode.DENY;
            default -> DEFAULT_MODE;
        };
    }
}
