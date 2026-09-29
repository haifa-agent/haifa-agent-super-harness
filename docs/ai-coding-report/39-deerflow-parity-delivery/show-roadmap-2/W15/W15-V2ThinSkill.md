# W15-task-1 交付报告（待主控验收）

日期：2026-09-29。工作树：`D:\workspace\haifa-agent-worktrees\w15-v2-skill-prep\haifa-agent-super-harness`；分支：`codex/w15-v2-skill-prep`；基线：`main@d867f408f4425045e254d3c5fba9f67cf3035804`。最终 commit SHA 由主控在提交后补记私有副本。执行者仅提交实现与证据，不修改 SHPM，不合并，也不宣告 V2-M2 PASS。

## 设计与来源

- 仅消费本地发布的 HA `0.1.2-SNAPSHOT` BOM/JAR。`spike/pom.xml` 的公开 API 探针使用 sdk-starter、skill-core 与 SQLite 持久层；没有 SDK 源码路径依赖。
- 生产 `third-party/deerflow/skills/manifest.json` **仅登记 consulting-analysis**，完整原件放在按 DeerFlow commit、内容 SHA 分层的不可变目录。原件 `SKILL.md` SHA-256 为 `5BC99DF7E5F8AF5471EAA5D20B85FC46E99895CF3F2642FA9DA94108D03CA13E`。`third-party/deerflow/SOURCE.md` 记录来源、许可与固定 commit。测试用的 `contracts/customer-demo-v2/fixtures/skills/v2-format-control/` 只在 `local-tmp` 独立测试 manifest 中登记，未加入生产 manifest。
- SDK 默认解析上限拒绝冻结的 632 行、约 8196 token 原件；只对这个已哈希校验的受信来源声明 700 行、9000 token 上限。第二个 v2 fixture 保持独立的默认限制。解析器证据见 `evidence/public-run-canonical.raw.log`、`evidence/negative-probe-3.raw.log`。
- 发行物保留旧版完整字节；当前 manifest 只把一个版本标 `active`，旧版仍供 loader 读取。`data-root/skills_config.json` 只存 enable 开关，原子替换。SDK 持有 catalog、binding、activation、Run 持久化；SH 没有 Skill Runtime、Run→Skill 表或 thread metadata。设计依据见 `spike/immutable-resource-decision.md`。
- `README.md` 已同步中文资源、配置与接口说明。

变更文件：`.gitattributes`、`README.md`、`super-harness-server/pom.xml`、`super-harness-server/src/main/java/io/haifa/agent/superharness/server/{DeerFlowBootstrapApi,DeerFlowConfigurationApi,DeerFlowSkillsApi,DeerFlowThreadRunApi,HaifaRunBridge,SkillConfigStore,SkillRunTools}.java`、`super-harness-server/src/test/java/io/haifa/agent/superharness/server/V2SkillConfigStoreTest.java`、`third-party/deerflow/SOURCE.md`、`third-party/deerflow/skills/manifest.json`、冻结 `SKILL.md` 与本报告。

## 已执行的定向证据

| 门禁 | 结果 | 原始证据（均相对 `local-tmp/slot-2-w15-v2-skill/`） |
| --- | --- | --- |
| 公开 JAR/BOM 两 Skill 持久 Run | PASS | `evidence/public-run-canonical.raw.log`；两个真实 SDK ToolCall，Run 重启后可恢复。 |
| 旧版/新版快照及禁用后恢复 | PASS | `evidence/versioned-immutable-spike.raw.log`、`evidence/versioned-disabled-spike.raw.log`；旧 Run `01a0ebe0-d07c-7d23-9faa-ad46cb72d1f8` 重启后仍读 v1，新 Run 使用 v2。 |
| SDK None/[]/allowlist、禁用与按需激活 | PASS | `evidence/visibility-canonical-spike.raw.log`；正文只在 `skill_load` 后可见。 |
| SH 生产资源/持久配置 | PASS | `evidence/store-probe.raw.log`；来源、许可、哈希、禁用、reload、重开保持。 |
| SH Run 桥与 ToolCall | PASS | `evidence/sh-bridge-probe-4.raw.log`；启用 Run `01a0ebf6-8753-744d-a2e6-7a4d8a46059f` 完成真实 `skill_load`；禁用和 Agent `[]` 的模型请求没有目录、正文或 `skill_load` 工具。 |
| 恢复桥后新 Run | PASS | `evidence/sh-http-recovery-probe-3.raw.log`；恢复历史建桥一次，禁用后的新 Run 重建桥，`loadTool=false,index=false`。 |
| pending Approval 跨重启 | PASS（修复后） | `evidence/sh-http-pending-probe-2.raw.log`；旧 Run `01a0ebf6-21b5-7f4d-930c-07d6dae507a4`、request `01a0ebf6-31ce-7613-bebe-564abd0a8668`，禁用并重启后审批执行，旧正文仍可读。 |
| 两 Skill 独立投影与 REST | PASS | `evidence/sh-dual-skill-http-probe-2.raw.log`；独立 enable、GET list/detail、PUT 单条响应、reload 形状、None/[]/双 allowlist、激活隔离、禁用后新 Run。测试专用 manifest、资源和数据在 `spike/dual-http-repository-2/`。 |
| 坏包/越界/哈希负例 | PASS | `evidence/store-negative-probe.raw.log`、`evidence/negative-probe-3.raw.log`；缺 license、哈希漂移、重复 active、路径逃逸、坏 frontmatter 均拒绝，失败 reload 保留已发布视图。 |
| 仓库根与模块 cwd | PASS | `evidence/repo-root-probe.raw.log`、`evidence/module-root-probe.raw.log`；均可解析受信 manifest。 |
| `git diff --check` 与静态 secret scan | PASS | 本 lane 执行；secret pattern 无命中。提交后再执行 `git diff --cached --check`。 |
| reactor compile | PASS，但不能提升为完整门禁 | `evidence/reactor-compile.raw.log`；约 14:45:23–14:45:33，FreePhysicalMemory 4,473,856 KB，`mvn -o -ntp -pl super-harness-server -am -DskipTests compile` 5/5，10.292 秒。此时其它 `spring-boot:run` PID 39852 尚在，主控随后明确要求将其视为其它 Maven，故不再发起 Maven。之后变更已用本地 classpath 的 `javac` 编译，JUnit 源也可编译，JUnit 运行仍未执行。 |

首阶段全部原始 probe 日志直接写在本工作包外置独立目录 `local-tmp/slot-2-w15-v2-skill/evidence/`，没有放在任何 `target`；首阶段未执行 `mvn clean`，故无清理前复制动作。首阶段有 25 份证据文件（包括 Maven compile、首次失败和复跑）；轻量探针没有统一计时器，各次独立耗时记 **未测量**，不能推定为零。首阶段唯一计时的 Maven compile 为 10.292 秒。

### 可复现的轻量命令

在上述工作树根目录的 PowerShell 7 中，先准备本地发布 JAR 的 classpath。以下 `local-tmp` 下的 Java 探针源码、class 和测试数据为本 lane 的原始证据，不属于产品发行物；重跑应使用全新的独立 data 子目录，保留本次首次失败日志。

```powershell
$repo = (Resolve-Path .).Path
$spike = Join-Path $repo 'local-tmp/slot-2-w15-v2-skill/spike'
$deps = (Get-Content (Join-Path $spike 'classpath.txt') -Raw).Trim()
$moduleClasses = @(Get-ChildItem -Directory -Filter 'super-harness-*' | ForEach-Object { Join-Path $_.FullName 'target/classes' })
$jna = 'D:/DevCache/maven/repository/net/java/dev/jna/jna/5.13.0/jna-5.13.0.jar'
$jnaPlatform = 'D:/DevCache/maven/repository/net/java/dev/jna/jna-platform/5.13.0/jna-platform-5.13.0.jar'
$sdkCp = @((Join-Path $spike 'classes'), $deps) -join ';'
$shCp = (@((Join-Path $spike 'sh-classes')) + $moduleClasses + @($deps, $jna, $jnaPlatform)) -join ';'
$frozen = Join-Path $repo 'third-party/deerflow/skills/e545c28ac301e6f9feae247c513d8ea8f83df294/5bc99df7e5f8af5471eaa5d20b85fc46e99895cf3f2642fa9da94108d03ca13e/public'
$fixture = Join-Path $repo 'contracts/customer-demo-v2/fixtures/skills'
java -cp $sdkCp SkillRunSpike $frozen $fixture (Join-Path $spike 'repro-sdk-data')
java -cp $sdkCp SkillRunSpike (Join-Path $spike 'versions/v1') (Join-Path $spike 'versions/v2') (Join-Path $spike 'repro-versioned-data') versioned
java -cp $sdkCp SkillRunSpike (Join-Path $spike 'versions/v1') (Join-Path $spike 'versions/v2') (Join-Path $spike 'repro-disabled-data') versioned-disabled
java -cp $sdkCp SkillRunSpike $frozen $fixture (Join-Path $spike 'repro-visibility-data') visibility
java -cp $shCp io.haifa.agent.superharness.server.SkillBridgeProbe $repo (Join-Path $spike 'repro-bridge-data')
java -cp $shCp io.haifa.agent.superharness.server.SkillHttpProbe $repo (Join-Path $spike 'repro-http-data') pending
java -cp $shCp io.haifa.agent.superharness.server.SkillStoreNegativeProbe $repo (Join-Path $spike 'repro-negative-repository')
java -cp $shCp io.haifa.agent.superharness.server.SkillStoreNegativeProbe $repo (Join-Path $spike 'repro-dual-repository') dual-http
```

上述各关键探针的最终通过轮 exit code 均为 0。首次 `sh-http-pending-probe.raw.log` 与 `sh-http-recovery-probe.raw.log` exit code 为 1；首次 `sh-bridge-probe.raw.log` 进程 exit 0，但其禁用 Run 仍有 `loadTool=true`，按行为判 FAIL，未因进程码为零放过。`git diff --cached --check` exit 0，静态 secret pattern 扫描 exit 0、无命中。`mvn -o -ntp -pl super-harness-server -am -DskipTests compile` exit 0。探针源码未设计为可反复覆写同一 data-root，因此恢复运行前须为 `repro-*` 选择新的空目录。

`dual-fixture` 的 Agent 明确配置 `skills=[v2-format-control]`。其激活前模型输入是 `consultIndex=false,fixtureIndex=true,consultBody=false,fixtureBody=false`（日志第 18 行）；激活后为 `consultBody=false,fixtureBody=true`。激活后的 `consultIndex=true` 仅因为测试 fixture 的正文第 26 行包含字符串 `consulting-analysis`；它不表示 consulting-analysis 目录入选。报告将“目录选择”和“激活后正文”分开判断。

## 保留的首次失败

- `evidence/sh-bridge-probe.raw.log`：最初禁用 Run 仍暴露 `skill_load` Tool。修复为仅有当前 Skill 或恢复旧 Run 时安装 Skill Tool；`sh-bridge-probe-4.raw.log` 复核。
- `evidence/sh-http-recovery-probe.raw.log`：轻量 harness classpath 漏 JNA，`NoClassDefFoundError`，补本地 JNA classpath 后 `sh-http-recovery-probe-3.raw.log` 通过；非产品故障。
- `evidence/sh-http-pending-probe.raw.log`：首次 HTTP 500 `SQLITE_TRANSACTION_FAILED`。`evidence/sh-http-pending-direct.raw.log` 的最内层异常为 `LocalDirectorySkillSource.load` 报“frozen Skill was not discovered from this source”。retained loader 在当前 catalog 未选旧源时必须先调用 SDK `discover`；增加每个受信 source 的 eager discover 后 `sh-http-pending-probe-2.raw.log` 通过。未放宽断言。

## 资源恢复后的真实门禁（2026-09-29）

主控停止另一个项目的精确服务进程链后，本 lane 预检空闲物理内存 5.24 GiB、无其他 Maven、槽位 30618/35618 非 NAT 排除且 loopback bind PASS。所有下表原始日志均直接写在 `local-tmp/slot-2-w15-v2-skill/evidence/`；首次失败及其 Surefire XML/TXT 保留，未改记为 NOT_RUN。

| 门禁/命令 | 结果 | 原始证据、次数与耗时 |
| --- | --- | --- |
| `mvn -o -ntp -pl super-harness-server -am '-Dtest=V2SkillConfigStoreTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx768m -XX:+UseSerialGC' test` | PASS，exit 0，2/2、0 skipped | `evidence/junit-targeted-1.raw.log`，15.063 秒；clean 前把 `super-harness-server/target/surefire-reports/` 复制到 `evidence/junit-targeted-1-surefire-reports/`。 |
| `mvn -o -ntp '-DargLine=-Xmx768m -XX:+UseSerialGC' clean verify` 首次 | **FAIL**，exit 1，196 tests / 12 failures / 9 skipped | `evidence/full-clean-verify-1.raw.log`，5:45；`evidence/full-clean-verify-1-surefire-reports/` 是 63 个原始 Surefire 文件的外置副本。11 个 W04 fixture 失败源于 checkout CRLF，另 1 个 W15 ArchUnit 失败源于公开 SDK 方法返回内部类型。原始及副本路径均保留。 |
| `mvn -o -ntp -pl super-harness-server -am '-Dtest=DeerFlowUploadRunContextHttpTest' ... test` 修复 checkout 后首次 | **FAIL**，exit 1，11 个 license ERROR | `evidence/upload-checkout-repair-1.raw.log`。W15 manifest 的 license SHA 误取 CRLF checkout 字节 `3e8f6a...`，Git 固定 LF 字节实际 `b23dff...`；这是独立的首次失败。 |
| 同一定向命令，修正受信 license hash 与 `.gitattributes` 后第二次 | PASS，exit 0，11/11、0 skipped | `evidence/upload-checkout-repair-2.raw.log`，测试 77.61 秒、Maven 1:21；含真实上传 SHA `ec326b7d...`、Run、ToolCall、回答。 |
| `tests/contracts/verify-deerflow-contracts.ps1 -DeerFlowRoot D:/workspace/haifa-agent-super-harness/deer-flow` | PASS，exit 0 | `evidence/deerflow-contract-gate-1.raw.log`；冻结源 HEAD `e545c28ac301e6f9feae247c513d8ea8f83df294`。 |
| `tests/contracts/verify-resource-license-inventory.ps1` | PASS，exit 0，24 checks | `evidence/resource-license-gate-1.raw.log`。 |
| `tests/evidence/verify-evidence-report.ps1` 首次 | **FAIL**，exit 1 | `evidence/evidence-report-gate-1.raw.log`；本 worktree 被忽略的 `private-docs/` 缺失，测试无法把 SHPM prompt 路径复制到临时 fixture。 |
| 同一证据命令，仅复制 SHPM 只读快照登记的 24 个 prompt/roadmap 文件到忽略的 `private-docs/` 后 | PASS，exit 0，101 assertions | `evidence/evidence-report-gate-2.raw.log`；只读快照 `evidence/shpm-readonly-snapshot.json`，未修改 SHPM。 |
| `corepack pnpm install --offline --frozen-lockfile`（`frontend/`） | PASS，exit 0 | `evidence/frontend-offline-install-1.raw.log`；`pnpm 10.26.2`，1088 包，`downloaded 0`，41.9 秒；`next.cmd`/`playwright.cmd` 已存在。 |

CRLF 修复过程：先确认整个 worktree clean，`git archive HEAD` 中 `contracts/customer-demo-v2/fixtures/customers.csv` 的 SHA256 为 `EC326B7D...`、工作树原字节为 `6E8E37B5...`；用 `git archive --format=tar -o local-tmp/slot-2-w15-v2-skill/evidence/checkout-byte-repair.tar HEAD frontend third-party contracts` 仅重落这些已跟踪字节，随后 CSV SHA 与 Oracle 一致、`SKILL.md` SHA 保持 `5BC99D...`。`git add -u frontend third-party contracts` 只刷新相同 blob 的索引状态，`git diff --cached --stat` 为空。W15 license hash 与 checkout 属性的产品修正单独保留在当前分支。

ArchUnit 的具体失败见 `evidence/full-clean-verify-1-surefire-reports/io.haifa.agent.superharness.server.ServerSdkBoundaryArchitectureTest.txt`：`SkillToolContributions.create` 的公开签名返回内部 `SkillToolCatalogContribution`，SH 拆取该类型的四个字段触犯 SDK 边界。最小上游 API 修复设计和 `javap` 证据在 `local-tmp/slot-2-w15-v2-skill/skill-tool-public-registration-gap.md`，待主控复审；不放宽架构测试，也不在 SH 复制 Skill Tool。

冻结 UI runner 已新增 `-V2Skill` profile 和 `tests/w18-ui-smoke/w15-v2-skill.spec.cjs`，静态 `node --check` 与 PowerShell parser PASS；**Next build、冻结 UI 同脚本两轮、30618/35618 真实服务 smoke 仍 NOT_RUN**，等待 SDK 公共 API 修复与最终 SHA 后执行。Playwright 次数 0、耗时 0、trace/screenshot 尚不存在；不能把 Java/HTTP 探针或本轮 W04 Run 提升为 W15 UI PASS。V2-M2 差分 runner 与 W16 成果也未由本 lane 执行。脚本当前没有启动常驻槽位进程，30618/35618 检查均无监听。受信 manifest 后续新增 Skill/版本必须保留完整旧字节，不做原位覆盖或自动 GC。

## 提交与验收边界

本报告按提示词放在 `docs/ai-coding-report/39-deerflow-parity-delivery/show-roadmap-2/W15/W15-V2ThinSkill.md`；主控之后同步到私有报告库并登记 SHPM 证据。最终代码 SHA 与 clean 状态待上游 API 修复、全仓及 UI 门禁结束后补记。下一步先解决 ArchUnit SDK 公开 API 缺口，重跑全仓门禁并做冻结 UI 双轮；之后 W18 才能在合入后的同一主线 SHA 上跑差分。W16 不以本轻量证据提前启动验收。
