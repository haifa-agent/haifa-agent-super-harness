# W04 restricted Workspace and Artifact core

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

## Public Haifa API audit

The implementation consumes only local Maven artifacts at `0.1.1-SNAPSHOT`:

- `haifa-agent-project-api`: `WorkspaceId`, `WorkspacePath`, `ProjectPath`, filesystem records, the
  public read-side `WorkspaceFileService`, and mutation contracts. The published API has no concrete
  persistent binding/upload/mutation provider or factory; this slice implements the read contract,
  while owner/thread binding, immutable upload, and atomic write policy remain a narrow SH adapter.
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

The six integration tests use JUnit temporary directories and verify persisted workspace bindings
and files after service restart, owner isolation, path rejection, immutable same-name upload policy,
real bytes and independent SHA-256, provenance, immutable artifact loading after a working-file edit,
Haifa `WorkspaceFileService` stat/read/search, stale-hash rejection, missing files, invalid ranges,
and configured size limits.

## Deliberately remaining in W04

- frozen DeerFlow multipart/REST/download/Range adapters and browser-visible UI validation;
- Project shelf/document conversion, archive handling, diff/change APIs, and content preview policy;
- execution-provider mounting so exec and this workspace demonstrably see the same file identity;
- integration into the root reactor POM, coordinated by the main integrator;
- full W04 differential evidence and customer flow.

These omissions keep W04 `in_progress`; this module does not expose HTTP and does not claim W04
complete.
