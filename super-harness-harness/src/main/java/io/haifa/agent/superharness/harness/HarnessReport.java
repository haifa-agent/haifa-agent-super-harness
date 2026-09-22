package io.haifa.agent.superharness.harness;

import java.util.List;

public record HarnessReport(boolean passed, int requiredCaseCount, int observedCaseCount, List<String> issues) {
    public HarnessReport {
        issues = List.copyOf(issues);
        if (passed != issues.isEmpty()) {
            throw new IllegalArgumentException("passed must match whether issues are empty");
        }
    }

    public HarnessReport requirePassed() {
        if (!passed) {
            throw new IllegalStateException("HARNESS_FAILED: " + String.join(",", issues));
        }
        return this;
    }
}
