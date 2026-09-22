# W18/W01 frozen DeerFlow frontend import

## Delivered slice

This slice imports the complete tracked DeerFlow frontend at
`e545c28ac301e6f9feae247c513d8ea8f83df294` into `frontend/`. The original
import was zero patch. W18's second slice adds one documented, reproducible
server-only routing patch for the two memory BFF handlers; it retains the
upstream package manager declaration, lockfile, workspace overrides, public
assets, and UI.

The source, license, tree, content digest, and patch state are recorded under
`third-party/deerflow/`. This is L0 provenance/build evidence. It does not claim
that the DeerFlow reference backend or Haifa backend integration cases have
passed.

## Local Haifa configuration

Use a dedicated frontend port, Haifa gateway port, browser profile, and Haifa
data directory for this work package. Do not put credentials in a tracked file.

Preferred same-origin development configuration is now enforced by
`scripts/start-haifa-frontend.ps1`; see
`docs/development/w18-haifa-local-profile.md`. Its default guarded ports are
Haifa gateway `18018` and frontend `33018`.

The underlying environment is:

```powershell
$env:DEER_FLOW_INTERNAL_GATEWAY_BASE_URL = "http://127.0.0.1:<haifa-port>"
$env:DEER_FLOW_AUTH_DISABLED = "1"
$env:DEER_FLOW_ENV = "development"
pnpm --dir frontend dev -- --hostname 127.0.0.1 --port <frontend-port>
```

Leave `NEXT_PUBLIC_BACKEND_BASE_URL` and
`NEXT_PUBLIC_LANGGRAPH_BASE_URL` unset. Next.js then sends browser calls to its
own origin and forwards REST/BFF traffic plus `/api/langgraph/*` to the Haifa
gateway selected by the server-only variable. This must target Haifa only; there
is no fallback proxy to DeerFlow. The recorded memory BFF patch closes the only
direct `127.0.0.1:8001` Route Handler fallback found in the frozen tree.

For a deliberately split-origin test, set both public variables to the Haifa
gateway and configure Haifa CORS explicitly:

```powershell
$env:NEXT_PUBLIC_BACKEND_BASE_URL = "http://127.0.0.1:<haifa-port>"
$env:NEXT_PUBLIC_LANGGRAPH_BASE_URL = "http://127.0.0.1:<haifa-port>/api"
pnpm --dir frontend dev -- --port <frontend-port>
```

`NEXT_PUBLIC_*` values may be embedded at build time. Never place a token,
cookie, password, API key, or other secret in them. Auth-disabled mode is only
for a loopback-bound, controlled single-user profile and is rejected by the
frontend when `DEER_FLOW_ENV` or `ENVIRONMENT` is `prod`/`production`. It does
not disable Haifa owner checks, workspace boundaries, tool policy, approvals,
credential leases, or origin protection.

## Verification

Offline provenance/inventory gate:

```powershell
pwsh -File scripts/verify-deerflow-frontend.ps1
```

Pinned-source comparison when the read-only DeerFlow checkout is available:

```powershell
pwsh -File scripts/verify-deerflow-frontend.ps1 `
  -SourceRepository D:\workspace\haifa-agent-super-harness\deer-flow
```

Frontend checks, from the frozen lockfile:

```powershell
pnpm --dir frontend install --frozen-lockfile
pnpm --dir frontend check
pnpm --dir frontend test
pnpm --dir frontend build
```

Do not use the upstream mocked Playwright suite as proof of Haifa compatibility.
Real replacement acceptance remains a later integration step: the same frontend
must separately connect to the isolated DeerFlow reference backend and to Haifa,
with the DeerFlow destination blocked in the Haifa profile and real
Run/Tool/Artifact plus browser/SSR/BFF network evidence retained.

## Known limits after this slice

- No real Thread/Run/SSE backend was started by the import/config slices.
- No REST/SSE/WS/file/auth route is marked compatible merely because the UI
  builds or unit tests pass.
- Local auth-disabled mode does not satisfy full login/PAT/RBAC acceptance.
- Dependency lock and root MIT license are preserved, but a release distributor
  must still perform the normal transitive package and embedded asset notice
  review for the selected distribution form.
- Feature gates are not patched in this slice. Unimplemented Haifa endpoints
  must fail visibly until their owning work packages deliver them.

## Validation record

Validation was run on Windows with Node `v22.22.3` and the pinned Corepack
package manager `pnpm 10.26.2`:

| Check | Result |
| --- | --- |
| `pnpm install --frozen-lockfile` | PASS; 1088 packages, lockfile unchanged |
| `scripts/verify-deerflow-frontend.ps1 -SourceRepository <frozen-checkout>` | PASS; 980 files, 21,171,292 bytes, inventory `eb42ed47...e092` |
| `pnpm check` | PASS |
| `pnpm build` | PASS; production build and 101 static pages generated |
| `pnpm test` with frozen sibling test resources | PASS; 219 files, 1894 tests |

The standalone repository does not intentionally import DeerFlow backend or
Skill source trees. Three upstream frontend unit files read test-only sibling
fixtures from `../contracts`, `../skills/public`, and
`../backend/.../capabilities/builtin.json`. A direct standalone run therefore
reported those four missing-resource cases after 1850 tests passed. The full
suite was then executed from an isolated temporary copy of the same pinned
frontend, referencing those exact sibling fixtures from the frozen source
checkout; all 1894 tests passed. No expected value, assertion, frontend file,
fixture, or upstream checkout was changed to obtain the result.

These checks establish import/build/unit-test readiness only. The DeerFlow
reference backend and Haifa backend real-network matrix, including
REST/SSE/WS/files/auth and Run/Tool/Artifact evidence, remains NOT_RUN and must
be completed by the owning integration work packages.
