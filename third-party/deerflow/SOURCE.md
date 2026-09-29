# DeerFlow frontend source record

## W15 frozen Skill package

`skills/manifest.json` records the complete `skills/public/consulting-analysis/` package from the same pinned upstream commit. Its sole `SKILL.md` is copied byte for byte to a versioned directory, with SHA-256 `5bc99df7e5f8af5471eaa5d20b85fc46e99895cf3f2642fa9da94108d03ca13e`. The package is attributed to `bytedance/deer-flow@e545c28ac301e6f9feae247c513d8ea8f83df294` under the repository's MIT license in `LICENSES/deer-flow-MIT.txt`; the Skill frontmatter itself does not declare a license. The manifest checks both the package file and license bytes before the SDK catalog is built. Older manifest entries and their full directories must be retained while persisted Runs may reference them. The V2 `v2-format-control` contract fixture belongs to this repository's tests and is absent from the production manifest.

## Frontend source

The `frontend/` directory started from the complete tracked `frontend/` tree
at the pinned DeerFlow revision below. W18 applies one documented, reproducible
configuration-boundary patch after import.

| Field | Value |
| --- | --- |
| Repository | `https://github.com/bytedance/deer-flow.git` |
| Commit | `e545c28ac301e6f9feae247c513d8ea8f83df294` |
| Source path | `frontend/` |
| Git tree | `993ef89baf273257b7940ceae002200c416f380d` |
| Local path | `frontend/` |
| License | MIT; see `LICENSES/deer-flow-MIT.txt` |
| Patch state | One documented patch; see `patches/0001-memory-bff-use-server-gateway.patch` |

## Import boundary

The import was produced from the pinned Git object, not from the source
checkout's working directory. It includes all 980 tracked files under
`frontend/`, including `AGENTS.md`, `pnpm-lock.yaml`, `pnpm-workspace.yaml`,
Next.js configuration and route handlers, tests, public assets, and build
scripts.

Generated, ignored, local, and sensitive paths were not imported. In
particular, the import excludes `node_modules/`, `.next/`, `.env`,
`next-env.d.ts`, caches, test output, and local editor state.

`manifest.json` binds the import to the upstream commit/tree, the patch digest,
and a deterministic SHA-256 inventory over every local frontend file. Run
`scripts/verify-deerflow-frontend.ps1` to verify the offline import. Pass the
read-only upstream checkout with `-SourceRepository` to additionally verify
its HEAD, clean tracked frontend, Git tree, and file-by-file content.

## Configuration boundary

The pinned frontend supports the intended same-origin configuration except for
its two server-side `/api/memory` Route Handlers: they used the public browser
variable and otherwise fell back directly to `127.0.0.1:8001`. That could
bypass the selected Haifa target. The documented patch makes both handlers use
the existing server-only `getGatewayConfig().internalGatewayUrl` and adds a
target regression test. No UI, client state, or business payload is changed.

The supported configurations are:

- same origin: leave `NEXT_PUBLIC_BACKEND_BASE_URL` and
  `NEXT_PUBLIC_LANGGRAPH_BASE_URL` unset, and set the server-only
  `DEER_FLOW_INTERNAL_GATEWAY_BASE_URL` to the Haifa gateway;
- explicit split origin: set both `NEXT_PUBLIC_*` backend variables to Haifa
  endpoints and configure the Haifa gateway's CORS policy.

The same-origin configuration is preferred because it covers browser calls,
Next.js SSR, BFF route handlers, and `/api/langgraph/*` rewrites without
putting secrets in public environment variables. Neither configuration has a
DeerFlow backend fallback. A missing Haifa route must fail visibly.

Use `scripts/start-haifa-frontend.ps1` for the guarded Haifa-only local profile.
It rejects split-origin/public backend overrides, reference topology ports,
non-loopback targets, production markers, and secret-like `NEXT_PUBLIC_*`
variables before starting the frontend.

Local auth-disabled mode is opt-in through `DEER_FLOW_AUTH_DISABLED=1` and
must not be used when `DEER_FLOW_ENV` or `ENVIRONMENT` is `prod` or
`production`. The Haifa backend remains responsible for the compatible local
principal, permissions, owner boundaries, tool policy, and approvals.

## Updating

Do not follow DeerFlow `main`. An update requires an explicit new approved
commit, a fresh archive import, license review, manifest/hash regeneration,
patch-state review, and the relevant frontend checks. Never refresh the
manifest merely to make an unexplained local difference pass.
