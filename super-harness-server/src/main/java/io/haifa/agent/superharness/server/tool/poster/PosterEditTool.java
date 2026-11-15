package io.haifa.agent.superharness.server.tool.poster;

import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.util.Optional;

/** The bounded poster selection and date/copy tool. No paths, new visuals, QR URLs or commands are accepted. */
public final class PosterEditTool implements JavaTool<PosterEditTool.Input, PosterRenderTool.Output> {
    private static final JavaToolSpec<Input, PosterRenderTool.Output> SPEC =
            JavaToolSpec.builder("poster_edit", Input.class, PosterRenderTool.Output.class)
                    .title("选稿或更新当前海报")
                    .description("""
                            Record a user's explicit A or B draft selection with select_draft only (A or B).
                            Or update the current draft using only the date or copy fields the user changed.
                            Dates use YYYY-MM-DD; use empty end_date to change to a single day. The current draft
                            and its source image are resolved from this conversation. Use present_files for
                            returned files; a selection alone has no new file.
                            """)
                    .sideEffects(ToolSideEffect.FILE_WRITE)
                    .build();

    private final PosterRenderTool poster;

    public PosterEditTool(PosterRenderTool poster) {
        this.poster = poster;
    }

    @Override
    public JavaToolSpec<Input, PosterRenderTool.Output> spec() {
        return SPEC;
    }

    @Override
    public PosterRenderTool.Output invoke(Input input, JavaToolContext context) {
        return poster.edit(input, context);
    }

    public record Input(Optional<String> select_draft, Optional<String> title, Optional<String> subtitle,
                        Optional<String> details, Optional<String> start_date, Optional<String> end_date) {}
}
