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
  frontend build inputs and auth fixtures/oracles by SHA-256. It cross-checks the
  W18 provenance manifest: package, lockfile, Next routing, and root license are
  `IMPORTED`; dedicated parity fixture/oracle copies that do not exist remain
  `NOT_RUN`. Import status never implies runtime parity.
- `scripts/verify-deerflow-contracts.ps1` rejects a wrong checkout SHA, a changed
  manifest SHA, a missing critical surface, missing source evidence, invalid
  status claims, schema-invalid fixtures, and drifted resource hashes.
- `tests/contracts/verify-deerflow-contracts.ps1` proves the positive case and
  six negative gates without changing the read-only upstream checkout.

The incremental core Oracle is split into deliberately small, reviewable files:

- `contracts/upstream/deerflow/core-protocol-oracle.json` binds each REST,
  error, and event contract to exact frozen source evidence and keeps four
  independent dimensions: `SOURCE_VERIFIED`, `STATIC_CONTRACT_VERIFIED`,
  implementation status, and runtime `PASS`/`FAIL`/`NOT_RUN`.
- `contracts/upstream/deerflow/schemas/*.schema.json` freezes the first exact
  JSON boundaries: setup status, `/auth/me`, run creation, HTTP errors, SSE
  envelopes, and replay-gap control data.
- `contracts/upstream/deerflow/fixtures/core-protocol-fixtures.json` contains
  source-derived positive fixtures. The auth-disabled user and permissions are
  taken from the upstream real-backend browser test; SSE metadata/end/gap
  examples are tied to the upstream formatter and consumer tests.

All fixtures in this change remain `implementation_status=NOT_ASSESSED` and
`runtime_status=NOT_RUN`. A verifier success proves source and static schema
integrity only; it cannot be reported as Haifa implementation or runtime parity.

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
- Core JSON Oracle: exact current setup-status and auth-disabled `/auth/me`
  bodies, the forbidden-extra-field run request surface and its accepted stream
  options/defaults, FastAPI string/validation and structured auth error envelopes.
- SSE Oracle: wire field ordering (`event`, `data`, optional `id`), metadata/end
  fixtures, id-less replay-gap shape, and durable-state recovery instruction.
- Browser WS: URL construction, binary JPEG frames, JSON status/navigation frames,
  user input frame names, ownership/auth/origin/browser-capability conditions.
- Next routing: conditional `/api/langgraph/*` and `/api/*` rewrites, internal
  Gateway target, and the Memory Route Handler/BFF surface.
- Resource seed: root license, package/lockfile, Next routing config, auth-disabled
  fixture, and browser/backend auth oracle files. Existing W18 imports are verified
  against the same frozen source hashes without duplicating their files.

## Intentionally not covered yet

This is not the full frontend consumer inventory. Agents, projects, skills, MCP,
schedules, channels, console, trash, persistent batches, all mutations,
non-core request/response/error schemas, Range/download behavior,
message/tool/custom SSE payload schemas, and dynamic registration branches still
need incremental expansion. The core replay-gap contract is now frozen, but no
live reconnect has run in this slice. The root MIT license does not finish nested
dependency, font, image, brand, or asset-specific licensing review.

W18 has imported the frozen frontend with its own provenance/license record;
W02 and later packages must still attach real Run/Tool/Artifact and network evidence.
Static source verification must never be promoted to parity `PASS`.
