# M-01～M-06 实机验收执行手册

适用基线：`4571a89` 之后的代码（含 F01–F07 修复）。执行人：Codex，必要时用户辅助。本手册只是复现步骤，不代表任何一项已通过；结果记入文末记录表，失败项回到 Devin 修复。

## 0. 准备（每次验收开头做一次）

1. 构建当前代码：`mvn -B clean verify "-DargLine=-Xmx768m"`，记录 `git rev-parse HEAD` 和 JAR SHA-256（`Get-FileHash target\docfinder-1.0.0.jar`）。内存紧张时先关闭 IDE，fork 偶发 "insufficient memory" 重跑即可。
2. 建隔离根目录（示例 `E:\qa\dirscope`，不得在仓库 user.dir 子树内、不得是真实 `.docfinder`）：

```powershell
$root = "E:\qa\dirscope"; New-Item -ItemType Directory -Force "$root\work","$root\home" | Out-Null
$repo = "E:\idea_projects\doc-finder"
```

3. 启动桌面 harness（真实 MainWindow + 夹具来源 ProjectA/ProjectB + 已建索引 + 内嵌 Web 端口 18088）：

```powershell
Set-Location "$root\work"
java -Xmx384m "-Duser.home=$root\home" -cp "$repo\target\test-classes;$repo\target\docfinder-1.0.0.jar" `
  org.abitware.docfinder.ui.DirectoryScopeDesktopHarness $root 18088
```

   - 工作目录必须是 `<root>\work`，否则 harness 直接拒绝启动（应用数据全在 `user.dir\.docfinder`）。
   - 夹具树：`source\ProjectA`（含 `sub\deep`）、`ProjectAB`（兄弟，未配置为来源）、`ProjectB`；每个目录一个含 `alpha` 的 txt。来源配置为 ProjectA 与 ProjectB。
   - 追加 `--empty` 得到无来源、无索引的全新环境（M-06 首次建库用）。每个场景需要全新环境时：关闭进程，删除 `<root>\work\.docfinder`，重启。
4. 验收前后核对真实仓库 `user.dir\.docfinder` 的控制文件哈希/必要清单，并核对旧home迁移目录（若存在）。应用数据主根是user.dir，不可只核对 `~\.docfinder` 就声称隔离；确认临时进程cwd/home均正确。

## M-01 桌面主流程（DS-01/02/03/07/11/12）

1. 在“Search in”选 ProjectA，关键词 `alpha`，模式 CONTENT/FILENAME 各试一次：命中 `alpha-A.txt`、`alpha-nested.txt`，不含 ProjectAB/ProjectB。
2. 模式 FOLDER 搜 `ProjectA`：根目录自身可命中，ProjectAB 不混入。
3. 粘贴路径、Browse 选子目录（`ProjectA\sub`）：确认对话框一次提交一次请求，取消提交 0 次。
4. 文本框手工改成 ProjectB 路径（不回车）：下拉应即时显示对应来源/Custom/All，且未发起搜索；回车后才查询。
5. Windows 等价写法（`e:/…/projecta` 大小写、斜杠）：显示为同一来源，不显示 Custom。
6. 在 Manage Sources 增删来源后关闭窗口：下拉刷新，文本和已提交范围不变；删除当前来源后显示 Custom，下次查询被拒绝。
7. 排序后右键结果：“Search in this folder”（目录行）、“Search in containing folder”（文件行）映射到正确行，保留关键词与过滤条件，只触发一次请求；盘根/UNC 根按预期禁用。
8. Clear 回到全局范围；重复点击同一快捷动作不循环请求。
9. `sources.txt` 里手工加一行坏路径：刷新后出现一次警告指向日志；修复后警告消失，其余来源仍可查。

## M-02 桌面错误（DS-04/09/12）

逐项触发并核对状态栏文案：固定文案，不含原始异常消息或堆栈。
- 输入相对路径、源外路径（预期 DIRECTORY_NOT_INDEXED 类提示，不自动扫描/重建/全局回退）。
- 输入非法语法 `report AND (`，有目录与无目录各一次：同为 INVALID_QUERY，且不进入历史。
- 全部来源行损坏或 `sources.txt` 不可读：报来源配置错误；好坏混合时仍可查。
- 用旧版本（无 `path_scope`）索引：提示需要 Index All / Full Rebuild，不自动执行。
- 索引进行中：范围动作不可发起，状态提示索引中。
- 源内零命中只说明“索引内容无命中”。

## M-03 桌面竞态（DS-07/10）

自动测试已覆盖，实机只做可观察核对：
- 连续快速切换范围和关键词，最终结果只对应最后一次请求。
- 搜索未返回时清空关键词并回车：结果清空，延迟结果不回写（F05）。
- 索引开始（Index All）瞬间正在返回的搜索不应覆盖“Indexing…”状态。
- 搜索中关闭窗口，无异常弹窗、进程正常退出。

## M-04 Web 主流程（DS-02/08/09/11）

浏览器打开 `http://127.0.0.1:18088/`，开 DevTools Network：
1. 目录输入含中文、空格、`+ & # []`：请求里 `directory` 被正确编码，服务端还原无误（可在夹具中另建 `资料 空格&#+[草稿]` 目录并加入来源）。
2. 目录范围、Clear、行内目录/父目录快捷；父目录对盘根/UNC 正确，无父目录时按钮禁用。
3. 复制路径：允许时成功；浏览器拒绝剪贴板时走手动复制回退，不伪报成功。
4. 触发各类错误（源外目录 422、`report AND (` 400、旧索引 409、索引不可用 503）：状态栏显示固定文案，旧结果/选择/预览被清空（F04），失败词不进历史（F07）。
5. 旧 API 结构兼容：不带 `directory` 的请求行为与之前一致；德语等小数逗号区域下响应仍是合法 JSON（`java -Duser.language=de -Duser.country=DE` 启动再测，F01）。

## M-05 Web 竞态（DS-08/10）

用 DevTools Local overrides 或 Playwright 路由延迟请求 A：
1. A 慢、B 快：先返回 B，再放行 A（成功/错误各一次）：页面保持 B。
2. A 在途时清空关键词并回车：0 个新请求，放行 A 后页面仍为空（F03）。
3. 撤销拦截后正常搜索恢复。

## M-06 迁移与重建（DS-05/06/12）

均在隔离环境，**不得**对真实 `.docfinder` 做重建。
1. `--empty` 启动：加来源 → Index All → 不重启即可在桌面和 Web 查询。
2. 旧索引（可由 `git stash`/旧版本 JAR 在隔离目录建出无 `path_scope` 的索引）：
   a. 无陈旧残留 → Index All 后范围查询可用；
   b. 含已删除文件/已移除来源的陈旧条目 → 范围查询仍 409，提示需要备份后手动 Full Rebuild；
   c. 来源离线或权限不足的部分失败 → 仍 409，提示检查可达性。
3. Full Rebuild：期间桌面提示不可用，Web 非空请求返回 503（不是 0 结果）；成功后两端恢复；人为制造失败（如占用索引文件/锁）后服务保持不可用（F02），状态栏提示失败。
4. Index All 部分失败但已有提交：已提交记录可查，失败提示不被 “Ready” 覆盖。
5. 抽查预览、打开、分享功能无回归。

## 记录模板（每项一行，原始截图/日志放 `evidence/` 下）

| 项 | 日期 | 环境（OS/JDK/浏览器） | 代码 SHA / JAR SHA-256 | 结果 PASS/FAIL/BLOCKED | 失败现象与复现 | 证据文件 |
| --- | --- | --- | --- | --- | --- | --- |
| M-01 |  |  |  |  |  |  |
| M-02 |  |  |  |  |  |  |
| M-03 |  |  |  |  |  |  |
| M-04 |  |  |  |  |  |  |
| M-05 |  |  |  |  |  |  |
| M-06 |  |  |  |  |  |  |

补充：P-00/P-01、Java 8 运行时、POSIX、native、UNC 若未执行，在此单列“未执行”，不得合并成通过。


## 2026-10-24 Codex 实际执行记录

OS=Windows10，JDK17；浏览器Chrome155；受测4eb417b（本地8d41b0c的产品树相同）；JAR SHA-256=`a69e51ff33d4739ed604fda0f900c029dbeac21deceadb23176fc839ebf6aa39`。临时根 `C:\Users\Administrator\AppData\Local\Temp\docfinder-qa-20261024-up0jca24`；源码/日志及真实WebServer夹具均隔离，端口14970。执行者Codex，未让用户代签结果。

| 项 | 结果 | 实际观察 / 剩余范围 |
| --- | --- | --- |
| M-01 | PARTIAL / FAIL | 真实JFileChooser确认一次回调、取消零次通过；已提交A/草稿B没有applied A显示，QA24-02失败；其余来源管理、坏行警告、真实右键/UNC全流程未逐项执行 |
| M-02 | FAIL / PARTIAL | 显示结果后null服务搜索或重建失败仍1行，QA24-01失败；常规错误映射套件通过，完整真实八错误/ACL/预览清理待复验 |
| M-03 | PARTIAL | 常规token/空词测试通过；Index All真实入口时序及关闭交错需补，不能全项PASS |
| M-04 | PARTIAL | 真实页面整树2结果、Unicode/空格/&#+[]单引号编码1结果、根folder1、422/400清旧显示、排序快捷/复制分支通过；复制用受控clipboard，真实409/503/区域/真实根全表未跑 |
| M-05 | PASS（规定受控场景） | 延迟A后B成功，A迟到成功/422/网络拒绝均不覆盖；空词提交/Clear零请求；当前失败清结果/选择/预览；撤拦截真实搜索恢复，失败/迟到不入历史 |
| M-06 | PARTIAL / 未完成 | 现有集成通过，部分写失败用例没有真实失败（QA24-06）；首次建库、实际部分失败、完整重建成功/失败、打开/分享主机流程未全跑 |

逐项数据与受控脚本：[browser-results.json](qa-20261024/browser-results.json)、[失败探针](qa-20261024/regression-failures.txt)、[选择器/显示测试](qa-20261024/CodexScopeDisplayProbeTest.java)。完整边界见 [独立QA报告](Codex-QA-2026-10-24.md)。原149项套件在真实Java8 fork也通过（148通过、1 POSIX跳过）；不是native/App正式启动全验证。P-01门槛OK，P-00完整缓存证明未满足；POSIX宿主/真实UNC/native/真实大网络未跑。

本轮前后真实仓库config.properties/sources.txt/shares.properties/history.txt四控制文件SHA相同；临时cwd/home属性已核对。没有全量业务索引逐字节证明。临时服务已停止、QA页面已关闭，原始证据保留。以上失败交Devin，Codex修复后复验；不据此推进用户A8。


## 4f56d0d实际执行记录（2026-10-24，本日第二轮）

执行人Codex；Windows10/JDK17/Chrome155，另真实Java8 fork。受测SHA=4f56d0d500de72abdade2da21e1ca024d1e1bc11，JAR SHA-256=b4796270e4a20eee54d48dbea801b14803e41e7c5de2738b53a66a2a193127a4。隔离根C:\Users\Administrator\AppData\Local\Temp\docfinder-reverify-20261024-ap2igz6p，真实桌面harness及其Web端口14971，均已停止。

| 项 | 最新结果 | 边界与待办 |
| --- | --- | --- |
| M-01 | PARTIAL | 原生JFileChooser确认/取消及Applied标签自动探针通过；window捕获先FrameArrived timeout，刷新绑定后仍capture timeout，没有继续点击级来源管理/坏行/右键或视觉验收 |
| M-02 | PARTIAL | 原不可用/重建失败清理自动探针及错误映射套件通过，完整真实八错误/选择/预览交互未全跑 |
| M-03 | FAIL | 旧查询阻塞→重开新索引失败→释放旧查询，旧结果重新出现1行（R24-01）；实际Index All入口时序仍需R24-03 |
| M-04 | PARTIAL | 本轮19项Web总检查含整树/根自身/源外422/语法400/持久说明/排序快捷/复制通过；clipboard受控，未全跑真实409/503/特殊编码/区域/根全表 |
| M-05 | PASS（规定受控场景） | 旧成功/业务失败/网络失败迟到均忽略；空词/Clear零请求，当前错误清显示且错误/迟到不入历史 |
| M-06 | PARTIAL | 常规ACL写失败与A提交可查通过；路径隔离与生命周期证明R24-02/03未满足；真实UI首建/部分失败/重建成功失败及打开/分享全流程仍缺 |

逐项材料见 [reverify-20261024](reverify-20261024/verification-summary.json) 和 [独立复验报告](Codex-Reverification-2026-10-24.md)。常规164项/0失败不覆盖新增2失败；原8通过不代替M完整清单。真实四控制文件前后SHA相同；发现的错误测试索引路径已单列，不能继续声称所有测试都写在其声明临时idx。native/POSIX宿主/真实UNC/真实大网络未跑，不代签用户A8。


## 0e8de6d本日最新记录

执行者Codex；原版及当前帮助文案变体（JAR分别99fba76d…/9eac540b…，完整哈希见最终报告），Windows10/JDK17/实际Java8，独立临时根docfinder-finalqa-20261024-5q25y5l9。Web端口14972。本轮8项实际页面：A直系+深层两条、真实文本预览、真实clipboard路径回读、400错误清结果/选择/预览、FOLDER根自身、未配兄弟目录拒绝、Clear保词全局3条、排序后父目录缩小范围两条，均通过。未将旧受控网络/拒绝剪贴板场景计入这8项。

R24两种EDT顺序与Index All时序/实际提交已由169套件及16追加检查验证；watchdog重复5次通过。原生AX仅窗框、截图再次FrameArrived timeout，未执行完整点击级来源管理/坏行/重建/窗口关闭等清单，不伪报M-01/02/03/06全通过。M-04特殊编码/409/503/区域/真实根全表尚缺，M-05旧受控通过且web源码/资源tree未变，本轮未重复注入。各M最新状态以最终报告表为准；用户补充的最新版主机结果到达后另记原始范围与版本，仍不代签A8。

证据：[最终报告](Codex-Final-Review-2026-10-24.md)、[运行摘要](finalqa-20261024/verification-summary.json)。四真实控制文件哈希未变；native/POSIX宿主/真实UNC/真实大网络未验。


## 用户直接确认：最新版手工未执行（2026-10-24）

用户答复“尚未进行最新版手动验收”，详见 [确认记录](User-Manual-Acceptance-2026-10-24.md)。用户手动执行列统一保留NOT_EXECUTED；既有Codex自动/harness/浏览器通过项按原范围保留，不能合并为完整M通过。后续在隔离夹具逐项补结果与实际版本后再更新；当前A8未通过。


## 最新用户验收结论（2026-10-24，替代之前未执行状态）

用户明确“手动验收完全成功”，原文及版本边界见 [User-Acceptance-2026-10-24.md](User-Acceptance-2026-10-24.md)。M-01/02/03/04/05/06用户整体手测结论为PASS_USER_REPORTED；用户未附逐步日志/截图/JVM/JAR hash，不补造。此前Codex实测与工具限制继续作为其原执行范围。该用户接受与既有自动证据共同关闭A验收；native/POSIX宿主/真实UNC仍未新增验证。
