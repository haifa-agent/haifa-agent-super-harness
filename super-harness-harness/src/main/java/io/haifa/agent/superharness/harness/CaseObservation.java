package io.haifa.agent.superharness.harness;

import java.util.Objects;

public record CaseObservation(String caseId, CaseStatus status, String evidence) {
    public CaseObservation {
        caseId = requireCaseId(caseId);
        status = Objects.requireNonNull(status, "status must not be null");
        evidence = Objects.requireNonNull(evidence, "evidence must not be null");
    }

    public static CaseObservation pass(String caseId, String evidence) {
        return new CaseObservation(caseId, CaseStatus.PASS, evidence);
    }

    public static CaseObservation fail(String caseId, String evidence) {
        return new CaseObservation(caseId, CaseStatus.FAIL, evidence);
    }

    static String requireCaseId(String value) {
        String normalized = Objects.requireNonNull(value, "caseId must not be null").trim();
        if (!normalized.matches("T-[A-Za-z0-9][A-Za-z0-9._-]{2,127}")) {
            throw new IllegalArgumentException("invalid caseId: " + normalized);
        }
        return normalized;
    }
}
