# DeerFlow frontend source record

The `frontend/` directory is an unmodified import of the complete tracked
`frontend/` tree from the pinned DeerFlow revision below.

| Field | Value |
| --- | --- |
| Repository | `https://github.com/bytedance/deer-flow.git` |
| Commit | `e545c28ac301e6f9feae247c513d8ea8f83df294` |
| Source path | `frontend/` |
| Git tree | `993ef89baf273257b7940ceae002200c416f380d` |
| Local path | `frontend/` |
| License | MIT; see `LICENSES/deer-flow-MIT.txt` |
| Patch state | Zero patch |

## Import boundary

The import was produced from the pinned Git object, not from the source
checkout's working directory. It includes all 980 tracked files under
`frontend/`, including `AGENTS.md`, `pnpm-lock.yaml`, `pnpm-workspace.yaml`,
Next.js configuration and route handlers, tests, public assets, and build
scripts.

Generated, ignored, local, and sensitive paths were not imported. In
particular, the import excludes `node_modules/`, `.next/`, `.env`,
`next-env.d.ts`, caches, test output, and local editor state.

`manifest.json` binds the import to the upstream commit/tree and to a
deterministic SHA-256 inventory over every local frontend file. Run
`scripts/verify-deerflow-frontend.ps1` to verify the offline import. Pass the
read-only upstream checkout with `-SourceRepository` to additionally verify
its HEAD, clean tracked frontend, Git tree, and file-by-file content.

## Configuration boundary

No frontend source patch is required for the first local integration slice.
The pinned frontend already supports both intended configurations:

- same origin: leave `NEXT_PUBLIC_BACKEND_BASE_URL` and
  `NEXT_PUBLIC_LANGGRAPH_BASE_URL` unset, and set the server-only
  `DEER_FLOW_INTERNAL_GATEWAY_BASE_URL` to the Haifa gateway;
- explicit split origin: set both `NEXT_PUBLIC_*` backend variables to Haifa
  endpoints and configure the Haifa gateway's CORS policy.

The same-origin configuration is preferred because it covers browser calls,
Next.js SSR, BFF route handlers, and `/api/langgraph/*` rewrites without
putting secrets in public environment variables. Neither configuration has a
DeerFlow backend fallback. A missing Haifa route must fail visibly.

Local auth-disabled mode is opt-in through `DEER_FLOW_AUTH_DISABLED=1` and
must not be used when `DEER_FLOW_ENV` or `ENVIRONMENT` is `prod` or
`production`. The Haifa backend remains responsible for the compatible local
principal, permissions, owner boundaries, tool policy, and approvals.

## Updating

Do not follow DeerFlow `main`. An update requires an explicit new approved
commit, a fresh archive import, license review, manifest/hash regeneration,
patch-state review, and the relevant frontend checks. Never refresh the
manifest merely to make an unexplained local difference pass.
