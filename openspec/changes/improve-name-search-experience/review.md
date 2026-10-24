# B 方案0.1：Codex产品/架构评审

日期：2026-10-24；代码基线 `0e8de6dcefe155fb7d8e938df332ea3d14942ca4`。范围：proposal/design/spec/tasks及实际查询/桌面调用链。结论：**CHANGES_REQUESTED，仅修订方案，暂不实施**。方向符合roadmap B；当前设计有3项P1语义缺陷及6项P2契约/证据缺口。A的修复与B的方案审批分别管理，不把代码已交付理解为A8已完成。

## 主要发现与修订要求

| ID | 级别 | 证据与影响 | 0.2要求 |
| --- | --- | --- | --- |
| B-01 | P1 | D2/tasks2.2在ALL仍`parsed MUST + nameGroup SHOULD`。AnnualReport2026.PDF若旧name分词与正文均不命中report，新增通配只有SHOULD，仍被MUST挡住，核心示例不能保证命中 | 普通字面ALL明确为“完整名称条件分支 OR 既有正文分支”，至少一个分支成立；NAME/FOLDER用名称分支；CONTENT不加名称条件。类型与目录FILTER仍先于Top-N |
| B-02 | P1 | NAME/FOLDER的`parsed OR group`保留旧parser的多词OR路径，能绕过新名称条件的词间AND | 普通字面名称分支自身必须满足全部词；不让旧parsed OR分支重新放入只含部分词的名称。正文命中与名称命中分开解释；高级查询保持旧路径 |
| B-03 | P1 | proposal称“保持现有完整名称EXACT”，spec又要求仅整名相等；实际buildExactQuery同时OR了name_raw整名与name分词短语，ALL还含正文短语。本轮特征测试证实NAME/EXACT下report可命中final report draft.txt | 冻结真实现状及特征测试，不把“现状不变”写成新整名契约。若要改成严格整名匹配，需要明确新产品决定与兼容说明，不暗改 |
| B-04 | P2 | boost3/2/1与parsed相关性评分叠加不能证明严格三档；多词逐tok整名与“完整输入等于完整文件名”也不同，正文命中相对顺序未定义 | 定义单词/多词各档及正文-only位置，采用能证明优先级的查询/排序方案；不得只对旧Top-N重排而漏掉高档结果。加入高正文评分/高词频对抗样本 |
| B-05 | P2 | 提案说不做总命中，D5/B3却引入精确total与“first N of M”；直接TopDocs.totalHits未处理relation，默认searcher只精确计数至阈值，不能承诺M精确 | 本期只做可靠hasMore/truncated（建议取limit+1），不提供精确M；若保留总量必须带relation/下界文案和成本评审。兼容默认实现的信息不足不能伪装精确total |
| B-06 | P2 | spec承诺任意连续序列，D3却禁止ASCII单字符子串；“非ASCII即CJK”不成立。D1“10万文档约数万独立名”也无样本证据，未覆盖高基数字典/单字高命中 | 明确单字符、Unicode码点、空白、符号和高级语法分类；不静默漏查。性能夹具同时覆盖10万独立名称、长名/CJK/符号/高命中短词，实测词项数。100ms为拟定本机门槛，固定硬件/JVM/冷暖/预热/样本/端到端开销后才审批 |
| B-07 | P2 | D1的2..3 n-gram配“查询整词TermQuery”不能命中report这类长度6词；CJK单字也不在2..3gram中。基准失败不能自动授权新的字段/迁移 | n-gram需拆查询gram并准确校验完整子串、多词/单字回退；只作备选研究。触发时重新给出字段、覆盖、迁移/回退方案，经评审与用户批准，不自动新增重建成本 |
| B-08 | P2 | D4只在timer到点doSearch失效旧代次，未定义250ms等待期旧结果、程序化setText引发重复timer、Enter/历史/范围/过滤立即提交和挂起timer冲突、索引/关闭时取消 | 规定编辑即失效在途请求，timer只延迟提交；所有立即提交取消挂起timer；清空、索引开始和dispose停止timer。加入确定性时序与“每动作一次请求”测试，不靠放宽sleep |
| B-09 | P2 | D1/proposal引用acceptance固定硬件，Review boundary引用delivery-status用户批准，但两个文件不存在；“词数截断或拒绝留实施期决定”也未形成可验契约 | 补验收矩阵、状态/批准边界、查询长度/词数超限行为和可复现实验记录；A M/A8未完成保持记录，B可并行修订/评审但不得从CLI artifacts done推导实施授权 |

B-05来源：[Lucene8.11.2 IndexSearcher](https://lucene.apache.org/core/8_11_2/core/org/apache/lucene/search/IndexSearcher.html)说明默认search只精确计数到1000并可能返回下界。B-06性能风险依据：[WildcardQuery](https://lucene.apache.org/core/8_11_2/core/org/apache/lucene/search/WildcardQuery.html)说明前导通配可能枚举大量词项且使用常量评分重写；不能据此推断固定规模必达100ms。

## 对四个开放项的产品建议（供0.2和用户最终批准）

1. **EXACT保留真实现状**：整名term+分词短语，按scope选择字段；不要把当前行为误称为严格整名。B只扩FUZZY字面名称路径。
2. **ALL不混目录**：保持ALL/NAME为文件，FOLDER为目录；CONTENT继续只看正文。
3. **不做分页/精确总数**：返回前N条及可靠hasMore/truncated，显示“仅显示前N条”；不足信息的兼容服务可声明unknown，不虚构总数。
4. **优先零迁移通配，但须实测**：10万基准覆盖真实服务入口/目录与类型过滤/命中解释/截断取样，100万记录观察；不达标先回设计评审，不能自行开启n-gram重建。

这四项是Codex评审建议，不冒充用户已经批准的新实现。单ASCII字符建议优先保留用户预期的字面子串能力并作为高命中性能样本；若改为最小长度限制，0.2必须明确提示与取舍，待用户接受。

## 允许推进的下一步

Devin仅修订0.2的proposal/design/spec/tasks及验收/状态材料，逐条回B-01～09；同步草案用户示例，不把未实现功能写进当前帮助。排序/分类/超限规则应有可直接执行的用例，不以“实现时调参”代替契约。然后交Claude复核和用户批准；批准前0/22实施任务保持未勾选。相关旧QueryBuilder/QueryExecutor若要清理，单独证明无生产引用并保留小差异，不能成为功能前置大重构。
