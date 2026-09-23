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
- the frozen SDK's `?wait=0&action=interrupt` cancellation request;
- reload of a completed thread.

## Latest captured result

The 2026-09-23 slot 2 run passed all four independent Chromium cases against
the packaged Haifa gateway and the unmodified frozen frontend:

- no-cookie local auth and the executable managed-model catalog;
- Thread creation plus a real controlled-provider Run whose tuple payload was
  delivered as LangGraph Platform `event: messages` and rendered by the UI;
- the frozen SDK's actual `?wait=0&action=interrupt` request, HTTP 202, and
  terminal `interrupted` status;
- reload of a completed Thread with both human and assistant messages restored
  through `/messages/page` and `/history`.

The sanitized identities and event cursor are recorded in
`evidence/20260923-sse-projection-pass.json`. The runner keeps screenshots,
traces, process logs, and response attachments under ignored
`target/w18-ui-smoke-evidence/playwright` for local review and always stops the
exact provider, gateway, and frontend processes it started.

The cold diagnostic run spent 36.463 seconds packaging Maven modules and the
frozen Next build reported 101 seconds of compilation, 99 seconds of type
checking, and 6.2 seconds generating static pages. It reached Playwright and
failed on two exact assertion mismatches rather than a runner/Next hang or an
infrastructure timeout; its trace, screenshot, error context, and three process
logs were sufficient to identify the event-name and cancellation-query gaps.
After the fixes, a warm run passed 4/4 in 21.0 seconds (3.7/4.0/3.5/4.0 seconds
for auth, Run/SSE, cancel, and reload respectively) with zero retries. A second
warm execution also passed 4/4 in 22.123 seconds
(3.684/3.639/3.548/5.269 seconds) with zero retries; its complete runner
lifecycle took 76.847 seconds. The repeat-run provider, Gateway, and frontend
PIDs exited and ports `64182`, `38018`, and `53018` were released. No runner or
Next hang, test timeout, or owned child-process residue was observed.

This remains controlled loopback wiring evidence. A real external provider and
active-stream disconnect/reconnect recovery were not exercised and remain
`NOT_RUN`; the test does not claim either result.
