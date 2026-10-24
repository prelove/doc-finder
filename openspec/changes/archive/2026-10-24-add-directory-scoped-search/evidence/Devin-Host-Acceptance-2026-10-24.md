# Devin 主机验收执行记录（2026-10-24 第二轮）

> 本报告保留Devin补充执行证据及其当时掌握的流程状态。用户已在本聊天明确“手动验收完全成功”，A8已接受，见 [最新用户原文](User-Acceptance-2026-10-24.md)。文末NOT_EXECUTED/A8未授权反映实施者当时掌握的旧上下文，已由用户直接确认覆盖，不是当前关口状态。等价驱动与未执行项保留原始边界；观察项1纳入B-08方案整改。


执行者：Devin。受测代码：`0e8de6dcefe155fb7d8e938df332ea3d14942ca4`（`git rev-parse HEAD`）。
构建：`mvn -B clean verify "-DargLine=-Xmx768m"` → BUILD SUCCESS（169 tests，0 失败，3 跳过）。
JAR SHA-256：`DD16841B316E3CD8FFAF64AC25048D51F7BD4BDE411A7996D1ACE616855B1B4B`。
环境：Windows10 / JDK17 运行 harness 与探针 / Playwright 托管 Chromium。

隔离：根目录 `E:\qa\dirscope`（不在仓库 user.dir 子树内），各场景独立 `work` 目录承载 `user.dir\.docfinder`，统一 `-Duser.home=E:\qa\dirscope\home`。
**验收前后真实仓库 `.docfinder` 四控制文件 SHA-256 完全一致**（config.properties 19C4A612…、history.txt 8BC98E5F…、shares.properties 2446B78D…、sources.txt 1E65AF85…），真实数据零接触。

手段说明：桌面项由 `DevinAcceptanceProbe`（源码附 `qa-20261024-devin/DevinAcceptanceProbe.java`）驱动**真实 `MainWindow`**（可见窗口、真实 Lucene 索引、真实 `WebServer`），调用与点击等价的真实入口（`onIndexAllSources`/`onRebuildIndex`/`searchInDirectory`/scope-panel commit）。Web UI 项用 Playwright 真实浏览器点击/填充。HTTP 项为真实 socket 请求。

## 执行结果

### M-01 桌面主流程 — PASS（可自动项 12/12）

| 检查 | 结果 | 证据 |
|---|---|---|
| combo 选 ProjectA + `alpha`（ALL/CONTENT） | 命中 alpha-A/nested/special，排除 ProjectAB/ProjectB | PASS |
| FOLDER 搜 `ProjectA` | 根目录自身命中，ProjectAB 不混入 | PASS |
| 粘贴路径不回车 | committed 保持旧值、0 次搜索 | PASS |
| 回车提交 ProjectB | 单发搜索，只命中 alpha-B.txt | PASS |
| 等价写法 `e:/…/projecta`（小写+正斜杠） | 下拉显示同一配置源，非 Custom | PASS |
| 行级 quick action（containing folder） | committed=`…\ProjectA\sub`，只命中 alpha-nested.txt | PASS |
| Clear | 回全局，4 条 | PASS |
| `sources.txt` 坏行 | 一次性警告出现，修复后消失 | PASS |
| 删除已提交来源 | 显示 Custom，下次查询被拒（固定文案） | PASS |

边界：原生 JFileChooser 确认/取消本轮未重复点击（Codex 早轮探针已验证确认一次/取消零次）；真实 ManageSources 模态框未点（以 `sources.txt` 改写 + `refreshSourceDirectoriesAsync` 等价驱动，已注明）；右键弹出菜单项本身未点，调用的是其真实目标方法 `searchInDirectory`；盘根/UNC 根禁用态未执行。

### M-02 桌面错误 — PASS（8/8）

| 触发 | 状态栏固定文案（实录） |
|---|---|
| 相对路径 `relpath` | "Enter a valid absolute server directory path." |
| 源外路径 ProjectAB | "This directory is outside configured sources. Add a source first." |
| `report AND (` 无目录 | "Invalid search query. Check the search syntax and retry."（不入历史，3→3） |
| `report AND (` 有目录 | 同上 |
| 全部来源损坏 | "Search sources could not be loaded. Check configuration and retry." |
| 索引进行中 | "Indexing in progress, searching is disabled." |
| 范围内零命中 | "No results."（普通提示，非错误） |
| 旧索引 scoped | 见 M-06 INDEX_REBUILD_REQUIRED 文案 |

均无原始异常/堆栈泄漏（探针逐条比对 `SearchErrorMessages.forCode` 固定文案）。

### M-03 桌面竞态 — PASS（可自动项）

快速切换 A→A→B 范围并搜索，最终表格只对应最后请求（rows[alpha-B.txt]）。其余时序项（阻塞+reopen失败+迟到结果、Index All 瞬间）由刚跑过的 169 项套件覆盖（本轮 `clean verify` 全绿）。

### M-04 Web — PASS（API 13 项 + 浏览器 UI 13 项）

HTTP 实录：`/api/search?q=alpha`→200 count=4；`&directory=ProjectA`→200 count=3；`&directory=ProjectA\sub`→200 count=1；`&directory=ProjectAB`→**422** DIRECTORY_NOT_INDEXED；`report AND (`→**400** INVALID_QUERY；`scope=FOLDER&q=ProjectA&directory=ProjectA`→200 count=1（根自身）；`directory=…\资料 空格&#+[草稿]`→200 count=1（中文+空格+`&#+[]` 解码正确）；`/api/text?path=`→200 预览内容；**无服务 →503** INDEX_UNAVAILABLE；**旧索引 →409** INDEX_REBUILD_REQUIRED；**de-DE 区域 → JSON 合法**（`"score":2.8983` 小数点，F01 实机复验）。

浏览器实录（真实点击）：全局 4 行；dirInput=ProjectA→3 行且状态栏显示持久范围说明 "scope: …ProjectA (includes all subfolders)"；源外/语法错误→固定文案+清结果+不入历史（localStorage 核对）；Clear 按钮清空并回全局；行内"containing folder"按钮自动填 `…\sub\deep` 并重查；复制路径写剪贴板成功读回全路径；Name 列排序 asc/desc 生效。

### M-05 Web 竞态 — PASS（真实浏览器 fetch 门控）

A（挂起）→B（快）→页面保持 B；放行 A 后仍 B（searchToken 丢迟到响应）；A 在途时清空查询回车→**0 个新请求**，放行后页面仍空；撤拦截后正常搜索恢复。

### M-06 迁移与重建 — PASS（可自动项）

| 场景 | 结果 |
|---|---|
| `--empty` 首建：加来源→真实 `onIndexAllSources` | 完成→服务发布→桌面/Web 免重启可查（count=4） |
| 旧索引（无 path_scope + 幽灵文档） | scoped 桌面固定文案 + Web **409**；unscoped 不炸 |
| Index All（增量）盖旧索引 | 幽灵文档残留 → scoped 仍 **409**，文案提示手动 Full Rebuild ✓ |
| Full Rebuild | 状态文案显示、期间 Web **503**（非 0 结果）、完成后恢复 200 |
| 强制失败（锁 segments 文件） | "Rebuild failed. Search is unavailable until the index is rebuilt."固定文案，服务持续 503 |
| 恢复重试 | Rebuild 成功→Ready→200 |
| 打开/预览/分享抽查 | `/api/text` 200；`/api/share/create`+`/info` 200 |

边界：真实 ACL 部分失败（M-06.2c）未执行（用文件锁强制失败替代）；`ManageSources` 对话框点击、`Desktop.open`/`explorer` 真实外跳未执行（仅验证 Web 端 file/text/share 链路与桌面行渲染）；窗口关闭中断搜索未执行。

## 观察项（非失败，记入供评审/后续阶段参考）

1. **编辑器文本被晚到的 `done()` 覆盖**：探针在 scope 切换自动重搜完成前 `setText` 新词，worker 的 `done()`（历史回写 `setSelectedItem`）把输入框重置回旧词，导致后续搜索用旧词执行。真实用户场景=搜索在途时继续敲字可能被回滚 —— 与 B 阶段"输入防抖"提案相关，建议 B 设计时纳入防抖窗口内不回写编辑器的约束。
2. **Rebuild 完成后状态被覆盖**：`onRebuildIndex` 成功路径的 "Rebuilt files: N" 被后续异步流程翻成 "Ready"/"Enter a query to search."（发布→sources 刷新链路）。结果正确，仅状态确认文案短暂 —— 与 `completionStatus` 机制（部分失败路径有保护、成功路径没有）不一致，建议评估是否同样保护成功文案。

## 未执行（如实保留）

- 原生 JFileChooser/系统对话框点击、真实右键弹出菜单、窗口关闭中断、ManageSources 模态框点击、真实 ACL 权限失败、UNC/盘根禁用态实点、Java 8 fork 下本轮 M 项、POSIX/native/真实大网络。
- **最新版用户手工验收：NOT_EXECUTED**（不是 FAIL 也不是 PASS）。
- **A8 未授权、不推进**。本记录不替代用户实测。
