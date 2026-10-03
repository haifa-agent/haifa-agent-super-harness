# W08 Delegation Transcript Slice

This slice connects SDK `task` Tool Calls to the existing `DeerFlowToolTranscriptProjection`. It does not yet register child agents in `HaifaRunBridge`, implement the subagent directory, or add live/replay step endpoints. It must not be reported as W08 completion.

## Implemented contract

SDK `agent` and `objective` become outward `subagent_type` and `prompt`. The authorized SDK arguments and model-visible Tool Schema are unchanged. `context` and `expected_output` remain in the SDK transcript rather than being reinterpreted as unsupported frozen parameters. There is no invented description; the frozen card falls back to prompt. Request and result both use the same Runtime Tool Call ID already used by the existing transcript.

For terminal task calls, `subagent_status` is always one of `completed`, `failed`, `cancelled`, or `timed_out`. A completed Tool call cannot promote a failed or unknown Child result to success. Creation-time rejection uses the SDK's structured `REJECTED` reason and needs no Child Run. Cancelled pending requests can have no child, model, usage or successful-output metadata.

Complete-output metadata from Haifa Agent PR #273 is mapped to the frozen result preview and digest. Without it, a complete legacy summary may be displayed, but its hash is never presented as a complete-output hash. Usage requires both nonnegative integer counts and a total representable exactly by the frontend; missing, invalid or overflowing values are omitted. Model and capped reason fields are only emitted when explicitly present in the authorized source. Ordinary tools, clarification handling, artifact projection and model-visible Tool Result content are unchanged.

No table, Run state, scheduler, Child ID scheme, approval engine, or independent output store is introduced.

## Verification and dependency

`DeerFlowDelegationProjectionTest` covers pure mapping and the actual transcript entry points. The existing public-JAR `SdkParentChildDelegationSmokeTest` now also asserts the new output metadata and Runtime Tool Call/lifecycle correlation. That smoke intentionally fails against a stale SDK without PR #273; it must not be weakened to accept missing metadata.

Keep this change in draft until the SDK change is reviewed, published through the approved SDK workflow, and the exact JAR fingerprint is used for the consumer gate. No candidate SDK version is committed to the POM. The standalone JDK mapping checks do not replace Maven/JUnit, ArchUnit, SDK/JAR, HTTP/SSE, restart, or frozen-browser evidence.

The full requirements and this iteration's verification report are maintained in the internal documentation repository under W08, with documentation PR #8. Remaining work includes catalog and Child registration, non-interactive Child policy, actual Child model attribution, live steps, persistent replay, Run-level usage aggregation and end-to-end acceptance.
