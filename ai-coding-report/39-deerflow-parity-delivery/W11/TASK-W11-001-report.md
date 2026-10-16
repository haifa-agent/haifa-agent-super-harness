# TASK-W11-001 开发与交付报告

## 1. 任务基本信息
- **任务编号**：TASK-W11-001 (SHPM 任务 W11-task-6 / 派工 asg-W11-task-6)
- **任务目标**：`view_image` 工具实现与 `read_file` 二进制防呆重定向
- **工作分支**：`feat-w11-view-image-tool`
- **槽位与端口**：Slot 4 (Gateway 11618, Frontend 16618)
- **对应 PRD / 冻结来源**：
  - PRD 功能项 F21、工具项 T07
  - `bytedance/deer-flow@e545c28ac301e6f9feae247c513d8ea8f83df294`
  - `deer-flow/backend/packages/harness/deerflow/tools/builtins/view_image_tool.py`
  - `deer-flow/backend/packages/harness/deerflow/sandbox/tools.py:2575-2580`

---

## 2. 变更内容清单

1. **`ProductionViewImageTool.java`**
   - 路径：`super-harness-server/src/main/java/io/haifa/agent/superharness/server/tool/file/ProductionViewImageTool.java`
   - 工具名：`view_image`，副作用声明 `ToolSideEffect.FILE_READ`。
   - 描述：加载自 `upstream/deerflow/tool-descriptions/view_image.txt`，1:1 对齐冻结 DeerFlow 工具描述。
   - 输入：`Input(String image_path)`，要求绝对虚拟路径。
   - 路径限制：仅允许 `/mnt/user-data/workspace`、`/mnt/user-data/uploads`、`/mnt/user-data/outputs`，越界及路径逃逸安全拦截并返回规范错误。
   - 扩展名与魔数严格核验：
     * 支持 `.jpg` / `.jpeg` (`\xff\xd8\xff` -> `image/jpeg`)
     * 支持 `.png` (`\x89PNG\r\n\x1a\n` -> `image/png`)
     * 支持 `.webp` (`RIFF..WEBP` -> `image/webp`)
     * 支持 `.gif` (`GIF87a`/`GIF89a` -> `image/gif`)
     * 准确拦截非图片及扩展名欺骗伪造（报 `Error: File contents do not match...` 或 `Error: Image contents are <detected>, but file extension indicates <expected>`）。
   - 契约大小上限：严格按 DeerFlow 冻结契约执行 20 MiB (`20 * 1024 * 1024` = 20971520 字节) 校验，超限精准报错。
   - 模型侧纯文本与元数据解耦：重写 `summarize(Output output)` 返回纯文本 `"Successfully read image"` 或 `"Error: ..."`，杜绝 Base64 塞入模型上下文；元数据（`image_path`, `mime_type`, `sha256`, `size`）记入结构化数据。

2. **`view_image.txt`**
   - 路径：`super-harness-server/src/main/resources/upstream/deerflow/tool-descriptions/view_image.txt`
   - 对齐 DeerFlow `_view_image` 的 docstring 描述文本。

3. **`ProductionReadFileTool.java` 二进制防呆重定向**
   - 路径：`super-harness-server/src/main/java/io/haifa/agent/superharness/server/tool/file/ProductionReadFileTool.java`
   - 当检测到非 UTF-8 二进制文件时，错误信息完全对齐 DeerFlow 规范：
     `cannot read '<path>' as text — it appears to be a binary file (e.g. .xlsx, .pdf, or an image). read_file only supports UTF-8 text. Use bash with a suitable library instead (pandas/openpyxl for spreadsheets), or view_image for images.`
   - 并在摘要中附带 `encoding=not valid UTF-8` 保障全链路回归与上下文投影一致性。

4. **工具装配与注册**
   - 路径：`super-harness-server/src/main/java/io/haifa/agent/superharness/server/api/DeerFlowBootstrapApi.java`
   - 在 `fileTools` 清单中完成 `ProductionViewImageTool` 的注册。

5. **单元测试与回归套件**
   - `ProductionViewImageToolTest.java`（新增）：11 个用例全覆盖，含合法 PNG/JPEG/WEBP/GIF、越界路径拦截、缺失文件、格式不支持、魔数欺骗拦截、10M/20M 大小边界、纯文本摘要检查以及安全主体/生命周期校验。
   - `ProductionReadFileToolTest.java`（新增）：6 个用例覆盖文本读取、二进制文件防呆重定向文案、范围检查与安全检验。
   - `ProductionFileToolsTest.java`：扩充参数契约 Schema 校验断言以及 Fixture 装配。

---

## 3. 验收断言对照（Oracle 验证）

| # | 验收项 | 测试断言位置 | 结果 |
| :--- | :--- | :--- | :--- |
| 1 | 正常读取合法 PNG 图片（带标准魔数） | `ProductionViewImageToolTest.readsValidPngImageSuccessfully` | PASS |
| 2 | 正常读取合法 JPEG、WEBP、GIF 图片 | `ProductionViewImageToolTest.readsValidJpegWebpAndGifSuccessfully` | PASS |
| 3 | 传入越界路径（如 `/etc/passwd` 或 `C:\Windows`） | `ProductionViewImageToolTest.rejectsDisallowedVirtualPathRoots` | PASS |
| 4 | 传入不存在的虚拟文件路径 | `ProductionViewImageToolTest.rejectsMissingFile` | PASS |
| 5 | 扩展名伪造（非图片改名或 JPEG 改名 PNG） | `ProductionViewImageToolTest.detectsExtensionSpoofingAndInvalidContent` | PASS |
| 6 | 边界大小断言（10 MiB, 10 MiB+1B, 20 MiB, 20 MiB+1B） | `ProductionViewImageToolTest.validatesSizeBoundariesAt10MAnd20M` | PASS |
| 7 | 使用 `ProductionReadFileTool` 读取合法 PNG 图片引导 | `ProductionViewImageToolTest.readBinaryFileWithReadFileToolGivesHelpfulRedirectionGuidance` 及 `ProductionReadFileToolTest.readingBinaryFileReturnsFriendlyRedirectionGuidance` | PASS |
| 8 | 验证模型侧 ToolMessage 内容为纯文本非序列化 JSON | `ProductionViewImageToolTest.toolMessageIsStrictlyPlainText` | PASS |

---

## 4. 验证命令与输出证据

1. **定向测试**：
   ```powershell
   pwsh -NoProfile -File scripts/test-targeted.ps1 -Test 'ProductionViewImageToolTest,ProductionReadFileToolTest,ProductionFileToolsTest,ProductionWriteFileToolTest' -LowMemory
   ```
   **结果**：
   ```json
   {
     "status": "PASS",
     "exitCode": 0,
     "tests": 34,
     "failures": 0,
     "errors": 0,
     "skipped": 0
   }
   ```

2. **关联回归测试**：
   ```powershell
   pwsh -NoProfile -File scripts/test-targeted.ps1 -Test 'DeerFlowUploadRunContextHttpTest' -LowMemory
   ```
   **结果**：
   ```json
   {
     "status": "PASS",
     "exitCode": 0,
     "tests": 12,
     "failures": 0,
     "errors": 0,
     "skipped": 0
   }
   ```

3. **代码与 Git 规范**：
   ```powershell
   git diff --check
   ```
   **结果**：0 warning/error（Clean）。
