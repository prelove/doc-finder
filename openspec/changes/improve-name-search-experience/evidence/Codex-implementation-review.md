# B 独立复验

**最新结论（2026-10-25，2c47ab2）**：BQA-01/02/03全部关闭，实施及收尾复验通过，等待当前JAR的用户短试用接受。下方按时间保留首次发现及后续修复，最新结果见文末。

复验提交：`81978a23f817dbfaaa517e942a8ac39a2663a03f`。结论：核心功能通过，交付前修正下面两处回归；不增加方案评审轮次。当前工作树中的修复尚不计为已通过。

## 已完成的独立验证

- 隔离 Git 快照 `mvn -B clean verify`：185 项总数，183 通过、0 失败/错误、2 个 POSIX 平台跳过，BUILD SUCCESS。使用 `-DargLine=-Xmx768m -Ddocfinder.argline.probe=present`。
- 同一快照以 `-Djvm=D:\java\java8\bin\java.exe` 再跑完整测试：Java 8 1.8.0_40，同样 185/0/0/2。不是仅启动 JAR 观察。
- Java 8 真实进程，实际 `LuceneIndexer → LuceneSearchService → WebServer → HTTP`：22 场景通过。包含中段/Unicode/多词、名称优先与正文补齐、目录整树及目录自身、过滤先于上限、limit=0/1/5/6/55/1000、截断与错误响应。
- 浏览器真实页面：确认 `report` 的名称与正文分组顺序、ProjectA 包含子目录且排除 ProjectB、`zz` 的 50 条结果与可见 `Showing first 50 results` 提示。
- 10 万文件 + 1001 目录，768MB heap、预热10/取样100，临时补测生产 `name_raw` 不存储的布局。p95：NAME/report 66.49ms、单字符 f 10.90ms、符号 . 38.64ms、强制执行正文补齐的 ALL/common 83.77ms、限定目录 NAME/f 32.79ms、限定目录 ALL/common 57.01ms。本机均低于100ms优化目标，无需 n-gram 或迁移；不承诺其他硬件 SLA。
- `openspec validate improve-name-search-experience --strict` 通过；`git diff --check` 通过。真实 config/sources/shares/history 四文件哈希未变。

## Devin 当前修复任务

1. **BQA-01 / P2，EXACT 路由回归**：真实小索引对比旧 JAR，`foo AND bar` 旧版只匹配 `foo AND bar note.txt`，新版还匹配 `foo bar draft.txt`；`report (` 旧版匹配 `report draft.txt`，新版 INVALID_QUERY。NAME/ALL 都复现。新分类只用于本次普通 FUZZY 名称路径，冻结模式复用原分类规则，并补回归。
2. **BQA-02 / P2，新文本首次 Enter 重复提交**：真实 EDT/输入框 `postActionEvent()`，计数 executor 记录首次提交2次、再次1次。按事件语义修正，补真实输入控件及提交计数测试；不以同文本100ms抑制替代，否则会吞合法的快速再次 Enter。
3. **说明收尾**：tasks.md 当前为非法 UTF-8，需要恢复文字并保留真实勾选；README.md:106/310、README_CN.md:39 尚称 B 未实施/未授权，须同步实际功能及内置帮助。历史 RED 过程不补造；缺少旧过程记录不阻塞交付。

修复及必要交互测试提交后，Codex 按新 SHA 复验，再交用户进行 B 的简短试用验收。A 的既有验收不重开，C1 保持暂停。

## 本机原始证据

`C:\Users\Administrator\AppData\Local\Temp\docfinder-b-qa-20261024-3zl64vxw\`：`clean-verify.log`、`baseline-reports/`、`java8-test.log`、`java8-reports/`、`independent-benchmark.log`、`controls-after.json`；`contracts/` 内含两个旧新对照/桌面探针源码和结果；`web/` 内含真实 HTTP 22 场景结果 JSON、服务日志及 `truncation.png`。测试服务器和临时浏览器页已关闭。

复验快照 JAR SHA256：`99431f23cf973fa3dd62d4618c360180a6bad8ef9a332a1f3dddce3b9c6ad866`。这是被审查的版本，不是修复后交付候选。


## 2026-10-25 修复与排序补充复验

提交 `932c62c532f9cf832d132a20752d3650e0d6be53`，包含0a2214b、d49e3af、932c62c。干净Git快照独立构建与运行；未使用开发者target。

- **全套**：JDK17 `clean verify` 2m38s、真实Java8 1.8.0_40 fork `test` 1m56s；均198总数/196通过/0失败/0错误/2个POSIX跳过。新桌面防抖6项、排序5项都实际执行通过。
- **BQA-01关闭**：独立旧新对照探针，在NAME/ALL的EXACT中`foo AND bar`只返回预期短语文件；`report (`正常匹配，与旧版一致。
- **BQA-02关闭**：真实输入框首次Enter提交1次、紧接的再次Enter也提交1次；程序化写入0次、Timer一次。实现按事件语义去重，没有100ms抑制窗口。
- **真实HTTP26场景通过**：Java8进程、实际indexer/service/WebServer链路；在上轮22场景基础上增加NAME/ALL的EXACT布尔词和括号4例。
- **排序通过**：桌面5项回归；浏览器真实点击确认升序→降序→完整原始顺序，按钮复位、选中body-only.txt及预览保持、新非空结果清除箭头。默认顺序不是score降序。
- **P3 / BQA-03未关闭**：桌面手动排序后空查询得到0行，sort keys仍为1且复位按钮启用；Web排序后无结果/错误时renderResults提前返回，按钮仍启用。正常非空排序复位不受影响。交Devin作最后小收尾并补内置帮助说明。
- 文档tasks恢复有效UTF-8；Codex同步外部使用说明/roadmap/进度，不复写历史RED证据。原1.2过程项按明确豁免关闭；5.1采用真实控件/executor计数和既有worker回归，完整虚拟时钟矩阵未建立。
- 本轮未重跑10万基准：查询变化已由兼容回归验证，排序仅作用于当前返回切片；先前10万独立性能记录继续作为路线依据，不伪称本轮新测。100万/native/POSIX/真实UNC未验证。

原始证据根：`C:\Users\Administrator\AppData\Local\Temp\docfinder-final-b-qa-20261025-iw6q2f1o\`，含clean-verify.log、java8-test.log、两套XML、summary.json；contracts/下为EXACT与Enter/空查询探针，web/下为26项HTTP JSON、复位后的选中状态JSON及截图、P3截图。真实config/sources/shares/history哈希不变。

JAR：71,938,064字节；SHA256 `6618b0e2379650dbd063272f303730cf513a6346222fc33786a2e571b7ee2d42`。用户尚未接受当前B候选，暂不合并或归档；Devin仅处理tasks 9.3，随后Codex定向复验及用户短试用。


## 2026-10-25 最后收尾关闭：2c47ab2

- 产品提交 `2c47ab2490f87e4c7124c7979bf2fec645049943`；通过git ls-remote确认已推送origin/codex/name-search。源码差异仅空态清理、对应两项回归和内置帮助。
- **独立定向测试通过**：干净Git快照在JDK17 clean package及真实Java8 1.8.0_40 fork分别执行32项，0失败/错误/跳过：TriStateSortDesktopTest 7、QueryDebounceDesktopTest 6、NameSubstringSearchTest 16、WebAssetsOfflineTest 3。未重复声称本轮执行全套200项；上轮独立198全套及开发者报告200项分别保留原证据范围。
- **BQA-03关闭**：直接运行开发者交付JAR（非仅测试独立构建）。桌面排序后清空查询输出 rows=0, keys=0, resetEnabled=false；Web实际点击确认空查询、无匹配、INVALID_QUERY三个空态均0行/0箭头/按钮禁用。正常恢复仍保留body-only.txt选择及预览，完整引擎顺序恢复正确。
- EXACT/连续Enter/程序化写入/Timer探针直接在同一交付JAR和Java8运行通过；JAR内已包含新增帮助说明。应用类均为Java8字节码，未发现JDT编译错误占位字符串。独立构建与交付JAR未要求逐字节可重复（若干class字节有差异），因此通过结论同时依据交付二进制实测，不将源码测试冒充该JAR测试。
- 本地交付JAR SHA256核实为 `C5740ED4AF7DF04FC77561461EAAEDF645E8C4702C56774227B48CC07421CB82`；路径 `E:\idea_projects\doc-finder\target\docfinder-1.0.0.jar`。当前验收绑定此代码SHA与JAR哈希。
- 真实config/sources/shares/history四文件哈希未变，测试服务及临时页面均已关闭。原始证据根：`C:\Users\Administrator\AppData\Local\Temp\docfinder-b-closeout-20261025-7m54scgq\`，含两套XML与日志、contracts下桌面/EXACT探针、web/browser-results.json与截图。

结论：没有剩余实施发现；23/25关闭，只待8.1用户接受和8.2交付归档。未将“Devin已完成”当作用户接受，也不启动C1或扩大新功能。
