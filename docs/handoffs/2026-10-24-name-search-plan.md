# Devin当前任务：B最后收尾（2026-10-25）

Codex已独立核验 `932c62c`：JDK17 clean verify及真实Java8全套各198项、0失败、2个平台跳过；真实Java8 HTTP26场景通过；EXACT兼容与Enter重复提交回归均关闭。桌面/Web三态排序、按钮恢复引擎顺序、选中结果及预览保持、新结果清除箭头已通过。

仅执行以下收尾，不新增方案或扩大功能：

1. **P3 / BQA-03：空结果状态也清理排序。** 桌面排序后清空查询并提交，doSearch的空查询分支目前清了行，却仍保留sort keys和启用的复位按钮。Web renderResults在0条时提前return，导致clearCurrentResults/无结果/错误之后按钮仍启用。统一空结果时的sort keys、表头箭头及按钮禁用状态；保留正常三态、当前选中项/预览、原始引擎顺序，不重新查询。加小回归验证即可。
2. 内置 Help → Usage Guide 补一句三态列排序与 Restore default sort 的用法；外部README/README_CN/UsageGuide已由Codex同步，勿重复改写整篇。
3. 跑这两处修改相关的回归、打包、diff检查，提交功能与回归；回复实际SHA、JAR路径/hash及结果。当前全套和HTTP证据已具备，不重跑百万规模实验、不追加流程文档。更新tasks 9.3，用户接受8.1与最终归档8.2留待后续。

复现原始材料：`C:\Users\Administrator\AppData\Local\Temp\docfinder-final-b-qa-20261025-iw6q2f1o\`，contracts/desktop-results.log有 rows=0, keys=1, resetEnabled=true；web/empty-sort-state.png记录0结果但按钮仍启用。详见OpenSpec当前evidence/Codex-implementation-review.md。

下一步为Codex按新SHA复验这项小收尾，然后用户短试用：中段关键词、多词、一次Enter、列排序三态及按钮复位、新搜索恢复默认。用户接受后交付归档。C1继续暂停；native/POSIX/真实UNC与100万观察保持已声明边界。
