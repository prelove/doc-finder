# Design: Java 21 升级与虚拟线程改造

## 平台与依赖

- 编译基线 `<release>21</release>`；不在代码中使用 Java 21 之后的预览特性。
- Lucene 10.6.0 要求 Java 21，与平台升级互为前提，一次到位；不保留 Lucene 9 过渡态。
- 依赖版本以 proposal.md 版本表为准；执行环境为离线 Maven 仓库，新版本坐标须先经 `~/tools/seed-repo.sh` 以 curl 播种后再离线构建（配方见侦察记录 ~/workspace/pilot/docfinder-recon.md §1）。

## 虚拟线程改造设计约束

### 索引主链路（LuceneIndexer）

- 现状：UI 以单个 SwingWorker 顺序遍历每个 source，`walkDir` 递归单线程逐文件同步调用提取并写入同一 IndexWriter。
- 目标形态：遍历与提取分发改为虚拟线程并行（`Executors.newVirtualThreadPerTaskExecutor` 或等价构造），多线程并发调用 IndexWriter（其本身线程安全）。
- **背压是硬要求**：并行度 MUST 以信号量（Semaphore）设上限，上限与提取侧容量挂钩（助手 JVM 解析池进程数 × 合理排队深度，或进程内提取的并发度），禁止无界堆积任务与文档对象导致内存膨胀。具体数值由 Codex 设计说明定稿并写入代码常量，附中文注释说明取值理由。
- 助手 JVM 池（IsolatedFileAccess）保留隔离架构；允许把解析池进程数（现 2）与队列深度（现 16）调整为设计说明定稿的常量，须注释说明与背压上限的对应关系。
- 正确性：同一语料并行索引与串行索引的结果 MUST 等价（文档数一致、抽样查询结果一致）；既有代际回收、写锁协调（IndexWriteCoordinator）语义不得破坏；失败文件的降级（name-only）与超时统计逻辑保持原语义。

### 钉住（pinning）规避

- 被虚拟线程执行的路径中，不得在 synchronized 块内做阻塞 I/O 或长等待；盘点命中的同步点（LuceneIndexer 的 synchronizedMap/RECENT_UPSERTS、LiveIndexService/NetPollerService 的 synchronized 段）逐个评估：量小且不跨 I/O 的可保留并注释，跨等待的改 ReentrantLock。

### ContentExtractor

- 现状每次调用新建单线程执行器做 Tika 超时包裹。改用虚拟线程执行提取、保留既有超时与取消语义；线程命名/日志口径保持可诊断。

### Web 服务器

- `web/WebServer.java` 的 ThreadPoolExecutor(2,8,ABQ128,AbortPolicy) 改为虚拟线程每任务执行器；须显式处理原有界队列/拒绝策略语义的等价物：以信号量限制并发处理数，超限时的行为在代码注释中说明；优雅停机行为不变。

### 不改动区

- watch 包的 WatchService 循环、防抖、单线程增量 worker 保持平台线程（低频、状态机性质，虚拟线程无收益）；仅当其调用的提取路径受益于上述改造时自然受益。

## 代码规范

- 遵循仓库既有代码风格与命名；不顺手重构无关代码。
- **注释要求**：新增/修改的关键逻辑 MUST 附中文注释，重点说明「为什么」（背压上限取值、pinning 规避、语义等价处理、API 迁移中的行为对齐点），与仓库现有中文注释风格一致；机械性代码不堆砌无意义注释。
- 每项实现任务按仓库规约走 TDD：先（或同步）补针对性测试并保留运行证据。
