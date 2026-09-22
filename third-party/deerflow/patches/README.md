# DeerFlow frontend patches

There is one patch for the pinned frontend import:

- `0001-memory-bff-use-server-gateway.patch` changes only the two server-side
  memory Route Handlers to use the existing server-only gateway configuration,
  then adds a regression test. It prevents the upstream `127.0.0.1:8001`
  fallback from bypassing the selected Haifa target. It does not change UI,
  branding, client state, or response payloads.

The patch applies cleanly to
`bytedance/deer-flow@e545c28ac301e6f9feae247c513d8ea8f83df294` and is bound by
SHA-256 in `../manifest.json`. The verifier reconstructs the pinned frontend,
applies all recorded patches, and compares the resulting inventory when a
source checkout is supplied.

If a future approved change is unavoidable, store a reproducible patch here,
set `import.modified` to `true`, change `import.patchStatus`, and document the
reason, affected protocol surface, tests, and resulting inventory digest in
`../manifest.json` and `../SOURCE.md`.
