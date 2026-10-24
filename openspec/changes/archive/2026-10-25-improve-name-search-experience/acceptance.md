# B验收矩阵与证据要求

**当前状态（2026-10-25）**：用户明确确认当前版本试用通过，25/25关闭，BQA-01/02/03全部关闭；已同步主规格并归档。自动验证范围见 [复验记录](evidence/Codex-implementation-review.md)。

用户原话：“当前版本试用通过，ok,，请更新并推送。”

接受对象：代码 `2c47ab2490f87e4c7124c7979bf2fec645049943`，JAR `E:\idea_projects\doc-finder\target\docfinder-1.0.0.jar`，SHA256 `C5740ED4AF7DF04FC77561461EAAEDF645E8C4702C56774227B48CC07421CB82`。用户接受与自动验证分别记录；本轮仅完成记录、规格同步、归档和功能分支推送，不改变产品二进制。

| 主题 | 最低证据 |
| --- | --- |
| 字面与范围 | NAME/ALL命中只有name_raw中段存在的AnnualReport2026.PDF；空正文负例；CONTENT不能因名称命中；FOLDER/目录自身/兄弟边界/Top-N前过滤 |
| 多词与符号 | 全部词命中同一名称；旧parsed OR不能放入缺词名称；C++、spring-core、点/下划线、空白、单引号、CJK/ASCII单字、补充平面码点；规则必须逐项明确 |
| 兼容 | 当前EXACT的整名/分词短语及正文行为特征测试；name:/通配/引号/括号/非法语法/全局限定错误码一致；Match字段准确；DS-03兼容例外：仅FUZZY字面查询的名称规则与排序被授权改变，CONTENT/EXACT/高级语法评分与FILTER先于limit不变 |
| 排序 | 整名/前缀/子串的单词和多词明确分档（整名=含扩展名全串相等）；高正文分数/词频对抗；**limit=1/2 且高档名称结果数超过取样窗口**；名称+正文双命中不重复进正文列；同分tie-break只承诺切片内不承诺全局入选 |
| 上限 | 0、limit-1、limit、limit+1、远大于limit及删除文档；hasMore三态（FALSE=已知无更多、UNKNOWN不得伪装FALSE、HTTP UNKNOWN省略字段、hasMore与truncated一致、生效limit含clamp）；HTTP旧字段兼容，错误码不变 |
| 防抖 | 三类事件分离：键入失效+防抖；Enter/历史/范围/Clear/过滤各一次不重复；**程序化写入（addToHistory回写/初始化）零请求且保留草稿光标**；清空/索引/关闭取消timer及worker |
| 性能 | 固定CPU/RAM/磁盘/JVM/heap/reader与来源规模；10万独立名称及高命中短词，ALL/NAME/有无目录/limit取样；冷首次另列，预热后至少100样本，p50/p95/错误数/索引体积及内存；100万观察 |
| 路线与迁移 | 通配达标才选零迁移；不达标只触发备选设计评审。n-gram字段及查询gram/准确校验/单字/旧记录覆盖/补字段或重建成本需另获明确批准 |
| 交付 | 最终SHA、隔离守卫、JDK17及真实Java8、strict validate、README/README_CN/UsageGuide/内置帮助一致；Codex复验及用户接受分别记录 |

建议性能门槛100ms需在0.2给出完整测量定义并获批准，不能当所有用户硬件SLA。禁止把A的169项套件通过或P-00/P-01数据当B子串性能通过。


## Codex R1矩阵补充（未执行B实现测试）

- 同一请求两分支/解释共享reader，来源只读一次；中途refresh不混代、第二分支故障不返部分成功。
- 字面+/-/a[b]在NAME/ALL不走错误语法；空正文分析不匹配，name+content解释不带排他条件。
- UNKNOWN默认省略HTTP字段，FALSE只代表已知无更多，空词保持旧结构；search/detailed不递归。
- 名称分数和并非唯一词档编码；验证跨档序的证明前提和多词边界，tie-break只承诺取回切片。
