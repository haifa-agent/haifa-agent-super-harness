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

The run endpoint calls the public `haifa-agent-sdk-starter:0.1.1-SNAPSHOT` JAR. Its SSE IDs are
derived from the durable public `AgentRunEvent.sequence`, not a process-local counter. The server
projects the authoritative completed output as a LangGraph-compatible `messages-tuple`, preserves
distinct terminal status through the run resource, and invokes the public `AgentRunHandle.cancel()`
API. `Last-Event-ID` replays the bounded tail idempotently. An expired cursor emits the frozen
`stream_replay_gap` signal together with a bounded authoritative run view; `/state` supplies the
durable messages before the client rejoins at the retained tail. Creator disconnect obeys
`cancel|continue`, while an observer join disconnect never cancels the run.

W03 now persists owner-scoped managed-model and custom-agent configuration under the repository-
local data directory. Writes are serialized and atomically replaced, model updates require the
frozen `expected_revision`, credentials are AES-GCM encrypted at rest and never returned, and
SOUL plus `null`/`[]`/`false` values round-trip without erasing omitted fields. The model test route
uses Haifa's public OpenAI-compatible model API for a bounded streaming tool-call probe. Missing
credentials, unknown providers, unsupported modes, and unbound Run models fail closed.

The W03 Run-binding slice now advertises only enabled, credentialed and executable managed models
from `GET /api/models`. A Run selects an explicit `context.model_name`, then a custom Agent model,
then the first executable managed model. It freezes the selected model name and revision into a
public Haifa SDK model snapshot and invokes the public OpenAI-compatible adapter through the same
SDK Run/SSE/cancel path. Unknown, disabled, credential-less, non-loopback plain-HTTP, reasoning-mode,
and revision-drift selections fail closed. Plain HTTP is accepted only for a literal loopback
provider; other providers require HTTPS. The W02 local probe is available only through a
package-private test bootstrap and is never exposed by the production server entry point.

This is still an in-progress compatibility slice. A thread keeps its first model revision so its
in-memory Haifa Conversation remains coherent; after a model revision changes, the caller must
create a new thread. The real external-provider probe and Run remain **NOT_RUN** because no external
credential was supplied; the controlled loopback provider is L2 protocol evidence, not L3 provider
evidence. Process-restart reconstruction, `updates` and `custom` event
projection, HTTP interaction submission/card projection, full history, files, same-UI differential
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
- `backend/app/gateway/run_models.py`
- `backend/app/gateway/routers/threads.py` and `thread_runs.py`
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

Build and start the executable JAR with:

```powershell
mvn -pl super-harness-server -am package
$env:SUPER_HARNESS_PROFILE = 'local'
$env:DEER_FLOW_AUTH_DISABLED = '1'
java -jar super-harness-server/target/super-harness-server-0.1.0-SNAPSHOT-exec.jar
```
