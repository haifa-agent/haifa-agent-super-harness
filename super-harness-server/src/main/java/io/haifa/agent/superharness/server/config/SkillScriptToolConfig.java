package io.haifa.agent.superharness.server.config;

import io.haifa.agent.execution.core.tool.TrustedScriptArgumentMapper;
import io.haifa.agent.execution.core.tool.TrustedScriptArguments;
import io.haifa.agent.skill.api.FrozenSkillBinding;
import io.haifa.agent.tool.api.ToolName;
import io.haifa.agent.tool.api.ToolSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Product declaration of the baoyu-image-gen Skill script Tool (the SDK's trusted skill script
 * entry). The declaration reuses the host shell switch: it stays <strong>off</strong> unless the
 * host enables {@link HostShellToolConfig} <em>and</em> declares the bun runtime, so the default
 * Tool surface, its disclosure and the frozen prompt signature stay byte-identical when off.
 *
 * <p>Everything the model could otherwise choose is a product fact: the runtime (the declared bun
 * executable), the script (the Skill package's frozen entry), the working directory (the current
 * Thread workspace root, so {@code outputs/} is reachable) and the Provider/argv shape (the mapper
 * maps only the bounded business arguments). The SDK entry validates the declaration again when it
 * assembles the platform, and the frozen Tool identity covers the runtime reference, the script
 * timeout and the approval purpose.
 */
public record SkillScriptToolConfig(
        Path workspaceRoot,
        Path dataRoot,
        Path bunExecutable,
        Duration defaultTimeout,
        Duration maximumTimeout,
        int maximumOutputBytes,
        int maximumOutputLines) {

    /** Alias of the seeded Skill whose frozen binding the Tool executes. */
    public static final String SKILL_ALIAS = "baoyu-image-gen";

    /** Product Tool alias the model calls. */
    public static final String TOOL_NAME = "baoyu_image_gen";

    /** Frozen entry of the Skill package, executed as a file (never through stdin). */
    public static final String SCRIPT_RELATIVE_PATH = "scripts/main.ts";

    /** Declared runtime reference; the SDK entry preflights the exact executable. */
    public static final String SCRIPT_RUNTIME_REF = "bun";

    /** Environment variable carrying the absolute bun executable (or its home directory). */
    public static final String BUN_ENVIRONMENT_KEY = "HAIFA_SUPER_HARNESS_BUN_PATH";

    /** Fixed Provider argument; it needs no API key and is not the model's choice. */
    static final String PROVIDER_ARGUMENT = "codex-cli";

    private static final Set<String> MODEL_ARGUMENTS = Set.of("prompt", "image_name", "help", "sleep_millis");

    public SkillScriptToolConfig {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null");
        Objects.requireNonNull(dataRoot, "dataRoot must not be null");
        Objects.requireNonNull(bunExecutable, "bunExecutable must not be null");
        Objects.requireNonNull(defaultTimeout, "defaultTimeout must not be null");
        Objects.requireNonNull(maximumTimeout, "maximumTimeout must not be null");
        if (!workspaceRoot.isAbsolute() || !dataRoot.isAbsolute() || !bunExecutable.isAbsolute()) {
            throw new IllegalArgumentException("declared paths must be absolute");
        }
        if (defaultTimeout.isNegative() || defaultTimeout.isZero()) {
            throw new IllegalArgumentException("defaultTimeout must be positive");
        }
        if (maximumTimeout.compareTo(defaultTimeout) < 0) {
            throw new IllegalArgumentException("maximumTimeout must not be below defaultTimeout");
        }
        if (maximumOutputBytes <= 0 || maximumOutputLines <= 0) {
            throw new IllegalArgumentException("output bounds must be positive");
        }
    }

    /** The Tool name as a public value object, so no caller re-derives the string. */
    public static ToolName toolName() {
        return new ToolName(TOOL_NAME);
    }

    /**
     * Resolves the whole declaration, or an empty value when any product fact is missing: the switch
     * stays off, the Skill is not admitted with its scripts entry, or the bun executable is not
     * declared. Every missing fact fails closed: no Tool surface, no prompt mention, no process.
     */
    public static Optional<SkillScriptToolConfig> fromEnvironment(
            Map<String, String> environment, Path workspaceRoot, Path dataRoot,
            SkillRunTools.Projection skillProjection) {
        Objects.requireNonNull(environment, "environment must not be null");
        if (!HostShellToolConfig.isEnabled(environment.get(HostShellToolConfig.ENVIRONMENT_KEY))) {
            return Optional.empty();
        }
        if (declaredBinding(skillProjection).isEmpty()) {
            return Optional.empty();
        }
        Path bun = bunExecutable(environment.get(BUN_ENVIRONMENT_KEY));
        if (bun == null) {
            return Optional.empty();
        }
        return Optional.of(new SkillScriptToolConfig(
                workspaceRoot,
                dataRoot,
                bun,
                // The real codex-cli generation runs for multiple minutes (the first full round
                // timed out at 120 s while the wrapper was still generating); the script timeout
                // therefore carries the product's largest declared bound, matching the host shell
                // maximum.
                HostShellToolConfig.MAXIMUM_TIMEOUT,
                HostShellToolConfig.MAXIMUM_TIMEOUT,
                HostShellToolConfig.MAXIMUM_OUTPUT_BYTES,
                HostShellToolConfig.MAXIMUM_OUTPUT_LINES));
    }

    /**
     * Tool names the declaration adds for a candidate Thread, used before any Bridge is built so the
     * prompt signature comparison stays consistent without assembling a platform.
     */
    public static Set<String> toolNames(
            Map<String, String> environment, SkillRunTools.Projection skillProjection) {
        // The names only depend on model-reachable declaration facts; a workspace root is not
        // involved, so callers compare them before binding any Thread workspace.
        if (!HostShellToolConfig.isEnabled(environment.get(HostShellToolConfig.ENVIRONMENT_KEY))) {
            return Set.of();
        }
        if (declaredBinding(skillProjection).isEmpty()) {
            return Set.of();
        }
        if (bunExecutable(environment.get(BUN_ENVIRONMENT_KEY)) == null) {
            return Set.of();
        }
        return Set.of(TOOL_NAME);
    }

    /**
     * The frozen binding of the declared Skill from the current catalog, or empty. Only enabled
     * Skills enter the catalog, and the binding must carry the declared entry file: the Tool never
     * binds a package whose loader will not serve the declared entry bytes.
     */
    public static Optional<FrozenSkillBinding> declaredBinding(SkillRunTools.Projection skillProjection) {
        if (skillProjection == null || !skillProjection.installTools()) {
            return Optional.empty();
        }
        return skillProjection.catalog().snapshot().bindings().stream()
                .filter(binding -> binding.alias().value().equals(SKILL_ALIAS))
                .filter(binding -> binding.packageIndex().resources().stream()
                        .anyMatch(resource -> resource.relativePath().equals(SCRIPT_RELATIVE_PATH)))
                .findFirst();
    }

    /**
     * Only an absolute path to an existing regular file qualifies; a directory resolves the bun
     * executable inside it. A symbolic link - the layout a packaged bun ships with, for example
     * {@code /usr/bin/bun -> ../lib/node_modules/bun/bin/bun.exe} - is followed to the file it names,
     * because the SDK entry requires the declared runtime to be an exact file rather than a link.
     * Anything else, including a link that does not resolve to a file, keeps the declaration empty,
     * so a typo can never widen the Tool surface and no host PATH guessing happens here either.
     */
    public static Path bunExecutable(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        Path path = Path.of(rawValue.strip());
        if (!path.isAbsolute()) {
            return null;
        }
        Path declared = Files.isSymbolicLink(path) ? resolvedTarget(path) : path;
        if (declared == null) {
            return null;
        }
        if (Files.isRegularFile(declared)) {
            return declared.toAbsolutePath().normalize();
        }
        if (Files.isDirectory(declared)) {
            for (String candidate : List.of("bun.exe", "bun")) {
                Path entry = declared.resolve(candidate);
                Path resolved = Files.isSymbolicLink(entry) ? resolvedTarget(entry) : entry;
                if (resolved != null && Files.isRegularFile(resolved)) {
                    return resolved.toAbsolutePath().normalize();
                }
            }
        }
        return null;
    }

    /**
     * The file or directory a symbolic link names, or null when the link cannot be resolved. The
     * declaration follows a link exactly as the operator wrote it; a broken link stays undeclared so
     * the Tool is off rather than admitted with a runtime the SDK entry would reject.
     */
    private static Path resolvedTarget(Path link) {
        try {
            return link.toRealPath();
        } catch (java.io.IOException broken) {
            return null;
        }
    }

    /** Product title of the Tool as shown on the approval card. */
    public static String title() {
        return "Run the baoyu-image-gen Skill script";
    }

    public static String description() {
        return "Executes the frozen baoyu-image-gen Skill entry script (scripts/main.ts) with the "
                + "declared bun runtime inside this Thread's workspace. The runtime, script, working "
                + "directory and outputs/ location are product-fixed; pass only the business "
                + "arguments: prompt, image_name, optional help and optional sleep_millis probe. "
                + "Every call requires approval; host execution is not a security boundary.";
    }

    /** Approval purpose; it is part of the frozen Tool identity. */
    public static String purpose() {
        return "generate an image with the baoyu-image-gen Skill script";
    }

    /**
     * The bounded input the model may provide; everything a host execution would otherwise accept
     * (runtime, script, working directory, output root) is deliberately absent, so no argument can
     * widen the execution surface.
     */
    public static ToolSchema inputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("prompt", Map.of(
                "type", "string",
                "minLength", 1,
                "maxLength", 4096,
                "description", "Describe the image to generate; the script receives it as --prompt."));
        properties.put("image_name", Map.of(
                "type", "string",
                "maxLength", 64,
                "description", "File name (a single safe path segment, .png) placed under outputs/; the mapper proofreads it."));
        properties.put("help", Map.of(
                "type", "boolean",
                "description", "Run the script entry with --help instead of generating an image."));
        properties.put("sleep_millis", Map.of(
                "type", "integer",
                "minimum", 0,
                "maximum", 600000,
                "description", "Acceptance probe: sleep that long before acting; 0 or omitted disables it."));
        return new ToolSchema(TOOL_NAME + "-input", "1.0.0", Map.of(
                "$schema", ToolSchema.DRAFT_2020_12,
                "type", "object",
                "additionalProperties", false,
                "properties", properties));
    }

    private static final Pattern IMAGE_NAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,55}\\.png$");

    /**
     * Product argument mapping: model arguments to fixed argv, with {@code --provider codex-cli} and
     * {@code --image outputs/<name>} always fixed for generation. Unknown arguments, unsafe output
     * names and out-of-bound probes fail closed here, before any approval carries weight and before
     * any process can start.
     */
    public static final class ImageGenArguments implements TrustedScriptArgumentMapper {
        @Override
        public TrustedScriptArguments map(Map<String, Object> arguments) {
            Objects.requireNonNull(arguments, "arguments must not be null");
            for (String key : arguments.keySet()) {
                if (!MODEL_ARGUMENTS.contains(key)) {
                    throw new IllegalArgumentException("unknown argument: " + key);
                }
            }
            if (Boolean.TRUE.equals(arguments.get("help"))) {
                return TrustedScriptArguments.atWorkspaceRoot(List.of("--help"));
            }
            String prompt = boundedText(arguments.get("prompt"), "prompt");
            String imageName = boundedText(arguments.get("image_name"), "image_name");
            if (!IMAGE_NAME.matcher(imageName).matches()) {
                throw new IllegalArgumentException(
                        "image_name must be a single safe path segment ending in .png within outputs/");
            }
            List<String> argv = new ArrayList<>();
            argv.add("--provider");
            argv.add(PROVIDER_ARGUMENT);
            argv.add("--image");
            argv.add("outputs/" + imageName);
            argv.add("--prompt");
            argv.add(prompt);
            if (arguments.get("sleep_millis") != null) {
                if (!(arguments.get("sleep_millis") instanceof Integer sleepMillis)
                        || sleepMillis < 0 || sleepMillis > 600_000) {
                    throw new IllegalArgumentException("sleep_millis must be an integer within 0..600000");
                }
                argv.add("--sleep");
                argv.add(Integer.toString(sleepMillis));
            }
            return TrustedScriptArguments.atWorkspaceRoot(List.copyOf(argv));
        }

        private static String boundedText(Object value, String field) {
            if (!(value instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException(field + " must be a non-empty string");
            }
            int maximum = field.equals("prompt") ? 4096 : 64;
            if (text.length() > maximum) {
                throw new IllegalArgumentException(field + " exceeds " + maximum + " characters");
            }
            return text;
        }
    }
}
