# W24 deterministic evidence aggregation slice

Status: `in_progress`. This is a fail-closed L0/L1 evidence and governance slice, not a release or
Full Parity decision.

## Delivered boundary

`scripts/generate-evidence-report.ps1` validates the repository's project-management and static
provenance inputs, aggregates Maven Surefire XML, and emits deterministic files below a caller-selected
repository-local directory:

- `evidence-manifest.json`: structured statuses, source-file hashes, Surefire report hashes/counts,
  frozen DeerFlow source/tree/inventory identities, and work-package state counts;
- `evidence-manifest.sha256`: SHA-256 sidecar for the exact manifest bytes;
- `evidence-report.md`: compact human-readable projection of the same statuses.

The manifest deliberately keeps independent status axes. A successful static check of a
`SOURCE_VERIFIED` contract or `IMPORTED` resource is `validationStatus=PASS`; its actual protocol
status remains the recorded `PASS`, `FAIL`, or `NOT_RUN`. Current frozen contracts say `NOT_RUN`, so
the overall report remains `NOT_RUN` even when governance and Maven tests pass.

## Fail-closed governance

The verifier requires:

- `schemaVersion=5`, exactly 25 unique IDs `W00` through `W24`, and only the five allowed states, including `ready_for_acceptance` for whole-package acceptance handoff;
- timezone-bearing `startedAt`/`completedAt` values satisfying each state's lifecycle invariants;
- existing, unique, non-self prerequisites with no cycles;
- at least one machine-recognizable evidence reference for every `complete` package;
- `dashboard.html`, plus `work-package-dag.html` when present, to load `work-packages.js` exactly once,
  consume `window.WORK_PACKAGES_DATA`, and not embed another work-package array or per-ID rendering;
- the dashboard timeline to sort a copied milestones array by `at` descending, leaving source data
  untouched;
- all frozen contract/provenance SHAs to match
  `e545c28ac301e6f9feae247c513d8ea8f83df294`; imported seed files, license, lockfile, and recorded
  patches must exist with the declared hashes;
- runtime `PASS` claims to include explicit runtime evidence. Static extraction cannot create one.

Surefire failures are written as `FAIL` and then make the command fail. Missing Surefire XML is
`NOT_RUN`; `-RequireSurefire` turns that into a failed gate after preserving the emitted status.

## Architecture gate

This slice is a QA script and introduces no product domain object, storage model, endpoint, or
business runtime. The manifest records the product-model complexity gate as `NOT_RUN`: if a future
Super Harness functional model would be more complex than the corresponding frozen DeerFlow model,
maintainer review and an explicit manual decision are required before coding. Evidence aggregation
does not infer or auto-approve that decision.

## Commands

Run the complete offline gate:

```powershell
./build-support/verify-evidence.ps1
```

Run only the deterministic generator after a Maven test run:

```powershell
./scripts/generate-evidence-report.ps1 -RequireSurefire
```

Run the verifier's synthetic normal and negative cases:

```powershell
./tests/evidence/verify-evidence-report.ps1
```

Generated evidence stays under `target/` and is not source-controlled.

## Deliberately NOT_RUN

- frozen DeerFlow UI against the DeerFlow and Haifa backends, including fallback blocking;
- live model/provider quality, customer D01-D05 flows, Browser, external services, and L3/L4 evidence;
- non-Windows platform combinations, deployment, release approval, and Full Parity;
- product-model complexity review for future business implementations.

Those items remain explicit `NOT_RUN`; this static/Java-test slice cannot promote them.
