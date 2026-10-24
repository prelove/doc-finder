# Spec Delta

> 2026-10-24 Codex评审：CHANGES_REQUESTED。此为未批准的0.1草案，B-01～09见 [评审意见](../../review.md)；修订0.2并经Claude复核/用户批准前不得实施，当前0/22。


## Purpose

让用户按文件/目录名的任意中段输入稳定命中结果，并在桌面与 Web 上获得一致的名称匹配、排序与结果上限语义。

## ADDED Requirements

### Requirement: 名称子串匹配

在 FUZZY 匹配模式下，系统 SHALL 将普通字面查询作为名称子串条件参与名称命中：输入连续字符序列时，文件或目录的名称中任意位置包含该序列（不区分大小写）即视为名称命中候选。

#### Scenario: 中段命中英文文件名

- **WHEN** 索引中存在 `AnnualReport2026.PDF`，用户输入 `report`
- **THEN** 该文件出现在名称命中结果中

#### Scenario: 中段命中中日文名称

- **WHEN** 索引中存在名称含 `支付流程` 的文件，用户输入 `支付`
- **THEN** 该文件出现在名称命中结果中

#### Scenario: 大小写不敏感

- **WHEN** 索引中存在 `README.md`，用户输入 `readme` 或 `ReadMe`
- **THEN** 两种输入均能命中该文件

### Requirement: 多词与符号规则

系统 SHALL 将空格分隔的多个字面词解释为名称条件：每个词均按子串规则命中同一名称时该名称才算名称命中；`.`、`-`、`_` 等符号按字面字符参与子串比较，不触发高级语法。

#### Scenario: 多词全部命中

- **WHEN** 索引中存在 `2020春节-task.txt`，用户输入 `2020 task`
- **THEN** 该文件命中（两个词均为其子串）

#### Scenario: 多词部分未命中

- **WHEN** 索引中存在 `2020春节-task.txt`，用户输入 `2020 other`
- **THEN** 该文件不因名称命中（`other` 不是其子串）

#### Scenario: 符号按字面处理

- **WHEN** 用户输入含 `.` 或 `-` 的字面词（如 `spring-core`），且不包含高级语法标记
- **THEN** 按字面子串匹配，不产生语法错误

### Requirement: 命中排序优先级

系统 SHALL 对名称命中结果施加稳定排序层级：完整名称相等 > 前缀命中 > 子串命中；同档内保持既有相关性评分；排序不得因目录限定开关而变化。

#### Scenario: 完整名称优先于子串

- **WHEN** 索引中同时存在 `report.txt` 与 `AnnualReport2026.PDF`，用户输入 `report`
- **THEN** `report.txt`（完整名称/前缀档）排在 `AnnualReport2026.PDF`（子串档）之前

### Requirement: 显式语法与匹配模式不变

显式高级语法（`name:` 过滤器、`*`/`?` 通配、字段语法、引号、括号）和 EXACT 匹配模式的语义 SHALL 与现状一致；含高级语法标记的查询 MUST NOT 被套用子串扩展规则。

#### Scenario: name: 过滤器保持原语义

- **WHEN** 用户输入 `name:report`
- **THEN** 按现有名称过滤规则执行，不扩展为子串查询之外的行为

#### Scenario: 通配符字面词走现有通配路径

- **WHEN** 用户输入 `*.pdf`
- **THEN** 按现有通配规则按扩展名匹配，不触发子串扩展

#### Scenario: EXACT 不启用子串

- **WHEN** 用户选择 EXACT 模式并输入 `report`
- **THEN** 仅命中完整名称等于 `report`（不区分大小写）的条目，不含 `AnnualReport2026.PDF`

### Requirement: 范围与类型语义一致

名称子串规则 SHALL 与 SearchScope 及目录限定兼容：FOLDER 范围对目录名应用同套子串规则；NAME/ALL 范围的文件命中规则一致；目录限定过滤与子串规则可叠加，错误码契约不变。

#### Scenario: 限定目录内子串命中

- **WHEN** 用户启用目录限定并输入名称中段
- **THEN** 仅返回该目录整树内同时满足名称子串与目录范围的结果

#### Scenario: 无效语法错误一致

- **WHEN** 用户输入非法高级语法
- **THEN** 无论是否启用目录限定均返回 INVALID_QUERY，不吞为空结果

### Requirement: 输入防抖与过期取消（桌面）

桌面端连续输入 SHALL 经防抖（约 250ms）后才提交查询；新查询发出后，先前未完成查询的结果 MUST NOT 写入结果表、预览或状态栏。

#### Scenario: 快速输入只保留最终状态

- **WHEN** 用户快速输入 `r` → `re` → `rep` → `repo` → `repor` → `report`
- **THEN** 结果表最终只呈现 `report` 的结果，中间代次的迟到结果被丢弃

### Requirement: 结果上限提示

当命中数达到返回上限时，系统 SHALL 在桌面与 Web 结果中以一致文案提示"仅显示前 N 条"；上限语义与现有 limit 参数一致。

#### Scenario: 达到上限显示提示

- **WHEN** 某查询命中数超过返回上限
- **THEN** 界面显示命中被截断的提示及其数值，而非静默省略

### Requirement: Web 端语义一致

Web 搜索接口 SHALL 复用同一名称匹配与排序规则；`q`/`scope`/`mode`/`directory`/`ext`/`limit` 参数契约与错误码映射保持不变。

#### Scenario: HTTP 子串命中

- **WHEN** 调用 `GET /api/search?q=report` 且索引存在 `AnnualReport2026.PDF`
- **THEN** 返回体包含该结果，响应结构不新增必填字段
