package io.haifa.agent.superharness.server.integrations.lark;

/**
 * Network boundary for the official Lark/Feishu app-registration device flow.
 *
 * <p>Frozen DeerFlow performs this flow over plain HTTPS form posts to the brand accounts host
 * ({@code /oauth/v1/app/registration}); it never asks the user to run a terminal command. The
 * interface exists so offline contract tests can drive the exact pending/approved/slow-down
 * branches without claiming a live OAuth PASS.
 */
public interface LarkAppRegistration {
    /** What Lark returned for the browser step. */
    record Begin(String userCode, String deviceCode, Integer expiresIn, Integer interval) {}

    /** One poll outcome. An approved poll carries the app credentials. */
    record Poll(String appId, String appSecret, String tenantBrand) {}

    /** Pending, approved, or an explicit terminal error. */
    record PollOutcome(Kind kind, Poll approved, String error, String description) {
        public enum Kind { PENDING, SLOW_DOWN, APPROVED, ERROR }

        public static PollOutcome pending() {
            return new PollOutcome(Kind.PENDING, null, null, null);
        }

        public static PollOutcome slowDown() {
            return new PollOutcome(Kind.SLOW_DOWN, null, null, null);
        }

        public static PollOutcome approved(Poll poll) {
            return new PollOutcome(Kind.APPROVED, poll, null, null);
        }

        public static PollOutcome error(String error, String description) {
            return new PollOutcome(Kind.ERROR, null, error, description);
        }
    }

    /** Start a brand app-registration flow. */
    Begin begin(String brand);

    /** Poll once; never sleeps and never retries. */
    PollOutcome pollOnce(String deviceCode, String brand);

    /** Best-effort newest published CLI tag, or null when the GitHub API is unreachable. */
    String latestCliVersion();
}
