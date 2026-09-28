# Super Harness Runtime / Product Boundary

Status: normative architecture guardrail.

This document defines where a capability belongs between **Haifa Agent** and **Haifa Super Harness**.
It is a stable repository rule, not a work-package task prompt. Task-specific prompts still belong under
`private-docs/prompts/39-deerflow-parity-delivery/` as required by `AGENTS.md`.

## Core rule

When Super Harness discovers a missing foundational capability, first decide whether the capability is
part of a generic Agent Runtime.

If the answer is yes, the capability belongs in **Haifa Agent**. It must be exposed through Haifa's
public API/SPI and published JAR/BOM, then consumed by Super Harness through those public artifacts.

**Super Harness must not implement a second runtime to fill an upstream gap.**

Super Harness is the product, compatibility, and integration layer. Its job is to compose Haifa Agent
capabilities into a DeerFlow-compatible product experience, not to become an alternative implementation
of Haifa Agent.

## Ownership map

| Capability | Primary owner | Super Harness responsibility |
| --- | --- | --- |
| Agent Runtime / Agent Loop | Haifa Agent | Invoke and adapt public runtime APIs |
| Run / Thread / Session execution semantics | Haifa Agent | Map DeerFlow REST/SSE/UI semantics onto Haifa concepts |
| Context management / compaction | Haifa Agent | Product configuration and compatibility mapping |
| Memory model and memory backends | Haifa Agent | Product UX and configuration surfaces |
| Tool execution lifecycle | Haifa Agent | Product tool exposure and compatibility mapping |
| Skill registry / activation semantics | Haifa Agent | Product selection, presets, and UI |
| MCP client/runtime lifecycle | Haifa Agent | Product configuration and business integration |
| Parent/Child Agent, delegation, steer | Haifa Agent | Product interaction and presentation |
| Sandbox lifecycle and isolation semantics | Haifa Agent | Product policy/configuration and provider selection |
| Policy / permission / approval semantics | Haifa Agent | Product approval UI and workflow presentation |
| Artifact lifecycle / provenance | Haifa Agent | Product rendering, download, and compatibility mapping |
| Checkpoint / recovery semantics | Haifa Agent | Product UX and compatibility mapping |
| Workspace core contracts | Haifa Agent | Narrow adapters when a public port exists but no suitable concrete provider is published |
| Model abstraction and invocation | Haifa Agent | Product model catalog/configuration |
| Principal / runtime identity / credential injection / generic RBAC | Haifa Agent | Product administration and tenant-facing UI |
| Observability primitives and runtime events | Haifa Agent | Product dashboards, projections, and operational views |
| DeerFlow REST/SSE/WS compatibility | Super Harness | Own the compatibility adapter |
| Frozen DeerFlow frontend integration | Super Harness | Own BFF/protocol adaptation and product wiring |
| Product presets / prompts / personas | Super Harness | Own product semantics |
| Product-specific workflows and use cases | Super Harness | Compose existing runtime capabilities |
| Enterprise application adapters | Super Harness or integration module | Use Haifa extension points; do not replace runtime subsystems |
| Control-plane UI / management views | Super Harness | Manage and visualize runtime capabilities |
| Customer-demo and parity orchestration | Super Harness | Own product-level acceptance and evidence |

The table describes ownership of **semantics**, not necessarily every implementation class. A narrow
Super Harness adapter may implement a public Haifa port when the SDK intentionally exposes the port but
does not publish a suitable concrete provider. That adapter must remain an adapter: it must not grow a
parallel domain model, state machine, lifecycle, or second source of truth.

## Explicit non-goals for Super Harness

Super Harness must not introduce a parallel:

- Run Engine or Agent Loop;
- Thread/Run execution state machine;
- Memory Engine;
- Tool Engine or independent Tool Registry;
- Skill Runtime or independent Skill Registry;
- MCP Runtime;
- Sandbox Manager;
- Artifact lifecycle;
- Checkpoint system;
- Permission / approval engine;
- generic multi-agent runtime.

A compatibility object, projection, DTO, cache, or adapter is allowed only when it translates between
the frozen DeerFlow contract and an existing Haifa public capability. Naming a duplicate subsystem
"adapter", "manager", "service", or "core" does not make it an adapter if it owns independent runtime
semantics.

## Decision procedure for every new capability

Before coding, answer these questions in order.

1. **Could the capability be reused by more than one Haifa product?**  
   If Coding Agent, SDK applications, Personal Assistant, Super Harness, or another future product could
   reasonably need the same semantics, treat it as a Haifa Agent capability.

2. **Does it define execution lifecycle, durable state, security, isolation, recovery, or model-visible
   behavior?**  
   If yes, it is presumptively Runtime and belongs in Haifa Agent.

3. **Is it only product presentation, DeerFlow protocol mapping, a preset, a product-specific workflow,
   or a business-system integration?**  
   If yes, it normally belongs in Super Harness.

4. **Does Haifa Agent already expose a public API/SPI for it?**  
   Reuse that API. Do not bypass it through internal packages, source-path dependencies, private tables,
   copied code, or a new Super Harness subsystem.

5. **Is the required public Haifa capability missing?**  
   Record the exact upstream gap and implement it in Haifa Agent first. Publish the updated public
   `0.1.2-SNAPSHOT` artifacts and then consume them from Super Harness.

6. **Is only a concrete adapter missing while the public port already exists?**  
   A narrow Super Harness implementation is acceptable when it is product/integration-specific and does
   not duplicate the upstream domain semantics.

If the classification is still ambiguous, stop before adding new domain objects, state machines, or
persistence tables and escalate the boundary decision.

## Warning signs of a second runtime

Treat any of the following as an architecture-review trigger:

- a new Super Harness table that becomes authoritative for Run, Thread, Message, Memory, Tool, Skill,
  Approval, Checkpoint, Artifact, or Sandbox lifecycle state already owned by Haifa;
- a scheduler/loop that decides the next Agent step independently of Haifa Runtime;
- independent retry, cancellation, suspension, resume, or terminal-state semantics for Agent Runs;
- a new Tool or Skill registry whose truth can diverge from Haifa;
- direct reads/writes of Haifa private storage instead of public services;
- copied Haifa SDK/runtime code;
- a product adapter accumulating generic execution policy;
- two implementations that must be kept behaviorally synchronized to make the same Run correct.

The preferred repair is usually to move the missing generic capability upstream and reduce Super
Harness back to translation, configuration, composition, and presentation.

## Allowed exception and escalation

An exception requires concrete evidence that the frozen DeerFlow compatibility contract cannot be met
through the current Haifa public surface and that a narrow Super Harness implementation is the least
complex option.

Before implementing such an exception, document:

1. the exact frozen DeerFlow behavior that must be satisfied;
2. the closest existing Haifa public capability;
3. the missing upstream seam;
4. why a public Haifa extension cannot reasonably land first;
5. the additional domain/state/persistence complexity introduced in Super Harness;
6. how the implementation remains a narrow adapter rather than a second source of truth;
7. the migration or deletion path if Haifa later publishes the missing capability.

This escalation complements the existing `AGENTS.md` rule that Super Harness domain complexity must
remain balanced with or lower than the frozen DeerFlow implementation.

## Reusable architecture prompt

The following prompt may be copied into a development or review conversation as a stable architecture
guardrail. It does **not** replace the task-specific work-package prompt required by `AGENTS.md`.

> You are implementing or reviewing a change in Haifa Super Harness.
>
> Before writing code, classify every proposed capability as either **generic Agent Runtime** or
> **Super Harness product/integration**.
>
> Haifa Agent is the single owner of reusable runtime semantics: Agent Loop, Run/Thread lifecycle,
> context and memory, Tool execution, Skill runtime, MCP lifecycle, Parent/Child and steer, sandbox,
> policy/approval, Artifact lifecycle, checkpoint/recovery, generic workspace contracts, model
> invocation, runtime identity/credential injection/RBAC, and observability primitives.
>
> Super Harness owns DeerFlow compatibility, frozen frontend integration, product presets/prompts,
> product-specific workflows, business adapters, control-plane views, and presentation.
>
> First inspect existing Haifa public APIs and existing Super Harness adapters. Reuse them before
> designing anything new. If a generic runtime capability is missing, identify the exact Haifa Agent
> SDK/runtime gap and move that capability upstream; do not implement a parallel subsystem in Super
> Harness. Super Harness may provide only a narrow adapter when a public Haifa port already exists but
> a product-specific concrete provider is missing.
>
> Explicitly reject designs that create a second Run Engine, Agent Loop, Memory Engine, Tool/Skill
> registry, MCP Runtime, Sandbox Manager, Artifact lifecycle, Checkpoint system, permission engine, or
> generic multi-agent runtime.
>
> In the implementation/review report, state:
> 1. which existing Haifa capability is reused;
> 2. which behavior is genuinely Super Harness-specific;
> 3. whether any upstream Haifa gap was found;
> 4. what duplicate-runtime design was considered and rejected;
> 5. why any new Super Harness state or persistence is only compatibility/product state and cannot
>    diverge from Haifa's runtime truth.
>
> If the ownership boundary is unclear, stop before adding new domain models, state machines, or
> persistence tables and request an architecture decision.

## PR review gate

A Super Harness PR touching runtime-adjacent behavior should be rejected or redesigned unless all of
the following are true:

- it consumes Haifa through public JAR/BOM APIs only;
- it does not create an independent runtime source of truth;
- any new state is clearly compatibility/product state;
- existing Haifa capabilities are reused before local abstractions are added;
- an upstream Haifa gap is handled upstream rather than patched with a parallel Super Harness engine;
- adapters remain narrow and simpler than the runtime semantics they translate;
- the change remains compatible with the frozen DeerFlow contract and existing repository dependency
  guards.
