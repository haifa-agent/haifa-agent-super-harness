# W00 DeerFlow frozen contract seed

This increment is a machine-checkable **source inventory seed**, not a parity result.
It binds the inspected frontend and Gateway evidence to
`bytedance/deer-flow@e545c28ac301e6f9feae247c513d8ea8f83df294` and deliberately
uses `SOURCE_VERIFIED` plus `runtime_status=NOT_RUN`. It does not claim that the
reference stack, Haifa stack, browser, SSE, WebSocket, file flow, or differential
matrix has run.

## Files

- `contracts/upstream/deerflow/frontend-consumer-manifest.json` separates:
  browser REST fetches, Next SSR calls, Next Route Handler/BFF calls, LangGraph
  SDK REST/SSE, Browser WebSocket, file/auth calls, and Next rewrites.
- Every surface records the public path, Gateway path, method, consumer layer,
  source evidence, registration/configuration condition, static status, and
  runtime status.
- `contracts/upstream/deerflow/resource-seed.json` freezes a small first set of
  frontend build inputs and auth fixtures/oracles by SHA-256. `intended_local_path`
  is planning metadata only; nothing has been imported.
- `scripts/verify-deerflow-contracts.ps1` rejects a wrong checkout SHA, a changed
  manifest SHA, a missing critical surface, missing source evidence, invalid
  status claims, and drifted resource hashes.
- `tests/contracts/verify-deerflow-contracts.ps1` proves the positive case and
  three negative gates without changing the read-only upstream checkout.

## Run

From the Super Harness repository or its W00 worktree:

```powershell
pwsh -NoProfile -File scripts/verify-deerflow-contracts.ps1 `
  -DeerFlowRoot D:\workspace\haifa-agent-super-harness\deer-flow

pwsh -NoProfile -File tests/contracts/verify-deerflow-contracts.ps1 `
  -DeerFlowRoot D:\workspace\haifa-agent-super-harness\deer-flow
```

The verifier also accepts `DEERFLOW_SOURCE_ROOT`. Its default discovery covers a
repository-local `deer-flow` checkout and the sibling main checkout used by the
isolated worktree layout.

## Covered in this seed

- Auth startup: browser `setup-status` and `auth/me`, SSR `setup-status`/`auth/me`,
  cookie/CSRF behavior, and auth-disabled production exclusion.
- Browser REST: feature/model catalogs, uploads, artifact read/update, and live
  browser navigation.
- LangGraph SDK: thread create/search/state, run create stream, join stream, and
  cancel; chat stream modes are `messages-tuple`, `updates`, and `custom`.
- Browser WS: URL construction, binary JPEG frames, JSON status/navigation frames,
  user input frame names, ownership/auth/origin/browser-capability conditions.
- Next routing: conditional `/api/langgraph/*` and `/api/*` rewrites, internal
  Gateway target, and the Memory Route Handler/BFF surface.
- Resource seed: root license, package/lockfile, Next routing config, auth-disabled
  fixture, and browser/backend auth oracle files.

## Intentionally not covered yet

This is not the full frontend consumer inventory. Agents, projects, skills, MCP,
schedules, channels, console, trash, persistent batches, all mutations, exact
request/response/error schemas, Range/download behavior, SSE payload schemas,
event IDs/gap recovery, and dynamic registration branches still need incremental
expansion. The root MIT license does not finish nested dependency, font, image,
brand, or asset-specific licensing review.

W01 may import the frozen frontend only after its own provenance/license record;
W02 and later packages must attach real Run/Tool/Artifact and network evidence.
Static source verification must never be promoted to parity `PASS`.

