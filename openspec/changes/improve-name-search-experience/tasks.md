# Tasks

> 2026-10-24 Codex评审：CHANGES_REQUESTED。此为未批准的0.1草案，B-01～09见 [评审意见](review.md)；修订0.2并经Claude复核/用户批准前不得实施，当前0/22。


名称定位体验 Implementation Plan，草案 0.1（待评审/批准）。唯一实施进度来源。Goal：字面中段命中、三档排序、防抖与上限提示、桌面/Web 一致。Tech：Java8/依赖不变；默认 name_raw 通配，基准未达标才评 n-gram。Spec/Design 见同目录；每包目标 RED→最小实现→GREEN→证据。

## Global Constraints

- 实施授权未取得；获得批准前只产出方案，不改产品代码、不推进验收。
- A0 隔离约定沿用（测试路径不落业务 user.dir/.docfinder；不操作真实索引）。
- EXACT/高级语法/目录限定/错误码契约冻结；新行为先写目标失败测试。
- 性能门槛以夹具实测为准，不以推演宣称达标；不提交/推送/归档未验收实现。

## 1. B0 基准夹具与特征基线

**Owner:** Devin；**Depends:** 实施批准。**Files:** test-only 夹具（参考 `DirectoryScopeCheckBenchmark` 模式）、`LuceneSearchServiceTest`。

- [ ] 1.1 建 10 万文档临时夹具（名称分布含英文/中日文/符号/长名），记录生成耗时与词项统计；验证夹具可重复生成且位于系统临时目录。→ 夹具构建脚本/测试 setup，控制台输出词项数。
- [ ] 1.2 写特征基线测试：FUZZY 下 `report` 不命中 `AnnualReport2026.PDF`（当前行为），多词、CJK 单字、`spring-core` 符号输入的现状断言。→ 定向测试运行，记录 RED/基线。
- [ ] 1.3 先实现方案 A 通配原型于夹具（不改生产），p50/p95  warmed 查询计时（ALL/NAME × 有/无目录限定）。→ benchmark 输出原始数字，对照 D1 门槛（p95≤100ms）给出路线结论记录。

**Verify:** `mvn -B "-Dtest=<benchmark&baseline 测试>" test`；结论写入 evidence/B0.md。

## 2. B1 字面子串与多词规则

**Owner:** Devin；**Depends:** B0（路线已定）。**Files:** `LuceneSearchService.java`；`NameSubstringSearchTest`。

- [ ] 2.1 目标 RED：中段命中（`report`→`AnnualReport2026.PDF`、CJK `支付`、大小写、单 ASCII 字符不启用子串档、CJK 单字启用、多词 AND、`spring-core` 字面）逐断言。→ 新增测试运行，确认按预期失败。
- [ ] 2.2 实现 D2/D3：字面词切分→三档 OR 组，词间 AND；NAME/FOLDER 为 `(parsed OR group) MUST`，ALL/CONTENT 为 `parsed MUST + group SHOULD`；ASCII 子串词 len≥2、非 ASCII len≥1。→ 定向 GREEN。
- [ ] 2.3 高级语法（`name:`/通配/引号/括号/字段）与 EXACT 回归断言不变；INVALID_QUERY 全局/限定一致。→ 既有测试 GREEN + 新增不变性断言。
- [ ] 2.4 `detectMatchType` 对子串命中仍标记 `name`；`name+content` 组合标记正确。→ 定向断言 GREEN。

**Verify:** `mvn -B "-Dtest=NameSubstringSearchTest,LuceneSearchServiceTest,DirectoryScopedSearchTest" test`；同步 UsageGuide 查询语法段。

## 3. B2 命中排序三档

**Owner:** Devin；**Depends:** B1。**Files:** `LuceneSearchService.java`；排序断言并入 B1 测试类。

- [ ] 3.1 目标 RED：同库含完整名/前缀/子串三档文档，断言输入词的排序次序与目录限定开/关下一致。→ 新增断言失败。
- [ ] 3.2 校准 boost（3.0/2.0/1.0）使三档次序稳定，同档保留原评分序。→ 定向 GREEN；记录无法靠 boost 稳定区分的边界（若有）。

**Verify:** 排序断言 GREEN；多词与单字边界情形补录。

## 4. B3 命中总量契约

**Owner:** Devin；**Depends:** B1。**Files:** `SearchService.java`（默认 `searchDetailed`+`SearchOutcome`）、`LuceneSearchService`（TopDocs totalHits 覆写）、`FakeSearchService`；测试 `SearchServiceNotifyTest` 同风格新增。

- [ ] 4.1 目标 RED：`searchDetailed` 返回 `totalHits`（>limit 时>results.size()），默认实现回退为 `results.size()`。→ 新增测试失败。
- [ ] 4.2 实现默认方法+Lucene 覆写；删除位/FILTER 后计数正确。→ 定向 GREEN（含 scoped/unscoped 两路径）。

**Verify:** `mvn -B "-Dtest=<新契约测试>,SearchServiceNotifyTest" test`。

## 5. B4 桌面防抖与上限提示

**Owner:** Devin 单一 UI 写入者；**Depends:** B3。**Files:** `MainWindow.java`、相关 harness/EDT 测试、`DirectoryScopeDesktopHarness` 复用。

- [ ] 5.1 目标 RED：250ms Timer 防抖（连续输入仅最终词触发一次 `doSearch`）、程序化提交立即生效不防抖、`searchSequence` 失效路径复用。→ latch/定时可控测试先败。
- [ ] 5.2 实现 Swing Timer 防抖接线与代次复用；状态栏 `Results: N (showing first N of M)` 文案；空词/清空不发查询。→ 组件/harness GREEN。
- [ ] 5.3 同步 UsageGuide 桌面交互段。→ 文档核对。

**Verify:** `mvn -B "-Dtest=<桌面防抖/提示测试>" test`；GUI 依赖部分沿用 harness，实机部分留 M 记录。

## 6. B5 Web 一致与遗留清理

**Owner:** Devin；**Depends:** B3。**Files:** `SearchHandler.java`、`index.html`（截断提示展示）、`SearchHandlerTest`、`WebAssetsOfflineTest`；删除 `search/query/QueryBuilder.java`/`QueryExecutor.java`。

- [ ] 6.1 目标 RED：HTTP 子串命中、`total`/`truncated` 可选字段出现且既有字段不变、错误码映射不变。→ 定向失败。
- [ ] 6.2 实现 handler 走 `searchDetailed`、JSON 可选字段与页面截断提示。→ 定向 GREEN（含非英语 score、控制字符回归）。
- [ ] 6.3 删除遗留 `QueryBuilder`/`QueryExecutor`（D6），全仓搜索确认无引用。→ `mvn -B test` GREEN。

**Verify:** `mvn -B "-Dtest=SearchHandlerTest,WebAssetsOfflineTest" test`。

## 7. B6 集成与验收候选

**Owner:** Devin 自动集成；Codex 复验；用户验收。**Depends:** B2/B4/B5。

- [ ] 7.1 真实 indexer→service→HTTP 端到端：子串/多词/三档排序/截断/目录限定叠加。→ E2E GREEN。
- [ ] 7.2 `mvn -B clean verify`、真实 Java8 编译、strict validate、diff 空白核对、100 万文档观察数据归档（非门槛）。→ 证据 evidence/B6.md。
- [ ] 7.3 交实际 SHA/diff/逐 spec 场景证据，形成验收候选；M 类实机项与 A8 由 Codex/用户流程完成。→ 交付物清单。

## 8. 验收与交付（占位，同 A 治理）

- [ ] 8.1 用户明确接受并授权交付目标；记录实际版本与原始记录。
- [ ] 8.2 仅执行获授权的归档/提交/PR/推送；未授权部分保持未做。
