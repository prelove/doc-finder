# Design

> 方案 0.3（2026-10-24）：0.2 按 B-01～B-09 修订后，Codex 第二轮复核指出排序方案存在阻断缺陷——正文 BM25 无上界可越过常量档、被 Top-N 截掉的高档结果无法靠呈现层重排找回、呈现层推导依赖未存储的 `name_raw`。0.3 将分档改为**名称/正文双列表结构合并**（见 D4），不依赖分数上界假设。五项既定方向不变：冻结真实 EXACT、ALL 不混目录、不做分页或精确总数、保留单字符子串、通配先行。未批准草案；实施须经 Claude 复核 + 用户批准。

## Context

现有名称命中路径（`LuceneSearchService.buildUserQuery`/`shouldAddNamePrefix`）在 FUZZY 下对 `name`（StandardAnalyzer 分词）做解析查询，并对 ASCII 单词型输入追加 `name`/`name_raw` 前缀查询；仅 FOLDER 范围额外加 `name_raw` 的 `*term*` 通配。`name_raw` 是小写 StringField，天然支持 TermQuery/PrefixQuery/WildcardQuery。中段匹配缺失的原因：`AnnualReport2026.PDF` 分词为单一词项，子串无前缀锚点。

目录限定（A 阶段）已交付并归档；本 change 只叠加名称命中规则，不触碰目录契约。Web 端为按钮/回车触发，无输入即搜。

主机验收补充事实（计入设计约束）：搜索在途时 worker `done()` 的历史回写可把编辑器文本重置为旧查询词 —— 防抖设计必须保证编辑期不回写编辑器。

## Goals / Non-Goals

**Goals:**

- 字面输入的名称子串命中：NAME/FOLDER 走纯名称条件；ALL 为名称条件 OR 正文分支；CONTENT 不变。
- 命中分档可证明：完整名称 > 前缀 > 子串 > 仅正文；名称命中与仅正文命中分属两个查询列表，档位由查询结构保证而非分数上界假设；Top-N 在结构上不可能丢失高档名称结果。
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

- 档位定义（逐词，与"完整查询匹配整名"不混用）：`name_raw` 为**含扩展名**的完整名称小写串。对每词 tok：整名档 = `tok == name_raw` 全串相等（`report` 对 `report.txt` 为前缀档而非整名档）；前缀档 = `name_raw` 以 tok 开头；子串档 = `name_raw` 含 tok。每词构造三档子句：`BoostQuery(ConstantScoreQuery(TermQuery(name_raw,tok)),4)`、`BoostQuery(ConstantScoreQuery(PrefixQuery(name_raw,tok)),3)`、`BoostQuery(ConstantScoreQuery(WildcardQuery(name_raw,*tok*)),2)`，经 `DisjunctionMaxQuery(tieBreaker=0)` 输出该词最高档常量分 ∈ {4,3,2}。
- 多词：各词的名称子句组间 **AND**（全部词子串命中同一名称才算名称命中），词序无关、原输入空白仅作分隔；文档名称分 = 各词档常量之和（如 4+2=整名+子串），故分值精确编码档位构成；文档展示档位取各词最低档（最弱词决定档级）。
- 范围映射（B-01/B-02）：
  - `NAME`/`FOLDER`：根查询 = 名称条件 MUST（+ `kind` FILTER）。**名称命中必须由名称条件满足全部词**；旧 parsed 的 name 多词 OR 路径不再进入名称命中（正文命中与名称命中分开解释；FOLDER 原本就只查名称）。
  - `CONTENT`：根查询 = 既有 parsed 正文路径，不变。
  - `ALL`：不按单查询 OR 拼接，改走 D4 的双列表合并；语义等价"名称条件 OR 既有正文分支至少一个成立"，但结构上分列求值。
- 目录限定与 `kind` 仍以 FILTER 子句叠加，先于 Top-N 生效。
- 高级语法（含 `name:`/`*`/`?`/`"`/括号/字段前缀/布尔算符标记的输入）与 EXACT 模式完全沿用旧解析路径，不套用本子串扩展。

### D3 输入分类规则（显式，不静默漏查）

| 输入类别 | 判定 | 行为 |
| --- | --- | --- |
| 高级语法标记 | 含 `:` 字段前缀、`*`、`?`、`"`、`(`、`)`；或 `AND`/`OR`/`NOT` 作为**独立词法 token**（按 Unicode 空白切分后整词相等才认，大小写按现状 parser）——`band`/`SANDY` 等含子串的名称不误判 | 走既有 parser；非法语法 → INVALID_QUERY 固定文案 |
| 空白/空串 | 按 Unicode 空白（`Character.isWhitespace`/isSpaceChar）trim 后为空 | 现有空查询行为（清空结果，不发请求） |
| 字面词（含 `.` `-` `_` `+` `'`、CJK、补充平面码点） | 无高级标记 | 按 Unicode 码点逐字面子串比较（小写化 `Locale.ROOT`） |
| 单字符字面词 | 长度=1 码点（任意书写系统） | 默认保留子串能力；列入高命中性能样本；若用户选最小长度 2 方案则此类词不走子串档并给出 UI 明示 |

约束：字面词按 Unicode 空白切分；词数上限 16、单词长度上限 64 **码点**（`String.codePointCount`，非 UTF-16 char——补充平面字符算 1），超限 → INVALID_QUERY 固定文案（可验收契约，非实现期再定）。UI 截断/错误提示跟随界面语言：英文 UI 用英文文案（如 "Showing first N results"），中文表述仅作语义说明。

### D4 命中分档：名称/正文双列表结构合并（可证明，不依赖分数上界）

0.2 的单查询常量+BM25 叠加被否：正文 BM25 无上界，可越过常量档；Top-N 截掉的高档名称结果呈现层无法找回；且 `name_raw` 为 `Store.NO`（DocumentBuilder 实测），呈现层无法按其推导档位。0.3 改为**双列表结构合并**：

- **列表 1（名称命中）**：仅 `名称条件` + `kind`/`path_scope` FILTER，取 `limit+1`。该查询只含 `DisjunctionMax` 常量档（单词文档得分 ∈ {4,3,2}，多词为各词档常量之和）——**正文/解析分完全不参与此列表**，故"名称命中排在仅正文命中之前"由查询结构保证，无需任何分数上界假设。
- **列表 2（仅正文命中）**：`parsed 正文分支 MUST + 名称条件 MUST_NOT`（+ 同套 FILTER），取 `limit+1`，保留自然 BM25 序。`MUST_NOT` 使两列表不相交，名称+正文双命中文档只进列表 1。
- **合并**：列表 1 全量（截断至 limit+1）→ 列表 2 补齐余量；hasMore = (两列表实际取回数之和 > limit)。Top-N 分别作用于两列表内部：列表 1 内高档不可能被低档挤出（分值即档常量之和，天然单调）；列表 2 内纯 BM25。
- **名称列表内呈现序**：分值降序（= 档常量之和降序）→ 同分按存储字段 `name` 长度升序（短名优先，子串占比更大）→ 再按 `path` 字典序。仅依赖已物化的 `SearchResult` 名称/路径（`name`/`path` 均 `Store.YES`），不读取 `name_raw`。**边界诚实声明**：同档常量分（同 tier-sum）文档在 Top-N 边界处的入选集合由 Lucene 内部序决定——按长度/path 的 tie-break 只对已取回切片排序，不构成全局同分入选承诺；跨档序（整名>前缀>子串>仅正文）才是结构性保证，被截断时由 hasMore 标记。
- **NAME/FOLDER/CONTENT**：单查询即可（NAME/FOLDER 仅列表 1 结构 + `kind` FILTER；CONTENT 沿用旧路径），无合并成本。
- **ALL 代价**：两次搜索/查询（名称 + 排他正文），成本进 B0 基准门槛口径；EXACT/高级语法路径不变仍为单次。
- **Match 字段**：对最终返回的 ≤limit+1 文档按文档求 `explain`（名称条件 / 正文分支分别判定）得到 `name`/`content`/`name+content`，保证真实反映命中来源。

### D5 截断提示：limit+1 可靠 hasMore（三态），不提供精确总数

`SearchService` 新增默认 `searchDetailed(SearchRequest) → SearchOutcome{results, hasMore}`，`hasMore` 为三态 `TriState{TRUE,FALSE,UNKNOWN}`：FALSE=已知无更多（非"不确定"），UNKNOWN=兼容实现信息不足，**UNKNOWN 不得伪装 FALSE**。默认实现包装 `search()` 返回 `UNKNOWN`（结果数 ≤ limit 不足以证明无更多）；`LuceneSearchService` 覆写为取 `limit+1` 判定：取回数 > limit → TRUE 并截断至 limit，否则 FALSE（含恰好=limit、0 结果、FILTER/删除位后命中）。不使用 `TopDocs.totalHits` 作精确 M（默认计数到阈值即止，可能是下界）。桌面文案 `Results: N` → TRUE 时 `Showing first N results`（英文 UI 文案）；UNKNOWN → 不显示截断提示也不声称完整。Web JSON 增加可选 `hasMore`/`truncated`：`UNKNOWN` 时**省略该字段**（或显式 null）；`hasMore` 与 `truncated` 若同时输出 MUST 一致；既有字段与必填结构不变。HTTP 上限：limit 参数超服务端最大值按现有 clamp/校验行为处理，hasMore 判定基于生效 limit。

### D6 桌面防抖与失效：三类输入事件分开定义

- **类 A 用户键入**（DocumentListener 源于真实编辑）：**立即** `searchSequence++` 并取消 `activeSearchWorker`（在途即刻失效，迟到结果不落表/预览/状态栏）；重启单一 `javax.swing.Timer`（250ms、非 coalesce），到点走 `doSearch()`。
- **类 B 明确提交动作**（Enter、历史下拉选择、scope/mode/目录限定变更、Clear、quick action、Apply filters）：取消挂起 Timer 后恰好一次 `doSearch()`。
- **类 C 程序化写入**（初始化、`addToHistory` 的 `setQueryText` 回写、历史模型刷新、内部同步恢复）：**只更新控件不提交**——经显式 guard/非通知路径写文本，不触发 DocumentListener→搜索链路；历史回写保留用户当前草稿与光标位置，不得覆盖编辑期输入（验收实测回写缺陷按此修复）。
- 清空输入、开始索引（isIndexing）、窗口 dispose：停 Timer + 取消 worker。
- 测试：确定性时序（注入可控 Timer/时钟）+ "每个动作恰好一次请求" + "类 C 写入零请求"断言，不靠放宽 sleep 放行。

### D7 遗留 `search.query.QueryBuilder`/`QueryExecutor`

未接线旧实现。本 change 实施期先用全仓引用搜索证明无生产引用，再以小步独立删除/标注，不并入功能改动主体。

### D8 EXACT 现状冻结与特征钉住

现状（实测）：EXACT+非高级输入时 `buildExactQuery` 并列 `name_raw` 整名 term 与 `name` 分词短语（`report` 在 NAME/EXACT 可命中 `final report draft.txt`）；ALL 还叠加正文短语。本 change 不改此行为：spec 记录真实现状并新增特征测试钉住；任何"严格整名"改造属另一个产品决定，不暗改。

### D9 ALL 范围类型语义

维持现状：ALL/NAME 只返回文件，FOLDER 只返回目录；CONTENT 只看正文。

## Risks / Trade-offs

- [前导 `*` 通配扫描词项集] → D1 完整门槛定义 + 高命中短词样本实测；不达标回评审，不自动迁移。
- [ALL 双查询成本翻倍] → 列表 2 仅排他正文 + 各取 limit+1，开销进 B0 门槛实测；EXACT/高级路径不受影响。
- [防抖少搜观感/回写冲突] → D6 立即失效 + Timer 只延迟提交 + 编辑期不回写约束。
- [`searchDetailed` 兼容实现信息不足] → 默认 `hasMore=false`/unknown 声明，不伪装精确（B-05）。
- [n-gram 分支若批准将要求全量重建] → 覆盖检测复用 `path_scope` leaf 统计模式（泛化字段参数），方案另审。
- [`detectMatchType` 解释] → 合并后按返回文档 `explain` 名称/正文分支判定，Match 字段如实反映来源；不按 `name_raw` 在场与否推断。

## Migration Plan

方案 A：无迁移，滚动即用。方案 B（仅批准触发）：新增字段 → Index All 补字段 → 兜底 Full Rebuild + `name_ngram` 覆盖检查，流程同 A 阶段契约；独立提交评审。

## Open Questions

- 100 万文档观察是否改变"10 万做门槛"——实施期记录，不改 spec。
- n-gram gram 区间与索引体积——仅方案 B 激活时定。
