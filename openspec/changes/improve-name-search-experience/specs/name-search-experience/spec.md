# Spec Delta

> 方案 0.3（2026-10-24）：排序分档改为"名称/仅正文双列表结构合并"（见设计 D4），修复 0.2 的分数上界假设与未存储字段依赖。未批准草案；spec 落地实施须经 Claude 复核 + 用户批准。

## Purpose

让用户按文件/目录名的任意中段输入稳定命中结果，并在桌面与 Web 上获得一致的名称匹配、可证明的命中分档与截断提示语义。

## ADDED Requirements

### Requirement: 名称子串匹配与范围语义

在 FUZZY 匹配模式下，普通字面查询（无高级语法标记）的名称条件 SHALL 定义为：名称按 Unicode 码点小写化后，依次包含全部字面词作为子串。

- `NAME`/`FOLDER` 范围 MUST 以名称条件为唯一命中条件；名称命中不得由只命中部分词的旧解析分支产生。
- `ALL` 范围 MUST 为"名称条件 OR 既有正文分支"至少一个成立，并以"名称命中列表 + 仅正文命中列表（名称条件 MUST_NOT）"双列表合并实现；正文命中与名称命中分开解释（Match 字段）。
- `CONTENT` 范围 MUST NOT 因名称命中产生结果，沿用既有正文路径。
- 目录限定过滤、`kind` 类型过滤与错误码契约不变，并以 FILTER 语义先于 Top-N 生效。

#### Scenario: 中段命中英文文件名

- **WHEN** 索引中存在 `AnnualReport2026.PDF`，用户输入 `report`
- **THEN** 该文件出现在名称命中结果中（NAME 与 ALL 均如此）

#### Scenario: ALL 范围正文命中不因名称分支丢失

- **WHEN** 索引中某文件正文含 `quarterly` 但名称不含该词，用户输入 `quarterly`
- **THEN** 该文件仍作为正文命中返回，Match 字段标记为正文命中

#### Scenario: 中段命中中日文名称

- **WHEN** 索引中存在名称含 `支付流程` 的文件，用户输入 `支付`
- **THEN** 该文件出现在名称命中结果中

#### Scenario: 大小写不敏感

- **WHEN** 索引中存在 `README.md`，用户输入 `readme` 或 `ReadMe`
- **THEN** 两种输入均能命中该文件

### Requirement: 输入分类与多词规则

系统 SHALL 按下表归类输入，行为显式定义、不静默漏查：

- 含高级语法标记（`:` 字段前缀、`*`、`?`、`"`、`(`、`)`、布尔算符）→ 走既有 parser，非法语法 → INVALID_QUERY 固定文案。
- trim 后为空 → 现有空查询行为。
- 字面词（含 `.` `-` `_` `+` `'`、CJK、补充平面码点）→ 字面子串比较。
- 单字符字面词 → 默认保留子串能力（并入高命中性能样本实测）；若用户批准最小长度方案，则此类词不启用子串档且 UI 明示限制。
- 字面词按空白切分；词数 >16 或单词长度 >64 字符 → INVALID_QUERY 固定文案。

多字面词 SHALL 以 AND 语义组合：全部词均按子串命中同一名称，该名称才算名称命中。

#### Scenario: 多词全部命中

- **WHEN** 索引中存在 `2020春节-task.txt`，用户输入 `2020 task`
- **THEN** 该文件命中（两个词均为其子串）

#### Scenario: 多词部分未命中不算名称命中

- **WHEN** 索引中存在 `2020春节-task.txt`，用户输入 `2020 other`
- **THEN** 该文件不因名称命中返回；NAME/FOLDER 下不得出现该结果

#### Scenario: 符号按字面处理

- **WHEN** 用户输入 `spring-core` 且不含高级语法标记
- **THEN** 按字面子串匹配，不产生语法错误

#### Scenario: 超长输入固定拒绝

- **WHEN** 用户输入的词数超过 16 或某词长度超过 64 字符
- **THEN** 返回 INVALID_QUERY 固定文案，不进入历史记录

### Requirement: 命中排序分档

系统 SHALL 对命中结果施加确定性档位：完整名称 > 前缀 > 子串 > 仅正文命中，排序保证由**查询结构**提供而非分数上界假设：

- ALL 范围以双列表求值——名称命中列表只含名称条件（DisjunctionMax 常量档，单词得分 ∈ {4,3,2}，多词为各词档常量之和），仅正文命中列表为"正文分支 MUST + 名称条件 MUST_NOT"；合并顺序为名称命中全部在前、仅正文命中补齐余量。
- 名称命中 MUST NOT 与仅正文命中在同一排序键下竞争，故高正文评分文档在结构上不可能越过任何名称命中，高档结果也不可能被 Top-N 截出列表。
- 名称列表内排序：分值降序 → 同分按名称长度升序 → 再按 path 字典序；仅依赖存储字段 `name`/`path`（`name_raw` 不存储、不作为排序输入）。
- 目录限定开/关不改变档位规则；两列表均叠加同套 FILTER。

#### Scenario: 完整名称优先于子串

- **WHEN** 索引中同时存在 `report.txt` 与 `AnnualReport2026.PDF`，用户输入 `report`
- **THEN** `report.txt` 排在 `AnnualReport2026.PDF` 之前

#### Scenario: 高正文频率不越过名称档

- **WHEN** 对抗夹具中某文档正文高频出现查询词（任意高 BM25）而另一文档仅名称子串命中
- **THEN** 名称命中文档排在高正文频率文档之前（由列表结构保证，与正文分值无关）

#### Scenario: limit 很小时高档不被挤出

- **WHEN** limit 远小于命中数，且存在完整名称命中
- **THEN** 返回结果含该完整名称命中，hasMore 如实标记

### Requirement: EXACT 与高级语法不变（现状冻结）

EXACT 模式与显式高级语法语义 SHALL 与现状一致；含高级语法标记的查询 MUST NOT 套用子串扩展。现状契约记录：EXACT+字面输入 = `name_raw` 整名 term 与 `name` 分词短语并列（ALL 叠加正文短语），即非严格整名相等；本 change 不改变该行为，新增特征测试钉住。

#### Scenario: name: 过滤器保持原语义

- **WHEN** 用户输入 `name:report`
- **THEN** 按现有名称过滤规则执行，不套用子串扩展

#### Scenario: 通配符字面词走现有通配路径

- **WHEN** 用户输入 `*.pdf`
- **THEN** 按现有通配规则按扩展名匹配，不触发子串扩展

#### Scenario: EXACT 现状语义钉住

- **WHEN** 用户选择 EXACT 并输入 `report`
- **THEN** 命中满足 `name_raw` 整名 term 或 `name` 分词短语的条目（现状特征，非严格整名断言）

### Requirement: 输入防抖与在途失效（桌面）

桌面端键入 SHALL 立即使在途请求失效（代次递增 + worker 取消），并以 ~250ms 单一 Timer 延迟提交；Enter/历史选择/范围或过滤变更/Clear/quick action/程序化 setText 等立即提交路径 MUST 取消挂起 Timer；清空、开始索引、窗口 dispose MUST 停止 Timer 与 worker。编辑期间的迟到结果与历史回写 MUST NOT 覆盖编辑器文本、结果表、预览或状态栏。

#### Scenario: 快速输入只保留最终状态

- **WHEN** 用户快速输入 `r` → `re` → `rep` → `repo` → `repor` → `report`
- **THEN** 结果表最终只呈现 `report` 的结果，中间代次的迟到结果被丢弃

#### Scenario: 每个动作恰好一次请求

- **WHEN** 用户连续触发"键入+Enter""键入+范围变更"等组合
- **THEN** 每个立即提交动作至多发出一次请求，且挂起 Timer 被取消不再补发

### Requirement: 截断提示（hasMore）

当命中数达到返回上限时，系统 SHALL 以 limit+1 探测返回可靠 `hasMore`（ALL 合并路径下为两列表实际取回数之和 > limit），桌面与 Web 一致显示"仅显示前 N 条"；MUST NOT 呈现精确总命中数（Lucene 默认计数可能是下界）。兼容实现可声明 hasMore unknown → UI 不显示截断提示也不虚构精确数。

#### Scenario: 达到上限显示提示

- **WHEN** 某查询命中数超过返回上限
- **THEN** 界面显示"仅显示前 N 条"，不显示精确总数

### Requirement: Web 端语义一致

Web 搜索接口 SHALL 复用同一名称匹配、分档与截断规则；`q`/`scope`/`mode`/`directory`/`ext`/`limit` 参数契约与错误码映射保持不变；响应可增加可选 `hasMore`/`truncated` 字段，不新增必填字段。

#### Scenario: HTTP 子串命中

- **WHEN** 调用 `GET /api/search?q=report` 且索引存在 `AnnualReport2026.PDF`
- **THEN** 返回体包含该结果，响应结构不新增必填字段

#### Scenario: 截断标记透出

- **WHEN** 命中数超过 limit
- **THEN** JSON 含 `hasMore=true`（或等价 truncated 标记），既有字段不受影响
