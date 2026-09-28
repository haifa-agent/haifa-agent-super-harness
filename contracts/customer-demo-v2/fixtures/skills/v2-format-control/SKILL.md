---
name: v2-format-control
description: Test-only Skill fixture for V2 contract verification. This Skill is NOT an upstream DeerFlow resource. It exists only to exercise independent enable/disable, version/hash recording, reload and context isolation. It must never be activated in the Golden Scenario.
license: TEST-FIXTURE-ONLY
version: 0.0.1-v2-contract
---

# v2-format-control (test fixture only)

This is a **test-only** Skill used by the V2 contract verifier. It is not an
upstream DeerFlow Skill and must not be presented as one.

## Behavior

When (and only when) it is explicitly enabled and activated, it instructs the
agent to append the marker `V2-FORMAT-CONTROL-ACTIVE` to its response. This
marker is used to prove two things:

1. A Skill that is disabled does not change behavior.
2. Activating one Skill does not leak into an unrelated run or another Skill's
   context.

## Non-goals

- It does not perform data collection.
- It does not replace or emulate `consulting-analysis`.
- It must not enter the Golden Scenario.
