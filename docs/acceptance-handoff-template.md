# 验收短交接模板

复制到当前执行worktree的`local-tmp/acceptance-handoff.md`，事实变化时原位更新。只写核实过的摘要与原件路径，不放密钥、受保护正文或长日志；任务生命周期仍由SHPM维护。

```markdown
# 当前执行交接

- 更新时刻：<带时区>
- Task/范围：<SHPM ID；本次交付与延期范围>
- 验收档位：<L1 日常 / 附带 L2 WP 差分；L3 仅演示>
- Worktree/branch：<绝对路径；分支>
- Source：<HEAD SHA；sourceTree 哈希；javaInputFingerprint；dirty路径；最后验证的源码状态>
- SDK：<selection ABSENT/SELECTED；坐标；sdkIdentity；上游发布清单路径/commit；JAR hash>
- 槽位/数据：<完整端口组；本轮独立data-root>
- 正在等待：<精确session/PID；deadline；日志路径；无则写无>
- 偶发重跑：<是否命中 known-intermittent；issue；实际重跑次数；首次失败归档路径；无则写无>

| Case/断言 | 结果 | 源码/SDK绑定 | 原始证据 |
|---|---|---|---|
| <ID与实际断言> | <PASS/FAIL/NOT_RUN> | <SHA/hash；dirty候选须明示> | <绝对路径> |

- 首个FAIL：<签名；原件；依赖影响；无则写无>
- 未运行/条件不足：<具体case与前置；不升级PASS>
- 自审待修复：<按根因合并，不扩范围>
- 下一步：<最短执行步骤；需重建/复验的原因>
- Review：<`pr_review_digest.py` 结论 CLEAN/FINDINGS/NO_REVIEW_YET/REVIEW_FAILED；findings 条数；原始 JSON 路径；未运行则写原因>
- 清理：<实际进程/端口释放结果与证据；未执行则NOT_RUN>
```
