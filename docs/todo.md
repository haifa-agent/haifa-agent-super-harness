# SDK follow-up requiring human merge

- [ ] Human-review and merge [haifa-agent PR #239](https://github.com/haifa-agent/haifa-agent/pull/239) into `feat-0.1.1-baseline`. Agents must not merge this PR.
  - SDK branch/commit: `feat-sqlite-durable-assembly` / `6a4575f13c5a5153ab4781a4ae96cc9988426a82`.
  - Public surface: `SqliteSdkContributions.initializeWithKey(..., SecretKey)` and lifecycle-safe `borrowedPersistence()`; no Super Harness domain model was added upstream.
  - Validation: SDK `-Pci-fast clean verify` passed 2,095 tests; targeted continuation reopen/wrong-key/invalid-key/borrowed-lifecycle tests passed. The published local `0.1.1-SNAPSHOT` SQLite JAR SHA-256 is `6958A9EDCFE974DE493580FD42DB2F02676EEC5B1721919557E1DAEF0005B70E`; `javap` verified both public methods before the Super Harness `clean verify` passed 95 tests with 1 credential-gated live test skipped.
  - Super Harness consumes only the local Maven `0.1.1-SNAPSHOT` public JAR/BOM; it does not depend on the SDK source tree or Runtime implementation packages.
