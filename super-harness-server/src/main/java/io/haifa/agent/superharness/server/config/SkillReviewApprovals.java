package io.haifa.agent.superharness.server.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.sdk.api.SdkCaller;
import io.haifa.agent.skill.api.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Maps product screening evidence to the public SDK's exact-package review grants. */
final class SkillReviewApprovals {
    static final String PRODUCT = "haifa-super-harness";
    private SkillReviewApprovals() {}

    static SkillDiscoveryContext discovery() {
        var caller = SdkCaller.defaultPublicUser();
        return new SkillDiscoveryContext(new SkillVisibilityContext(caller.tenant(), caller.principal(),
                Optional.empty(), false, java.util.Set.of(SkillScope.USER, SkillScope.PRODUCT)));
    }

    static SkillTrustSubject subject() {
        var caller = SdkCaller.defaultPublicUser();
        return new SkillTrustSubject(caller.tenant(), caller.principal(), PRODUCT, Optional.empty());
    }

    static ObjectNode evidence(ObjectMapper json, SkillRegistration registration, JsonNode scan) {
        String decision = scan.path("decision").asText();
        if (!java.util.Set.of("allow", "warn").contains(decision)) throw new IllegalArgumentException("Skill review did not permit publication");
        return json.createObjectNode().put("registration_digest", registration.registrationDigest().value())
                .put("package_digest", registration.packageIndex().digest().value())
                .put("issued_at", Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString())
                .put("decision", decision).put("revoked", false);
    }

    static SkillPackageReviewGrant grant(SkillRegistration registration, JsonNode evidence) {
        if (!registration.registrationDigest().value().equals(evidence.path("registration_digest").asText())
                || !registration.packageIndex().digest().value().equals(evidence.path("package_digest").asText())
                || !java.util.Set.of("allow", "warn").contains(evidence.path("decision").asText())) {
            throw new IllegalStateException("Skill review evidence does not match the exact SDK package");
        }
        var caller = SdkCaller.defaultPublicUser();
        Instant issued = Instant.parse(evidence.path("issued_at").asText());
        boolean revoked = evidence.path("revoked").asBoolean(false);
        boolean human = "human".equals(evidence.path("authorization_source").asText());
        return new SkillPackageReviewGrant("sh-review-" + registration.registrationDigest().value(), 1, 1,
                caller.tenant(), caller.principal(), PRODUCT,
                registration.coordinate().scope().scope() == SkillScope.USER ? SkillTrustScope.USER : SkillTrustScope.PRODUCT, Optional.empty(),
                registration.coordinate(), registration.registrationDigest(), registration.packageIndex().digest(),
                issued, Optional.empty(), revoked ? Optional.of(issued) : Optional.empty(),
                revoked ? SkillTrustGrantState.REVOKED : SkillTrustGrantState.ACTIVE,
                human ? "human-operator" : "configured-skill-screen", "product-package-publication",
                human ? "HUMAN_AUTHORIZED_PACKAGE" : "SCREENED_PACKAGE");
    }

    static SkillTrustSnapshot snapshot(List<SkillPackageReviewGrant> grants) {
        String digest = SkillPackageFiles.hash(grants.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new SkillTrustSnapshot("sha256:" + digest, grants, List.of());
    }
}
