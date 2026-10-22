# W05 event projection core evidence

The first slice was transport-neutral. The current slice connects that projection to the local
compatibility server using only the public Haifa Agent `0.1.4-SNAPSHOT` event/run API and proves
ordering, replay, gap, terminal, and disconnect behavior through a real loopback SSE socket.

## Frozen DeerFlow evidence

Source snapshot: `bytedance/deer-flow@e545c28ac301e6f9feae247c513d8ea8f83df294`.

- `backend/app/gateway/services.py:2197-2289`: the creating stream alone applies
  `on_disconnect=cancel|continue`; join/observer streams do not cancel the run.
  `Last-Event-ID` is passed to the bridge. A retention gap is emitted as `event:
  gap` with `code=stream_replay_gap`, `run_id`, `requested_event_id`,
  `earliest_available_event_id`, `latest_available_event_id`, and
  `recovery=reload_durable_state`, after which that stream ends.
- `backend/app/gateway/routers/thread_runs.py:1298-1438`: GET join and existing-run
  observation streams use `apply_on_disconnect=False`; mutation requires the POST
  action path and cancel permission.
- `frontend/src/core/api/api-client.ts:74-81,197-219,340-419`: the UI validates the
  gap payload exactly, rejects a mismatched run, exposes a custom gap control event,
  reloads authoritative thread state, emits a replacement `values` frame, and then
  rejoins after the retained tail. Recovery is bounded to five attempts.
- `frontend/src/core/threads/hooks.ts:2059-2085`: a gap clears optimistic/transient
  message and task state before invalidating authoritative caches.
- `frontend/src/core/api/api-client.ts:41-68`: `success`, `error`, `timeout`, and
  `interrupted` are distinct terminal statuses.
- `backend/packages/harness/deerflow/agents/human_input.py:8-75` and
  `frontend/src/core/messages/human-input.ts:37-68,340-479`: Interaction response
  correlation uses `request_id`; only a valid response for a seen request is accepted,
  and the first matching response closes it.

These are source observations, not yet claims about a working Haifa-compatible
network endpoint.

## Public Haifa API used

The module has one direct Haifa dependency: `io.haifa:haifa-agent-sdk:0.1.4-SNAPSHOT`
from the local Maven repository. Maven Enforcer rejects product artifacts and direct
implementation-module dependencies.

Public bytecode signatures were checked with `jar tf` and `javap -public`:

- `AgentRuns.events`, `AgentRuns.subscribe`, and `RunEventCursor` expose the durable
  ordered feed.
- `AgentRuns.view` supplies the authoritative `AgentRunViewSnapshot` used after a
  gap. `SdkAuthoritativeRunViewSource` is the narrow adapter.
- `AgentRunEvent` supplies `eventId`, `eventType`, `eventSchemaVersion`, `runId`,
  `sequence`, cursor, correlation/causation identifiers, and a typed payload.
- `RunEventPayloads.RunLifecycle` supplies status, version, reason, and error data.
- `RunEventPayloads.InteractionLifecycle` supplies request ID, kind, state, and
  action/reason. `AgentRuns.pendingInteraction` and `AgentRuns.respond` remain the
  public command-side APIs for the later server slice.
- `AgentRunStatus` provides distinct `COMPLETED`, `FAILED`, `CANCELLED`, and `TIMEOUT`
  terminals.

No Haifa source checkout, private table, source-path dependency, copied SDK code, or
product JAR is used.

## Implemented behavior

`BoundedRunEventProjector` is single-process and synchronized. It:

- derives a stable key from the public `(runId, eventId)` pair;
- uses the durable public event sequence as `ProjectionCursor`; public allowlist filtering may
  create legitimate numeric holes, so sequences must be strictly increasing rather than contiguous;
- treats an exact duplicate as idempotent, but fails closed if a key is reused with
  different content or an unknown event arrives at an old sequence;
- bounds retained events, detects replay outside that retention window, and returns
  a gap together with a run-matching authoritative view;
- rejects non-monotonic or conflicting events without advancing the head;
- fails closed when the authoritative view is missing or belongs to another run;
- projects only known public Interaction kinds/states and preserves `requestId`;
- preserves completion, failure, cancellation, and timeout as different terminal
  kinds, and rejects non-duplicate events after a terminal event.

## Verification and remaining boundary

Verified offline:

```text
mvn -o -ntp -f super-harness-events/pom.xml clean verify
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

mvn -o -ntp -pl super-harness-server -am clean verify
Tests run: 31, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

mvn -o -ntp clean verify
Tests run: 42, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The tests cover durable ordered cursors, exact duplicates, conflicting duplicates,
non-monotonic and cross-run inputs, sparse public sequences, retention gaps, missing/mismatched
authoritative views, Interaction validation, all four terminal outcomes, and late
events.

The compatibility server now covers real loopback SSE serialization, stable IDs,
`Last-Event-ID` replay, duplicate replay, retention gap plus authoritative run view,
durable `/state` recovery, distinct creator/join disconnect behavior, and terminal outcomes.
The frozen LangGraph SDK's initial observer cursor (`Last-Event-ID: -1`) is accepted only as the
before-first cursor; blank values, other negative values, decimals, foreign-run shapes, and
non-zero sub-cursors still fail closed. A controlled-provider browser run proved an active creator
reload with `on_disconnect=continue`, observer join, ordered `messages`/`end` delivery, one rendered
assistant message, and terminal `success`. The browser case passed twice without retries and released
its provider, Gateway, and frontend processes. The sanitized evidence is in
`tests/ui/smoke/evidence/20260923-active-reconnect-pass.json`.

The unsupported Interaction card projection fails closed. `NOT_RUN`: process-restart reconstruction,
HTTP Interaction submission/card rendering, real `updates`/`custom` payload projection, and
differential DeerFlow/Haifa verification.
