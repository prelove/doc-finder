# 给 Devin Local：A7 验证后的修复与补证指令

继续使用共享仓库 `E:\idea_projects\doc-finder`。先读取 [Codex-QA-2026-10-24.md](Codex-QA-2026-10-24.md)、tasks.md 第 12 组、acceptance.md 与 delivery-status.yaml。当前 A8 尚无最终验收结论；请先回到 A7 修复/补证，不把常规套件全绿或 Claude 方案 PASS 写成用户验收，也不要提前归档。

本轮受测产品为 `4eb417b`，文档后本地 HEAD `8d41b0c` 产品树相同；另跑了未升级依赖的 `c7f33f4`。原始临时副本根为 `C:\Users\Administrator\AppData\Local\Temp\docfinder-qa-20261024-up0jca24`。持久材料在本目录 `qa-20261024/`。仓库生产/测试代码未被 Codex 修补；材料里的测试仅在隔离副本运行。开始前核对 git status，不覆盖别人的未提交文件。

## 第一批：4 类已复现产品偏差

所有修改沿用既有设计，不新增依赖。先把以下补充用例整理到正式测试集，确认目标 RED，再最小修改；不要只改期望值让断言变绿。提供每项命令、失败原因、通过报告和最终 SHA。

| ID / owner 范围 | 复现与预期 | 实现注意 |
| --- | --- | --- |
| QA24-01 / MainWindow、DesktopHarnessTest | 先显示 1 行；setSearchService(null) 后 doSearch，或 handleFullRebuildFailure；当前仍 1 行。应清结果、选择和预览并保留不可用/失败提示 | 覆盖实际入口，不只测试空表初始态；不得把失败显示为 No results；保持旧请求失效保护 |
| QA24-02 / DirectoryScopePanel、MainWindow、PanelTest | 提交 A，编辑 B 不回车；getDirectoryPath 仍 A 但面板无 applied A 标签。必须显示实际已应用 A 和可编辑草稿 B | 编辑不提交、不查磁盘；Clear 一次；无范围为 All；异步结果不能修改新草稿；不是把查询偷偷改用草稿 |
| QA24-03 / LuceneSearchService、DirectoryScopedSearchTest | 全局/限定 `name:alpha-report.txt` 应标 name；加 `content:alpha` 应标两者。当前分别空/仅 content | 在保留 keywordQuery 的 Explain 逻辑中处理显式字段查询；别让 path_scope FILTER 参与命中解释；保留原合法查询排序/子串规则 |
| QA24-04 / MainWindow 服务重开和 HarnessTest | 已有 fake old service，reopen 指向另一个 index，工厂抛错；当前旧服务仍活动而 UI 报不可用 | 落实失败后不可用的既定口径并关闭/释放正确实例；sequence 防迟到候选；不要引入新存储迁移功能。若认为保留旧服务是产品取舍，先解释矛盾，不能静默回退 |

可复用 [regression-probes.patch](qa-20261024/regression-probes.patch) 的 6 个用例及 [CodexScopeDisplayProbeTest.java](qa-20261024/CodexScopeDisplayProbeTest.java) 的范围标签断言；选择器用例已通过，是行为保护。补丁针对 4eb 临时副本，不盲目覆盖现有测试。

## 第二批：来源错误与自动证明缺口

- **QA24-05 / SourceManager**：readSearchRoots 的 `!Files.exists(file)` 无法区分缺失和存在性未知。仅确定缺失可返回空列表；AccessDenied/其他 IOException 必须让 provider/HTTP 返回来源配置不可用。使用可控 reader/provider 或隔离文件系统故障注入；真实 ACL 若无法可靠构造写明未跑，不能用“传入目录”替代访问拒绝。
- **QA24-06 / EndToEnd、DesktopHarnessTest**：现有部分失败用例 upsert 不存在文件，Indexer 会 delete+return，没有写失败。加入明确 assertThrows/失败返回断言和可控实际失败；先让来源 A 成功 commit，再让来源 B 失败，确认已提交内容可查、整体失败提示保留、Web 恢复；完整重建失败仍不可用。Index All token 测试须调用实际 indexAllSources 生命周期，不能只设 isIndexing 后调用 doSearch。同时覆盖离线/权限不足旧 live doc 残留的 409，不把“空结果离线根”当迁移证明。
- **QA24-07 / 能力缓存**：检查中通过 latch 暂停，close，再释放；证明迟到结果不写服务缓存。当前先 join 后 close 不证明该契约。未通过前保留 5.3 未完成，不把静态风险写成已观察到泄漏。
- **QA24-08 / 跨平台测试**：使用 TempDir 宿主绝对路径；Windows 规范特例加条件。SearchHandlerTest 的 `E:\Root` 在 POSIX 没有预期父目录；补真实盘根/UNC/POSIX 根分别断言。DirectoryScopeCompatibilityTest 明确构造两组 maxDoc=3、numDocs=2、docCount=2：删除旧文档后支持，删除新文档后仍旧则不支持；不 merge，并断言统计前提，防退回另一快路径。
- **QA24-11 / P-00**：分别测真实服务缓存入口和 raw helper；至少 100 次热采样、首次和刷新分别记录，补所要求的 live-doc 场景/调用次数。若不做就登记部分完成，不能继续宣称 P-00 缓存收益已证明。

## 第三批：说明与台账

- **QA24-09**：Web 初始状态有 indexed files 说明，成功后消失；在范围附近保留仅查询索引内容的提示，或明确登记移期，不扩大为新页面设计。
- **QA24-10**：修订 modernize-java-dependencies 的“全部最新/消除 EOL 风险”承诺。Logback 1.3.x/Tika 2.x 仍 EOL，FlatLaf 已有更新发布；只说明本次具体版本升级与 Java8 兼容范围。不在这批自动升 Java/Tika/Lucene 大版本，不把 Java8 tests 当安全扫描。
- 每项更新 tasks 对应框与第 12 组、A 包证据、acceptance、review、delivery-status，保留历史记录。QA24 与历史 F01–F07/F-001～022 是不同编号，不覆盖已修复历史。
- 本轮 GitHub main 实查 `36139f5`，本地 `8d41b0c`；最终如按已获授权提交/推送，分别记本地 SHA、远端实查 SHA 和操作证据，不根据旧 tracking ref 宣称已推送。

## 回交门槛

先交第一批最小修复与证据，随后完成第二/三批。正式测试仍用 A0 隔离；不得重建业务 `.docfinder`。末尾在最终 SHA 执行 clean verify（带 argLine 探针）、真实 Java8 test、必要 P-00/P-01、两 change strict validate、diff 检查；报告总数/通过/失败/跳过和未执行项，不能重复使用旧数值。

Codex 负责补充主机 M 记录与独立复验；Devin 不代签用户 A8。当前受控浏览器 22 项与选择器确认/取消已通过；修复触及相应链路才重跑，M-01/02/03/04/06 尚缺的完整流程仍需安排。native/POSIX 主机/真实 UNC/真实大网络来源未验证，明确保留。
