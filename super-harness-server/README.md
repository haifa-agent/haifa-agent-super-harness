# DeerFlow-compatible local HTTP API

This module contains the first two W02 vertical slices. It exposes the startup endpoints and a
bounded Thread -> real Haifa SDK Run -> SSE -> cancel path consumed by the frozen DeerFlow
frontend at `e545c28ac301e6f9feae247c513d8ea8f83df294`:

- `GET /api/v1/auth/setup-status`
- `GET /api/v1/auth/me`
- `GET /api/features`
- `GET /api/models`
- `GET /api/managed-models`, `PUT /api/managed-models`, and
  `POST /api/managed-models/test`
- `GET /api/agents`, `GET /api/agents/check`, and custom-agent
  `GET`/`POST`/`PUT`/`DELETE`
- `GET /health` for local process readiness
- `POST /api/threads`, `GET /api/threads/{thread_id}` and
  `GET /api/threads/{thread_id}/state`
- `POST /api/threads/search`
- `POST /api/threads/{thread_id}/runs/stream`
- `GET /api/threads/{thread_id}/runs/{run_id}/stream` with `Last-Event-ID`
- `GET /api/threads/{thread_id}/runs/{run_id}`
- `POST /api/threads/{thread_id}/runs/{run_id}/cancel`
- `POST /api/threads/{thread_id}/uploads` with frozen multipart field `files`
- `GET /api/threads/{thread_id}/uploads/limits` and
  `GET /api/threads/{thread_id}/uploads/list`
- `GET /api/threads/{thread_id}/artifacts/mnt/user-data/{uploads|workspace|outputs}/{path}`
  with a single HTTP byte Range and explicit download support

The upload adapter deliberately advertises a stricter 25 MiB per-request/per-file limit than frozen
DeerFlow's configurable default. It retains at most one bounded multipart upload request at a time,
fully parses it before storage, and compensates earlier new files if a later member fails. Artifact
full/Range responses use one bounded verified file snapshot and one SHA-256 pass; Range output is
written from that snapshot without a second range-sized copy. Unknown filename types use DeerFlow's
8 KiB no-NUL content sniff so extensionless text remains previewable.

The run endpoint calls the public `haifa-agent-sdk-starter:0.1.2-SNAPSHOT` JAR. Its SSE IDs are
derived from the durable public `AgentRunEvent.sequence`, not a process-local counter. The server
projects the authoritative completed output as a LangGraph-compatible `messages-tuple`, preserves
distinct terminal status through the run resource, and invokes the public `AgentRunHandle.cancel()`
API. `Last-Event-ID` replays the bounded tail idempotently. An expired cursor emits the frozen
`stream_replay_gap` signal together with a bounded authoritative run view; `/state` supplies the
durable messages before the client rejoins at the retained tail. Creator disconnect obeys
`cancel|continue`, while an observer join disconnect never cancels the run.

The W05 compatibility slice also recognizes the frozen hidden-human-response shape on the same
`runs/stream` endpoint. When the public SDK exposes a pending approval Interaction, the server
projects an `ask_clarification` tool message with a v1 `single_choice` card, validates the exact
request/source/action on reply, and resumes that same SDK Run through `AgentRuns.respond`. The SDK
remains the lifecycle and idempotency authority. An identical retry is first verified as SDK
`DUPLICATE` and then returns HTTP 409 rather than manufacturing a cursorless success stream; a
different, stale, malformed, or cross-thread response also fails closed. The accepted hidden reply
is only an in-process `RunRecord` UI read-model cache, not durable Interaction storage.

W03 now persists owner-scoped managed-model and custom-agent configuration under the repository-
local data directory. Writes are serialized and atomically replaced, model updates require the
frozen `expected_revision`, credentials are AES-GCM encrypted at rest and never returned, and
SOUL plus `null`/`[]`/`false` values round-trip without erasing omitted fields. The model test route
uses Haifa's public OpenAI-compatible model API for a bounded streaming tool-call probe. Missing
credentials, unknown providers, unsupported modes, and unbound Run models fail closed.

Managed models may use either the frozen `api_key` field or a controlled `credential_ref` with the
form `os://NAME`. An OS reference wins when both are supplied; clearing it explicitly with `null`
allows the encrypted `api_key` compatibility path to be selected again. The server delegates
`os://` resolution to Haifa Agent's public local-model-auth integration and never copies the OS
secret into its JSON store. Responses expose only `has_credential` and `credential_source`; the
reference name and resolved value are not returned. The exact official endpoint
`https://api.deepseek.com` selects Haifa's `deepseek-openai-chat` dialect with thinking disabled;
all other compatible endpoints retain the standard dialect. Probe and Run both use the same model
binding, credential reference, dialect, endpoint, and frozen snapshot.

The W03 Run-binding slice now advertises only enabled, credential-configured and executable managed
models from `GET /api/models`. A Run selects an explicit `context.model_name`, then a custom Agent model,
then the first executable managed model. It freezes the selected model name and revision into a
public Haifa SDK model snapshot and invokes the public OpenAI-compatible adapter through the same
SDK Run/SSE/cancel path. Unknown, disabled, credential-less, non-loopback plain-HTTP, unsupported
reasoning-mode selections and stale managed-model writes fail closed. Plain HTTP is accepted only for a literal loopback
provider; other providers require HTTPS. The W02 local probe is available only through a
package-private test bootstrap and is never exposed by the production server entry point.

This is still an in-progress compatibility slice. A new Turn may select a different enabled model
or revision in the same durable Haifa Conversation. Each Run retains its selected model snapshot;
an active Run still rejects a concurrent Turn. The thread catalog's `model_binding` records the
last successfully admitted model for restart recovery, not an immutable thread constraint.
The controlled loopback provider remains L2 protocol evidence; real-provider
evidence must be reported separately from an explicitly enabled live test using an OS credential.
`updates` and `custom` event
projection, production Tool wiring, full history, Project/archive/conversion/upload deletion,
same-UI differential
verification, and account authentication are **NOT_RUN**. Unsupported Interaction projection fails
closed instead of manufacturing a card. Cancel currently implements the frozen UI's actual
`wait=false&action=interrupt` request; blocking wait and rollback are rejected rather than
silently approximated. No request falls back to the DeerFlow backend.

The response fixtures were derived from these files in the frozen checkout, not from a moving
upstream branch:

- `frontend/src/core/auth/auth-disabled-user.ts`
- `frontend/tests/e2e-real-backend/auth-disabled-contract.spec.ts`
- `frontend/src/core/auth/setup.ts` and `frontend/src/core/auth/types.ts`
- `frontend/src/core/features/api.ts` and `backend/app/gateway/routers/features.py`
- `frontend/src/core/models/{api,types}.ts` and `backend/app/gateway/routers/models.py`
- `backend/tests/test_auth_me_permissions.py`
- `frontend/src/core/threads/api.ts`
- `frontend/src/core/threads/hooks.ts`
- `frontend/src/core/api/api-client.ts` and `frontend/src/core/api/stream-mode.ts`
- `frontend/src/core/uploads/api.ts` and `frontend/src/core/artifacts/{utils,loader}.ts`
- `backend/app/gateway/run_models.py`
- `backend/app/gateway/routers/threads.py`, `thread_runs.py`, `uploads.py`, and `artifacts.py`
- `backend/app/gateway/services.py` (`format_sse` and `sse_consumer`)

The server accepts only the `local` profile and resolves the listener to a loopback address before
opening the socket. `DEER_FLOW_ENV`, `ENVIRONMENT`, or `SUPER_HARNESS_ENVIRONMENT` set to `prod` or
`production`, a non-loopback bind address, or a non-local profile aborts startup. The principal and
permissions are server constants; identity or role headers from the caller are ignored. Thread
metadata keys that could forge ownership (`owner`, `user_id`, `system_role`) are discarded.

Configuration variables and defaults:

| Variable | Default |
|---|---|
| `SUPER_HARNESS_PROFILE` | required: `local` |
| `DEER_FLOW_AUTH_DISABLED` | required: `1` |
| `SUPER_HARNESS_BIND_ADDRESS` | `127.0.0.1` |
| `SUPER_HARNESS_PORT` | `8001` |
| `SUPER_HARNESS_DATA_DIR` | `.super-harness/data` under the launch directory |

Run the contract and real-socket integration tests with:

```powershell
mvn -pl super-harness-server -am test
```

The opt-in W05 browser profile registers its executable mutation fixture only from
`src/test/java`; the normal executable JAR and normal bootstrap still register no tools:

```powershell
pwsh -NoProfile -File scripts/run-w05-interaction-smoke.ps1
```

This is acceptance evidence for the frozen card/HTTP wiring, not evidence that a product Tool is
configured. Cross-process restart recovery remains `NOT_RUN` with the surrounding in-memory
Thread/Run projection.

The explicit DeepSeek L3 test uses only the Windows Credential Manager entry referenced by
`os://DEEPSEEK_API_KEY`; it never reads an env file. Run it in the assigned isolated slot with:

```powershell
mvn -pl super-harness-server -am "-Dtest=DeepSeekOsCredentialLiveTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" "-Dhaifa.deepseek.live=true" `
  "-Dsuper.harness.live.data.dir=target/agent-slot-3-data" test
```

Build and start the executable JAR with:

```powershell
mvn -pl super-harness-server -am package
$env:SUPER_HARNESS_PROFILE = 'local'
$env:DEER_FLOW_AUTH_DISABLED = '1'
java -jar super-harness-server/target/super-harness-server-0.1.0-SNAPSHOT-exec.jar
```
