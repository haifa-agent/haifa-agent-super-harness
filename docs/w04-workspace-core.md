# W04 restricted Workspace, Project shelf, Artifact, and change core

Status: `in_progress`. This slice is an L2 filesystem integration core, not the complete W04 exit.

## Delivered boundary

`super-harness-workspace` binds a trusted `ownerId` and `threadId` to a deterministic, persisted
Haifa `WorkspaceId`. Its filesystem root contains separate `uploads/`, `workspace/`, and `outputs/`
areas. Logical paths accept the frozen DeerFlow form `/mnt/user-data/{area}/...` and the equivalent
relative form. Absolute paths, traversal, empty segments, Windows device names, non-portable path
characters, symbolic links, unknown workspaces, and cross-owner access fail closed.

Uploads are immutable. The first upload keeps its original filename; uploading the same bytes again
is idempotent; different bytes with the same requested name receive a deterministic
`__<sha256-prefix>` suffix. Mutable files are limited to `workspace/` and `outputs/`, use atomic
replacement, and support optional SHA-256 compare-and-set.

Publishing reads the verified workspace bytes once and calls Haifa's public `ArtifactService` with
an `ArtifactProvenance` containing workspace, project, Run, session, execution, source path, source
SHA-256, export policy, and trusted owner. Artifact metadata and content-addressed payload bytes are
persisted below the configured storage root. Later edits to the working file do not alter the
published artifact.

The second core slice adds a persistent Project document shelf without coupling it to the server.
Projects have a persisted owner binding. Uploading or promoting a plain-named thread upload/output
creates a project-owned immutable snapshot; content deduplication preserves the first document's
name and provenance. Document metadata records the frozen client's `upload`/`output` source values,
source thread/name, byte count, SHA-256, and timestamps. Bounded reads recheck the persisted size and
hash after restart. Project owners and source threads are verified independently.

A run can now persist a bounded pre-run workspace baseline and compare it with the current workspace
after restart. Created, replaced, and deleted files are represented with the public Haifa
`FileChange`/`FileVersion` records. Text changes produce a deterministic unified diff with file
headers and a valid `@@` hunk header through public `DiffRequest`/`DiffResult`; invalid UTF-8 files
remain in the change list and are explicitly reported as diff-unavailable. File count, per-file byte,
and UTF-8 output-byte limits fail closed. Baseline path, size, hash, missing-payload, and owner/thread
metadata are revalidated before use.

## Public Haifa API audit

The implementation consumes only local Maven artifacts at `0.1.1-SNAPSHOT`:

- `haifa-agent-project-api`: `WorkspaceId`, `WorkspacePath`, `ProjectPath`, filesystem records, the
  public read-side `WorkspaceFileService`, `FileChange`, `FileVersion`, `DiffFile`, `DiffRequest`, and
  `DiffResult`. The published API has no concrete persistent binding/upload/mutation/diff provider or
  factory; this slice implements those storage and bounded-diff policies as a narrow SH adapter while
  retaining the published value contracts.
- `haifa-agent-artifact`: public `ArtifactService`, `ArtifactStore`, `ArtifactPayloadStore`, and
  provenance/value types. Publication and loading go through `ArtifactService`; SH supplies
  persistent implementations of its two public store ports because the published concrete store is
  in-memory only.

There is no source-path dependency, copied SDK code, private-table access, product JAR, or direct
Haifa implementation-module dependency. The parent dependency guards run for the standalone module.

## Verification

Run from the repository root:

```powershell
mvn -o -ntp -f super-harness-workspace\pom.xml verify
mvn -o -ntp -f super-harness-workspace\pom.xml dependency:tree -Dscope=compile
```

The integration tests use JUnit temporary directories. The original six cover persisted
workspace bindings and files after restart, owner isolation, path rejection, immutable same-name
upload policy, real bytes and independent SHA-256, provenance, immutable artifact loading after a
working-file edit, Haifa `WorkspaceFileService` stat/read/search, stale-hash rejection, missing files,
invalid ranges, and configured size limits. The additional tests cover Project first-writer deduplication,
output promotion and persisted provenance, bounded document reads after restart, project/thread/name/
size/missing failures, created/modified/deleted/binary changes, persisted baselines, valid unified
hunks, exact UTF-8 output boundaries, duplicate/missing run baselines, and tampered path/hash metadata.
Project document id/project/hash/size/name/timestamp metadata corruption also fails as content-integrity
errors instead of leaking parser or path exceptions.

The third slice wires the restricted Workspace into the compatibility server without introducing a
second thread or owner registry. An existing server-owned thread can use the frozen client's
`files` multipart upload, limits, and listing routes. Returned `artifact_url` values resolve through
the frozen `/api/threads/{thread}/artifacts/mnt/user-data/...` shape. Full reads and one RFC 9110 byte
range return verified Workspace bytes with real `Content-Length`, `Content-Range`, `ETag`, MIME, and
download disposition. Invalid or multiple ranges return 416; unknown threads, other path roots,
traversal, links, non-files, size drift, or hash drift fail closed. Multipart request bytes, headers,
file count, individual file bytes, and total bytes are bounded before storage. The HTTP adapter lowers
the frozen client's configurable 100 MiB total to 25 MiB, accepts only one upload request at a time,
and caps multipart framing overhead at 256 KiB. Its retained payload arrays are therefore bounded to
the request body (25.25 MiB), decoded parts (25 MiB total), and one service-owned 25 MiB clone; an
existing-name integrity comparison can transiently add one further 25 MiB read. Later-member failure
compensates every earlier newly-created receipt in reverse order after rechecking owner, path, size,
and hash; pre-existing deduplicated files are never deleted. Active HTML/XHTML/SVG content is always
an attachment.

Artifact delivery reads and verifies the bounded file into one snapshot and hashes it once. Full and
Range responses write directly from that snapshot, so a large response no longer re-reads/re-hashes
the whole file per page or allocates a second Range-sized array. Filename MIME detection is followed
by the frozen backend's 8 KiB no-NUL text sniff, preserving extensionless text previews while leaving
extensionless binary content as `application/octet-stream`.

This HTTP slice still uses the server-selected local owner `default`; client input cannot choose an
owner. It asks the existing Thread API whether the thread exists and keeps all file identity below
the already persisted owner/thread Workspace binding. Its Java adapter is materially smaller than
the frozen DeerFlow upload, ingestion, conversion, artifact, and sandbox layers and adds no product
domain record, state machine, or separate persistence authority.

## Frozen source anchors

- `frontend/src/core/projects/api.ts` and `types.ts`: multipart field `file`, optional `name`,
  `thread_id`/`kind`/`name`/`shelf_name`, content preview Range request, and project document fields.
- `frontend/src/core/workspace-changes/api.ts` and `types.ts`: thread+run endpoint, status/summary/file
  model, and `include_files`/`include_diff` query flags.
- `frontend/src/core/uploads/api.ts` and `frontend/src/core/artifacts/{utils,loader}.ts`: multipart
  `files`, limits/list routes, artifact URL projection, download, and bounded Range behavior.
- `backend/app/gateway/routers/uploads.py` and `artifacts.py`: response fields, limits, content
  disposition, active-content policy, ETag, and Range semantics.
- `backend/app/gateway/routers/project_documents.py`: immutable snapshot, first-writer deduplication,
  plain-name confinement, source provenance, content integrity, and attach semantics.
- `backend/app/gateway/routers/thread_runs.py`: workspace-changes route registration and run ownership.

These anchors are from detached `bytedance/deer-flow@e545c28ac301e6f9feae247c513d8ea8f83df294`.

## Deliberately remaining in W04

- browser-visible frozen-UI validation remains `NOT_RUN`; the HTTP contract has automated real-socket
  coverage but this slice does not claim a browser run;
- Project conversion, archive/trash, attach-to-thread ingestion, HTTP diff/change response projection,
  and upload deletion;
- execution-provider mounting so exec and this workspace demonstrably see the same file identity;
- full W04 differential evidence and customer flow.

These omissions keep W04 `in_progress`; the new server adapter is a bounded vertical slice and does
not claim W04 complete.
