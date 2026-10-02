# W18 frozen UI smoke

This opt-in harness launches the real frozen Next frontend, the Haifa gateway,
and a process-local OpenAI-compatible provider. It exists to collect browser
contract evidence; it is not part of the default test suite and does not turn
failed or unimplemented parity paths into passing assertions.

Run it from the repository root:

```powershell
pwsh -NoProfile -File scripts/ui/run-w18-ui-smoke.ps1 `
  -SourceRepository D:\workspace\haifa-agent-super-harness\deer-flow
```

The W05 active-stream reconnect case uses the same controlled provider and fixed
slot, but an independent spec and evidence directory:

```powershell
pwsh -NoProfile -File scripts/ui/run-w18-ui-smoke.ps1 `
  -SourceRepository D:\workspace\haifa-agent-super-harness\deer-flow `
  -ActiveReconnect -SkipBuild
```

It reloads the frozen page while the creator Run is still `running`, proves the
SDK's initial observer join uses `Last-Event-ID: -1`, checks ordered/non-duplicate
identified frames, waits for authoritative terminal `success`, and asserts that
the assistant message is rendered exactly once. The Java Oracle separately covers
retention gap plus authoritative recovery and the creator-cancel/observer-continue
boundary.

The W05 Interaction acceptance profile uses a guarded test-classpath server entry point. It adds
one approval-gated JavaTool fixture without changing the executable JAR or normal bootstrap:

```powershell
pwsh -NoProfile -File scripts/ui/run-w05-interaction-smoke.ps1
```

It runs the unmodified frozen card twice: approve and reject. Both cases capture the exact hidden
`human_input_response` POST, require the same SDK Run in both `Content-Location` responses, wait
for terminal `success`, and reload the page to prove the answered card closes from the same-process
message read model. The SDK duplicate path is intentionally covered by the HTTP test as a 409, not
as a fabricated replay stream. This profile is fixture evidence only; product Tool configuration
and process-restart restoration remain `NOT_RUN`.

The W03 title/suggestions profile reuses the controlled loopback provider and the unmodified frozen
frontend. It never mocks the page or the protocol: the auto-title travels through the real
Run/SSE/metadata path and the follow-up suggestions through the real
`POST /api/threads/{thread_id}/suggestions` call, whose controlled provider answer is a deterministic
JSON array. It asserts the first-turn header/document title, `GET /api/suggestions/config`
(`enabled`/`max_suggestions`), the rendered suggestion buttons, and an explicit rename that wins and
survives a reload:

```powershell
pwsh -NoProfile -File scripts/ui/run-w18-ui-smoke.ps1 `
  -SourceRepository D:\workspace\haifa-agent-super-harness\deer-flow `
  -AgentSlot 3 -TitleSuggestions
```

`-TitleSuggestions` selects `w03-title-suggestions.spec.cjs`, uses
`target/agent-slot-<n>-title-suggestions-data`, and writes evidence under
`local-tmp/slot-N-w03-title-suggestions/evidence/run-<timestamp>`. It is mutually exclusive with
`-ActiveReconnect`, `-Interaction`, and `-LiveDeepSeek`, and it never starts the live provider.

The W03 input-polish profile reuses the controlled loopback provider and the unmodified frozen
frontend. The frozen composer calls the same-origin `POST /api/input-polish`; the gateway performs
one bounded non-streaming provider call, and the rewrite/undo/cancel/error state is the frozen
`input-box.tsx` implementation. It asserts the exact `{text, locale, thread_id}` wire body, that
polishing creates no Thread/Run/Tool/Artifact (`404` on the candidate thread id and zero run-stream
requests), that a follow-up send submits only the current edit-box text, that cancel/unmount drop a
late response and ignore duplicate clicks, and that whitespace/over-limit/provider-failure/empty/
think-block/code-fence answers fail closed without leaking provider details. It runs the same spec
twice with independent data-roots:

```powershell
pwsh -NoProfile -File scripts/ui/run-w03-input-polish-ui.ps1
```

`-InputPolish` selects `w03-input-polish.spec.cjs`, defaults the data root to
`target/agent-slot-<n>-input-polish-data`, and writes evidence to
`local-tmp/slot-<n>-w03-input-polish-ui/evidence` so a later `mvn clean` cannot destroy it. It is
mutually exclusive with the other profiles and never starts the live provider. The wrapper
snapshots each round's Playwright results/artifacts under `evidence/round-1` / `evidence/round-2`.

The explicitly authorized DeepSeek L3 variant uses the same runner and slot,
but a separate data/evidence directory and one independent Playwright case:

```powershell
pwsh -NoProfile -File scripts/ui/run-w18-ui-smoke.ps1 `
  -SourceRepository D:\workspace\haifa-agent-super-harness\deer-flow `
  -LiveDeepSeek -SkipBuild
```

`-LiveDeepSeek` never starts the controlled provider and never accepts an API
key argument or environment value. The gateway resolves only the existing
Windows Credential Manager reference `os://DEEPSEEK_API_KEY`. It saves the
official `https://api.deepseek.com` endpoint, which selects Haifa's
`deepseek-openai-chat` dialect, and uses `deepseek-v4-pro`. This command incurs
a real external model call and therefore must only be run when explicitly
authorized.

The runner defaults to slot 2 gateway/frontend ports (`30618` and `35618`). A
subagent may pass its assigned `-AgentSlot`, `-GatewayPort`, and
`-FrontendPort`; the runner verifies the registered port formula before
starting anything. Historical evidence entries below retain the former ports
and must not be used as defaults for new runs. The controlled provider binds
to `127.0.0.1:0`, publishes its actual address and PID
through repo-local ready metadata, and is stopped by its exact process object.
Both `SUPER_HARNESS_DATA_ROOT` and `SUPER_HARNESS_DATA_DIR` point at
the selected `target/agent-slot-<n>-data` unless an in-worktree data directory is
passed explicitly.

The four Playwright cases are independent so a reload failure cannot skip
cancellation evidence:

- no-cookie auth startup plus executable model catalog;
- Thread creation, Run/SSE rendering, replay event IDs, and terminal status;
- the frozen SDK's `?wait=0&action=interrupt` cancellation request;
- reload of a completed thread.

## Latest captured result

The 2026-09-24 slot 3 cold run and same-data-root warm restart each passed all
four independent Chromium cases against the packaged Haifa gateway and the
unmodified frozen frontend:

- no-cookie local auth and the executable managed-model catalog;
- Thread creation plus a real controlled-provider Run whose tuple payload was
  delivered as LangGraph Platform `event: messages` and rendered by the UI;
- the frozen SDK's actual `?wait=0&action=interrupt` request, HTTP 202, and
  terminal `interrupted` status;
- reload of a completed Thread with both human and assistant messages restored
  through `/messages/page` and `/history`.

The initial cold execution used a fresh SQLite authority and took approximately
254 seconds end-to-end, including the Maven package and frozen Next production
build. Next reported 10.1 seconds compilation, 13.1 seconds TypeScript, and 4.9
seconds static generation. After the final clean Java gate, the already-verified
package and frontend build were exercised again from a new SQLite database:
Playwright passed in 13.6 seconds (1.2/3.8/2.5/4.5 seconds) and the full runner
took approximately 58 seconds. Its same-data-root warm restart passed in 16.0
seconds (1.3/4.2/3.4/5.1 seconds) and took 60.821 seconds end-to-end. Neither
latest execution retried or encountered a timeout or startup hang.

After the warm run stopped, a third gateway-only process was started against
the same data root and Credential Manager reference. It queried the oldest
thread created by the cold run (not a warm-process cache entry):
`/messages/page`, `/history`, and `/runs` each returned HTTP 200 with 2, 1, and
1 durable records respectively. The gateway was then stopped by its exact PID
and port `48018` was verified free. Historical reads deliberately remain
available when a restarted controlled provider has a different ephemeral
binding revision; starting a new Run on that old thread still fails with HTTP
409 rather than silently changing its model binding.

The reload evidence records HTTP 200 responses from both
`/api/threads/{id}/messages/page` and
`/api/langgraph/threads/{id}/history`; Run/SSE evidence records requested tuple
payloads on wire event `messages`. Screenshots, traces, process logs, and JSON
attachments are under ignored `local-tmp/slot-N-w18-ui-smoke/evidence/run-<timestamp>`. The
runner stopped its exact provider, Gateway, and frontend processes after both
runs; slot ports `48018`/`63018` and the two provider ports were verified free.

The controlled-provider result remains L2 wiring evidence. On 2026-09-23 the
separately authorized DeepSeek L3 case also passed once through the same frozen
page: non-empty assistant text rendered, the Run reached `success`, the stream
contained `messages` and `end`, and a browser reload restored the answer. Its
sanitized identities, timing, credential-safety checks, and artifact paths are
recorded in `evidence/20260923-deepseek-web-l3-pass.json`. Active-stream
disconnect/reconnect against the controlled provider also passed twice; its
sanitized identities and health data are recorded in
`evidence/20260923-active-reconnect-pass.json`. Process-restart recovery and the
DeerFlow-vs-Haifa runtime differential remain `NOT_RUN`.

The 2026-09-24 Interaction browser profile passed approve/reject twice with zero retries. The full
run took 133.155 seconds (including Maven reactor and frozen Next build); Playwright cases took
5.5/3.9 seconds. The immediate `-SkipBuild` repeat took 62.617 seconds end-to-end and 5.915/3.372
seconds per case. The first harness attempt exposed an early acceptance flag during Maven package;
the second exposed a manually built Next rewrite fixed to port 8001. Both failed before product
assertions, retained trace/screenshot/log evidence, and released all owned processes. The fixed
runner keeps the flag off during ordinary tests and builds Next with the assigned slot gateway (now `30618`).
The sanitized identities and health summary are recorded in
`evidence/20260924-interaction-http-pass.json`.
After a full six-module `mvn clean test` (90 tests, zero failures), a final browser execution also
passed 2/2 in 8.1 seconds (3.516/3.044 seconds) and 47.806 seconds for the complete runner lifecycle.


新的 UI 验收从 `w05-demo-artifact.spec.cjs` 黄金样例起步。`fixtures.cjs` 使用 Playwright `test.extend`，提供 `runtime`（必须由 runner 导出环境）、`workspace`（进入工作区）、`runFlow`（原 20 秒有界等待并报告最后状态）、`approval`（当前待批准卡片）与 `evidence`（截图、JSON、真实下载字节）。`w18-ui-smoke.spec.cjs` 的 4 个用例也使用该 fixtures；其他 spec 暂未迁移。

每次 runner 运行创建独立的 `local-tmp/slot-<N>-<任务标签>/evidence/run-<时间戳>/`。默认标签为 `w18-ui-smoke`，黄金样例为 `w05-demo-artifact`；其他模式同样外置，旧 `target` 路径停止使用。`flow-health.json` 汇总冷暖、阶段耗时、首个失败、重试次数、清理与日志位置；`case-results.json` 逐项保留 PASS/FAIL/NOT_RUN 与相对证据路径；原始 `results.json`、trace、截图和日志仍保留。预检失败不启动进程，case 记 NOT_RUN；就绪或 Playwright 失败仍执行精确 PID 清理；清理失败使 runner 失败。
