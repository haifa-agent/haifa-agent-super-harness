# W08 Delegation Compatibility

This candidate connects the frozen subagent catalog, SDK Child profiles, `task` Tool Calls and persisted Child steps through public SDK APIs. It must not be reported as full W08 completion.

## Implemented contract

SDK `agent` and `objective` become outward `subagent_type` and `prompt`. The authorized SDK arguments and model-visible Tool Schema are unchanged. `context` and `expected_output` remain in the SDK transcript rather than being reinterpreted as unsupported frozen parameters. There is no invented description; the frozen card falls back to prompt. Request and result both use the same Runtime Tool Call ID already used by the existing transcript.

For terminal task calls, `subagent_status` is always one of `completed`, `failed`, `cancelled`, or `timed_out`. A completed Tool call cannot promote a failed or unknown Child result to success. Creation-time rejection uses the SDK's structured `REJECTED` reason and needs no Child Run. Cancelled pending requests can have no child, model, usage or successful-output metadata.

Complete-output metadata from Haifa Agent PR #273 is mapped to the frozen result preview and digest. Without it, a complete legacy summary may be displayed, but its hash is never presented as a complete-output hash. Usage requires both nonnegative integer counts and a total representable exactly by the frontend; missing, invalid or overflowing values are omitted. Model and capped reason fields are only emitted when explicitly present in the authorized source. Ordinary tools, clarification handling, artifact projection and model-visible Tool Result content are unchanged.

The managed catalog stores product configuration alongside the existing configuration boundary. `HaifaRunBridge` freezes enabled catalog entries into SDK profiles, keeps selected model identity and credentials, and delegates scheduling, cancellation, Child execution and noninteractive approval denial to the SDK. No second Runtime, Child scheduler, approval engine or output store is introduced.

Child messages are read from the public SDK's authorized durable message pages. The product projects them into the existing Parent SSE and the task-scoped replay endpoint described in the [README](../README.md#w08-子智能体目录). Subscription signals only wake a connection's cursor reader; restart replay does not depend on transient subscriptions. Unknown or unsafe usage remains omitted.

## Verification and dependency

`DeerFlowDelegationProjectionTest` covers pure mapping and the actual transcript entry points. The existing public-JAR `SdkParentChildDelegationSmokeTest` now also asserts the new output metadata and Runtime Tool Call/lifecycle correlation. That smoke intentionally fails against a stale SDK without PR #273; it must not be weakened to accept missing metadata.

Keep this change in draft until the SDK change is reviewed, published through the approved SDK workflow, and the exact JAR fingerprint is used for the consumer gate. No candidate SDK version is committed to the POM. The standalone JDK mapping checks do not replace Maven/JUnit, ArchUnit, SDK/JAR, HTTP/SSE, restart, or frozen-browser evidence.

The exact SDK commit and JAR fingerprints come from the official publication manifest selected by the controller in ignored `local-tmp/sdk-selection.json`. Tracked dependencies retain the baseline version. The candidate cannot merge to Harness main until the upstream SDK is reviewed and merged by a human, the baseline is published from `dev`, and baseline gates are rerun.

The requirements and validation report remain in internal documentation PR #8. Full W08 is blocked by the retained Oracle 12: the original frozen live conversion drops truncation fields retained by replay. The SDK Windows database-close CI failure remains under review. Ultra depends on W07 plan execution; created-but-not-started Child coverage, complete dual-backend and dual-identity acceptance, and the unknown-usage/schema discrepancy remain open. Component tests and the catalog UI profile do not settle these gates.
