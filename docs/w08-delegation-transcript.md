# W08 Delegation Compatibility

The compatibility layer connects the frozen subagent catalog, SDK Child profiles, `task` Tool Calls and persisted Child steps through public SDK APIs.

## Implemented contract

SDK `agent` and `objective` become outward `subagent_type` and `prompt`. The authorized SDK arguments and model-visible Tool Schema are unchanged. `context` and `expected_output` remain in the SDK transcript rather than being reinterpreted as unsupported frozen parameters. There is no invented description; the frozen card falls back to prompt. Request and result both use the same Runtime Tool Call ID already used by the existing transcript.

For terminal task calls, `subagent_status` is always one of `completed`, `failed`, `cancelled`, or `timed_out`. A completed Tool call cannot promote a failed or unknown Child result to success. Creation-time rejection uses the SDK's structured `REJECTED` reason and needs no Child Run. Cancelled pending requests can have no child, model, usage or successful-output metadata.

Complete-output SDK metadata is mapped to the frozen result preview and digest. Without it, a complete legacy summary may be displayed, but its hash is never presented as a complete-output hash. Usage requires both nonnegative integer counts and a total representable exactly by the frontend; missing, invalid or overflowing values are omitted. Model and capped reason fields are only emitted when explicitly present in the authorized source. Ordinary tools, clarification handling, artifact projection and model-visible Tool Result content are unchanged.

The managed catalog stores product configuration alongside the existing configuration boundary. `HaifaRunBridge` freezes enabled catalog entries into SDK profiles, keeps selected model identity and credentials, and delegates scheduling, cancellation, Child execution and noninteractive approval denial to the SDK. No second Runtime, Child scheduler, approval engine or output store is introduced.

Child messages are read from the public SDK's authorized durable message pages. The product projects them into the existing Parent SSE and the task-scoped replay endpoint described in the [README](../README.md#w08-子智能体目录). Subscription signals only wake a connection's cursor reader; restart replay does not depend on transient subscriptions. Unknown or unsafe usage remains omitted.

Each admitted Run's normalized stream selection is saved as protected product metadata in the existing thread catalog. Restored Runs use that Run's selection after cache eviction or Gateway restart; requesting no `custom` mode remains respected. Legacy Runs without a stored selection retain the historical message-tuple default. A connection replays Child messages using its own durable cursor. Child discovery is bounded by the persistence recheck interval; a task association batch reads the Parent lifecycle feed once.

Thread token totals include the Parent and its actual Children, and model attribution comes from their SDK model events. Each Parent contributes once to a model's Run count, even when both Parent and Child use that model. Missing usage or an exhausted bounded scan returns unavailable usage rather than estimated counts.

Parent terminal frames and `end` remain authoritative when SDK Child cancellation settles asynchronously beyond the connection's bounded terminal drain. The stream never invents a Child terminal frame. A later join reads the actual durable Child result; if already-terminal Child facts cannot be projected, the stream reports that diagnostic while preserving the Parent end.

## Validation commands and SDK selection

```powershell
pwsh -NoProfile -File scripts/test-targeted.ps1 -Test 'DeerFlowTaskDelegationApiTest,DeerFlowSubtaskProjectionApiTest,DeerFlowDelegationProjectionTest' -LowMemory
```

The public-JAR `SdkParentChildDelegationSmokeTest` checks output metadata and Runtime Tool Call/lifecycle correlation. The test requires the declared public SDK capabilities and fails against incompatible artifacts.

Tracked dependencies use `0.1.4-SNAPSHOT`, published from the upstream primary `dev` checkout. The official publication manifest identifies the SDK commit and JAR hashes. Isolated candidate selection, publication prerequisites and baseline gates follow the [README SDK selection rules](../README.md#定向测试与-sdk-版本选择). Work-package status, review decisions and execution evidence belong in the private W08 reports and SHPM.

The frozen-function Oracle 12 checker is `scripts/check_w08_oracle12.py`; its command and prerequisites are documented in the Chinese [README](../README.md#w08-子智能体目录). It compares all consumer step fields except the two known truncation metadata differences, then checks those metadata independently in the original wire, including large-output prefixes from the recorded Provider responses. The frozen TypeScript and product payloads remain unchanged. Complete-object equality is retained as a diagnostic, and prior failed evidence is not overwritten.
