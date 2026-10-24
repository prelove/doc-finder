# Devin当前任务：名称搜索最小可用实现

用户最新指示以完成检索功能为目标、尽早实装，不再增加文档评审轮次。当前既定B0.3+Codex R1零迁移范围已放行；不等Claude再跑一轮才开发。

1. 暂停C1新增实验；已有场景按finally安全结束、保留结果。确认自己的QA进程退出后再构建，不kill任意Java进程。
2. 从当前已提交基线新建 `codex/name-search` 分支。共享目录中Codex待提交的B R1/授权修订由Codex先提交；保留未跟踪C1材料，功能提交不要夹带它们。先看git status，不覆盖他人修改。
3. 先实现可用核心：默认FUZZY字面名称子串（含单字符/Unicode/符号）、多词AND、NAME/ALL一致、名称优先排序；使用既有name_raw，无索引字段或迁移变化。保留EXACT/高级语法、CONTENT、目录整树过滤和错误契约。
4. 再完成250ms防抖、编辑即失效、历史不覆盖新草稿、可靠hasMore截断提示及桌面/Web一致。按已定方案共享reader，Match解释用原始关键词子查询；不需要再写新一版设计。
5. 用小夹具先钉住关键失败回归并修成绿，尽快产出可运行JAR；再用10万夹具测p50/p95及内存，100ms作为优化目标，报告真实影响，不自动改n-gram。100万观察可后置，不阻塞第一版试用。
6. 跑必要回归和完整构建、真实Java8验证，更新实际使用说明/内置帮助；交提交SHA、JAR路径/hash、测试结果和剩余问题。核心可用时即报一次进度，继续把本包交完，不逐条等批准。

不新增依赖，不改业务索引，不实现分页/精确总数、不启动C1修复或D大版本。出现必须新增字段/迁移/改变既定语义的阻断，给具体案例和最小选择再反馈。

范围参考：`openspec/changes/improve-name-search-experience/` 的当前任务与D10；Codex只要求保留简短任务进度及验收结果，不再扩充流程文档。

## 当前接续：修正独立复验发现的两处回归

基线 `81978a2`，功能已实现；不再重写方案。Devin 可立即修下面两项，补对应回归测试后提交。

1. **EXACT 路由回归**：同一真实 indexer 夹具含 `foo bar draft.txt`、`foo AND bar note.txt`、`report draft.txt`。旧版 EXACT 查询 `foo AND bar` 仅返回第二个文件；新版还返回第一个。旧版 EXACT 查询 `report (` 返回第三个文件，新版返回 INVALID_QUERY。NAME/ALL 均复现。新增布尔 token 与左括号识别不能改变冻结路径；按 D10/R1 让新分类仅作用于本次 FUZZY NAME/FOLDER/ALL，保留旧 EXACT/CONTENT 路由。请加入上述两种输入的旧行为断言。
2. **Enter 重复提交**：实际 `MainWindow.getSearchField().setText("report")` 后，在 EDT 调用 `postActionEvent()`，替换 executor 的计数探针记录 **2 次** submit；相同文本再次 Enter 为 1 次。编辑型 JComboBox 的双事件需去重，不能靠取消第一个 worker 掩盖。补实际输入框 Enter、历史选择、范围切换、Timer、程序化写入的确定性测试；每次动作请求数必须符合约定。

复现源码/日志位于本机 `C:\Users\Administrator\AppData\Local\Temp\docfinder-b-qa-20261024-3zl64vxw\contracts\`（FrozenSearchProbe.java、DesktopDebounceProbe.java 及 old/new/desktop-results.log）。探针独立于产品代码，使用隔离索引与配置。Devin 负责产品及回归测试；Codex 继续 HTTP/性能与最终复验，避免同时修改同一文件。

另请修复本次被错误转码的 `tasks.md` 中文，以及检查修改过的使用说明/内置帮助和输入框示例是否可读。恢复 UTF-8 原文后保留真实任务勾选；不要趁机扩充文档。历史 RED 过程不补录伪证据，不作为交付阻塞。C1 继续暂停。

修复中追加：Enter 去重请按组合框的编辑提交/历史选择事件区分，避免以“同文本100ms内忽略”替代事件处理；那会吞掉两次合法的快速 Enter。测试须包含两次独立 Enter（短于100ms）各一次提交。README.md:106/310 与 README_CN.md:39 仍称 B 未实施/未授权，请与 UsageGuide 及内置帮助一起更新为实际行为。Codex 已完成 Java8 真实 HTTP 22 场景，以及 Web 页面排序/整树限定/50条截断提示验证；无需重复写新方案。
