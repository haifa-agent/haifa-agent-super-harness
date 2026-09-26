# W18 冻结 UI 双后端差分：2026-09-26

## 范围与拓扑

本切片只建立同源 UI 的 L2 差分 runner，不修复业务协议，也不代表 W18 整包验收。冻结 DeerFlow 源码为 `e545c28ac301e6f9feae247c513d8ea8f83df294`，Super Harness 开发基线为 `045e8e01010a8f7b2a16a122a96f533ea097f740`。两组合按 `reference`、`haifa` **顺序**运行，共用本 worktree 构建的冻结 UI（Next build ID `9aBFzl5D6YSPX1kUU5ptt`），各有独立 data-root、Run、Cookie 上下文与日志。

| 项目 | 值 |
| --- | --- |
| Gateway | `127.0.0.1:48018`，两组合互斥使用 |
| Frontend | `127.0.0.1:63018`，浏览器 API 只见同源地址 |
| Controlled provider | `127.0.0.1:58018`，主控额外批准的本次内部端口，不属于固定槽位；仅 L2，不是付费/真实模型 |
| data-root | `local-tmp/slot-3-differential/{reference,haifa}`，`.gitignore` 排除 |
| SSR/BFF 目标 | 同一个 `.next/routes-manifest.json`：`/api/langgraph/:path* → http://127.0.0.1:48018/api/:path*`，`fallback=[]`；route manifest SHA-256 `81784ceadda95ecb189f5fea10a55c78d0cbcf8f0e7dd2316c0ce673681ad6ec` |
| 来源门禁 | `verify-deerflow-frontend.ps1` PASS：981 文件、21,172,960 字节、inventory SHA-256 `1fc6b2e42ac36b4669f6b9a374241b09428e2c0406796392fc3adb4519a5f8a3` |

运行：在本 W18 worktree 中执行 `pwsh -NoProfile -File scripts/run-w18-dual-backend.ps1 -Backend reference -SkipBuild`，随后将 `reference` 换为 `haifa`；去掉 `-SkipBuild` 时脚本先构建同一 UI。仅限主控所分配的槽位 3，并须先具备 worktree 本地 pnpm 10.26.2 依赖、Chromium、冻结 Python venv 和本 worktree Haifa exec JAR。Runner 检查 worktree/来源 SHA/端口，启动各进程、有界等待、运行真实 Playwright、写拓扑/结果/trace/log/哈希、最终清理进程树及端口。

## 逐案例外部行为

| 同一冻结 UI 案例 | DeerFlow reference | Haifa 当前开发基线 | 证据/差异 |
| --- | --- | --- | --- |
| 无 Cookie 的 setup/auth/me、模型/能力目录及页面 | PASS ×3 | PASS ×3 | 匿名本地默认主体、模型 `w18-controlled`、页面可见；浏览器 API 目标仅 `63018` 同源，无其它槽位/参考回落 |
| Thread 创建、真实 SSE 回答、Run 终态、完成后刷新 | PASS ×3 | PASS ×3 | `Content-Location` 给出 Thread/Run，SSE 有 `messages` 和 `end`，Run `success` 且刷新后回答可见 |
| 冻结 UI 停止按钮取消活跃 Run | PASS ×3 | PASS ×3 | UI 发 `POST .../cancel?wait=0&action=interrupt`，HTTP 202，持久 Run `interrupted` |
| 活跃 Run 中刷新并 GET join | PASS ×3 | **FAIL ×3** | 两侧均刷新前 `running`、join HTTP 200、最终 `success`；reference 的 200-dot 助手回答仅显示 **1** 次，Haifa 显示 **2** 次；唯一性断言未放宽 |

最新完整运行的原始证据目录：

- reference 首次：`local-tmp/slot-3-differential/reference/evidence-20260926-200346`，4/4 PASS，Playwright 39.3 秒、全程 56 秒；再次：`.../reference/evidence-20260926-200754`，4/4 PASS，Playwright 38.0 秒、全程 57 秒；测量版第三次：`.../reference/evidence-20260926-201520`，4/4 PASS，Playwright 39.5 秒、测试阶段 41.4 秒、全程 60 秒。
- Haifa 首次：`.../haifa/evidence-20260926-200137`，3/4（活跃刷新 FAIL），全程 78 秒；再次：`.../haifa/evidence-20260926-200616`，3/4（同一 FAIL），Playwright 38.9 秒、全程 78 秒；测量版第三次：`.../haifa/evidence-20260926-201631`，3/4（同一 FAIL），Playwright 40.7 秒、测试阶段 42.1 秒、全程 85 秒。
- 第二次 Haifa `active-reconnect.json`：Thread `5836099b-095a-457b-a31d-6be5c467a556`、Run `01a0dd9c-a78a-7b4d-a207-5357adc94f4d`、join `Last-Event-ID=-1`、`events=[messages,end]`、`renderedOccurrences=2`；reference 对应值为 `1`。最新第三次仍为 **2 对 1**。Haifa 失败 trace、screenshot、error-context 和两组合 `results.json`、`topology.json`、`summary.json`、六路日志均在各自 evidence 目录。`summary.json` 含逐日志 SHA-256 和 `remainingListeners=[]`。

这是**当前 main 起点**的缺口。W05 长会话身份/Interaction 收尾尚未合入该分支；归属 W05，请在 W05 最终 SHA 合入后复跑同一脚本。W18 不应为此另写协议翻译或降低断言。

## 流程健康度与门禁

- 本 worktree 使用 Corepack pnpm `10.26.2`，离线 frozen lockfile 安装 `downloaded 0`；Next 16.3.3 生产构建 PASS，后续重复运行均使用同一预构建 UI，**没有动态编译**。每轮后端/前端/Provider 是重新冷启动，后续轮次是依赖和构建缓存热运行；测量版从启动到 provider/gateway/frontend readiness：reference `0.7/10.4/13.4` 秒，Haifa `0.7/35.6/38.7` 秒，详见最新 `summary.json`。Playwright retries 固定 `0`，重复运行是显式再次执行同脚本。
- 首个参考侧卡点来自测试 fixture：冻结后端把 user input 包入框架且另外调用非 stream completion，受控 provider 最初未处理；后续取消用 5 秒 `__slow__` 与读取取消响应 body，造成测试竞争/Chromium 无 body。修复仅在 test provider/断言路径，保留首轮失败目录（例如 `reference/evidence-20260926-194644`、`...195422`），未改产品代码或跳过失败测试。
- 首个 Haifa Maven `package` 在 Windows `%TEMP%` 的 `PersistentProjectWorkspaceServiceTest.reportsOversizedChangedFileWithoutAttemptingAContentDiff` 抛 `AccessDeniedException`（`.completed-UUID` → `completed`），完整错误保留在当时终端输出；随后原断言定向 1/1 PASS、同一完整 package 23 个 Workspace + 82 个 Server 测试 PASS，未改断言。可能与临时目录索引/并发访问相关，尚无根因定论。
- 冻结来源门禁 PASS；DeerFlow contract verifier 12 条 PASS；W24 evidence verifier 41 条 PASS；`mvn.cmd -o -ntp -pl super-harness-server -am package` 复跑 PASS；全仓 `mvn.cmd -o -ntp verify` PASS（2 分 20 秒、Workspace 23/23、Server 82/82 且 2 skipped、Evidence Harness 5/5）。

## 明确未运行

本切片没有做 L3 真实/付费模型调用（未经授权）；没有覆盖上传→生产文件工具→下载 hash、Interaction、WebSocket 全量、DeerFlow 全量协议差分。它们仍是 `NOT_RUN`，不能用 L2 controlled provider 的成功代替。Haifa active reload 是真实 `FAIL`，不是 `NOT_RUN`。
