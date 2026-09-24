# W18 Haifa-only local frontend profile

This slice makes the frozen DeerFlow frontend executable against an explicitly
selected local Haifa gateway without claiming that the unfinished Run/SSE stack
already works.

## Guarded topology

The default work-package profile is deliberately separate from the frozen
DeerFlow reference topology:

| Role | Address |
| --- | --- |
| Frozen frontend | `http://127.0.0.1:33018` |
| Haifa gateway | `http://127.0.0.1:18018` |
| Browser REST | same frontend origin under `/api/*` |
| Browser LangGraph | same frontend origin under `/api/langgraph/*` |
| Local data root | `target/w18-haifa-config-data` |

The launcher leaves `NEXT_PUBLIC_BACKEND_BASE_URL` and
`NEXT_PUBLIC_LANGGRAPH_BASE_URL` unset. `next.config.js` therefore owns the
same-origin rewrites and uses the server-only
`DEER_FLOW_INTERNAL_GATEWAY_BASE_URL`. Secrets are never accepted in a
`NEXT_PUBLIC_*` variable.

Two upstream memory Route Handlers did not follow that rule: when the public
browser base was unset they forwarded directly to `127.0.0.1:8001`. The single
recorded frontend patch routes them through the existing server-only gateway
configuration. The patch changes no UI or wire payload.

## Verify and launch

Dry-run the complete source/config/toolchain gate:

```powershell
pwsh -NoProfile -File scripts/start-haifa-frontend.ps1 `
  -DryRun `
  -SourceRepository D:\workspace\haifa-agent-super-harness\deer-flow
```

The report records the exact gateway, same-origin browser routes, dedicated
ports/data root, Node and pinned pnpm versions, source gate, a configuration
SHA-256, and `runtimeDifferential=NOT_RUN`.

The non-dry launcher expects the W02 gateway to already be running at the
selected address. For the current bootstrap-only server, one terminal can run:

```powershell
mvn -o -ntp -pl super-harness-server -am package
$env:SUPER_HARNESS_PROFILE = 'local'
$env:SUPER_HARNESS_BIND_ADDRESS = '127.0.0.1'
$env:SUPER_HARNESS_PORT = '18018'
$dataRoot = (New-Item -ItemType Directory -Force 'target/w18-haifa-config-data').FullName
$env:SUPER_HARNESS_DATA_ROOT = $dataRoot
$env:SUPER_HARNESS_DATA_DIR = $dataRoot
$env:SUPER_HARNESS_ENVIRONMENT = 'development'
$env:DEER_FLOW_AUTH_DISABLED = '1'
$env:DEER_FLOW_ENV = 'development'
$env:ENVIRONMENT = 'development'
java -jar super-harness-server/target/super-harness-server-0.1.0-SNAPSHOT-exec.jar
```

The durable Haifa Runtime database uses AES-GCM only for protected model
continuation payloads. On Windows, the first start for a data root that does
not yet contain `runtime/haifa-agent.sqlite` provisions one random 256-bit key
in Windows Credential Manager under
`haifa:os:HAIFA_SUPER_HARNESS_CONTINUATION_KEY_<data-root-hash>`; the suffix is
the first 16 hexadecimal characters of SHA-256 over the normalized absolute
data-root path. This scopes key lifecycle to one canonical data root while
letting later starts deterministically find the same credential. The server
never prints or writes the key into the repository or data root. It never
replaces an existing credential, and an existing Runtime database whose
credential is unavailable fails closed rather than silently creating a new
key.

Linux/CI or an explicitly managed Windows launch can select an existing
credential reference. For example, configure a secret environment variable
outside the repository and point the server at it:

```powershell
$env:HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF = 'env://HARNESS_CONTINUATION_KEY'
# HARNESS_CONTINUATION_KEY must already contain a Base64-encoded 256-bit key.
```

`HAIFA_SUPER_HARNESS_CONTINUATION_KEY_REF` accepts only `env://NAME` or
`os://NAME`. An explicit reference is always operator-managed: if it is
missing, even for a fresh data root, startup fails closed and does not create a
credential under the supplied name. Only the implicit data-root-scoped Windows
target may be provisioned on the first start of a new database. Missing,
malformed, or non-256-bit credentials stop startup without exposing credential
material.

History, message paging, Run listing, and cold restoration read the public SDK
conversation pages without retaining the whole transcript. Memory is bounded,
but elapsed time is proportional to the visible transcript: at most 100 pages
of 100 turns (10,000 turns) are scanned. If the requested answer cannot be
proved within that budget, the gateway returns HTTP 503 rather than serving a
partial page or silently truncating durable state.

The persisted model-binding identity remains authoritative for future Runs.
Historical transcript/history/Run reads may reopen the shared SQLite authority
through the currently configured SDK assembly without executing that model;
if the current binding revision differs, those reads remain available but a
new Run on the historical thread returns HTTP 409 instead of rebinding it.

Then start the frontend from another clean shell:

```powershell
pwsh -NoProfile -File scripts/start-haifa-frontend.ps1 `
  -SourceRepository D:\workspace\haifa-agent-super-harness\deer-flow
```

Before invoking pnpm, the launcher verifies the frozen source and lockfile,
checks the exact package-manager version from `frontend/package.json`, probes
gateway health and the auth-disabled principal, and binds Next.js to loopback.
It refuses inherited public/split-origin backend variables instead of silently
overriding them.

## Rejection rules

The profile fails closed for:

- missing or non-exact local/auth-disabled markers;
- a non-HTTP or non-literal-loopback gateway;
- upstream reference ports `2026`, `3000`, `8001`, or `8002`;
- identical frontend and gateway ports;
- any public backend/LangGraph override or reference-backend variable;
- `SKIP_ENV_VALIDATION`;
- secret-like `NEXT_PUBLIC_*` variables;
- a missing, relative, or vendor-contained data root;
- frontend inventory, lockfile, license, source SHA/tree, or recorded patch
  drift;
- a gateway that fails bootstrap health or auth-disabled principal preflight.

These checks prevent accidental fallback configuration. They do not replace
the later network-level differential test that blocks the reference backend and
retains real Haifa Run/Tool/Artifact evidence.

## Assigned-slot browser smoke

The opt-in W18 browser harness uses the ports assigned by the orchestrator,
keeps both server data variables on `target/agent-slot-<n>-data`, and starts its
controlled provider on an OS-assigned loopback port:

```powershell
pwsh -NoProfile -File scripts/run-w18-ui-smoke.ps1 `
  -SourceRepository D:\workspace\haifa-agent-super-harness\deer-flow
```

The provider is test wiring for the UI-to-SDK bridge only. It does not count as
an external-provider run. The runner uses the real managed-model save, probe,
and executable catalog APIs before starting Playwright, records redacted
evidence under `target/w18-ui-smoke-evidence`, and stops only its own exact
process objects.

On 2026-09-24, a slot 3 cold execution and a same-data-root warm restart both
passed all four Chromium cases. The browser rendered a real SDK Run from wire
event `messages`, sent `?wait=0&action=interrupt`, and restored the completed
thread after a full process restart. Playwright explicitly captured HTTP 200
network responses from `/messages/page` and `/history`; ignored local evidence
is retained under `target/w18-ui-smoke-evidence/playwright`.

A subsequent gateway-only restart selected the oldest cold-run thread from the
same catalog and returned HTTP 200 from `/messages/page`, `/history`, and
`/runs` with 2, 1, and 1 records. This proves the SQLite authority is used
after both browser processes have exited rather than relying on a hot in-memory
cache.

## Verified and not run

Verified in this slice:

- frozen source plus recorded patch reconstructs the local 981-file inventory;
- the patch applies cleanly to the pinned source tree;
- PowerShell profile cases accept the guarded profile and reject missing auth,
  non-loopback, public/reference targets, reference ports, production mode,
  public secrets, and invalid data placement;
- launcher dry-run resolves Node 22 and `pnpm@10.26.2` from the frozen package;
- the memory BFF regression test uses only the server-side Haifa target;
- frontend lint, TypeScript checks, and the production build (101 generated
  pages) pass.

Verified at runtime in this slice: real frozen UI auth/model discovery, Thread
creation, Run/SSE rendering, cancellation, durable completed-thread reload,
`/messages/page`, and `/history`, including one cold and one warm full process
cycle with no retries. Reconnect/gap recovery, files, Browser WebSocket, a real
external provider/model/tool/artifact, and DeerFlow-vs-Haifa differential remain
`NOT_RUN`; the controlled loopback provider is deterministic contract evidence,
not an external-provider claim.
