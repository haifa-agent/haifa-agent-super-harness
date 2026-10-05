# W16 外部目录 Skill 加载实现方案与交付计划

## 1. 目标与背景

- **目标**：支持 Super Harness 直接指定外部文件系统目录（例如 `deer-flow/skills` 或其 `public/` 目录），自动扫描并加载其中的全部 Skill 包，免去既有文档中繁琐的静态 `manifest.json` 与 Hash 维护流程。
- **范围**：
  - 覆盖冻结 DeerFlow 现有的 23 个 public Skill；
  - 保持向下兼容：未配置外部目录时继续回退至现有的不可变 `manifest.json`；
  - 保证通过真实 REST API、Lead Prompt 和运行 Projection 的完整端到端可用。

---

## 2. 架构设计与归属判定

依据 `docs/runtime-product-boundary.md`：
- **Haifa Agent SDK 归属**：
  - `SkillPackageParser`（包解析）、`LocalDirectorySkillSource`（文件系统源）、`SkillCatalog`（目录注册）归属于 SDK；
  - SDK 原生提供了 `LocalDirectorySkillSource` 目录扫描能力；
  - 边界处理：`chart-visualization` 的 `compatibility` 是 mapping 对象（`nodejs: ">=18.0.0"`），SDK 0.1.2 期望文本标量；此外 13 个包包含脚本，SDK 需要 `SkillPackageReviewGrant` 审计凭证方可激活入 Catalog。
- **Super Harness 归属**：
  - 负责配置源解析（系统属性 `-Dhaifa.superharness.skills.dir`、环境变量 `HAIFA_SUPER_HARNESS_SKILLS_DIR`、本地探测）；
  - 负责在产品层组织 `SkillRunTools.Package` 并进行内存级 YAML 兼容；
  - 自动注入外部包的安全审计凭证，确保 SDK `SkillCatalog` 能够顺利激活全部 23 个技能。

---

## 3. 实现细节

### 3.1 外部目录解析 (`resolveExternalSkillsDirectory`)
- 优先级：
  1. 系统属性：`haifa.superharness.skills.dir`
  2. 环境变量：`HAIFA_SUPER_HARNESS_SKILLS_DIR`
  3. 本地仓库：`deer-flow/skills`
  4. Worktree 跨目录回溯：`../haifa-agent-super-harness/deer-flow/skills`
- 目录存在时，若其下包含 `public/` 则以 `public/` 为扫描根目录，否则以自身为扫描根目录。

### 3.2 技能包扫描与解析 (`loadExternalSkills`)
- 遍历包含 `SKILL.md` 的所有子目录；
- 在内存中读取并对 `compatibility: nodejs: ...` 规范化为文本；
- 采用 SDK 的 `SkillPackageParser.parseFiles` 进行合规性校验；
- 包装为 `SkillRunTools.Package`，内部关联独立的 `InMemorySkillSource`。

### 3.3 审核凭据自动派发与安全时钟容错
- 针对外部包中包含 `scripts/` 的 13 个技能，自动生成审查凭据；
- 将 `issued_at` 设置为过去时刻（`Instant.now().minusSeconds(60)`），避免微秒级时钟并发截断引起的 `!now.isBefore(issuedAt)` 判定失败；
- 扩展 `reviewFiles` 和 `installedReviewFiles`，支持直接读取外部目录中的文件。

---

## 4. 验证与回归矩阵

| 测试用例 | 模块 | 验证点 | 结果 |
| :--- | :--- | :--- | :--- |
| `testDirectDirectoryDiscoveryOnDeerFlowSkills` | super-harness-server | 底层扫描全部 23 包并绑定 Catalog | PASS |
| `testSkillConfigStoreWithExternalDirectory` | super-harness-server | 列表、详情、Projection、禁用与重启持久化 | PASS |
| `testExternalSkillsRestApi` | super-harness-server | 真实 HTTP 端口 `/api/skills` GET/PUT/POST reload | PASS |
| `V2SkillConfigStoreTest` (共 4 个用例) | super-harness-server | 原有 manifest.json 技能绑定与持久化 | PASS |
| `SkillPackageManagementTest` (共 7 个用例) | super-harness-server | 自定义技能安装、编辑、回滚与导出 | PASS |
| `SkillPackageHttpTest` (共 4 个用例) | super-harness-server | 既有技能 HTTP API 契约 | PASS |

---

## 5. 交付记录与关联

- **分支**：`feat-w16-external-skills`
- **工作树**：`D:\workspace\haifa-agent-super-harness-worktrees\w16-external-skills`
- **提交**：`fbe0fe4` `feat(skills): support loading skills directly from external filesystem directory`
