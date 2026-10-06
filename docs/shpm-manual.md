# SHPM 使用手册

SHPM（Super Harness Project Manager）管理本项目的工作包、任务、派工、checkpoint、验收证据与演示门禁。主控通过 `shpm` CLI 写入，子智能体提交 checkpoint、状态建议和证据；人工通过 <http://127.0.0.1:8787/> 只读查看。

提示词、roadmap、交付报告和原始证据继续保存在原目录，SHPM 登记其路径。不要直接编辑数据库、执行 SQL、修改 SHPM 实现来改变状态，或维护另一套进度台账。命令无法表达的操作交由人工裁决。

## 1. 准备环境

在 PowerShell 7 中明确选择本项目的已有数据库，并检查环境：

```powershell
$env:SHPM_DB='C:\Users\wangr\.shpm\data\project.db'
[Console]::OutputEncoding=[Text.Encoding]::UTF8
$env:PYTHONIOENCODING='utf-8'
if (-not (Test-Path -LiteralPath $env:SHPM_DB -PathType Leaf)) {
    throw 'SHPM 项目数据库不存在，请先确认数据库路径'
}
shpm doctor --json
shpm status --json
```

`doctor` 应返回 `HEALTHY`；否则停止状态写入并报告人工。命令也可用 `--db <绝对路径>` 显式选择数据库。不要通过初始化新库替代已有项目库。

状态查询和写入命令使用 `--json`：成功结果在 stdout，失败结果在 stderr。写入命令带 `--idempotency-key`，同一次操作重试复用原 key；新的操作或新的验收轮次使用新 key。PowerShell 参数里的中文引号使用「」，避免弯引号被当作字符串定界符。

| 退出码 | 含义 | 处理 |
| --- | --- | --- |
| `0` | 命令成功 | 核对返回对象与预期状态 |
| `2` | 参数校验失败或门禁未通过 | 修正输入或补齐门禁证据 |
| `3` | 对象不存在 | 核对对象 ID |
| `4` | 状态或资源冲突 | 检查当前状态与合法转换 |
| `5` | 环境漂移 | 核对 checkout、SHA、服务与槽位 |
| `10` | 内部错误 | 停止操作并报告人工 |

## 2. 查询任务与上下文

下文命令是模板：先替换占位值，再按任务当前状态逐步执行。任务 ID、派工 ID、阶段 ID 和 SHA 均以实际对象为准。

```powershell
$taskId='<任务ID>'
$wpId='<工作包ID>'
$sprintId='<演示轮次ID>'
$stageId="$sprintId/<阶段ID>"
$assignmentId="asg-$taskId"
$operationKey="$(Get-Date -Format 'yyyyMMdd-HHmmss')-$taskId"

shpm show $taskId --json
shpm wp show $wpId --json
shpm assign list --task $taskId --json
shpm verify list --task $taskId --json
shpm sprint show $sprintId --json
```

| 字段 | 用途 |
| --- | --- |
| `prompt_path` | 相对仓库根目录的派单提示词，开发和复审先读此文件 |
| `state`、`next_action`、`exit_criteria` | 当前状态、下一步和退出条件 |
| 工作包 `relations` | 工作包与任务的依赖关系，`requires` 为前置依赖 |
| 验证记录 `raw_path` | 原始证据位置 |
| Sprint `roadmap_path` | 演示范围与阶段验收依据 |

登记路径须存在并指向正确文件；路径缺失或内容不符时报告人工，不猜测替代文件。提示词登记后保持路径不变。

## 3. 登记任务与派工

先在 `private-docs/prompts/39-deerflow-parity-delivery/` 对应工作包目录编写提示词。提示词包含范围、依赖、实现边界、验收条件和证据要求；文件命名与保存位置按 [AGENTS.md](../AGENTS.md)「提示词与过程文档」。

```powershell
$promptPath='<提示词相对仓库根的路径>'
shpm task create $taskId --wp $wpId --title '<任务标题>' `
    --prompt $promptPath --exit '<退出条件>' --priority P1 --next '<下一步>' `
    --idempotency-key "$operationKey-create" --json

# 仅在任务服务于该演示阶段时建立关系
shpm sprint link $stageId --task $taskId --relation delivers --required `
    --idempotency-key "$operationKey-link" --json

shpm task start $taskId --idempotency-key "$operationKey-start" --json
```

派工前核对独立 branch/worktree、基线 SHA、槽位、全部端口和数据目录，并按 `AGENTS.md`「端口」完成实际绑定预检。SHPM 记录资源分配，实际端口隔离仍由主控检查。

```powershell
$repositoryRoot='D:\workspace\haifa-agent-super-harness'
$worktreeRoot='<分配的 worktree 绝对路径>'
$branchName='<开发分支>'
$baseSha='<基线提交SHA>'
$slot=1 # 替换为实际分配的槽位
$ports='<Gateway端口>/<Frontend端口>'
$dataRoot='<独立数据目录绝对路径>'

shpm assign create $taskId --id $assignmentId --executor opencode `
    --ref '<执行者引用>' --model deepseek/deepseek-flash `
    --repo $repositoryRoot --base-sha $baseSha --branch $branchName `
    --worktree $worktreeRoot --slot $slot --ports $ports --data-root $dataRoot `
    --checkpoint-in 15m --idempotency-key "$operationKey-assign" --json
```

`--executor` 可选 `main`、`codex`、`opencode`、`human`，`--ref` 与 `--model` 按实际执行者填写。`assign create` 缺少 `--data-root` 或端口时会拒绝登记。

上面四步（`task create`、`sprint link`、`task start`、`assign create`）用薄封装一次完成，不手工逐条拼：

```powershell
python scripts/governance/shpm_dispatch.py --task $taskId --wp $wpId --title '<任务标题>' `
    --prompt $promptPath --exit '<退出条件>' --executor opencode `
    --ref '<执行者引用>' --model deepseek/deepseek-flash `
    --branch $branchName --worktree $worktreeRoot --slot $slot --data-root $dataRoot `
    [--repo 'D:\workspace\haifa-agent'] [--stage $stageId] [--execute]
```

- 不带 `--execute` 只打印将执行的命令，核对后再加；带上后按顺序写入，首个失败即停。各步幂等键为 `<日期>-<任务ID>-<动作>`，派工 ID 为 `asg-<任务ID>`。
- `--slot` 取 1–4，端口按 `AGENTS.md`「派单与子智能体调度」端口表取值（槽位 4 为 `11618/16618`，不套用 `+10000`），不单独传端口。
- `--slot`、`--data-root` 必填，`--worktree` 须是已创建且有提交的 Git 检出。任一项不满足时脚本以退出码 2 结束，不打印也不执行任何步骤，避免任务已 `start` 却没有派工。
- 基线 SHA 取 `git -C <worktree> rev-parse HEAD`。
- `--repo` 默认是本仓库主检出。上游 SDK 任务的 worktree 属于 `D:\workspace\haifa-agent`，须传 `--repo` 指向该仓库；worktree 不属于 `--repo` 所指仓库时脚本拒绝。提示词仍按本仓库主检出下的相对路径检查。

派单消息注明任务 ID、派工 ID、提示词路径、SHA、端口、数据目录及 checkpoint 要求；OpenCode 调用方法见 `AGENTS.md`「OpenCode CLI」。

## 4. 记录进展、阻塞与恢复

每收到一次 checkpoint 或到达约定的 15 分钟间隔，主控追加进展记录；每次记录使用新 key。

```powershell
shpm assign checkpoint $assignmentId --completed '<已完成>' --current '<当前工作>' `
    --failure '<失败或无>' --next '<下一步>' --command '<实际命令>' --next-in 15m `
    --idempotency-key "$operationKey-checkpoint-1" --json

shpm task block $taskId --reason '<阻塞原因>' `
    --idempotency-key "$operationKey-block" --json

shpm issue add '<问题ID>' --kind blocker --severity P1 --title '<问题标题>' `
    --task $taskId --detail '<影响与所需处理>' --blocking `
    --idempotency-key "$operationKey-issue" --json
```

失联或执行异常按 `AGENTS.md` 的 checkpoint / 失联规则处理，先保存变更、日志与数据，再登记和恢复：

```powershell
shpm assign lost $assignmentId --reason '<失联原因>' `
    --idempotency-key "$operationKey-lost" --json
shpm assign recover $assignmentId --executor codex --preserve-worktree `
    --idempotency-key "$operationKey-recover" --json
```

恢复后重新核对工作树、进程、端口和任务下一步；不清理其他任务或供人工验收的服务。恢复命令不代替实际执行者交接。

## 5. 交付、验证与接受

交付只把任务送入 `ready_for_acceptance`，由主控独立复审和验收后接受。报告正文放在私有交付目录，原始证据放在外置证据目录。

```powershell
shpm task deliver $taskId --assignment $assignmentId --commit '<候选提交SHA>' `
    --handoff '<交付报告路径>' --idempotency-key "$operationKey-deliver" --json
```

每个门禁执行单独追加验证记录，绑定实际源码 SHA、命令、耗时、重试次数和原始证据。用 `--task`、`--stage` 或 `--wp` 指定一个验证对象；必需门禁加 `--required`。

```powershell
shpm verify add --task $taskId --key '<检查名称>' --criteria '<实际断言>' `
    --result PASS --required --command '<执行命令>' --repo-sha '<实际源码SHA>' `
    --raw '<原始证据路径>' --duration '<耗时，例如42s>' --retry-count 0 `
    --summary '<检查结果>' --idempotency-key "$operationKey-verify-round-1" --json
```

结果按实际情况填写 `PASS`、`FAIL`、`NOT_RUN`、`BLOCKED_EXTERNAL` 或 `UPSTREAM_UNSUPPORTED`。首次 FAIL 和未运行项均须保留；新轮次追加记录，不覆盖旧记录，不用静态检查或另一分支的结果替代真实运行证据。

脚本回归和批量门禁登记用两个薄封装，不手工逐条拼 `shpm verify add`：

```powershell
pwsh -NoProfile -File scripts/verify-scripts.ps1
python scripts/governance/shpm_record_gates.py --task $taskId <证据组目录> [--raw-root <已归档位置>] [--execute]
```

`verify-scripts.ps1` 统一运行 `tests/checks/build/` 与 `tests/checks/ui/` 清单内的检查并写 `local-tmp/verify-scripts/`；出现清单外的检查文件时直接失败，不提供跳过单项的开关。`shpm_record_gates.py` 从证据组目录递归发现全仓门禁摘要、脚本回归摘要、UI 证据目录和主服务刷新摘要，无法识别的 JSON 忽略并列出；不带 `--execute` 时只打印一张表（检查名、结果、提交、耗时、原件路径、幂等键），加时才逐条写入、失败即停。幂等键带证据身份（摘要文件名或 UI 证据目录的 `run-*` 身份），同一份证据重复登记仍幂等；UI 检查名带 `acceptanceProfile`，同一次调用里同 profile、同提交的多份证据目录按开始时间追加 `-r1`/`-r2`。`--raw-root` 用于证据已归档到别处时改写原件路径，目标不存在则报错。结果原样登记，脚本不自动提升阶段 `PASS`/`GO`。带 `reuse` 的全仓门禁摘要把复用来源（源摘要、提交、Java 输入指纹）写进说明并如实记录耗时；带 `knownIntermittent` 的摘要登记为 `PASS` 并填实际重跑次数，同时为每个 issue 追加一条非必需的 `known-intermittent-<issue>` `FAIL` 记录（原件指向首次失败归档目录），用于累计该 issue 的发生次数。

独立复审通过、改动合入且证据绑定最终主线 SHA 后接受，再确认本任务端口和子进程已清理并释放派工：

```powershell
shpm task accept $taskId --assignment $assignmentId --main-commit '<最终主线SHA>' `
    --idempotency-key "$operationKey-accept" --json
shpm assign release $assignmentId --idempotency-key "$operationKey-release" --json
```

已 `finished` 的任务需要返工时，用 `shpm task reopen $taskId --reason '<原因>' --idempotency-key "$operationKey-reopen" --json`。复审未通过的 `ready_for_acceptance` 任务保留必需验证 FAIL 与阻断 issue，并交由人工决定合法恢复方式；不要虚假 accept 后再 reopen。

## 6. 工作包、演示阶段与 GO

所有任务完成后仍须检查工作包退出条件、整包验证和人工验收要求，再登记工作包状态与完成：

```powershell
shpm wp state $wpId ready_for_acceptance --reason '<整包交付依据>' `
    --idempotency-key "$operationKey-wp-ready" --json
shpm wp complete $wpId --verification '<整包PASS验证记录ID>' `
    --idempotency-key "$operationKey-wp-complete" --json
```

演示阶段先核对前置阶段和必需的 `requires` / `delivers` 任务，再登记验证与阶段结果。前置未满足时不推进后续阶段，不将延期或局部通过记为阶段 PASS。

```powershell
shpm sprint stage result $stageId PASS --status finished --reason '<阶段验收依据>' `
    --idempotency-key "$operationKey-stage" --json
shpm sprint milestone $sprintId --title '<里程碑>' --detail '<依据>' `
    --idempotency-key "$operationKey-milestone" --json
shpm sprint gate $sprintId --json
shpm sprint decide $sprintId GO --reason '<完整门禁与人工裁决依据>' `
    --idempotency-key "$operationKey-go" --json
```

阶段 `--status` 使用 `pending`、`in_progress`、`ready_for_acceptance`、`blocked` 或 `finished` 中适用的值。`GO` 通过 `sprint decide` 设置；gate 未通过时停止，不绕过门禁。客户演示的阶段结果不自动关闭完整产品工作包或 Full Parity。

## 7. 审计、导出与只读查看

状态变化后运行证据聚合器的检查；证据门禁失败时保留失败并停止 PASS / GO 结算。

```powershell
pwsh -NoProfile -File tests/checks/evidence/verify-evidence-report.ps1
pwsh -NoProfile -File scripts/governance/audit-work-package-delivery.ps1
pwsh -NoProfile -File scripts/evidence/generate-evidence-report.ps1
shpm export snapshot --db $env:SHPM_DB --output '<快照输出路径>' --json
```

聚合器与审计入口读取 SHPM snapshot；数据库须存在。工作树中的真实源码、报告和原始证据仍是验收依据；`reconcile` 不替代路径存在性、端口实际绑定或 Oracle 检查。

人工查看已有网页 <http://127.0.0.1:8787/>。需要启动只读网页时使用 `shpm serve --db $env:SHPM_DB --host 127.0.0.1 --port 8787`，不要重复启动占用同一端口的实例。

不对本项目在线数据库执行 `shpm migrate *`，不通过 `shpm render` 维护平行进度文件。安装与初始化属于另行安排的环境准备，不在任务处理中自动执行。

其他命令和完整参数用 `shpm --help`、`shpm help agent` 或 `shpm <命令组> <命令> --help` 查询；执行前按本项目规则核对权限、状态和验收前置条件。
