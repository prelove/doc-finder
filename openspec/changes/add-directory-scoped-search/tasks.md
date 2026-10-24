# Tasks

目录限定检索Implementation Plan，批准版本0.4。2026-10-24用户明确“手动验收完全成功”，A实现/独立复核/用户手测与A8接受已闭环；当前55/56，10.2待本次授权提交推送及归档完成后关闭。记录见evidence/User-Acceptance-2026-10-24.md；历史状态保留对应版本范围。

> **For agentic workers:** 获得当前版本最终实施批准后，Devin按Superpowers executing-plans/TDD或handoff等价流程执行。每包目标RED→最小实现→GREEN→证据→审查；环境失败不算RED。

**Goal:** 来源内整树查询与便利操作，包含首次建库恢复、非破坏迁移优先、统一语法错误。
**Architecture:** 原path不变，path_scope字段、来源快照、reader能力判定及按reader生命周期缓存；实际MainWindow与HTTP链路。
**Tech Stack:** Java8；本change不擅自新增/升级依赖。并行的modernize-java-dependencies已修改pom版本，本次分别验证c7目录检索基线与4eb升级组合，不把并行变更伪装成原0.4批准范围。
**Spec:** design.md、specs/directory-scoped-search/spec.md；证据acceptance.md；评审review.md。

## Global Constraints

- 实施授权=true（2026-10-23用户批准0.4及A0测试配置）；该批准不等于最终验收或发布。当前仅修复既定契约并补验证，禁止操作真实业务索引。
- A包/DS编号保留；第11组为历史修复，第12组为2026-10-24新复核任务；任务总数由当前勾选项计算，不沿用最初31步。
- A0固定隔离是后续前提；只有A0首次验证用临时工作树，之后普通mvn命令使用受控fork/home/work；nofork不受该保证。
- source/index夹具为系统临时兄弟目录，不在user.dir子树；真实.docfinder不得访问/修改，禁止真实全量重建/强制解锁。
- 新行为先目标失败，既有特征测试可即绿；每包同步使用说明和evidence，不把扫描计数当成功文档数。
- Devin交自动测试/harness；Codex安排真实主机M-01～06，用户必要时辅助；手工未跑仍未通过，开发包交付不等于验收。
- App/MainWindow只能一名写入者；A5a后才做A5b；Web可在后端接口稳定后独立，不共享写同文件。
- 不提交/合并/推送/归档，除非用户另行批准；不带入AGENTS.md既有改动，不因技能默认频繁commit而越权。

## Review Focus

根自身/UNC边界；全库陈旧live docs；reader统计必须比maxDoc且缓存随删除失效；坏行与I/O区别；全局/限定INVALID_QUERY一致；新安装Index All后无需重启；Full Rebuild期间Web503；来源下拉/文本状态；关闭与迟到后台结果；GUI责任可落实。

## 1. A0 固定测试隔离与基线

**Owner:** Devin；**Depends:** 当前版本实施批准。
**Files:** 修改pom.xml中现有Surefire及空argLine属性（仅测试配置，依赖版本不改）、src/test/java/.../util/LegacyMigrationTest.java；新增src/test/resources/logback-test.xml、util/TestEnvironmentIsolationTest.java、search/LuceneSearchServiceTest.java，必要时SearchIndexFixture.java。生产迁移/AppPaths逻辑不改。
**Interfaces:** design D9；既有搜索构造。测试路径根为target/test-classes与target/test-home，不是业务user.dir/.docfinder。

- [x] 1.1 确认用户批准/评审指纹/源码；先在一次性隔离副本中完成Surefire fork/home/work、test-only日志、@TempDir迁移测试与守卫，按D9用${argLine}保留外部参数并最后追加隔离home，以-DargLine探针验证实际fork；保存JVM属性/参数和真实目录写前基线；读取隔离配置不能触发真实迁移。
- [x] 1.2 固定源缺失/正确复制/已有不覆盖/sentinel测试，运行定向为绿；故意破坏隔离的负例由测试配置或受控probe证明确会拒绝，不能破坏真实环境来测。
- [x] 1.3 回正常仓库cwd运行mvn -B test，核对真实.docfinder清单/哈希/mtime无变化；写shouldPreserveUnscopedModesAndScores、shouldPreserveFiltersAndLimits、shouldReturnEmptyForBlankQuery特征测试并通过。尚无provider时不声称证明未读provider。
- [x] 1.4 交A0证据、隔离操作说明、关联测试报告；默认fork和日志隔离必须成立，不能用nofork代替。

**Verify:** `mvn -B "-Dtest=TestEnvironmentIsolationTest,LegacyMigrationTest,AppPathsTest,LuceneSearchServiceTest" test`，然后`mvn -B test`；后续命令沿用已验证默认配置。

## 2. A1 路径、请求与容错来源

**Owner:** Devin；**Depends:** A0；**DS:** DS-02/04/10。
**Files:** 新增util/PathScope.java、search/SourceRootProvider.java；改SearchRequest.java、SourceManager.java；测试PathScopeTest、SourceManagerSearchRootsTest、SearchRequestTest。
**Interfaces:** design D1三static方法、D2 provider和loadSearchRoots/readSearchRoots、六参数请求。
**Assertions:** 索引/查询规范输出一致；Windows大小写/.与../UNC/盘根；POSIX保持case；扩展命名空间显式拒绝；有效+坏行继续且记录行号；全部坏/不可读错误；缺/空文件空来源；snapshot有跳过行号，provider仍只返回roots。

- [x] 2.1 【本次复验重新打开：R24-04宿主路径夹具→已补】先写以上参数化/来源格式/不可变快照/构造兼容测试，运行取得目标RED，不依赖真实sources.txt或真实UNC。→ `shouldReturnUnmodifiableSnapshot` 改用宿主绝对根 `Paths.get("").toAbsolutePath().getRoot()`，Windows/POSIX 均可达不可变断言。
- [x] 2.2 【4f56d0d独立复验通过】【2026-10-24重新打开：QA24-05】实现统一规范和坏行容错读取，保留path旧主键；无网络探测，I/O不吞；定向GREEN并记录平台skip。
- [x] 2.3 更新路径限制/坏行修复帮助及A1 evidence及日志行号断言，snapshot供桌面警告，不把跳过坏行等同于健康。

**Verify:** `mvn -B "-Dtest=PathScopeTest,SourceManagerSearchRootsTest,SearchRequestTest" test`。

## 3. A2 实际索引字段

**Owner:** Devin；**Depends:** A1；**DS:** DS-06。
**Files:** LuceneIndexer.java、index/content/DocumentBuilder.java；测试LuceneIndexerPathScopeTest、DocumentBuilderTest。
**Interfaces:** normalizeIndexedPath；原indexFolders/upsertFile/commit；两种DocumentBuilder入口保留。

- [x] 3.1 写shouldWriteScopeForFilesAndFolders、shouldKeepOriginalPathAndUnstoredScope、shouldUpdateWithoutDuplicate，term可查、stored无值、原path相同、live docs不增；运行RED。
- [x] 3.2 实际文件/目录全量/upsert和保留Builder写同字段，定向GREEN；不能仅改闲置类或断言扫描返回数。
- [x] 3.3 更新UsageGuide字段/Index All迁移成本、记录A2 evidence，不声称已修好watcher/poller。

**Verify:** `mvn -B "-Dtest=LuceneIndexerPathScopeTest,DocumentBuilderTest" test`。

## 4. A3 查询、来源、类型与统一语法错误

**Owner:** Devin；**Depends:** A1/A2；**DS:** DS-01～04/09/10。
**Files:** LuceneSearchService.java；新search/SearchException.java（替代未实现DirectoryScopeException）、DirectoryScopedSearchTest、DirectorySourceValidationTest。
**Interfaces:** provider构造；D3八code；D4目录自身folder OR后代FILTER。

- [x] 4.1 写根自身/深层/兄弟、limit=2前过滤、源外拒绝/来源变更、空词和无限定provider零调用、真正故障；同一report AND (在有效限定/全局均INVALID_QUERY；C++/转义输入按现有parser特征测试记录，不全部强制报错；POSIX含name:目录不误标命中来源；运行RED。
- [x] 4.2 【4f56d0d独立复验通过】【2026-10-24重新打开：QA24-03】实现来源快照、查询前目录/类型过滤、仅关键词Explanation判别、有/无目录ParseException一致，用户语法错误不记500；定向GREEN并跑A0回归，确认finally release。
- [x] 4.3 补模式/扩展名/时间、有效空commit/离线根/误输文件路径组合；同步兼容变化与错误帮助，记录A3 evidence。不得改变合法词的子串/精确规则。

**Verify:** `mvn -B "-Dtest=DirectoryScopedSearchTest,DirectorySourceValidationTest,LuceneSearchServiceTest" test`。

## 5. A4 reader兼容优化与迁移验证

**Owner:** Devin；**Depends:** A3；**DS:** DS-05/09。
**Files:** LuceneSearchService.java；DirectoryScopeCompatibilityTest、test-only DirectoryScopeCheckBenchmark.java。
**Interfaces:** ensureDirectoryScopeSupported(IndexSearcher)、reader CacheHelper key/closed listener；服务实例缓存仅SUPPORTED/REBUILD_REQUIRED。

- [x] 5.1 【本次复验重新打开：R24-05计算次数未实测→已补：scopeVerdictComputationCount真实计数】【2026-10-24重新打开：QA24-11→已补证】在隔离10万doc fixture做P-00微基准，对比原开放range、leaf快路径、reader缓存的首次/重复/刷新成本；记录真实枚举次数和耗时，不把top1当O(1)，证据不支持时回文档复核，不虚报收益。→ 本轮数字见 evidence/Devin-QA24-Remediation-2026-10-24.md。
- [x] 5.2 【2026-10-24重新打开：QA24-07/08→已补】写纯旧/混合/全新/删除/全删除leaf、maxDoc=3 numDocs=2 docCount=2两种相反结果（NoMergePolicy钉住）、helper=null/统计未知、I/O重试、同key复用、新key重验、并发refresh/close/缓存清理；取得目标RED。
- [x] 5.3 【2026-10-24重新打开：QA24-07→已补】实现D4充分条件快路径、不确定时精确live-doc回退及reader缓存生命周期；测试GREEN，禁止core key、错误缓存和关闭后迟到发布（closed gate + scopePublishLock）。
- [x] 5.4 【本次复验重新打开：R24-03实际部分失败链路及B隔离断言→已补：真实Index All入口+ACL中途拒写+全局查询证明B未提交】【2026-10-24重新打开：QA24-06→已补】测Index All补字段无陈旧时可用、有已删除文件/移除来源、来源离线/权限不足/部分失败旧live doc时仍409（离线live doc 409测试已加），提示检查可达性，手动Full Rebuild才兜底；更新迁移/回退说明和A4 evidence。不自动触发破坏操作。→ `shouldKeepPartialStatusAfterRealIndexAllSecondSourceCommitFails`（A commit 后 watchdog 加 ACL 拒写，B commit 真实失败；部分失败文案保留，reader 级断言 B 无文档）；`shouldServeCommittedDataAfterPartialCommitFailure` 增加全局 `q=delta` 断言；新增 `shouldDistinguishScopeFilterFromUncommittedIsolation` 对照（已提交 B 被 scope A 隐藏但全局可见）。

**Verify:** `mvn -B "-Dtest=DirectoryScopeCompatibilityTest,DirectoryScopedSearchTest,DirectorySourceValidationTest" test`。P-00耗时不作为普通单测断言，M-06实机迁移归Codex。

## 6. A5a 桌面面板与建库服务恢复

**Owner:** Devin单一UI写入者；**Depends:** A4；**DS:** DS-07/09/10/12。
**Files:** 新DirectoryScopePanel.java/测试、DirectoryScopeDesktopHarness.java、search/SearchErrorMessages.java及SearchErrorMessagesTest；改MainWindow.java和App.java。
**Interfaces:** D5面板接口（含setSourceWarning）；D8 reopenSearchServiceAsync(Path,IndexSettings)；受控harness工厂钩子只为构造/时序验证，不新建全局框架。

- [x] 6.1 【本次复验重新打开：R24-02/03测试路径与实际入口时序→已补：ConfigManager.setIndexDir序列化+读回/允许根守卫+真实reader验证+watchdog ACL部分失败】【2026-10-24重新打开：QA24-01/02/04/06→已补】写All/来源/Custom状态、仅编辑不提交、Clear一次、刷新不改文本/不提交/失效来源保留；增加Windows等价路径显示来源、非法编辑只Custom、坏行刷新警告及修复清除；headless八Code固定文案，fake逐个抛八Code/未知异常断言实际状态无原始消息/堆栈；latch测提交后service变null、错误不入历史/重建失效token；测首次Index All、部分commit后整体失败仍恢复且保留失败状态、旧候选迟到/关闭释放；记录RED（需GUI的部分交Codex执行，不虚报）。
- [x] 6.2 【本次复验重新打开：R24-01重开失败后旧查询回填→已补：失败分支searchSequence++/cancel worker；迟到成功与迟到错误两条latch回归GREEN】【2026-10-24重新打开：QA24-01/02/04→已补】实现面板和活跃MainWindow请求快照、来源三类刷新、后台服务打开和统一发布，逐一替换App启动、MainWindow添加单文件夹完成、完整重建完成三处new（0.3行77/1431/1636），生产new仅允许后台工厂；MainWindow服务字段volatile发布、worker局部捕获，null抛INDEX_UNAVAILABLE，done按Code固定映射而非getMessage；部分失败也恢复commit且Ready不覆盖失败；重建失效旧查询/Web不可用；组件GREEN，打开不在EDT。
- [x] 6.3 提供可复现harness/夹具/启动命令给Codex，自动测试与差异GREEN，同步UsageGuide及A5a evidence，附生产new全仓搜索只剩工厂的结果，并明确错误不入历史/保留字符转义；登记M-01/02/03/06待实机，不因Devin无GUI长期占住开发包，也不能标手工通过。

**Verify:** `mvn -B "-Dtest=DirectoryScopePanelTest,SearchErrorMessagesTest" test`；真正首次建库/服务恢复由M-06及A7组合检验。

## 7. A5b 桌面结果快捷操作

**Owner:** 同一UI写入者；**Depends:** A5a；**DS:** DS-11。
**Files:** MainWindow.java、DesktopHarness及必要EDT动作测试；不与A5a同时写窗口。
**Interfaces:** 目录自身/文件Path.getParent，程序setDirectoryPath后统一doSearch一次。

- [x] 7.1 在排序/单引号/盘根/当前根结果夹具写动作映射测试或harness断言；保留关键词条件、一次回调、禁用期无动作，记录RED。→ `DirectoryScopeQuickActionsTest`（5 用例：排序行→模型映射+一次提交、文件行→父目录、单引号路径、禁用期无动作、重复执行幂等）；有显示环境下 5/5 绿，headless 自动跳过。
- [x] 7.2 实现两个右键动作与正确view/model映射，定向GREEN；不重做预览/表格结构。→ `MainWindow.installTablePopupActions`：`Search in this folder`/`Search in containing folder`（复用 `getSelectedResult()` 的 view→model 转换，`Path.getParent` 安全解析，`searchInDirectory` 程序化 set + 单次 `doSearch`）。
- [x] 7.3 更新操作帮助及A5b evidence，交Codex执行M-01快捷部分，未执行单列。→ UsageGuide 右键段已更新；`evidence/A5b.md` 已写；M-01 实机点击**待 Codex**。

**Verify:** 组件/动作测试加受控DesktopHarness；最终M-01的实际点击由Codex/用户完成并保存记录。

## 8. A6 HTTP与Web

**Owner:** Devin单一Web写入者；**Depends:** A4；**DS:** DS-08～12。
**Files:** SearchHandler.java、resources/web/index.html；SearchHandlerTest、WebAssetsOfflineTest。
**Interfaces:** directory、兼容parentDirectory、八code/status；service=null固定INDEX_UNAVAILABLE，空词优先。

- [x] 8.1 【本次复验重新打开：R24-04逐结果根父目录证明→已补：parentToken/assertParent逐结果断言】【2026-10-24重新打开：QA24-08→已补】写编码、八类错误、全局/限定INVALID_QUERY一致、null服务503与空词、无commit不建库、非英语score/控制字符JSON、服务器父目录盘根/UNC/POSIX根；运行RED。→ `SearchHandlerTest` 11+ 用例（含盘根/UNC/POSIX根断言），实现后全绿。
- [x] 8.2 实现handler/JSON和页面目录/Clear/范围/快捷/复制/token/安全DOM，自动GREEN，不新增来源列表或浏览API；422覆盖探测风险有文档。→ `SearchHandler`（directory 参数、parentDirectory 字段、八 code→status、`{error,code}` 固定文案、控制字符 `\u00XX` 转义）；`index.html`（dirInput/ClearDirBtn/范围说明/dirscope-btn/copypath-btn/剪贴板失败回退）；`WebAssetsOfflineTest` 断言无 `/api/sources`、`/api/browse`。422 泄露风险已在 design 记录、evidence 复述。
- [x] 8.3 交浏览器fixture/可复现请求/受控fetch步骤，给Codex做M-04/05（含复制拒绝、排序、迟到与空词）；同步API/帮助及A6 evidence，静态资源检查不代替交互。→ UsageGuide Web/API 段已更新；`evidence/A6.md` 含 M-04/05 可复现步骤；**M-04/M-05 实机待 Codex**。

**Verify:** `mvn -B "-Dtest=SearchHandlerTest,WebAssetsOfflineTest" test`。

## 9. A7 集成与真实主机验收候选

**Owner:** Devin自动集成；Codex真实主机验收与证据复核，用户可辅助；**Depends:** A5a/A5b/A6。
**Files:** DirectoryScopeEndToEndTest.java；acceptance/review/evidence。

- [x] 9.1（2026-10-24重新打开：QA24-06及M记录；自动部分已完成；M-01～M-06 实机未完成，步骤见 evidence/M-Acceptance-Runbook.md，不据此宣称通过）真实indexer→service→HTTP测试正文/目录/来源变更/upsert、Index All迁移夹具（正常/陈旧/离线或拒绝访问/部分提交）、未就绪→恢复→重建不可用/恢复，运行自动GREEN；Codex执行M-01～06并记录环境与实际结果。→ `DirectoryScopeEndToEndTest` 8/8（真实 HttpServer+Indexer+Service+Handler）；**M-01～M-06 实机待 Codex**。
- [x] 9.2 执行P-01、mvn -B clean verify、本次diff空白、OpenSpec严格校验；实际目录不变、Java8编译、lint/static人工审查和平台skip记录；无GUI环境则状态WAITING_FOR_HOST_ACCEPTANCE，不降格验收。→ P-01 OK（scoped p95 15.5ms<unscoped 43.2ms）；clean verify SUCCESS（133 tests, target 1.8）；strict validate 通过；diff 空白仅 AGENTS.md 既有 EOF；`.docfinder` 14662 不变；人工静态审查记录于 A7 evidence。
- [x] 9.3（2026-10-24重新打开：独立复核未通过，待修复复验；交付物已交；独立实现 review 仅覆盖修复差异，完整功能 review 与验收候选待 Codex）交实际SHA/diff/逐DS证据/风险，独立实现review阻断闭环，Codex形成用户验收候选；旧Claude方案复核不替代实现review。→ 交付物：HEAD `9a1c26ad` + 工作树 diff（git status 全量清单见 A7 evidence 与提交说明）、evidence/A0–A7、逐 DS 索引；**review 阻断闭环与验收候选形成待 Codex**。

**Verify:** `mvn -B "-Dtest=DirectoryScopeEndToEndTest" test`后完整`mvn -B clean verify`；`openspec validate add-directory-scoped-search --strict`；M/P真实记录齐备。

## 10. A8 用户接受与授权交付

**Owner:** 用户决定；Codex核对；按授权执行；**Depends:** A7。

- [x] 10.1 用户明确接受结果/限制并给交付目标，保存原始记录和实际代码版本；模型评审不代用户验收。
- [ ] 10.2 只执行获授权的归档/同步/提交/PR/合并/推送，核对最终SHA/远端/文档证据，不force-push，不带无关AGENTS修改，未授权部分保持未做。

## 11. Codex 测试报告缺陷修复（用户已批准按顺序执行）

依据 evidence/Codex-Verification-2026-10-23.md，修复既定 DS 契约，不新增能力或改变设计。原 0.4 指纹保留为历史批准记录，本段是用户接受报告后追加的执行记录，不声称当前任务文件仍与原冻结包逐字节相同。每项先验证目标 RED，再最小修复与 GREEN；最终用户验收/提交/推送仍按第 10 组另行确认。

- [x] 11.1 F01：德语 FORMAT 区域下全局/限定 HTTP 分数用点号，先失败回归再 Locale.ROOT 修复，SearchHandlerTest 通过。
- [x] 11.2 F02：实际完整重建失败后不重新发布服务；保留 Index All 部分失败恢复，故障注入回归通过。
- [x] 11.3 F03：Web 空词提交/Clear 先失效旧请求、清空显示且不发查询，受控迟到响应回归通过。
- [x] 11.4 F04：Web 当前业务/网络失败清旧结果、选择和预览，迟到错误不覆盖新结果，浏览器回归通过。
- [x] 11.5 F07：只有当前成功查询才入 Web 历史，失败与迟到查询不入历史，浏览器回归通过。
- [x] 11.6 F05：桌面空词提交使已完成但 done 尚未执行的旧请求失效，确定性 EDT 回归通过。
- [x] 11.7 F06：手动编辑目录即时同步来源/Custom/All，不自动提交或查盘，组件回归通过。
- [x] 11.8 重跑 clean verify、隔离探针、报告中的浏览器失败场景，完成独立修复审查和修复报告；未跑的 M 项明确保留，不据此宣称最终验收。


## 12. 2026-10-24 Codex A7 修复与补证

依据 [独立验证报告](evidence/Codex-QA-2026-10-24.md) 与 [Devin Local 指令](evidence/Devin-Followup-2026-10-24.md)。Owner：Devin实施，Codex复验。不是新产品方案，不改变原批准指纹；未勾选表示尚未关闭，静态风险不能假称运行失败。第9组历史数字保留为当时证据，不代表当前验收。

- [x] 12.1 QA24-01（P2）：桌面service=null/完整重建失败时清除已显示结果、选择、预览，保留固定失败提示；合入两条目标失败回归并GREEN，对应6.1/6.2、DS-09/12。
- [x] 12.2 QA24-02（P2）：草稿编辑与已提交范围独立可见；不自动提交/查盘，测试A已提交、B草稿及Clear，对应6.1/6.2、DS-07。
- [x] 12.3 QA24-03（P2）：显式name字段及name/content组合准确标记命中来源，全局/限定三条回归GREEN，保持目录FILTER不参与解释，对应4.2。
- [x] 12.4 【本次复验重新打开：R24-01目标失败→已GREEN】QA24-04（P2）：新索引重开失败时服务实际状态与不可用提示一致，不静默沿用旧索引；覆盖资源释放/sequence，对应6.1/6.2、DS-12。
- [x] 12.5 QA24-05（P2，静态）：仅确定来源文件不存在才空来源，访问/读取失败报SOURCE_CONFIG_UNAVAILABLE；补确定性故障及HTTP映射证据，对应2.2、DS-04。
- [x] 12.6 【本次复验重新打开：R24-02/03→已GREEN】QA24-06（P2）：更正"upsert不存在文件产生写失败"的无效证明（改为索引目录 ACL 拒写→commit 真实失败传播）；真实Index All入口失效、A提交/B失败及旧live doc迁移残留补证，M-06仍待主机流程，对应5.4/6.1/9.1。
- [x] 12.7 QA24-07（P3，静态）：close与能力检查交错的latch测试，关闭后禁止迟到发布（closed gate + scopePublishLock），完成，对应5.3。
- [x] 12.8 【本次复验重新打开：R24-04→已GREEN】QA24-08（P2）：跨平台fixture与平台条件、真实父目录/盘根/UNC/POSIX根、两种maxDoc=3/numDocs=2/docCount=2删除反例（NoMergePolicy钉住叶统计）；单列未跑POSIX宿主，对应5.2/8.1。
- [x] 12.9 QA24-09（P3）：Web成功后仍有仅查询索引内容说明——`index.html` 范围提示改为持久可见（含"仅索引内容/整棵子树/必须在已配置来源内"），对应8.2。
- [x] 12.10 【本次复验重新打开：R24-06无条件最新语义→已统一为指定版本+例外】QA24-10（P2文档）：modernize proposal/design/spec/tasks统一具体版本与残留EOL风险（logback 1.3.x/Tika 2.x EOL 明示，FlatLaf 3.6.2非上游最新）；不自动改变Java基线。
- [x] 12.11 【本次复验重新打开：R24-05常量枚举次数→已改为实测scopeVerdictComputationCount】QA24-11（P3）：P-00已分开测真实服务缓存入口与raw helper——100热样本×2（首reader/刷新后）、verdict计算次数由LuceneSearchService.scopeVerdictComputationCount实测断言（首次1、热调用仍1、刷新后2）、删旧/删新两删除场景计时与409判定；报告所有原始数字，不以推演宣称收益。

关闭规则：各修复保留目标RED/GREEN证据；最终SHA重跑必要回归、clean verify/Java8及P项，Codex更新M记录并复核。12.x勾选不自动完成9.1/9.3或A8；用户最终接受和交付授权按10.1/10.2。当前22个浏览器检查与选择器确认/取消已通过，完整M清单仍以acceptance为准。


## 13. 最终提交4f56d0d独立复验后的任务

依据 [本次复验报告](evidence/Codex-Reverification-2026-10-24.md) 与 [Devin Local下一批指令](evidence/Devin-Next-2026-10-24.md)。Owner：Devin实施，Codex独立复验。原8探针全绿不覆盖更完整的新场景；不增加新能力，不推进A8归档。

- [x] 13.1 R24-02（P2，先做）：Index All测试正确序列化配置，建库前readback==idx且在允许QA根，验证真实目标索引文档；逐一核对报告所列历史越界测试目录，精确清理已证明为测试产物的目录或记录保留原因，禁止模糊删除。→ `setIndexDir`+Properties.store序列化、建库前断言readback==idx且位于tmp根、DirectoryReader按path成员断言（非裸doc数）；4个`C:\UsersAdministratorAppDataLocalTempqa-indexall-idx-*`逐一核验为单段Lucene索引且.cfs内含qa-indexall-src路径串，均为测试产物，已精确删除。
- [x] 13.2 R24-01（P2）：reopen失败失效旧查询代次/取消worker；latch覆盖旧成功及旧异常迟到，结果/选择/预览/状态保持不可用，Web服务仍null，目标RED→GREEN。→ `reopenSearchServiceAsync`失败分支先`searchSequence++`+cancel activeSearchWorker再setSearchService(null)；`shouldIgnoreLateSearchResultAfterReopenFailure`/`shouldIgnoreLateSearchErrorAfterReopenFailure`两条确定性回归GREEN（含web null发布断言）。
- [x] 13.3 R24-03（P2）：实际Index All入口的在途失效及A真实提交/B真实失败、部分失败提示/Web恢复；未提交B用全局/合法B查询证明，保留范围边界断言，不以fake发布代替全流程。→ `shouldInvalidateInFlightSearchDuringRealIndexAll`（真实onIndexAllSources+被阻塞旧查询失效+reader级doc断言）；`shouldKeepPartialStatusAfterRealIndexAllSecondSourceCommitFails`（watchdog在A提交后对idx加ACL拒写→B commit真实AccessDenied失败，部分失败文案保留、reader级B无文档）；E2E`shouldServeCommittedDataAfterPartialCommitFailure`增加全局`q=delta`断言B全局缺席；新增`shouldDistinguishScopeFilterFromUncommittedIsolation`对照。
- [x] 13.4 R24-04（P2）：清除来源用例剩余Windows硬编码，根parent按每条结果断言；Windows/UNC/POSIX分别条件，未跑宿主明确单列。→ `shouldReturnUnmodifiableSnapshot`改宿主根路径；SearchHandlerTest新增parentToken/assertParent，盘根/UNC/POSIX根均逐结果断言。
- [x] 13.5 R24-05（P3）：verdict计算入口真实计数，区分条目数/计算次数/term枚举，撤回或替换常量enumerations=1及衍生收益证明，P-00门槛不虚报。→ 新增`LuceneSearchService.scopeVerdictComputationCount`（AtomicLong，每次真正调用ensureDirectoryScopeSupported计数）；benchmark断言首次1/热100仍1/刷新后2；输出打印实测计数。
- [x] 13.6 R24-06（P3）：modernize proposal/design/spec/tasks以具体版本及例外统一，不再无条件保证最高兼容线最新；同步两change当前SHA、证据/状态，历史报告不覆盖。→ 全部改为"版本表指定版本+显式例外（FlatLaf 3.6.2）"；tasks3.1勾选、delivery-status记录RESOLVED；SHA待本次提交后由下一轮回写。

回交：最终产品SHA的必要定向/clean verify/带隔离探针/真实Java8/P-00/P-01及strict validate、diff结果。Codex再复验第13组并补M-01～06剩余流程；原生窗口截图本轮两次超时属验收工具限制，不能记M通过。第9组与A8继续未完成，直到实际证据和用户决定齐备。


## 2026-10-24 最新管理结论（0e8de6d独立复验）

第11～13组实现修复已独立闭环，保持勾选；不再让Devin重复修R24。当前主机8项实际Web流程通过，原生Swing截图再次超时，完整M记录尚缺。Codex继续负责A主机验收，用户决定A8；B提案可并行修订/评审，实施尚未授权。详见 [最终复验](evidence/Codex-Final-Review-2026-10-24.md) 与 [下一步交接](evidence/Next-Dispatch-2026-10-24.md)。本轮当前使用说明/内置帮助修订不改变查询行为，已单独回归。


## 用户最终手测通过与收尾推进（2026-10-24）

用户原文及版本/证据边界见 [验收确认](evidence/User-Acceptance-2026-10-24.md)。9.1/9.3/10.1按用户明确总体验收及独立验证关闭；M手测结论标PASS_USER_REPORTED，未补造截图/逐步日志。10.2由本次提交、推送、规格同步和归档实绩关闭。B仍仅方案修订。
