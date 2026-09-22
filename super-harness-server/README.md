# DeerFlow-compatible local bootstrap API

This module is the first W02 vertical slice. It exposes only the startup endpoints consumed by
the frozen DeerFlow frontend at `e545c28ac301e6f9feae247c513d8ea8f83df294`:

- `GET /api/v1/auth/setup-status`
- `GET /api/v1/auth/me`
- `GET /api/features`
- `GET /api/models`
- `GET /health` for local process readiness

It deliberately reports not-yet-implemented capabilities and models as unavailable. Thread, Run,
SSE, cancellation, files, and account authentication remain later W02/W04/W05 slices.

The response fixtures were derived from these files in the frozen checkout, not from a moving
upstream branch:

- `frontend/src/core/auth/auth-disabled-user.ts`
- `frontend/tests/e2e-real-backend/auth-disabled-contract.spec.ts`
- `frontend/src/core/auth/setup.ts` and `frontend/src/core/auth/types.ts`
- `frontend/src/core/features/api.ts` and `backend/app/gateway/routers/features.py`
- `frontend/src/core/models/{api,types}.ts` and `backend/app/gateway/routers/models.py`
- `backend/tests/test_auth_me_permissions.py`

The server accepts only the `local` profile and resolves the listener to a loopback address before
opening the socket. `DEER_FLOW_ENV`, `ENVIRONMENT`, or `SUPER_HARNESS_ENVIRONMENT` set to `prod` or
`production`, a non-loopback bind address, or a non-local profile aborts startup. The principal and
permissions are server constants; identity or role headers from the caller are ignored.

Configuration variables and defaults:

| Variable | Default |
|---|---|
| `SUPER_HARNESS_PROFILE` | required: `local` |
| `DEER_FLOW_AUTH_DISABLED` | required: `1` |
| `SUPER_HARNESS_BIND_ADDRESS` | `127.0.0.1` |
| `SUPER_HARNESS_PORT` | `8001` |

Run the contract and real-socket integration tests with:

```powershell
mvn -pl super-harness-server -am test
```

Build and start the executable JAR with:

```powershell
mvn -pl super-harness-server -am package
$env:SUPER_HARNESS_PROFILE = 'local'
$env:DEER_FLOW_AUTH_DISABLED = '1'
java -jar super-harness-server/target/super-harness-server-0.1.0-SNAPSHOT-exec.jar
```
