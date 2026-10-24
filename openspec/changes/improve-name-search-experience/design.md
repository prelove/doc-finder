# Design

## Context

现有名称命中路径（`LuceneSearchService.buildUserQuery`/`shouldAddNamePrefix`）在 FUZZY 下对 `name`（StandardAnalyzer 分词）做解析查询，并对 ASCII 单词型输入追加 `name`/`name_raw` 前缀查询；仅 FOLDER 范围额外加 `name_raw` 的 `*term*` 通配。`name_raw` 是小写 StringField，天然支持 TermQuery/PrefixQuery/WildcardQuery。中段匹配缺失的原因：`AnnualReport2026.PDF` 分词为单一词项，子串无前缀锚点。

目录限定（A 阶段）已交付 `SearchRequest.directoryPath`、FILTER 级 `path_scope` 过滤与统一错误码；本 change 只叠加名称命中规则，不触碰目录契约。Web 端搜索为按钮/回车触发，无输入即搜。

## Goals / Non-Goals

**Goals:**

- 字面输入的名称子串命中（files+folders，NAME/ALL/FOLDER 一致规则）。
- 命中排序三档：完整名称 > 前缀 > 子串，以打分层级实现而非二次排序。
- 桌面 ~250ms 输入防抖 + 代次失效；结果上限截断提示（桌面/Web 一致）。
- 性能门槛驱动实现选择，避免无谓的索引字段/迁移成本。

**Non-Goals:**

- EXACT、显式高级语法、目录限定契约、错误码不改。
- 不引入新字段/依赖为默认方案；n-gram 仅在基准未达标时进入第二方案。
- Web 不新增端点、不做输入即搜；C/D 阶段事项不纳入。

## Decisions

### D1 子串实现：默认 `name_raw` 通配，n-gram 为基准触发的备选

方案 A（默认）：对字面词组构造 `name_raw` WildcardQuery（`*tok*`）。Lucene 将通配编译为自动机；前导 `*` 需全词项扫描，但名称词项规模有限（10 万文档约数万独立名称词项），预期远低于交互阈值。零索引变更、零迁移。

方案 B（备选）：新增 `name_ngram` 字段（`NGramTokenizer` 2..3，索引侧展开、查询侧整词 TermQuery）。代价：名称字段词项数倍增长、必须全量重建索引、并需要类 `path_scope` 的覆盖门槛防止新旧混合。

**门槛（Gate）：** 在 100,000 文档夹具上测方案 A：FUZZY 名称查询 p95 ≤ 100ms（ warmed searcher，含目录限定与 ALL/NAME 两 scope）则采用方案 A；任一不达标进入方案 B 评审分支。100 万文档夹具仅作观察记录，不作通过门槛（硬件基线在 acceptance 固定）。备选：把 n-gram 常驻字段与按需字段两种迁移形态都列入，但默认不建。

### D2 查询构造：字面词组 → 三档打分组，作为名称替代/增强

对无高级语法的字面输入，按空白切词；每词构造三档子查询：

- `TermQuery(name_raw, tok)` boost 3.0（完整名称）
- `PrefixQuery(name_raw, tok)` boost 2.0（前缀）
- `WildcardQuery(name_raw, "*tok*")` boost 1.0（子串）

单词 → 三档 OR；多词 → 每词的 OR 组间 AND（全部命中才算名称命中）。组与既有 parsed 查询的关系沿用当前骨架：NAME/FOLDER 为 `(parsed OR group) MUST`；ALL/CONTENT 为 `parsed MUST + group SHOULD`（不剥夺正文命中）。boost 层级天然产生"完整>前缀>子串"排序，同档保留既有评分——不新增排序通道。

备选考虑过：二级 sort 字段（需改结果模型与 UI 排序假设，放弃）；`ConstantScore` 分档布尔（丢失子档内评分差异，放弃）。

### D3 词粒度门槛：ASCII 子串最小 2 字符，CJK 允许 1 字符

单 ASCII 字符 `*a*` 近乎全扫且噪声大——子串档仅对 `len≥2` 的 ASCII 词与 `len≥1` 的非 ASCII（CJK）词启用；不满足的词仍参与分词查询与前缀档。多词 AND 语义不变。理由：平衡 Everything 式中段体验与误报率，阈值写入 acceptance 便于评审调参。

### D4 桌面防抖与失效：Swing Timer 250ms + 既有代次

`queryBox` 编辑器每次变更重启单一 `javax.swing.Timer`（非 coalesce 语义），到点统一走 `doSearch()`；提交仍走 `searchSequence`+worker 取消，保证迟到结果不落表。不新增队列框架。Browse/Clear/scope 变化等程序化提交不经过防抖（立即生效），防抖只作用于键入路径。

备选：ScheduledExecutorService（与 Swing EDT 语义脱节，放弃）。

### D5 结果上限提示：`searchDetailed` 返回命中总量

`SearchService` 新增默认方法 `searchDetailed(SearchRequest) → SearchOutcome{results,totalHits}`；默认实现包装 `search()` 返回 `totalHits=results.size()`，`LuceneSearchService` 覆写返回 `TopDocs.totalHits`。桌面状态栏 `Results: N` → `Results: N (showing first N of M)` 等价文案；Web JSON 增加可选 `total`/`truncated` 字段（现有字段与结构不变，不新增必填）。

备选：改 `search()` 返回类型（破坏 `SearchService`/`FakeSearchService`/web/desktop 全部调用方，放弃）；把 totalHits 塞进 `SearchResult`（语义错位，放弃）。

### D6 遗留 `search.query.QueryBuilder`/`QueryExecutor`

两文件为未接线旧实现（服务内联了等价逻辑）。本 change 实施时先删除，避免规则双写漂移——与此前 `ui.workers` 清理同一原则；若评审认为需保留则标注 deprecated 并保证不接线。

### D7 ALL 范围目录混入

维持现状（ALL = `kind!=folder`，目录仅 FOLDER 范围返回）。已在 proposal Open decisions 列出，评审若改判则只动 `search()` 中 MUST_NOT 分支与 spec 对应场景，不动查询骨架。

## Risks / Trade-offs

- [前导 `*` 通配在大词项集上扫描慢] → D1 门槛未过则落 n-gram 分支；基准夹具代码先行。
- [多词 AND × 三档 OR 使布尔子句膨胀] → 词数上限沿用现有 parser 行为，超长子串词截断或拒绝由实现期测试定边界。
- [防抖引入"少搜一次"观感] → 250ms 与 roadmap 对齐，作为常量便于调；程序化路径立即提交不受影响。
- [`totalHits` 与 FILTER/删除位语义] → 直接用 Lucene `totalHits`（含过滤器后的真实计数），不写估算逻辑。
- [n-gram 分支若启用将要求全量重建] → 沿用 `path_scope` 覆盖门槛模式检测缺字段，错误码复用 INDEX_REBUILD_REQUIRED。
- [`detectMatchType` 解释路径] → 子串命中仍落在 `name_raw`，现有 Explanation walk 已按 `name_raw:` 标记 name 命中，无需改。

## Migration Plan

方案 A：无迁移，滚动发布即用。方案 B（仅基准触发）：新增字段→提示运行 Index All Sources 补字段→兜底 Full Rebuild，流程与 A 阶段既有契约相同；新增 `name_ngram` 覆盖检查可复用 `ensureDirectoryScopeSupported` 的 leaf 统计+精确回退模式（泛化为字段参数）。

## Open Questions

- 100 万文档观察数据是否会改变"仅 10 万做门槛"的决定——实施期记录，不改 spec。
- n-gram gram 区间（2..2 vs 2..3）与索引体积权衡——仅方案 B 激活时才需定。
