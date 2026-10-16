package io.haifa.agent.superharness.server.channels.feishu;

/** Non-200 Run stream response. {@code detail} is the API {@code detail} field only. */
public final class FeishuRunRejected extends Exception {
    private final int status;
    private final String detail;

    public FeishuRunRejected(int status, String detail) {
        super("Run stream failed: HTTP " + status);
        this.status = status;
        this.detail = detail == null ? "" : detail;
    }

    public int status() {
        return status;
    }

    public String detail() {
        return detail;
    }

    public static FeishuRunRejected find(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof FeishuRunRejected rejected) {
                return rejected;
            }
            current = current.getCause();
        }
        return null;
    }
}
