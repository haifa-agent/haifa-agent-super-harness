# DeerFlow frontend patches

There are no patches for the pinned frontend import.

`frontend/` is byte-for-byte equivalent to the tracked files in
`bytedance/deer-flow@e545c28ac301e6f9feae247c513d8ea8f83df294:frontend`.
The existing upstream environment variables provide the required same-origin
and explicit-backend configuration surfaces, so this slice does not change
application code, routes, UI, branding, or feature behavior.

If a future approved change is unavoidable, store a reproducible patch here,
set `import.modified` to `true`, change `import.patchStatus`, and document the
reason, affected protocol surface, tests, and resulting inventory digest in
`../manifest.json` and `../SOURCE.md`.
