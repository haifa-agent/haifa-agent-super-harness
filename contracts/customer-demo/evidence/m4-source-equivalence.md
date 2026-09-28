# M3 到 M4 的代码基线核对

2026-09-28，主控对 `6cfd9a49821fe0040d1e5bf944ba67de0c0ea2f7`（M3 最终差分实跑提交）和 `c112012e147cc11daec891af3d602aeefcdff1de`（M4 双轮实跑主线）执行 `git diff --name-only`。差异仅为：

- `docs/todo.md`
- `tests/dashboard/demo-dashboard.spec.cjs`
- `tests/evidence/verify-evidence-report.ps1`
- `work-packages.js`

Java 主代码、Java 测试、冻结前端、W18 双后端 runner 和客户演示 Oracle 均无文件差异。M3 原始 reference/Haifa 各两轮的 `results.json` 仍按各自原提交和原结果保留，不冒充在 `c112012` 重新运行了 reference；此处是代码等价复核。M4 在 `c112012` 上重新执行六模块 clean verify、live doctor 和真实模型纵向两轮，覆盖 M1/M2/M3 的 Haifa 正向主链。M3 的 reference distinct Run `UPSTREAM_UNSUPPORTED`、W04 两项 `FAIL` 与 `ask_clarification` `NOT_RUN` 保持原分类。
