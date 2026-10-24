# Design

> 方案 0.2（2026-10-24）：按 Codex 评审 B-01～B-09 逐条修订（见 [review.md](review.md)）。未批准草案；实施须经 Claude 复核 + 用户批准。

## Context

现有名称命中路径（`LuceneSearchService.buildUserQuery`/`shouldAddNamePrefix`）在 FUZZY 下对 `name`（StandardAnalyzer 分词）做解析查询，并对 ASCII 单词型输入追加 `name`/`name_raw` 前缀查询；仅 FOLDER 范围额外加 `name_raw` 的 `*term*` 通配。`name_raw` 是小写 StringField，天然支持 TermQuery/PrefixQuery/WildcardQuery。中段匹配缺失的原因：`AnnualReport2026.PDF` 分词为单一词项，子串无前缀锚点。

目录限定（A 阶段）已交付并归档；本 change 只叠加名称命中规则，不触碰目录契约。Web 端为按钮/回车触发，无输入即搜。

主机验收补充事实（计入设计约束）：搜索在途时 worker `done()` 的历史回写可把编辑器文本重置为旧查询词 —— 防抖设计必须保证编辑期不回写编辑器。

## Goals / Non-Goals

**Goals:**

- 字面输入的名称子串命中：NAME/FOLDER 走纯名称条件；ALL 为名称条件 OR 正文分支；CONTENT 不变。
- 命中分档可证明：完整名称 > 前缀 > 子串 > 仅正文；档位在查询侧保证候选不被低档挤出 Top-N，呈现序确定性分档。
- 桌面 ~250ms 防抖：编辑即失效在途请求、Timer 只延迟提交、立即提交取消挂起 Timer。
- 截断提示：limit+1 可靠 hasMore，桌面/Web 一致，不虚构精确总数。
- 性能门槛实测驱动实现选择，不引入迁移为默认。

**Non-Goals:**

- EXACT、显式高级语法、目录限定契约、错误码不改（EXACT 真实现状冻结见 D8）。
- n-gram 仅备选研究；触发须另行评审与用户批准。
- Web 不新增端点、不做输入即搜；C/D 阶段不纳入。

## Decisions

### D1 子串实现：默认 `name_raw` 通配，n-gram 为门槛触发的备选研究

方案 A（默认）：字面词 → `name_raw` WildcardQuery `*tok*`。前导 `*` 需全词项扫描，属常量评分重写；零索引变更、零迁移。

方案 B（备选研究，不在本 change 实施授权内）：`name_ngram`（NGramTokenizer 2..3 索引侧展开 + 查询侧逐 gram 分解后用原始 `name_raw` 做准确子串校验——Lucene 的 n-gram 只保证"候选"不含完整子串断言；多词/单字回退路径须另定义）。激活条件：方案 A 基准未达标 → 回到设计评审，提交字段定义/新旧索引覆盖检测/迁移与回退成本，经评审与用户批准，不自动开启重建。

**门槛定义（B-06，须先于实施批准固定）：** 固定记录 CPU/RAM/磁盘类型/JVM 版本与 `-Xmx`/reader 预热状态；夹具=10 万独立名称（英文/中日文/符号/长名/高命中短词如单字符 `a`、常见词 `report`），记录实际 `name_raw` 词项数；预热后 ≥100 次取样计 p50/p95/错误数；冷启动首次单列；端到端口径含目录 FILTER 与 ALL/NAME 两 scope；同步测 100 万文档仅作观察记录。拟定本机门槛 p95≤100ms，不是面向所有用户硬件的 SLA。

### D2 查询构造：字面词 → 名称条件（词间 AND、词内三档）

输入分类先行（D3）；仅"普通字面 + FUZZY"走本路径。

- 每词构造三档名称子句：`TermQuery(name_raw,tok)`、`PrefixQuery(name_raw,tok)`、`WildcardQuery(name_raw,*tok*)`，经 `DisjunctionMaxQuery(tieBreaker=0)` 取最高档常量分（见 D4 排序）。
- 多词：各词的名称子句组间 **AND**（全部词子串命中同一名称才算名称命中）。
- 范围映射（B-01/B-02）：
  - `NAME`/`FOLDER`：根查询 = 名称条件 MUST（+ `kind` FILTER）。**名称命中必须由名称条件满足全部词**；旧 parsed 的 name 多词 OR 路径不再进入名称命中（正文命中与名称命中分开解释；FOLDER 原本就只查名称）。
  - `CONTENT`：根查询 = 既有 parsed 正文路径，不变。
  - `ALL`：根查询 = `(名称条件 OR 既有 parsed 正文分支)` 两个 SHOULD、minShouldMatch=1；即"完整名称条件分支或既有正文分支至少一个成立"。
- 目录限定与 `kind` 仍以 FILTER 子句叠加，先于 Top-N 生效。
- 高级语法（含 `name:`/`*`/`?`/`"`/括号/字段前缀/布尔算符标记的输入）与 EXACT 模式完全沿用旧解析路径，不套用本子串扩展。

### D3 输入分类规则（显式，不静默漏查）

| 输入类别 | 判定 | 行为 |
| --- | --- | --- |
| 高级语法标记 | 含 `:` 字段前缀、`*`、`?`、`"`、`(`、`)`、`AND`/`OR`/`NOT`（大小写敏感按现状 parser 行为） | 走既有 parser；非法语法 → INVALID_QUERY 固定文案 |
| 空白/空串 | trim 后为空 | 现有空查询行为（清空结果，不发请求） |
| 字面词（含 `.` `-` `_` `+` `'`、CJK、补充平面码点） | 无高级标记 | 按 Unicode 码点逐字面子串比较（小写化 `Locale.ROOT`） |
| 单字符字面词 | len=1（任意书写系统） | 默认保留子串能力；列入高命中性能样本；若用户选最小长度 2 方案则此类词不走子串档并给出 UI 明示 |

约束：字面词按空白切分；词数上限 16、单词长度上限 64 字符，超限 → INVALID_QUERY 固定文案（可验收契约，非实现期再定）。

### D4 命中分档：常量分档 + 呈现层确定性重排

- 档位常量：`DisjunctionMaxQuery` 输出 `exact=4.0 / prefix=3.0 / substring=2.0`（`ConstantScoreQuery` 包装，档内不吃 tf/idf）；正文分支保留自然 BM25 分。
- 呈现序（结果落地前按存储字段 `name_raw` 实测档位确定性 stable-sort）：
  1. 完整名称档 → 2. 前缀档 → 3. 子串档 → 4. 仅正文命中；
  同档内按 Lucene 总分降序；同分按 `path` 字典序 tie-break。
- Top-N 不漏高档：档位常量量级（≥2.0，间隔 1.0）确保名称命中在候选集中天然高于一般正文命中；高正文评分/高词频对抗样本进基准夹具实测验证无越档（若实测出现越档，记录并按"呈现层重排仍保证高档优先于低档展示"的规格语义交付——排序契约定义的是命中分类序，不承诺跨类别的绝对分数隔离）。
- 目录限定开/关不改变档位规则。

### D5 截断提示：limit+1 可靠 hasMore，不提供精确总数

`SearchService` 新增默认 `searchDetailed(SearchRequest) → SearchOutcome{results, hasMore}`；默认实现包装 `search()` 返回 `hasMore=false`（兼容实现可声明 unknown）；`LuceneSearchService` 覆写为取 `limit+1` 条判定并截断。不使用 `TopDocs.totalHits` 作精确 M（默认计数到阈值即止，可能是下界）。桌面文案 `Results: N` → 截断时 `仅显示前 N 条`；Web JSON 增加可选 `hasMore`/`truncated` 字段（既有字段与必填结构不变）。

### D6 桌面防抖与失效

- 键入事件：**立即** `searchSequence++` 并取消 `activeSearchWorker`（在途请求即刻失效，迟到结果不落表/预览/状态栏）；重启单一 `javax.swing.Timer`（250ms、非 coalesce），到点走 `doSearch()`。
- 立即提交路径（Enter、历史下拉选择、scope/mode/目录限定变更、Clear、quick action、程序化 `setQueryText`/`setText`、Apply filters）**取消挂起 Timer** 并立即 `doSearch()`。
- 清空输入、开始索引（isIndexing）、窗口 dispose：停 Timer + 取消 worker。
- 约束新增：`done()`/历史回写不得覆盖编辑期输入文本（验收实测到的回滚行为须被防抖窗口隔离）。
- 测试：确定性时序（注入可控 Timer/时钟）+ "每个动作恰好一次请求"断言，不靠放宽 sleep 放行。

### D7 遗留 `search.query.QueryBuilder`/`QueryExecutor`

未接线旧实现。本 change 实施期先用全仓引用搜索证明无生产引用，再以小步独立删除/标注，不并入功能改动主体。

### D8 EXACT 现状冻结与特征钉住

现状（实测）：EXACT+非高级输入时 `buildExactQuery` 并列 `name_raw` 整名 term 与 `name` 分词短语（`report` 在 NAME/EXACT 可命中 `final report draft.txt`）；ALL 还叠加正文短语。本 change 不改此行为：spec 记录真实现状并新增特征测试钉住；任何"严格整名"改造属另一个产品决定，不暗改。

### D9 ALL 范围类型语义

维持现状：ALL/NAME 只返回文件，FOLDER 只返回目录；CONTENT 只看正文。

## Risks / Trade-offs

- [前导 `*` 通配扫描词项集] → D1 完整门槛定义 + 高命中短词样本实测；不达标回评审，不自动迁移。
- [常量档与正文分数越档] → D4 对抗样本实测；越档时按定义语义交付并记录。
- [防抖少搜观感/回写冲突] → D6 立即失效 + Timer 只延迟提交 + 编辑期不回写约束。
- [`searchDetailed` 兼容实现信息不足] → 默认 `hasMore=false`/unknown 声明，不伪装精确（B-05）。
- [n-gram 分支若批准将要求全量重建] → 覆盖检测复用 `path_scope` leaf 统计模式（泛化字段参数），方案另审。
- [`detectMatchType` 解释] → 子串命中落 `name_raw`，现有 Explanation 已标记 name，不改。

## Migration Plan

方案 A：无迁移，滚动即用。方案 B（仅批准触发）：新增字段 → Index All 补字段 → 兜底 Full Rebuild + `name_ngram` 覆盖检查，流程同 A 阶段契约；独立提交评审。

## Open Questions

- 100 万文档观察是否改变"10 万做门槛"——实施期记录，不改 spec。
- n-gram gram 区间与索引体积——仅方案 B 激活时定。
