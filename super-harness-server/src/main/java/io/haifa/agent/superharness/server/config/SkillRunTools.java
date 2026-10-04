package io.haifa.agent.superharness.server.config;

import com.fasterxml.jackson.databind.JsonNode;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.skill.api.SkillCatalog;
import io.haifa.agent.skill.api.SkillContentLoader;
import io.haifa.agent.skill.api.SkillDiscoveryContext;
import io.haifa.agent.skill.api.SkillResolutionPolicy;
import io.haifa.agent.skill.api.SkillScope;
import io.haifa.agent.skill.api.SkillSource;
import io.haifa.agent.skill.api.SkillVisibilityContext;
import io.haifa.agent.skill.core.CompositeSkillContentLoader;
import io.haifa.agent.skill.core.SkillCatalogBuilder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Maps the trusted package configuration to public SDK Skill declarations. */
public final class SkillRunTools {
    private SkillRunTools() {}

    public record Selection(Optional<Set<String>> names) {
        public Selection {
            names = names.map(Set::copyOf);
        }

        boolean admits(String name) {
            return names.map(allowed -> allowed.contains(name)).orElse(true);
        }

        public static Selection all() {
            return new Selection(Optional.empty());
        }

        public static Selection none() {
            return new Selection(Optional.of(Set.of()));
        }

        public static Selection of(Set<String> names) {
            return new Selection(Optional.of(names));
        }

        public static Selection fromJson(JsonNode value) {
            if (value == null || value.isNull()) return all();
            if (!value.isArray()) throw new IllegalArgumentException("skills must be an array or null");
            Set<String> names = new LinkedHashSet<>();
            value.forEach(item -> {
                if (!item.isTextual() || item.asText().isBlank()) {
                    throw new IllegalArgumentException("skills entries must be non-empty strings");
                }
                names.add(item.asText());
            });
            return new Selection(Optional.of(names));
        }
    }

    public record Package(
            String name,
            String description,
            String category,
            String license,
            String sourceName,
            String sourceSha,
            String version,
            String contentHash,
            boolean active,
            boolean enabledByDefault,
            SkillSource source) {}

    public record Projection(
            SkillCatalog catalog,
            SkillContentLoader loader,
            Set<String> allowedSkills,
            String fingerprint,
            List<Package> selectedPackages,
            boolean recovery,
            io.haifa.agent.skill.api.SkillTrustSnapshot trust) {
        public Projection {
            allowedSkills = Set.copyOf(allowedSkills);
            selectedPackages = List.copyOf(selectedPackages);
        }

        boolean hasSkills() {
            return !selectedPackages.isEmpty();
        }

        /** A restored Run may still call an SDK Skill Tool from its frozen binding. */
        public boolean installTools() {
            return hasSkills() || recovery;
        }

        public Projection forRecovery() {
            return new Projection(catalog, loader, allowedSkills, fingerprint, selectedPackages, true, trust);
        }
    }

    static Projection projection(List<Package> packages, Map<String, Boolean> enabled, Selection selection) {
        return projection(packages, enabled, selection, io.haifa.agent.skill.api.SkillTrustSnapshot.empty());
    }

    static Projection projection(List<Package> packages, Map<String, Boolean> enabled, Selection selection,
            io.haifa.agent.skill.api.SkillTrustSnapshot trust) {
        List<Package> selected = packages.stream()
                .filter(Package::active)
                .filter(pkg -> enabled.getOrDefault(pkg.name(), pkg.enabledByDefault()))
                .filter(pkg -> selection.admits(pkg.name()))
                .sorted(java.util.Comparator.comparing(Package::name))
                .toList();
        List<SkillSource> currentSources = selected.stream().map(Package::source).toList();
        // Retain every immutable published version in the loader. A persisted SDK Run
        // already holds its frozen binding, even when a later catalog is empty.
        List<SkillSource> retainedSources = packages.stream().map(Package::source).toList();
        SkillDiscoveryContext discovery = SkillReviewApprovals.discovery();
        // LocalDirectorySkillSource records the exact immutable package location
        // during discover(). A retained source absent from today's catalog still
        // needs that index when a persisted Run validates its frozen binding.
        for (SkillSource source : retainedSources) {
            if (source.discover(discovery).registrations().size() != 1) {
                throw new IllegalStateException("retained Skill source is unavailable or ambiguous");
            }
        }
        SkillCatalog catalog = new SkillCatalogBuilder(currentSources,
                new SkillResolutionPolicy("super-harness-bundled-skills", List.of(SkillScope.USER, SkillScope.PRODUCT), false),
                trust, SkillReviewApprovals.subject(), java.time.Clock.systemUTC())
                .build(discovery);
        if (catalog.snapshot().bindings().size() != selected.size()) {
            throw new IllegalStateException("enabled Skill catalog is incomplete or ambiguous: " + catalog.snapshot().diagnostics());
        }
        Set<String> allowed = selected.stream().map(Package::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        StringBuilder canonical = new StringBuilder(trust.manifestDigest()).append('\n');
        selected.forEach(pkg -> canonical.append(pkg.name()).append('\n')
                .append(pkg.sourceName()).append('\n').append(pkg.version()).append('\n')
                .append(pkg.contentHash()).append('\n'));
        return new Projection(catalog, new CompositeSkillContentLoader(retainedSources),
                allowed, fingerprint(canonical.toString()), selected, false, trust);
    }

    private static String fingerprint(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
