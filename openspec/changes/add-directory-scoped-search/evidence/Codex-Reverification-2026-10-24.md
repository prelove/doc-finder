# Codex：QA24 修复批次独立复验

日期：2026-10-24（Asia/Tokyo）。结论：**修复有效，但仍需补修；A7尚未形成A8验收候选**。原8个探针全部通过；新增更完整场景发现2个运行失败，另有测试/证据/文档残留。没有发现本批新增P0/P1；本轮P2偏差尚未被用户接受为例外。

## 版本、范围与执行

- 受测最终HEAD：`4f56d0d500de72abdade2da21e1ca024d1e1bc11`，产品修复提交`801d21fd7b68bd8d0719efefa3ab59405deff23a`。本轮GitHub GET main实查也是4f56d0d，确认已推送到该分支。
- 本轮针对8d41b0c之后的修复差异，结合既有DS映射复核；不是逐行重审所有历史源码。后端与桌面分别独立只读审查，Codex主执行者运行构建、Java8、旧/新探针、浏览器和性能验证。
- Windows10、JDK17/Maven3.9.12、真实Oracle Java8 `1.8.0_40`、Chrome155。一次性Git archive副本：`C:\Users\Administrator\AppData\Local\Temp\docfinder-reverify-20261024-ap2igz6p\checkout`。原始日志/XML/JAR留在同一QA根。
- 仓库业务代码、正式测试、pom均未被Codex修改；补充探针仅在副本运行，补丁保存后恢复。真实仓库四控制文件前后哈希相同；不把这等同于全业务索引逐字节证明。

## 实际结果

| 执行项 | 结果 | 材料 |
| --- | --- | --- |
| JDK17 clean verify | exit0，164总数，162通过，0失败/错误，2平台跳过 | [JDK17摘要](reverify-20261024/jdk17-clean-verify.txt) |
| 真实Java8 fork test | exit0，164总数，162通过，0失败/错误，2平台跳过；XML核对运行时/隔离目录/探针 | [Java8摘要](reverify-20261024/java8-test.txt) |
| 原8个独立探针 | 8/8通过，包含原生JFileChooser确认/取消、Applied标签、显式name、基础不可用清表/旧服务关闭 | [逐项结果](reverify-20261024/focused-probes.txt) |
| 本轮3个新检查 | 2个目标断言失败，1个对照通过；合计11项为9通过/2失败、0环境错误 | [补丁](reverify-20261024/independent-probes.patch) |
| 实际浏览器 | 19/19通过；真实MainWindow发布服务→WebServer→Lucene链路，故障/clipboard分支显式受控 | [浏览器结果](reverify-20261024/browser-results.json) |
| P-00/P-01 | 2个benchmark均运行通过，P-01门槛OK；P-00计数声明证据仍不成立 | [性能原始摘要](reverify-20261024/performance.txt) |
| 两change严格校验/差异 | 本轮最终文档更新后重跑，结果记录在管理校验材料 | 新QA材料清单独立于历史批准manifest |

命令（在副本cwd，MAVEN_OPTS=`-Xms32m -Xmx256m`）：

```text
mvn -B "-DargLine=-Xmx768m -Ddocfinder.argline.probe=present" clean verify
mvn -B "-Djvm=D:\java\java8\bin\java.exe" "-DargLine=-Xmx768m -Ddocfinder.argline.probe=present" test
mvn -B "-DargLine=-Xmx768m -Ddocfinder.argline.probe=present" "-Dtest=DirectoryScopedSearchTest#codex*,DirectoryScopeDesktopHarnessTest#codex*,CodexScopeDisplayProbeTest,DirectoryScopeEndToEndTest#codex*" test
mvn -B "-DargLine=-Xmx768m -Ddocfinder.argline.probe=present" "-Dtest=DirectoryScopeCheckBenchmark,DirectoryScopedSearchPerfBenchmark" test
```

跳过2项分别为PathScopeTest的POSIX行为和SearchHandlerTest的POSIX根。与Devin报告的3项区别是本轮带argLine探针，隔离探针实际执行并通过。首次移植旧探针因已存在同名辅助方法编译失败，复用helper后重跑；该移植错误不计为产品RED。

JAR SHA-256：`b4796270e4a20eee54d48dbea801b14803e41e7c5de2738b53a66a2a193127a4`。构建有既有shade资源/module-info重叠警告，未执行独立漏洞扫描/native，不声称零警告或全平台通过。

## 本轮发现（R24编号只用于本次复核，不覆盖历史QA24）

| ID | 优先级 / 证据 | 发现及要求 | 关联 |
| --- | --- | --- | --- |
| R24-01 | P2，确定性运行失败 | reopen失败分支清空服务与表格，但未失效旧查询。旧查询latch阻塞→打开新索引失败并显示不可用→释放旧查询，表格变回1行。失败分支需要推进查询代次/取消worker，并阻止旧成功或错误覆盖不可用状态 | QA24-04；MainWindow.java:1880，SearchWorker.done:2107/2173；DS-09/12 |
| R24-02 | P2，运行失败+磁盘实证 | 新Index All测试直接拼接Windows路径到properties，反斜杠被Properties.load吞掉。声明的临时idx与读回路径不同，实际会写到其他位置。用ConfigManager.setIndexDir/Properties.store，建库前断言精确路径及允许根；失败应在写索引前停止 | QA24-06；DesktopHarnessTest.java:329；ConfigManager.java:165 |
| R24-03 | P2，静态+运行对照 | 实际Index All测试仍只验证fake服务发布；在途失效用例仍只改isIndexing；部分失败提示仅fake reopen。E2E“不泄漏B”断言查询已限定A，不能证明B未提交。新增对照已证明B真实commit后仍被A范围隐藏。补真正入口时序、A提交/B失败及全局/合法B查询 | QA24-06；DesktopHarnessTest.java:273/314/358；EndToEndTest.java:291 |
| R24-04 | P2，静态，未跑POSIX | shouldReturnUnmodifiableSnapshot仍用E:\\Docs，POSIX会先因相对路径失败而到不了不可变断言；改宿主绝对路径，Windows专例加条件。父目录根测试也应逐结果断言而非任一null即可 | QA24-08；SourceManagerSearchRootsTest.java:128；SearchHandlerTest根用例 |
| R24-05 | P3，证据错误 | benchmark把常量enumerations=1打印出来，仅以cacheSize==1断言；同key每次重算覆盖也可始终size1。这证明条目数，不证明计算/枚举次数。加真实计算入口计数，或撤回计数与收益证明并保留门槛未完成 | QA24-11；DirectoryScopeCheckBenchmark.java:169–173及Devin报告/tasks12.11 |
| R24-06 | P3，文档内部矛盾 | 依赖文档已明示EOL风险，改善成立；仍声称兼容线内最新，同时FlatLaf3.x兼容Java8且选3.6.2未追已记载的3.7.x。请统一为指定版本的兼容升级/明确例外，不再留下无条件“最高线最新”规格 | QA24-10；modernize proposal/design/spec/tasks |

R24-01失败：expected rows=0，actual=1。R24-02失败示例：expected `C:\Users\Administrator\AppData\Local\Temp\codex-roundtrip-index-62293643907800`，actual为QA副本`target\test-classes\UsersAdministratorAppDataLocalTempcodex-roundtrip-index-62293643907800`。

R24-02磁盘核验：本轮C盘副本内发现被错误重定向的索引目录（完整路径在运行摘要），此前还存在4个C盘根目录：

```text
C:\UsersAdministratorAppDataLocalTempqa-indexall-idx-59092432944100
C:\UsersAdministratorAppDataLocalTempqa-indexall-idx-59313077434700
C:\UsersAdministratorAppDataLocalTempqa-indexall-idx-59466230580700
C:\UsersAdministratorAppDataLocalTempqa-indexall-idx-60535305363100
```

它们与错误properties规则及测试目录格式一致；不能仅凭时间断定每个由哪次提交生成。Codex未删除这些目录；交Devin逐一核对内容/来源后安全清理，禁止模糊匹配批量删除。本轮副本在C盘，盘相对路径仍落本次QA副本内；不据此声称原测试路径隔离正确。真实业务四控制文件哈希未变。

## QA24逐项结论

| 项 | 本轮状态 |
| --- | --- |
| QA24-01 | CLOSED_IN_REVERIFY：原不可用/重建失败残留结果探针通过；不涵盖R24-01新时序 |
| QA24-02 | CLOSED_IN_REVERIFY：Applied标签探针通过；原生窗口视觉/长路径裁剪未验 |
| QA24-03 | CLOSED_IN_REVERIFY：显式name全局/限定/组合三探针通过 |
| QA24-04 | REOPENED：基础旧服务关闭通过，R24-01迟到结果仍失败 |
| QA24-05 | CLOSED_IN_REVERIFY：只NoSuchFile为空，ACL读取拒绝与全套通过 |
| QA24-06 | PARTIAL：ACL确实触发失败，但R24-02/03阻止完整关闭 |
| QA24-07 | CLOSED_IN_REVERIFY：关闭gate及独立完整套件内latch通过 |
| QA24-08 | PARTIAL：两删除反例/统计修复通过，R24-04路径夹具残留 |
| QA24-09 | CLOSED_IN_REVERIFY：实际页面成功后持久提示仍可见 |
| QA24-10 | PARTIAL：EOL已明示；R24-06“最新”语义仍需统一 |
| QA24-11 | PARTIAL：服务入口/100热样本已补，R24-05次数证明未成立 |

## M/P状态与边界

| 项 | 最新状态与证据边界 |
| --- | --- |
| M-01 | 部分自动/选择器确认取消通过；Applied标签通过。原生窗口捕获先FrameArrived timeout，刷新绑定激活后再次window capture timeout，因此未进行点击级来源管理/坏行/右键全流程，不伪报视觉验收 |
| M-02 | 原错误清理探针通过；固定错误映射全套通过。完整真实八错误/选择预览交互未全跑 |
| M-03 | R24-01失败；真实Index All入口在途时序仍不足，待修复后重验 |
| M-04 | 本轮19项浏览器检查中的整树、根自身、422/400、快捷/复制及持久提示通过；未重跑全部特殊路径、真实409/503、区域设置、真实根/UNC、迟到预览全表，不沿用旧22项称本轮22 |
| M-05 | 本轮6个受控浏览器迟到/空词/当前网络错误/历史场景通过；clipboard受控，不读真实系统剪贴板 |
| M-06 | 真实ACL写失败与已提交A可查套件通过；真实UI首次/部分失败/重建成功失败、打开/分享全流程未完成，R24-02/03先补 |

P-00服务首次1.13ms，100热调用p95=0.00ms；刷新后首次0.09ms，再100次p95=0.00ms；删旧/删新分别4.35/19.58ms，后者409。均是实际时钟测量（有精度限制），不是打印常量枚举次数的证据。P-01 global/scoped热p95=49.94/31.13ms，门槛OK；单机合成数据，不是SLA。native/POSIX宿主/真实UNC/真实大网络来源未验。

## OpenSpec复核维度及下一关

| 维度 | 判断 |
| --- | --- |
| Completeness | 实现者交付时44/50勾选；当前35/56，逐项复核后按第13组重新打开未充分关闭项。M全清单/A8未完成，不因CLI规划artifact done判定实施完成 |
| Correctness | 原8探针全部通过，11项补充中2失败；DS-09/12有R24-01残留，DS-04/05/06的部分平台/生命周期证明不足；其他现有套件/19浏览器场景按上述范围通过 |
| Coherence | 指定路径隔离与证据真实性需修，依赖规格“最新”需一致；没有要求新增能力或改Java基线 |

下一负责人Devin：先R24-02测试路径守卫，再R24-01查询失效；随后R24-03/04/05/06补证/文档。Codex在修复最终SHA复验并安排余下M；用户再决定A8。原批准指纹保留历史，不改造成当前实现验收。临时GUI/Web服务已停止、浏览器页已关；本轮仅新增证据和更新台账，未提交推送这些管理改动。

工作流未完成项：2.1, 5.1, 5.4, 6.1, 6.2, 8.1, 9.1, 9.3, 10.1, 10.2, 12.4, 12.6, 12.8, 12.10, 12.11, 13.1, 13.2, 13.3, 13.4, 13.5, 13.6。具体描述与负责人见tasks.md；这是流程完成性缺口，不能与代码缺陷P0/P1等级混淆。适用但未完全验证的检查为M-01/02/03/04/06完整主机流程及POSIX/UNC/native所列平台项；不能给出全项archive-ready结论。
