# A阶段与依赖过渡版交付凭证

日期：2026-10-24（Asia/Tokyo）。

- 用户明确确认最新版手动验收完全成功，并要求继续提交和推进。原文：[用户接受](../../openspec/changes/archive/2026-10-24-add-directory-scoped-search/evidence/User-Acceptance-2026-10-24.md)。手测为用户整体报告，未补造逐步日志/JAR hash。
- 已验证源代码/帮助文案交付提交：`c90aa58bf6905468f284e50382ccfd802c44834c`，已推送origin/main。当前归档提交只改文档，最终归档SHA由本文件所在Git提交和Codex交付回复定位。
- A任务56/56；依赖过渡任务6/6；同步主规格后均归档。
- [A归档](../../openspec/changes/archive/2026-10-24-add-directory-scoped-search/tasks.md)；[依赖归档](../../openspec/changes/archive/2026-10-24-modernize-java-dependencies/tasks.md)。
- 主规格：[directory-scoped-search](../../openspec/specs/directory-scoped-search/spec.md)12条；[build-modernization](../../openspec/specs/build-modernization/spec.md)4条。

## 验证与来源

本轮新跑clean verify：169总数、167通过、0失败/错误、2POSIX跳过；隔离探针执行，四个真实控制文件SHA未变。此前相同帮助源码已在真实Java8验证，16追加检查和watchdog5次通过。原始测试结果见 [closeout verification](../../openspec/changes/archive/2026-10-24-add-directory-scoped-search/evidence/closeout-20261024/verification.json)。

主规格先经语义同步及严格校验；archive CLI使用skip-specs仅避免再次同步，不表示规格同步被省略。历史review-baseline/各次QA SHA清单描述原版本快照，归档仅调整必要文档链接，不重写这些指纹来冒充原文件仍逐字节相同。

native/POSIX宿主/真实UNC/真实大网络未新增验证；Java8兼容依赖线的EOL风险保留。归档不迁移或清理业务数据，.playwright-mcp不入库。

## 下一阶段

B仍为未批准实施的方案：按 [稳定交接指令](../handoffs/2026-10-24-name-search-plan.md) 基于0.2修订0.3，关闭N02复核残留并交Claude及用户评审；C1只读诊断可并行。A接受不自动授权名称子串代码、n-gram迁移或C/D扩围。
