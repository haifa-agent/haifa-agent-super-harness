package io.haifa.agent.superharness.server.integrations.lark;

import java.util.List;
import java.util.Set;

/**
 * The frozen official {@code larksuite/cli} skill-pack identity.
 *
 * <p>The exact 27 names are copied from the frozen DeerFlow
 * {@code LARK_SKILL_NAMES} tuple at
 * {@code backend/packages/harness/deerflow/integrations/lark_cli.py}. The pack is an official,
 * versioned first-party integration package, not user-authored mutable content, so it is never
 * routed through the custom-skill upload path. Discovery, activation and execution stay in the
 * existing {@code SkillConfigStore} / SDK Skill platform.
 */
public final class LarkSkillCatalog {
    /** Frozen DeerFlow fallback when the runtime CLI reports no parseable version. */
    public static final String FALLBACK_VERSION = "v1.0.65";

    /** Official npm package that provides the Gateway-side CLI binary. */
    public static final String NPM_PACKAGE = "@larksuite/cli";

    /** Official source repository of the CLI and its skill pack. */
    public static final String GITHUB_REPOSITORY = "larksuite/cli";

    /** Exact frozen skill names; count assertions must use this tuple, never an approximation. */
    public static final List<String> NAMES = List.of(
            "lark-approval",
            "lark-apps",
            "lark-attendance",
            "lark-base",
            "lark-calendar",
            "lark-contact",
            "lark-doc",
            "lark-drive",
            "lark-event",
            "lark-im",
            "lark-mail",
            "lark-markdown",
            "lark-minutes",
            "lark-note",
            "lark-okr",
            "lark-openapi-explorer",
            "lark-shared",
            "lark-sheets",
            "lark-skill-maker",
            "lark-slides",
            "lark-task",
            "lark-vc",
            "lark-vc-agent",
            "lark-whiteboard",
            "lark-wiki",
            "lark-workflow-meeting-summary",
            "lark-workflow-standup-report");

    /** Set view of {@link #NAMES} used for membership checks. */
    public static final Set<String> NAME_SET = Set.copyOf(NAMES);

    /** Skill whose presence proves the pack was extracted, mirroring frozen install semantics. */
    public static final String GUARD_SKILL = "lark-shared";

    /** Environment key that pre-stages the official source archive (offline/air-gapped install). */
    public static final String ARCHIVE_ENV = "HAIFA_SUPER_HARNESS_LARK_SKILLS_ARCHIVE";

    /** Frozen DeerFlow environment key for a pre-staged archive; accepted as a fallback. */
    public static final String FROZEN_ARCHIVE_ENV = "DEER_FLOW_LARK_CLI_SKILLS_ARCHIVE";

    private LarkSkillCatalog() {}
}
