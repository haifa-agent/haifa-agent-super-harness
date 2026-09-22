package io.haifa.agent.superharness.harness;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Minimal deterministic verifier. It does not invent cases, expected values, or evidence. */
public final class EvidenceHarness {
    private EvidenceHarness() {}

    public static HarnessReport verify(Set<String> requiredCaseIds, List<CaseObservation> observations) {
        var required = new TreeSet<String>();
        for (String caseId : Set.copyOf(requiredCaseIds)) {
            required.add(CaseObservation.requireCaseId(caseId));
        }
        var copiedObservations = List.copyOf(observations);
        var issues = new ArrayList<String>();
        var observed = new HashSet<String>();

        for (CaseObservation observation : copiedObservations) {
            if (!observed.add(observation.caseId())) {
                issues.add("DUPLICATE_CASE:" + observation.caseId());
                continue;
            }
            if (observation.status() == CaseStatus.FAIL) {
                issues.add("FAILED_CASE:" + observation.caseId());
            } else if (observation.evidence().isBlank()) {
                issues.add("MISSING_EVIDENCE:" + observation.caseId());
            }
        }
        for (String caseId : required) {
            if (!observed.contains(caseId)) {
                issues.add("MISSING_CASE:" + caseId);
            }
        }
        issues.sort(String::compareTo);
        return new HarnessReport(issues.isEmpty(), required.size(), observed.size(), issues);
    }
}
