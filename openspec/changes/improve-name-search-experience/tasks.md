# Tasks

> 方案 0.2（2026-10-24）：按 Codex 评审 B-01～B-09 修订（见 [review.md](review.md)）。未批准草案；实施须经 Claude 复核 + 用户批准。当前实施任务 0/22。

名称定位体验 Implementation Plan，草案 0.2（待复核/批准）。唯一实施进度来源。Goal：字面中段命中（NAME/FOLDER 纯名称条件，ALL=名称 OR 正文）、常量分档+呈现重排、防抖+在途失效、limit+1 截断提示。Tech：Java8/依赖不变；默认 name_raw 通配，基准未达标回评审（n-gram 另批）。

## Global Constraints

- 实施授权未取得；获得批准前只产出方案，不改产品代码、不推进验收。
- A0 隔离约定沿用（测试路径不落业务 user.dir/.docfinder；不操作真实索引）。
- EXACT/高级语法/目录限定/错误码契约冻结（EXACT 为现状特征冻结，见 D8）；新行为先写目标失败测试。
- 性能门槛以完整定义夹具实测为准，不以推演宣称达标；不提交/推送/归档未验收实现。

## 1. B0 基准夹具与特征基线

**Owner:** Devin；**Depends:** 实施批准。**Files:** test-only 夹具（参考 `DirectoryScopeCheckBenchmark` 模式）、`LuceneSearchServiceTest`。

- [ ] 1.1 建 10 万独立名称夹具（英文/中日文/符号/长名/高命中短词如单字符 `a` 与常见词 `report`/对抗性高词频正文文档），记录生成耗时、实际 `name_raw` 词项数与正文词项数；可重复生成、位于系统临时目录。
- [ ] 1.2 特征基线测试：现状 `report` 不命中 `AnnualReport2026.PDF`；EXACT 现状特征（整名 term + 分词短语 + ALL 叠正文，`report` 命中 `final report draft.txt`）；旧 parsed OR 在 NAME 下缺词命中的现状断言。→ RED/基线记录。
- [ ] 1.3 方案 A 通配原型于夹具（不改生产）：按 D1 完整门槛定义取样（固定硬件/JVM/-Xmx/reader 预热记录、预热后 ≥100 样本、冷首次单列、ALL/NAME × 有/无目录限定、端到端口径），输出 p50/p95/错误数/索引体积/内存；100 万文档观察记录（非门槛）。→ 对照拟定门槛 p95≤100ms 给路线结论；不达标 → 回设计评审（n-gram 方案另批）。

**Verify:** `mvn -B "-Dtest=<benchmark&baseline 测试>" test`；结论+原始数字写 evidence/B0.md。

## 2. B1 名称条件与范围语义（B-01/B-02/B-03/B-06）

**Owner:** Devin；**Depends:** B0 路线已定。**Files:** `LuceneSearchService.java`；`NameSubstringSearchTest`。

- [ ] 2.1 目标 RED：中段命中（`report`→`AnnualReport2026.PDF`、CJK `支付`、大小写、单字符、补充平面码点、多词 AND、`spring-core` 等符号）；ALL 正文命中不丢失；NAME 下"只命中部分词的名称"不出现；CONTENT 不因名称命中返回。→ 新增测试按预期失败。
- [ ] 2.2 实现 D2/D3：输入分类→字面词三档子句（DisjunctionMax 常量化见 B2）；NAME/FOLDER=名称条件 MUST；ALL=`(名称条件 OR parsed 正文)` minShouldMatch=1；CONTENT 不变；词数>16/词长>64 → INVALID_QUERY 固定文案。→ 定向 GREEN。
- [ ] 2.3 高级语法（`name:`/通配/引号/括号/字段/布尔算符）与 EXACT 现状特征回归；INVALID_QUERY 全局/限定一致且不进入历史记录。→ 既有测试 GREEN + 新增不变性断言。
- [ ] 2.4 `detectMatchType` 对子串命中仍标记 `name`；`name+content` 组合标记正确。→ 定向断言 GREEN。

**Verify:** `mvn -B "-Dtest=NameSubstringSearchTest,LuceneSearchServiceTest,DirectoryScopedSearchTest" test`；同步 UsageGuide 查询语法段。

## 3. B2 命中分档（B-04）

**Owner:** Devin；**Depends:** B1。**Files:** `LuceneSearchService.java`；排序断言并入 B1 测试类。

- [ ] 3.1 目标 RED：同库含整名/前缀/子串/仅正文四档 + 高正文词频对抗样本；断言档序与目录限定开/关下一致；limit 很小时高档不被挤出。→ 新增断言失败。
- [ ] 3.2 实现 D4：DisjunctionMax 常量档（4.0/3.0/2.0）+ 呈现层按存储 `name_raw` 实测档位 stable-sort + path tie-break；记录越档边界（若实测出现）。→ 定向 GREEN。

**Verify:** 排序断言 GREEN；对抗样本结果归档。

## 4. B3 截断提示契约（B-05）

**Owner:** Devin；**Depends:** B1。**Files:** `SearchService.java`（默认 `searchDetailed`+`SearchOutcome{results,hasMore}`）、`LuceneSearchService`（limit+1 覆写）、`FakeSearchService`；测试同 `SearchServiceNotifyTest` 风格。

- [ ] 4.1 目标 RED：`searchDetailed` 返回 hasMore（limit、limit+1、远大于 limit、删除位文档、scoped/unscoped 各路径）；默认实现回退 hasMore=false/unknown。→ 新增测试失败。
- [ ] 4.2 实现 limit+1 判定；不暴露精确总数。→ 定向 GREEN。

**Verify:** `mvn -B "-Dtest=<新契约测试>,SearchServiceNotifyTest" test`。

## 5. B4 桌面防抖与截断提示（B-08）

**Owner:** Devin 单一 UI 写入者；**Depends:** B3。**Files:** `MainWindow.java`、harness/EDT 测试、`DirectoryScopeDesktopHarness` 复用。

- [ ] 5.1 目标 RED：注入可控 Timer/时钟——键入立即使在途请求失效（代次+取消）；250ms 到点才提交一次；Enter/历史/范围变更/Clear/quick action/程序化 setText 取消挂起 Timer 且各至多一次请求；清空/开始索引/dispose 停 Timer+worker；编辑期 done()/历史回写不覆盖输入文本（钉住验收实测缺陷）。→ latch/定时可控测试先败。
- [ ] 5.2 实现 D6 接线；状态栏截断时 `仅显示前 N 条` 文案。→ 组件/harness GREEN。
- [ ] 5.3 同步 UsageGuide 桌面交互段。

**Verify:** `mvn -B "-Dtest=<桌面防抖/提示测试>" test`；GUI 依赖部分沿用 harness，实机部分留 M 记录。

## 6. B5 Web 一致与遗留清理（B-01/B-05/B-07 边界）

**Owner:** Devin；**Depends:** B3。**Files:** `SearchHandler.java`、`index.html`（截断提示展示）、`SearchHandlerTest`、`WebAssetsOfflineTest`；`search/query/QueryBuilder.java`/`QueryExecutor.java`。

- [ ] 6.1 目标 RED：HTTP 子串命中、`hasMore`/`truncated` 可选字段出现且既有字段不变、错误码映射不变、非英语 score 与控制字符回归。→ 定向失败。
- [ ] 6.2 实现 handler 走 `searchDetailed`、JSON 可选字段与页面截断提示。→ 定向 GREEN。
- [ ] 6.3 全仓引用搜索证明 `QueryBuilder`/`QueryExecutor` 无生产引用后独立小步删除（或标注 deprecated 不接线）。→ `mvn -B test` GREEN。

**Verify:** `mvn -B "-Dtest=SearchHandlerTest,WebAssetsOfflineTest" test`。

## 7. B6 集成与验收候选（B-09）

**Owner:** Devin 自动集成；Codex 复验；用户验收。**Depends:** B2/B4/B5。

- [ ] 7.1 真实 indexer→service→HTTP 端到端：子串/多词/四档排序/截断/目录限定叠加/对抗样本；逐 spec 场景证据。
- [ ] 7.2 `mvn -B clean verify`、真实 Java8 编译、strict validate、diff 空白核对、100 万观察数据与基准原始记录归档（非门槛）。→ evidence/B6.md。
- [ ] 7.3 交实际 SHA/diff/逐场景证据，按 acceptance.md 矩阵逐行对账形成验收候选；M 类实机项与 A8 由 Codex/用户流程完成。

## 8. 验收与交付（占位，同 A 治理）

- [ ] 8.1 用户明确接受并授权交付目标；记录实际版本与原始记录。
- [ ] 8.2 仅执行获授权的归档/提交/PR/推送；未授权部分保持未做。
