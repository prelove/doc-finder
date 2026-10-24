# Devin Local：A收尾后修订B0.2方案

工作区：`E:\idea_projects\doc-finder`。用户已明确最新版手动验收完全成功；A与指定Java8依赖升级接受，Codex完成其提交、主规格同步和归档。不要重新打开已关闭的R24项。

## 当前任务仅为方案修订

读取 `openspec/changes/improve-name-search-experience/` 下 proposal/design/specs/tasks/review/acceptance/delivery-status。保持B产品实现授权为false、0/22实施任务未勾选；当前用户的“继续”覆盖A收尾与B方案推进，不代替B新方案实施批准。

1. 将草案修订为0.2，逐条响应review.md的B-01～09。先消除三个P1：ALL的parsed MUST阻断中段命中、名称parsed OR绕过多词AND、EXACT基线与源码现状不符。
2. 明确单词/多词分档、名称与正文-only排序、Top-N前的优先级保障；不要靠任意boost声称严格三档成立。
3. 本期建议只做可靠hasMore/truncated，不做分页/精确总数；处理limit、limit+1、计数下界和兼容实现未知信息。
4. 定义单字/Unicode/符号/高级语法分类，超长/超词数行为；输入即失效旧代次，timer只延迟提交，立即提交/清空/索引/关闭取消timer。
5. 优先零迁移name_raw通配方案；固定10万高基数/高命中短词/各scope与目录组合的服务入口性能实验，100万作观察。100ms是拟定门槛，不能当所有硬件SLA。基准失败只触发n-gram备选评审，不自动批准字段迁移。
6. A已进入主规格：读取 `openspec/specs/directory-scoped-search/spec.md` 和 `openspec/specs/build-modernization/spec.md`，不再声称主规格库为空。0.2需明确对DS-03名称匹配/评分兼容条款的有意变更，必要时提供完整MODIFIED delta；不修改已接受的主规格来提前符合未实施B。
7. 同步proposal/design/spec/tasks/acceptance/delivery-status，形成问题响应表、可直接执行的验收例子、评审包指纹与给Claude的背景指令。未经批准不改B产品代码、不删除旧QueryBuilder、不升级依赖。

## 回交

提供0.2实际文件路径、相对0.1的变更摘要、B-01～09闭环表、仍需用户决定的真实取舍和指纹。然后由Claude复核、用户批准最终设计与实施范围，再进入B0基准/原型和TDD实施。

保持共享工作区已有修订，先看git status；不要带入.playwright-mcp或运行时数据。A归档证据和旧SHA是历史，不重写已审指纹。产品roadmap以 `docs/roadmap.md` 为长期入口。
