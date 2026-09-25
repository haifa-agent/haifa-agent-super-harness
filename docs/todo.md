# SDK follow-up requiring human merge

- [x] Human-review and merge [haifa-agent PR #239](https://github.com/haifa-agent/haifa-agent/pull/239) into `feat-0.1.1-baseline`. Agents must not merge this PR.
  - Status (verified 2026-09-25 via `gh pr view`): #239 merged 2026-09-24T08:10:02Z (`5e95cc78`), reverted by [#241](https://github.com/haifa-agent/haifa-agent/pull/241) at 09:40:43Z, then re-landed by [#242](https://github.com/haifa-agent/haifa-agent/pull/242) at 09:41:18Z (`78a0ee02`, merge `e89a8302`). Local SDK baseline `feat-0.1.1-baseline` is `22c9261c` and contains the re-land. All merges were performed by a human.
  - SDK branch/commit: `feat-sqlite-durable-assembly` / `6a4575f13c5a5153ab4781a4ae96cc9988426a82` (worktree since removed).
  - Public surface: `SqliteSdkContributions.initializeWithKey(..., SecretKey)` and lifecycle-safe `borrowedPersistence()`; no Super Harness domain model was added upstream.
  - Validation: SDK `-Pci-fast clean verify` passed 2,095 tests; targeted continuation reopen/wrong-key/invalid-key/borrowed-lifecycle tests passed. The published local `0.1.1-SNAPSHOT` SQLite JAR SHA-256 is `6958A9EDCFE974DE493580FD42DB2F02676EEC5B1721919557E1DAEF0005B70E`; `javap` verified both public methods before the Super Harness `clean verify` passed 95 tests with 1 credential-gated live test skipped.
  - Super Harness consumes only the local Maven `0.1.1-SNAPSHOT` public JAR/BOM; it does not depend on the SDK source tree or Runtime implementation packages.

- [x] ~~Republish the local `0.1.1-SNAPSHOT` JAR/BOM from the merged baseline and re-run the Super Harness clean gate.~~ Superseded 2026-09-25: HA `0.1.1` was released and Super Harness now consumes the `0.1.2-SNAPSHOT` line (next item).
  - The local SQLite JAR (SHA-256 above, installed 2026-09-24 10:03 +08:00) was built from `6a4575f`, before the merge. Between `6a4575f` and `22c9261c` the SQLite module's main code differs only by `requireAes256Key` going from `private` to package-private, so its public API is identical; `javap` still shows both methods.
  - Other local SDK JARs are a mix of 2026-09-22 and 2026-09-24 installs. The baseline also carries unpublished changes (native MCP `McpServerSpec`, the `JavaToolAssembly` → `ToolAssembly` rename, compaction). Super Harness Java sources do not reference the renamed types. Status: `NOT_RUN`.

# HA 0.1.2-SNAPSHOT SDK gap line

- [ ] Consume Haifa Agent `0.1.2-SNAPSHOT`, the HA development line dedicated to the Super Harness SDK gaps.
  - Gap index and requirement prompts: `private-docs/prompts/038-sdk-012-super-harness-gaps/` in the `haifa-agent-internal-docs` repository.
  - `haifa.version`, the clean-consumer BOM path and current-state docs now point to `0.1.2-SNAPSHOT`. Local artifacts were installed 2026-09-25 17:11–17:16 +08:00 from HA `feat-bump-0.1.2-snapshot` / `c66a3b76` (merged into `dev` by haifa-agent #246), which contains the released `0.1.1` baseline.
  - 2026-09-25 switch validation: `mvn -o -ntp clean verify` BUILD SUCCESS, 119 tests, 0 failure/error, 2 intentional skips; `dependency:list` resolves every `io.haifa` artifact at `0.1.2-SNAPSHOT`. `verify-clean-consumer.ps1`, Playwright UI regression and real-provider smoke: `NOT_RUN`.
  - There is no baseline branch for 0.1.2: every SDK gap fix branches `feat-*` from HA `dev`, opens its PR against `dev`, is republished to the local Maven repository, and needs a human-merged PR recorded here; agents must not merge it.
  - `DeerFlowConfigurationApi` still labels the managed provider version `0.1.1-SNAPSHOT`; it is part of the model configuration digest, so changing it needs a data-root compatibility decision first.
