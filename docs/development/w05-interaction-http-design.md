# W05 Interaction HTTP compatibility design checkpoint

This checkpoint compares the frozen DeerFlow contract with the public Haifa Agent
`0.1.1-SNAPSHOT` contract and records the implemented minimum projection that does not create a
second interaction authority.

## Frozen DeerFlow contract

At `bytedance/deer-flow@e545c28ac301e6f9feae247c513d8ea8f83df294`, a clarification
request is a tool message whose `artifact.human_input` contains a versioned
`human_input_request`. The stable correlation key is `request_id`. The clarification run writes
that message and ends. The frozen card submits its answer through the ordinary
`POST /api/threads/{thread_id}/runs/stream` endpoint as a new hidden human message:

```json
{
  "type": "human",
  "additional_kwargs": {
    "hide_from_ui": true,
    "human_input_response": {
      "version": 1,
      "kind": "human_input_response",
      "source": "ask_clarification",
      "request_id": "...",
      "response_kind": "option|text",
      "option_id": "...",
      "value": "..."
    }
  }
}
```

The first valid response for an already-seen request closes the card. A missing, malformed, or
unseen `request_id` does not. The request and response messages in thread history are the durable
UI read model; the frontend's in-memory `pending` flag is only a submit fence.

## Public Haifa mapping proved by the test-only spike

The public SDK has a different execution shape but already owns the interaction state machine:

- a policy-gated tool call leaves the same run in `WAITING_INTERACTION` or `WAITING_APPROVAL`;
- `AgentRuns.pendingInteraction(runId)` returns the authoritative request ID, revision, kind,
  state, prompt, actions, input contract, target, requester, expiry, and consequences;
- `AgentRuns.respond(InteractionResponseSubmission)` validates the run, request, expected
  revision, action, input, response ID, and idempotency key;
- an approved response resumes and completes the same run; the tool does not execute before
  approval.

`HaifaPublicInteractionSpikeTest` proves this only against a test-only side-effect tool. It does
not claim that the packaged Super Harness exposes an interactive production tool.

## Minimum HTTP projection

The compatible path can stay a projection over existing authorities:

1. When a lifecycle event says an Interaction is pending, load the full public
   `pendingInteraction(runId)` view and emit one frozen `messages` tuple containing a tool
   message with `artifact.human_input`.
2. Map an approval with no SDK input contract to a v1 `single_choice` card. Option IDs are the
   exact allowed Haifa action values (`approve`, `reject`); labels are presentation only.
   Map a text contract to a v1 `free_text` card and the exact `submit` action. Unsupported input
   contracts fail closed instead of being coerced.
3. On the next frozen `runs/stream` request, parse only the documented response object, select
   the existing thread's active waiting run, reload its pending Interaction, and require exact
   run/request/source/action agreement before calling `respond`.
4. Derive response ID and idempotency key deterministically from the request ID and normalized
   response. Let the SDK decide new, duplicate, stale-revision, and invalid-action outcomes.
5. Cache the accepted hidden response as a message projection on the existing `RunRecord` so a
   reload can close the card. This mirrors DeerFlow's persisted response message; it is not a
   second Interaction state machine and is not persistent in the current slice.

No `request_id -> run_id` table is required. The request arrives on a thread with one existing
active run, and the SDK revalidates the request against that run. No new Interaction aggregate,
status enum, transition table, or retry ledger is required.

## Restart authority and current boundary

The SDK remains authoritative for a live request through `pendingInteraction` and its durable
event feed. The current local Super Harness stores threads, runs, sessions, and message
projections in process memory, so process-restart restoration is already `NOT_RUN` for the
surrounding Thread/Run slice. Adding only an Interaction request table would not make restart
correct: the agent instance, thread/run index, session revision, message history, and model/tool
binding would still be absent. Restart recovery should therefore be solved once at the existing
Thread/Run persistence boundary, not by creating an Interaction-only authority here.

## Complexity decision

The proposed adapter is no more complex than frozen DeerFlow: one request projection, one hidden
response message projection, exact correlation, and SDK-owned transitions. It becomes more
complex than DeerFlow if it adds a separate request/run table, local lifecycle state machine, or
independent duplicate ledger; those are explicitly excluded. The implemented browser fixture is
separately approved, lives only under `src/test/java`, is guarded by an explicit acceptance flag,
and binds only the assigned loopback profile. The packaged bridge and normal bootstrap still
register no tools. Therefore frozen UI/HTTP mapping is verified, while real product Tool wiring is
still `NOT_RUN` and the fixture cannot establish product support.
