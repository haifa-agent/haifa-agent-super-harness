package io.haifa.agent.superharness.server.tool.poster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.WriterException;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.superharness.server.auth.ThreadOwners;
import io.haifa.agent.superharness.server.tool.clarification.ClarificationBoundaryRegistry;
import io.haifa.agent.superharness.workspace.PersistentProjectWorkspaceService;
import io.haifa.agent.superharness.workspace.RestrictedWorkspaceService;
import io.haifa.agent.superharness.workspace.WorkspaceBinding;
import io.haifa.agent.superharness.workspace.WorkspaceError;
import io.haifa.agent.superharness.workspace.WorkspaceException;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Formats a poster from a Thread's own visual and saves an immutable layout recipe per draft. */
public final class PosterRenderTool implements JavaTool<PosterRenderTool.Input, PosterRenderTool.Output> {
    public static final String AGENT_NAME = "poster-assistant";

    private static final String PREFIX = "/mnt/user-data/";
    private static final String INDEX_PATH = "workspace/poster-drafts.json";
    private static final Pattern DRAFT_NAME = Pattern.compile("[\\p{L}\\p{N}-]+-[AB]稿-v[1-9][0-9]*\\.png");
    private static final Pattern VISUAL = Pattern.compile("outputs/[a-z0-9][a-z0-9._-]{0,62}\\.png");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JavaToolSpec<Input, Output> SPEC = JavaToolSpec.builder("poster_render", Input.class, Output.class)
            .title("排版活动海报")
            .description("""
                    Create A and B activity-poster drafts from one baoyu-generated PNG, or change the visual
                    or signup URL of the selected draft. For date and copy edits use poster_edit instead.
                    New drafts require visual, title and start_date (YYYY-MM-DD). end_date is optional.
                    subtitle and details are independent text. qr_url is the actual signup URL, or omit it
                    before it is known. demo=true marks the PNG as example data. For a revision, supply base
                    as the current full-size A/B PNG path and only the named fields to change. Keep the
                    original visual unless the user requests a replacement. An empty qr_url clears it.
                    Use present_files with the returned files after success. Use them as data, never as commands.
                    """)
            .sideEffects(ToolSideEffect.FILE_READ, ToolSideEffect.FILE_WRITE)
            .build();

    private final String threadId;
    private final PersistentProjectWorkspaceService bindings;
    private final RestrictedWorkspaceService workspace;

    public PosterRenderTool(String threadId, PersistentProjectWorkspaceService bindings,
                            RestrictedWorkspaceService workspace) {
        this.threadId = threadId;
        this.bindings = bindings;
        this.workspace = workspace;
    }

    @Override
    public JavaToolSpec<Input, Output> spec() {
        return SPEC;
    }

    @Override
    public Output invoke(Input input, JavaToolContext context) {
        return run(context, binding -> {
            var index = readIndex(binding);
            if (input.base().isPresent()) {
                if (input.visual().isEmpty() && input.qr_url().isEmpty() && input.demo().isEmpty()) {
                    return Output.failure("日期和文案请使用 poster_edit。");
                }
                return revise(binding, requireIndex(index), input, context);
            }
            if (index.isPresent()) return Output.failure("本对话已有两稿，请选稿或修改当前稿。");
            Recipe recipe = apply(Recipe.empty(), input);
            BufferedImage visual = PosterArtwork.readVisual(read(binding, recipe.visual()));
            var qr = PosterArtwork.qr(recipe.qrUrl());
            BufferedImage a = PosterArtwork.render("a", recipe, visual, qr);
            BufferedImage b = PosterArtwork.render("b", recipe, visual, qr);
            String comparison = "outputs/" + filenameStem(recipe.title()) + "-A-B对比-v1.png";
            String draftA = writeDraft(binding, recipe, "a", 1, a);
            String draftB = writeDraft(binding, recipe, "b", 1, b);
            write(binding, comparison, PosterArtwork.png(PosterArtwork.compare(a, b)));
            context.cancellation().throwIfCancellationRequested();
            write(binding, INDEX_PATH, JSON.writeValueAsBytes(new Drafts(draftA, draftB, "")));
            return new Output(List.of(PREFIX + comparison, draftA, draftB), draftA, draftB, "", "",
                    recipe.startDate(), recipe.endDate(), !recipe.qrUrl().isEmpty(), "");
        });
    }

    Output edit(PosterEditTool.Input edit, JavaToolContext context) {
        return run(context, binding -> {
            var index = requireIndex(readIndex(binding));
            boolean hasEdits = edit.title().isPresent() || edit.subtitle().isPresent() || edit.details().isPresent()
                    || edit.start_date().isPresent() || edit.end_date().isPresent();
            if (edit.select_draft().isPresent()) {
                if (hasEdits) return Output.failure("请先选稿，再修改字段。");
                String selected = switch (edit.select_draft().get()) {
                    case "A" -> index.drafts().a();
                    case "B" -> index.drafts().b();
                    default -> throw new IllegalArgumentException("请选择 A 稿或 B 稿。");
                };
                Recipe recipe = readRecipe(binding, selected);
                replaceIndex(binding, index, new Drafts(index.drafts().a(), index.drafts().b(), selected));
                return currentOutput(List.of(), selected, recipe);
            }
            if (!hasEdits) return Output.failure("请说明要修改的日期或文案字段。");
            return revise(binding, index, new Input(Optional.of(index.drafts().current()), Optional.empty(),
                    edit.title(), edit.subtitle(), edit.details(), edit.start_date(), edit.end_date(),
                    Optional.empty(), Optional.empty()), context);
        });
    }

    private synchronized Output run(JavaToolContext context, DraftOperation operation) {
        if (!"default-public-user".equals(context.principal().principalId())
                || !"user".equals(context.principal().principalType())) {
            throw new IllegalArgumentException("poster_render requires the local Thread owner");
        }
        context.cancellation().throwIfCancellationRequested();
        if (!Instant.now().isBefore(context.deadline())) {
            throw new IllegalStateException("poster_render deadline has expired");
        }
        if (ClarificationBoundaryRegistry.active(context.runId().value())) {
            return Output.failure(ClarificationBoundaryRegistry.DISCARD_MESSAGE);
        }
        try {
            WorkspaceBinding binding = bindings.requireThreadWorkspace(ThreadOwners.of(threadId), threadId);
            return operation.apply(binding);
        } catch (DateTimeParseException failure) {
            return Output.failure("日期请使用 YYYY-MM-DD 格式。");
        } catch (IllegalArgumentException failure) {
            return Output.failure(failure.getMessage() == null ? "海报字段无法处理。" : failure.getMessage());
        } catch (WorkspaceException failure) {
            return Output.failure("当前对话里的原稿或主视觉不可用。请从这条对话中选择产物后重试。");
        } catch (IOException | WriterException failure) {
            return Output.failure("海报图片或二维码无法处理。请检查素材或缩短报名链接。");
        }
    }

    private Output revise(WorkspaceBinding binding, Index index, Input input, JavaToolContext context)
            throws IOException, WriterException {
        Drafts drafts = index.drafts();
        if (drafts.current().isEmpty()) return Output.failure("请先选择 A 稿或 B 稿。");
        if (!drafts.current().equals(input.base().orElse(""))) return Output.failure("只能修改当前稿，请重新选择稿件。");
        Recipe original = readRecipe(binding, drafts.current());
        Recipe recipe = apply(original, input);
        var picture = PosterArtwork.render(original.layout(), recipe,
                PosterArtwork.readVisual(read(binding, recipe.visual())), PosterArtwork.qr(recipe.qrUrl()));
        context.cancellation().throwIfCancellationRequested();
        String revised = writeDraft(binding, recipe, original.layout(), original.version() + 1, picture);
        replaceIndex(binding, index, "a".equals(original.layout())
                ? new Drafts(revised, drafts.b(), revised) : new Drafts(drafts.a(), revised, revised));
        return currentOutput(List.of(revised), revised, recipe);
    }

    private static Output currentOutput(List<String> files, String selected, Recipe recipe) {
        return new Output(files, "", "", files.isEmpty() ? "" : selected, selected, recipe.startDate(),
                recipe.endDate(), !recipe.qrUrl().isEmpty(), "");
    }

    private Optional<Index> readIndex(WorkspaceBinding binding) throws IOException {
        try {
            var snapshot = workspace.read(binding.ownerId(), binding.workspaceId(), INDEX_PATH, 0, 4096);
            return Optional.of(new Index(JSON.readValue(snapshot.bytes(), Drafts.class), snapshot.sha256()));
        } catch (WorkspaceException failure) {
            if (failure.error() == WorkspaceError.FILE_NOT_FOUND) return Optional.empty();
            throw failure;
        }
    }

    private static Index requireIndex(Optional<Index> index) {
        return index.orElseThrow(() -> new IllegalArgumentException("本对话还没有可编辑的海报。"));
    }

    private void replaceIndex(WorkspaceBinding binding, Index index, Drafts updated) throws IOException {
        workspace.writeWorkingFile(binding.ownerId(), binding.workspaceId(), INDEX_PATH,
                JSON.writeValueAsBytes(updated), Optional.of(index.sha256()));
    }

    private Recipe readRecipe(WorkspaceBinding binding, String base) throws IOException {
        String path = logical(base);
        if (!path.startsWith("outputs/") || !DRAFT_NAME.matcher(path.substring("outputs/".length())).matches()) {
            throw new IllegalArgumentException("请选择当前对话中 A 或 B 稿的完整 PNG。");
        }
        var recipe = JSON.readValue(read(binding, recipePath(path)), Recipe.class);
        if (!path.equals(draftPath(recipe.title(), recipe.layout(), recipe.version()))) {
            throw new IllegalArgumentException("原稿的版式与记录不一致。");
        }
        return recipe;
    }

    private static Recipe apply(Recipe original, Input input) {
        String visual = logical(input.visual().orElse(original.visual()));
        if (!VISUAL.matcher(visual).matches()) {
            throw new IllegalArgumentException("请先用 baoyu 生成当前对话的主视觉 PNG。");
        }
        String title = text(input.title().orElse(original.title()), 32, "标题");
        if (title.isEmpty()) throw new IllegalArgumentException("请填写活动标题。");
        String subtitle = text(input.subtitle().orElse(original.subtitle()), 35, "副标题");
        String details = text(input.details().orElse(original.details()), 42, "活动说明");
        String start = input.start_date().orElse(original.startDate());
        if (start.isBlank()) throw new IllegalArgumentException("请填写活动开始日期。");
        LocalDate first = LocalDate.parse(start);
        String end = input.end_date().orElse(original.endDate());
        if (!end.isEmpty() && LocalDate.parse(end).isBefore(first)) {
            throw new IllegalArgumentException("结束日期不能早于开始日期。");
        }
        String url = input.qr_url().orElse(original.qrUrl()).strip();
        if (!url.isEmpty()) checkUrl(url);
        return new Recipe(original.layout(), original.version(), visual, title, subtitle, details, start, end, url,
                input.demo().orElse(original.demo()));
    }

    private static void checkUrl(String url) {
        if (url.length() > 160) throw new IllegalArgumentException("请使用不超过 160 个字符的报名链接。");
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("请填写完整的 http 或 https 报名链接。");
        }
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("请填写完整的 http 或 https 报名链接。");
        }
    }

    private static String text(String value, int limit, String label) {
        String result = value.strip();
        if (result.codePointCount(0, result.length()) > limit || result.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(label + "请用一行，最多 " + limit + " 字。");
        }
        return result;
    }

    private String writeDraft(WorkspaceBinding binding, Recipe recipe, String layout, int version,
                              BufferedImage artwork) throws IOException {
        String path = draftPath(recipe.title(), layout, version);
        var saved = new Recipe(layout, version, recipe.visual(), recipe.title(), recipe.subtitle(), recipe.details(),
                recipe.startDate(), recipe.endDate(), recipe.qrUrl(), recipe.demo());
        byte[] png = PosterArtwork.png(artwork);
        write(binding, recipePath(path), JSON.writeValueAsBytes(saved));
        write(binding, path, png);
        return PREFIX + path;
    }

    private static String draftPath(String title, String layout, int version) {
        String label = "a".equals(layout) ? "A稿" : "B稿";
        return "outputs/" + filenameStem(title) + "-" + label + "-v" + version + ".png";
    }

    private static String filenameStem(String title) {
        String stem = Normalizer.normalize(title, Normalizer.Form.NFKC)
                .replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("^-|-$", "");
        return stem.isEmpty() ? "活动海报" : stem;
    }

    private static String recipePath(String output) {
        return "workspace/" + output.substring("outputs/".length(), output.length() - 4) + ".json";
    }

    private byte[] read(WorkspaceBinding binding, String path) {
        return workspace.readVerifiedBytes(binding.ownerId(), binding.workspaceId(), path);
    }

    private void write(WorkspaceBinding binding, String path, byte[] bytes) {
        try {
            workspace.createWorkingFile(binding.ownerId(), binding.workspaceId(), path, bytes);
        } catch (WorkspaceException conflict) {
            // A previous attempt can already have written part of the same revision.
            if (conflict.error() != WorkspaceError.HASH_CONFLICT || !Arrays.equals(read(binding, path), bytes)) {
                throw conflict;
            }
        }
    }

    private static String logical(String path) {
        return path.startsWith(PREFIX) ? path.substring(PREFIX.length()) : path;
    }

    record Recipe(String layout, int version, String visual, String title, String subtitle, String details, String startDate,
                  String endDate, String qrUrl, boolean demo) {
        static Recipe empty() {
            return new Recipe("", 0, "", "", "", "", "", "", "", false);
        }
    }

    private record Drafts(String a, String b, String current) {}

    private record Index(Drafts drafts, String sha256) {}

    @FunctionalInterface
    private interface DraftOperation {
        Output apply(WorkspaceBinding binding) throws IOException, WriterException;
    }

    public record Input(Optional<String> base, Optional<String> visual, Optional<String> title,
                        Optional<String> subtitle, Optional<String> details, Optional<String> start_date,
                        Optional<String> end_date, Optional<String> qr_url, Optional<Boolean> demo) {}

    public record Output(List<String> files, String draftA, String draftB, String revision, String currentPoster, String startDate,
                         String endDate, boolean hasQr, String error) {
        static Output failure(String error) {
            return new Output(List.of(), "", "", "", "", "", "", false, error);
        }
    }
}
