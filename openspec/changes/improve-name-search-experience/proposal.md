# Proposal

> 2026-10-24 Codex评审：CHANGES_REQUESTED。此为未批准的0.1草案，B-01～09见 [评审意见](review.md)；修订0.2并经Claude复核/用户批准前不得实施，当前0/22。


## Why

用户按名称中段（如 `report` 命中 `AnnualReport2026.PDF`）检索时经常找不到文件：`name` 字段经 StandardAnalyzer 分词后不含子串词项，`name_raw` 的前缀查询又只覆盖开头，通配符包含匹配目前仅对 FOLDER scope 生效，NAME/ALL 行为不一致。目录限定检索（A 阶段）实现与自动修复已交付，正式主机验收仍待补，本 change 解决"按名定位"这一最高频检索体验。

## What Changes

- 普通字面查询默认支持名称中段/子串匹配，NAME 与 ALL 的名称命中规则一致；显式高级语法（`name:`、通配符、字段语法）语义不变。
- 明确多词、大小写、单字符与符号（`.`/`-`/`_` 等）的匹配规则；中日文与英文文件名一致处理。
- 命中质量排序：完整名称 > 前缀 > 子串，同档内保持现有评分；目录与文件的类型语义保持清楚（默认 ALL 不混入目录，除非评审另有裁决）。
- 桌面输入防抖约 250ms 与过期请求取消（复用现有 token/sequence 机制）；达到结果上限时给出"仅显示前 N 条"提示，桌面与 Web 一致。
- 技术路线先实测后定论：先在 10 万元数据夹具上测 `name_raw` 通配包含的延迟与内存；达标则不新增字段，不达标再评估 n-gram 字段（含索引重建成本与回退说明），不引入外部服务。

## Capabilities

### New Capabilities

- `name-search-experience`：名称子串/中段匹配规则、多词与符号语义、显式高级模式、命中排序、输入防抖与取消、结果上限提示、桌面/Web 一致性。

### Modified Capabilities

无。主规格库为空；A 阶段 `directory-scoped-search` 规格仍在自身 change 内未归档，本 change 不改动其需求，仅复用其已交付接口（SearchRequest/LuceneSearchService/SearchHandler）。

## Impact

- 后台：`LuceneSearchService`（`name_raw` 通配/排序/上限提示元数据）、可能的 `QueryBuilder` 清理（注意：该类当前为遗留未接线代码，是否同步删除由设计决定）；若启用 n-gram 则涉及 `LuceneIndexer`/`DocumentBuilder` 与索引迁移策略。
- 桌面：`MainWindow` 查询提交路径的防抖/取消与结果上限提示文案；`DirectoryScopePanel` 不改动。
- Web：`SearchHandler`/`index.html` 复用同一查询规则与上限提示字段，不新增 API 端点。
- 兼容：EXACT 模式、显式 `name:`/通配符/高级语法、目录限定过滤、错误码契约保持不变；仅扩展 FUZZY 下的名称命中范围。
- 依赖：沿用 Java 8 与 pom 现有依赖版本；若选 n-gram 仅使用 Lucene 8.11.2 自带 `NGramTokenizer`/`EdgeNGramTokenFilter`，不新增依赖。
- 验证：临时 Lucene 夹具（含 10 万/100 万元数据性能观察）、单元/组件/HTTP/端到端测试、桌面 harness。

## Open decisions（评审裁决项）

1. **EXACT 定义**：建议保持"完整名称精确匹配"现状，子串只进 FUZZY；若需改为"不区分大小写整名"请评审明确。
2. **ALL 是否混入目录**：建议维持现状（ALL=文件+正文，目录归 FOLDER），保持类型语义清楚；若要求 ALL 也返回名称命中的目录，需评审确认并补交互。
3. **分页/总命中数**：建议不纳入本 change，仅做上限提示；排序微调（名称/前缀优先）纳入。
4. **子串实现路线**：`name_raw` 通配 vs 新增 n-gram 字段——以夹具实测门槛决定（阈值在设计中写明，未实测前不锁定实现）。

## Non-goals

不改变 EXACT 与高级语法语义、不做分页/总命中计数、不做正文子串高亮改写、不做拼音/别名/模糊纠错、不引入外部检索服务、不扩展 watcher/poller 实时性承诺、不新增索引迁移以外的重建路径。C 阶段索引生命周期与 D 阶段依赖现代化另行立项。

## Review boundary

文档包 0.1 / 2026-10-24。本轮授权为编写可评审方案，实施未批准；OpenSpec artifacts done 仅表示文件齐备，不能代替 delivery-status.yaml 引用的用户批准记录。A 阶段剩余验收（Codex 复验、M-01～06、A8）与本提案评审互不阻塞。
