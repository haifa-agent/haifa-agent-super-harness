# W18 frozen UI smoke

This opt-in harness launches the real frozen Next frontend, the Haifa gateway,
and a process-local OpenAI-compatible provider. It exists to collect browser
contract evidence; it is not part of the default test suite and does not turn
failed or unimplemented parity paths into passing assertions.

Run it from the repository root:

```powershell
pwsh -NoProfile -File scripts/run-w18-ui-smoke.ps1 `
  -SourceRepository D:\workspace\haifa-agent-super-harness\deer-flow
```

The runner owns only slot 2 gateway/frontend ports (`38018` and `53018`). The
controlled provider binds to `127.0.0.1:0`, publishes its actual address and PID
through repo-local ready metadata, and is stopped by its exact process object.
Both `SUPER_HARNESS_DATA_ROOT` and `SUPER_HARNESS_DATA_DIR` point at
`target/agent-slot-2-data`.

The four Playwright cases are independent so a reload failure cannot skip
cancellation evidence:

- no-cookie auth startup plus executable model catalog;
- Thread creation, Run/SSE rendering, replay event IDs, and terminal status;
- the frozen `?wait=false&action=interrupt` cancellation request;
- reload of a completed thread.

## Current captured blocker

The 2026-09-23 slot 2 run passed auth startup and the managed-model probe, but
all three Run-dependent cases stopped at the initial Run POST with HTTP 422.
Run/SSE is therefore `FAIL_422`; the actual cancel request and post-completion
reload assertion are `NOT_RUN` because their Run-creation prerequisite failed.
The sanitized network contract is recorded in
`evidence/20260923-run-stream-422.json`.

The browser sends JSON to
`/api/langgraph/threads/<id>/runs/stream`. The declarative Next rewrite maps it
unchanged to gateway path `/api/threads/<id>/runs/stream`. The frozen UI's
human message uses LangChain content blocks:

```json
{"type":"human","content":[{"type":"text","text":"<prompt>"}]}
```

`DeerFlowThreadRunApi.lastHumanMessage` currently calls `asText("")` on the
`content` node, so an array becomes empty and the handler returns
`input.messages requires a human message`. This is a W02 endpoint contract gap,
not a provider failure: the real managed-model API accepted the enabled model,
its connection probe passed, and the model appeared in `/api/models` before the
browser tests began.

The smallest compatible W02 repair is to preserve string content and additionally
extract non-empty `text` values from array content blocks whose `type` is
`text`, with contract tests for both shapes and empty/unsupported arrays. Until
that separate repair lands, Run/SSE remains failed, while cancellation, reload,
real external provider, and DeerFlow differential remain fail-closed `NOT_RUN`
and must not be reported as passing.
