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

## Slot 2 browser smoke

The opt-in W18 browser harness uses the assigned gateway/frontend ports
`38018`/`53018`, keeps both server data variables on
`target/agent-slot-2-data`, and starts its controlled provider on an
OS-assigned loopback port:

```powershell
pwsh -NoProfile -File scripts/run-w18-ui-smoke.ps1 `
  -SourceRepository D:\workspace\haifa-agent-super-harness\deer-flow
```

The provider is test wiring for the UI-to-SDK bridge only. It does not count as
an external-provider run. The runner uses the real managed-model save, probe,
and executable catalog APIs before starting Playwright, records redacted
evidence under `target/w18-ui-smoke-evidence`, and stops only its own exact
process objects.

On 2026-09-23, auth startup, the managed-model probe, and model discovery passed
through the real frozen frontend profile. The independent Run/SSE, cancel, and
reload cases all exposed the same W02 protocol gap before a Run was created:
the frozen UI sent a human message with `content` as a text-block array, while
`DeerFlowThreadRunApi.lastHumanMessage` only accepts textual `content`. The
gateway therefore returned HTTP 422 with
`input.messages requires a human message`. Sanitized request/response and the
three created Thread IDs are checked in at
`tests/w18-ui-smoke/evidence/20260923-run-stream-422.json`.

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

`FAIL`: real browser UI Run/SSE currently stops at the documented W02 HTTP 422
compatibility gap. No Run or event ID was created.

`NOT_RUN`: the actual cancel request and completed-thread reload assertion were
not reached because Run creation failed. Reconnect/gap recovery, files, Browser
WebSocket, real external provider/model/tool/artifact, and DeerFlow-vs-Haifa
differential are also `NOT_RUN`. A successful launcher, auth case, or controlled
model probe must not be reported as chat parity.
