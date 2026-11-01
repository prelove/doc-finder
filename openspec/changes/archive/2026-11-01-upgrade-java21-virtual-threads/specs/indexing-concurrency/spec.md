# indexing-concurrency Specification (delta)

## ADDED Requirements

### Requirement: VT-01 全量索引虚拟线程并行

全量索引（indexFolder/rebuild 路径）SHALL 使用虚拟线程并行执行文件遍历与内容提取分发，多线程并发写入 IndexWriter；索引的最终内容 SHALL 与串行实现等价。

#### Scenario: 索引等价性

- **WHEN** 对同一固定语料分别以串行基线与并行实现各建一份索引
- **THEN** 两份索引的文档总数一致，抽样查询（文件名与内容关键词）的命中集合一致

#### Scenario: 失败语义保持

- **WHEN** 索引过程中单个文件提取超时或失败
- **THEN** 该文件按既有降级逻辑处理（name-only 等），整体索引不中断，超时统计口径与基线一致

### Requirement: VT-02 背压与资源上限

并行索引 SHALL 以信号量设置在途任务上限，上限与提取侧容量挂钩并以代码常量形式给出中文注释说明；内存占用不得随语料规模无界增长。

#### Scenario: 大语料背压

- **WHEN** 对大批量文件语料执行全量索引
- **THEN** 在途提取任务数不超过设定上限，索引全程无 OutOfMemoryError 且正常完成

### Requirement: VT-03 Web 服务虚拟线程

内嵌 Web 服务器的请求处理 SHALL 使用虚拟线程执行器，并以信号量限制并发处理数；服务器启停与优雅停机行为与基线一致。

#### Scenario: 并发请求

- **WHEN** 多个搜索/预览请求并发到达
- **THEN** 请求由虚拟线程处理且全部正确响应，并发数超过上限时按注释说明的策略排队或拒绝，不出现线程耗尽错误
