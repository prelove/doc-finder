# Tasks

> 当前执行指示�E�用户要求尽早实裁E��以功�E效果为凁E��既定零迁移B茁E��已放行。�E最小特征基线→B1/B2可用核忁E�EB3/B4/B5交互与提示→实际性能及整体回归。历史草案的“征Elaude/未批凁E��仁E��此前状态E��以delivery-status和最新交接为凁E��E


> 方桁E0.3�E�E026-10-24�E�：按 Codex 复核 N02-01�E�N02-06 修订�E�见E[reviews/codex-0.2.md](reviews/codex-0.2.md)�E�。未批凁E��案；实施须绁EClaude 复核 + 用户批凁E��当前实施任务 0/22、E

名称定位体骁EImplementation Plan�E�草桁E0.3�E�征E��核/批凁E��。唯一实施进度来源、Eoal�E�字面中段命中�E�EAME/FOLDER 纯名称条件�E�ALL=名称列表+仁E��斁E�E表合并�E�、结构保证�E档、三类事件防抖、三态EhasMore。Tech�E�Java8/依赖不变�E�默认 name_raw 通�E�E�基凁E��达栁E��证E���E�E-gram 另批�E�、E

## Global Constraints

- 实施授权未取得；获得批凁E��只产出方案，不改产品代码、不推进验收、E
- A0 隔离约定沿用�E�测试路征E��落业务 user.dir/.docfinder�E�不操作真实索引）、E
- EXACT/高级语況E目录限宁E错误码契约冻结！EXACT 为现状特征�E结，见ED8�E�；新行为先�E目栁E��败测试、E
- 性能门槛以完整定义夹具实测为凁E��不以推演宣称达栁E��不提交/推送E归档未验收实现、E

## 1. B0 基凁E��具与特征基线

**Owner:** Devin�E�E*Depends:** 当前已放行的B茁E���E�小基线先行，大基凁E��置、E*Files:** test-only 夹具�E�参老E`DirectoryScopeCheckBenchmark` 模式）、`LuceneSearchServiceTest`、E

- [x] 1.1 建 10 丁E��立名称夹具�E�英斁E中日斁E符号/长吁E高命中短词如单字符 `a` 与常见证E`report`/对抗性高词频正斁E��档�E�，记录生成耗时、实际 `name_raw` 词项数与正斁E��项数�E�可重复生成、位于系统临时目录、E
- [ ] 1.2 特征基线测试：现状 `report` 不命中 `AnnualReport2026.PDF`�E�EXACT 现状特征E��整吁Eterm + 刁E��短语 + ALL 叠正斁E��`report` 命中 `final report draft.txt`�E�；旧 parsed OR 在 NAME 下缺词命中皁E��状断言。�E RED/基线记录、E
- [x] 1.3 方桁EA 通�E原型于夹具�E�不改生产�E�：按 D1 完整门槛定义取样�E�固定硬件/JVM/-Xmx/reader 颁E��记录、E��E��吁E≥100 样本、�E首次单�E、ALL/NAME ÁE朁E无目录限定、端到端口征E��E*ALL 含双查询合并全流程�E本**�E�，输�E p50/p95/错误数/索引体积/冁E��！E00丁E��察记录后置�E�不阻塞当前可用版本�E�。�E 对照拟定门槁Ep95≤100ms 给路线结论�E�不达栁EↁE回设计证E���E�E-gram 方案另批�E�、E

**Verify:** `mvn -B "-Dtest=<benchmark&baseline 测证E" test`�E�结论+原始数字�E evidence/B0.md、E

## 2. B1 名称条件与范围语义！E-01/B-02/B-03/B-06�E�E

**Owner:** Devin�E�E*Depends:** 最小特征基线�E�默认name_raw通�E已定，不以大基凁E��核忁E��现前置、E*Files:** `LuceneSearchService.java`�E�`NameSubstringSearchTest`、E

- [x] 2.1 目栁ERED�E�中段命中�E�Ereport`→`AnnualReport2026.PDF`、CJK `支付`、大小�E、单字符、补允E��面码点、多证EAND、`spring-core` 等符号�E�；ALL 正斁E��中不丢失�E�NAME 丁E只命中部刁E��的名称"不�E现�E�CONTENT 不因名称命中返回。�E 新增测试按颁E��失败、E
- [x] 2.2 【含D10 R1】实现 D2/D3�E�输�E刁E��→字面词三档子句�E�EisjunctionMax 常釁E4/3/2�E�；NAME/FOLDER=名称条件 MUST�E�ALL=名称列表+(正斁EMUST+名称 MUST_NOT)双�E表合并�E�CONTENT 不变�E�词数>16/词长>64 码点 ↁEINVALID_QUERY 固定文案；AND/OR/NOT 仁E��竁Etoken 证E��、Unicode 空白刁E�E。；ALL字面正斁E��义，空刁E��不匹配，双列表共享reader/来源快照�E�失败不返回部刁E�E功�E 定向 GREEN、E
- [x] 2.3 高级语法！Ename:`/通�E/引号/括号/字段/币E��算符�E�丁EEXACT 现状特征回归；INVALID_QUERY 全局/限定一致且不进�E厁E��记录。�E 既有测证EGREEN + 新增不变性断言、E
- [x] 2.4 【解释不含正斁Eonly排他条件】`detectMatchType` 对子串命中仍栁E�� `name`�E�`name+content` 绁E��栁E��正确。�E 定向断言 GREEN、E

**Verify:** `mvn -B "-Dtest=NameSubstringSearchTest,LuceneSearchServiceTest,DirectoryScopedSearchTest" test`�E�同步 UsageGuide 查询语法段、E

## 3. B2 命中刁E���E�E-04�E�E

**Owner:** Devin�E�E*Depends:** B1、E*Files:** `LuceneSearchService.java`�E�排序断言并入 B1 测试类、E

- [x] 3.1 目栁ERED�E�同库含整吁E前缀/子串/仁E��斁E��档 + 高正斁E��频对抗样本�E�E*limit=1/2 且高档名称结果数量趁E��样窗口**�E�断言档序与目录限定开/关下一致、名称命中列�E于仁E��斁E��中、名称+正斁E��命中只进名称列不重复。�E 新增断言失败、E
- [x] 3.2 实现 D4 双�E表合并�E�名称查询仁E��量档、正斁E��他查询 MUST_NOT、合并截断�E�名称列表冁E���E值→name 长度→path�E�排序；`name_raw` 不存储不改存储�E�零索引变更约束）；Match 字段按返回斁E�� explain 名称/正斁E��刁E��判定。�E 定向 GREEN、E

**Verify:** 排序断言 GREEN�E�对抗样本结果归档、E

## 4. B3 截断提示契约�E�E-05�E�E

**Owner:** Devin�E�E*Depends:** B1、E*Files:** `SearchService.java`�E�默认 `searchDetailed`+`SearchOutcome{results,hasMore}`�E�、`LuceneSearchService`�E�Eimit+1 要E�E�E�、`FakeSearchService`�E�测试同 `SearchServiceNotifyTest` 风格、E

- [x] 4.1 目栁ERED�E�`searchDetailed` 三态EhasMore——FALSE=已知无更多（命中=limit、Elimit、E、FILTER/删除位路征E��scoped/unscoped�E�，TRUE=limit+1 探测到�E�UNKNOWN=默认匁E��E��现�E�ENKNOWN 不得彁EFALSE�E�；HTTP UNKNOWN 省略字段、hasMore/truncated 一致性、最大 limit clamp。�E 新增测试失败、E
- [x] 4.2 实现 limit+1 三态判定；不暴露精确总数。�E 定向 GREEN、E

**Verify:** `mvn -B "-Dtest=<新契约测证E,SearchServiceNotifyTest" test`、E

## 5. B4 桌面防抖与截断提示�E�E-08�E�E

**Owner:** Devin 单一 UI 写�E老E��E*Depends:** B3、E*Files:** `MainWindow.java`、harness/EDT 测试、`DirectoryScopeDesktopHarness` 复用、E

- [ ] 5.1 目栁ERED�E�注入可控 Timer/时钟——类 A 键入立即使在途失敁E延迟一次提交�E�类 B�E�Enter/厁E��选择/茁E��变更/Clear/quick action�E�取涁ETimer 恰好一次提交�E�类 C 程序化写�E�E��E始化/addToHistory setQueryText/冁E��同步�E�零请求且保留草稿光栁E��渁E��/开始索弁Edispose 偁ETimer+worker�E�编辑期 done()/厁E��回�E不要E��输�E斁E���E�钉住验收实测缺陷�E�。�E latch/定时可控测试�E败、E
- [x] 5.2 实现 D6 接线�E�状态栏截断时 `仁E��示剁EN 条` 斁E��。�E 绁E��/harness GREEN、E
- [x] 5.3 同步 UsageGuide 桌面交互段、E

**Verify:** `mvn -B "-Dtest=<桌面防抁E提示测证E" test`�E�GUI 依赖部刁E��用 harness�E�实机部刁E�� M 记录、E

## 6. B5 Web 一致与遗留渁E���E�E-01/B-05/B-07 边界！E

**Owner:** Devin�E�E*Depends:** B3、E*Files:** `SearchHandler.java`、`index.html`�E�截断提示展示�E�、`SearchHandlerTest`、`WebAssetsOfflineTest`�E�`search/query/QueryBuilder.java`/`QueryExecutor.java`、E

- [x] 6.1 目栁ERED�E�HTTP 子串命中、`hasMore`/`truncated` 可选字段出现且既有字段不变、E��误码映封E��变、E��英语 score 与控制字符回归。�E 定向失败、E
- [x] 6.2 实现 handler 走 `searchDetailed`、JSON 可选字段与页面截断提示。�E 定向 GREEN、E
- [x] 6.3 全仓引用搜索证�E `QueryBuilder`/`QueryExecutor` 无生产引用后独立小步删除�E��E栁E�� deprecated 不接线�E�。�E `mvn -B test` GREEN、E

**Verify:** `mvn -B "-Dtest=SearchHandlerTest,WebAssetsOfflineTest" test`、E

## 7. B6 雁E�E与验收候选！E-09�E�E

**Owner:** Devin 自动雁E�E�E�Codex 复验；用户验收、E*Depends:** B2/B4/B5、E

- [ ] 7.1 真宁Eindexer→service→HTTP 端到端�E�子串/多证E四档排庁E截断/目录限定叠加/对抗样本�E�送Espec 场景证据、E
- [x] 7.2 `mvn -B clean verify`、真宁EJava8 编译、strict validate、diff 空白核对、基凁E��始记录保留�E�E00丁E��察后置。�E evidence/B6.md、E
- [ ] 7.3 交实际 SHA/diff/逐场景证据�E�按 acceptance.md 矩阵逐行对账形成验收候选；M 类实机项丁EA8 由 Codex/用户流程完�E、E

## 8. 验收与交付（占位，同 A 治琁E��E

- [ ] 8.1 用户明确接受并授权交付目栁E��记录实际版本与原始记录、E
- [ ] 8.2 仁E��行获授权皁E��档/提交/PR/推送E��未授权部刁E��持未做、E


## Codex R1补允E��收约杁E

执行D10与reviews/codex-0.3-r1.md�E�源码按一次acquire/release共享快照�E�不以两次public search拼接�E�加入+/-/a[b]原样输�E、空刁E��、�E支间刷新/第二�E支失败、名称+正斁E��始解释、UNKNOWN HTTP字段省略及接口委托不递归用例。仁E2项实施任务�E�未授权前不勾选、E
