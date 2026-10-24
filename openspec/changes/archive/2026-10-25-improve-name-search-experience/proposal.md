# Proposal

> 执行状态更新：用户已要求直接推进既定功能实装、减少额外形式流程。当前零迁移B范围放行；此前“须再复核后方可实施”的文字为方案历史状态。新增索引字段/迁移仍需另行决定。


> 方案 0.3（2026-10-24）：按 Codex 独立复核 N02-01～N02-06 修订（见 [reviews/codex-0.2.md](reviews/codex-0.2.md)）；B-01/02/03 已被接受，B-04/05/08/09 在本版闭合。**仍为未批准草案**：须交 Claude 复核与用户批准后方可实施；当前实施任务 0/22，不得从 artifacts 齐备推导实施授权。

## Why

用户按名称中段（如 `report` 命中 `AnnualReport2026.PDF`）检索时经常找不到文件：`name` 字段经 StandardAnalyzer 分词后不含子串词项，`name_raw` 的前缀查询只覆盖开头，通配包含匹配目前仅对 FOLDER scope 生效，NAME/ALL 行为不一致。目录限定检索（A 阶段）已交付、主机验收与手工验收均已记录通过并归档；本 change 解决"按名定位"这一最高频检索体验。

## What Changes

- 普通字面查询（无高级语法标记）获得名称子串命中能力：**NAME/FOLDER 范围以"名称条件"为唯一命中条件；ALL 范围为"名称条件 OR 既有正文分支"至少其一成立；CONTENT 不加名称条件**（B-01）。名称条件内多个字面词全部命中同一名称才算名称命中，旧的 parsed OR 分支不得把只命中部分词的名称放回结果（B-02）。
- 命中排序可证明分档：完整名称 > 前缀 > 子串 > 仅正文；ALL 以"名称命中列表 + 仅正文命中列表（名称条件 MUST_NOT）"双列表结构合并——排序保证来自查询结构而非分数上界假设，高档结果在结构上不可能被 Top-N 截出（N02-01）。档位推导只依赖已存储的 `name`/`path`，不读取未存储的 `name_raw`（N02-02）。
- EXACT 模式冻结真实现状并写清契约：现状 = `name_raw` 整名 term 与 `name` 分词短语并列，ALL 还叠加正文短语——不是"严格整名相等"；本 change 不改变该行为，新增特征测试钉住现状（B-03）。若要改严格整名，属另一个产品决定。
- 桌面输入防抖 ~250ms，按三类事件分开定义（N02-04）：用户键入立即使在途失效 + Timer 延迟提交；明确提交动作（Enter/历史选择/范围过滤变更/Clear/quick action）取消 Timer 后恰好提交一次；程序化写入（初始化、历史回写、内部同步）只更新控件不提交，且历史回写保留草稿与光标。清空、开始索引、dispose 停止 Timer 与 worker（B-08）。
- 结果截断诚实提示：三态 `hasMore`（TRUE/FALSE/UNKNOWN；UNKNOWN 不伪装 FALSE，默认兼容实现返回 UNKNOWN；HTTP UNKNOWN 省略字段），实现取 limit+1 判定，桌面/Web 一致；**不提供精确总命中 M**（N02-03/B-05）。
- 输入分类规则显式化：单字符（按码点计）、Unicode 码点（含补充平面）、Unicode 空白、`.`/`-`/`_`/`+`/`'` 等符号、高级语法标记（`AND`/`OR`/`NOT` 仅作独立词法 token 才认）各自归类写进 spec，不静默漏查（B-06/N02-06）。词数 ≤16、单词 ≤64 码点超限走 INVALID_QUERY 固定文案。单 ASCII 字符默认保留字面子串能力并作为高命中性能样本进基准；若用户选择最小长度 2 限制，则以明确提示与取舍另批。
- 技术路线先实测后定论：默认 `name_raw` 通配（零索引变更）；完整定义性能门槛（固定硬件/JVM/heap/reader 状态/预热/样本量/端到端口径，10 万独立名称 + 长名/CJK/符号/高命中短词夹具，实测词项数；100ms 为本机拟定门槛非 SLA）。不达标 → 回设计评审；n-gram 仅作备选研究，启用须另交字段/覆盖检测/迁移/回退方案并经评审与用户批准，不自动开启（B-06/B-07）。

## Capabilities

### New Capabilities

- `name-search-experience`：名称子串/中段匹配规则、输入分类与多词语义、显式高级模式豁免、可证明命中分档、输入防抖与在途失效、截断提示（hasMore）、桌面/Web 一致性。

### Modified Capabilities

- `directory-scoped-search`（DS-03 条件组合与数量）：B 有意改变 FUZZY 字面查询的名称命中规则与排序语义——对 DS-03"合法关键词保持评分语义"构成授权下的兼容例外。spec delta 提供完整 MODIFIED 段；保留 FILTER 先于 limit、目录不改相对排序规则、CONTENT/EXACT/高级语法评分语义不变、响应结构与错误码不变。**当前不改主规格文件**，同步发生在 B 验收归档时（N02-05）。

## Impact

- 后台：`LuceneSearchService`（名称条件构造、ALL 双列表合并、limit+1 三态截断元数据）；`SearchService` 增加默认 `searchDetailed` 三态契约；`FakeSearchService` 跟随。`QueryBuilder`/`QueryExecutor` 遗留类仅在证明无生产引用后单独小步删除，不作功能前置。
- 桌面：`MainWindow` 输入防抖/在途失效接线与截断提示文案；`DirectoryScopePanel` 不改动。需修复验收中实测到的"done() 历史回写覆盖正在编辑文本"交互（纳入防抖设计约束，非独立缺陷单）。
- Web：`SearchHandler`/`index.html` 复用同一查询规则并增加可选 `hasMore`/`truncated` 输出，不新增端点、不新增必填字段。
- 兼容：EXACT、显式 `name:`/通配符/高级语法、目录限定、错误码映射、history 不记失败查询等既有契约不变；仅扩展 FUZZY 下名称命中范围。
- 依赖：沿用 Java 8 与 pom 现有版本；不新增依赖（n-gram 备选也仅用 Lucene 自带组件）。
- 验证：临时目录夹具（10 万/100 万观察）、单元/组件/HTTP/端到端测试、桌面 harness；验收边界见 acceptance.md 矩阵。

## Open decisions（待用户最终批准，Codex 建议已并入）

1. **EXACT**：按 Codex 建议冻结真实现状（整名 term + 分词短语 + ALL 叠正文），不冒充严格整名。→ 待用户确认。
2. **ALL 是否混入目录**：按 Codex 建议维持不混（ALL/NAME 为文件，FOLDER 为目录）。→ 待用户确认。
3. **分页/精确总数**：不做；仅 limit+1 可靠截断提示。→ 待用户确认。
4. **单 ASCII 字符子串**：默认保留（用户预期 Everything 式中段体验），以高命中短词夹具实测代价；若改最小长度 2 则由用户拍板并在 UI 明示限制。→ 待用户确认。
5. **实现路线**：name_raw 通配先行；n-gram 为门槛触发的备选研究，启用需另行批准。

## Non-goals

不改变 EXACT 与高级语法语义；不做分页与精确总命中；不做正文子串高亮改写；不做拼音/别名/模糊纠错；不引入外部检索服务；不扩展 watcher/poller 实时性承诺；不新增索引迁移以外的重建路径；旧 QueryBuilder/QueryExecutor 清理仅作独立小步。C 阶段索引生命周期与 D 阶段依赖现代化另行立项。

## Review boundary

文档包 0.3 / 2026-10-24。本轮授权仅为修订方案；实施未批准。A 阶段验收与归档已完成（含 Devin 补充主机验收记录）；B 的 Claude 复核与用户批准完成后才允许勾选实施任务。


## Codex R1补注

本0.3包包含Codex对同reader/来源快照、字面符号转义、排他query不进入Match解释、UNKNOWN省略以及排序证明前提的技术澄清（D10）。无新增产品选择，B代码仍未授权。给Claude的整体评审包以reviews/codex-0.3-r1.sha256为准，不冒充原2bd4999逐字节材料。
