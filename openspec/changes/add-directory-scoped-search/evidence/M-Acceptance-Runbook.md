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
