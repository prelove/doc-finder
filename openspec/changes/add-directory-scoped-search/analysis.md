# Project Analysis and Risks

调查日期2026-10-23。以下为静态代码核对；历史测试基线见acceptance，未复现的运行时风险不写成已复现缺陷。路径相对仓库根，执行前核对最新行号。

## Feature map

- App：主题、单实例、后台旧数据迁移、可选Web、EDT窗口、后台打开Lucene后注入服务。
- index：SourceManager本地/网络来源，ConfigManager设置，LuceneIndexer文件和目录全量/upsert；Tika正文、标准/中/日文分析；原path为更新主键。
- search：NAME/CONTENT/ALL/FOLDER、FUZZY/EXACT、Lucene语法/name通配符、扩展名/mtime、历史、SearcherManager刷新。ALL当前只返回文件。
- watch：本地WatchService/去抖批量写，网络并行扫描+快照差异，IndexWriteCoordinator串行写。
- ui：实际MainWindow内联搜索/worker/预览；打开/Reveal/Open With/Properties/复制路径/Web预览链接/CSV/主题/快捷键/托盘/日志。
- web：JDK HttpServer默认127.0.0.1；搜索、文本、原文件、旧预览；HTML文本/图片/PDF/DOCX/XLSX预览及下载；token/密码/期限/次数共享。
- 数据实际user.dir/.docfinder；README部分仍写home；native配置存在但不能据此断言原生程序已验证。第三方压缩vendor只核对清单，未逐行审查。

## Evidence map and dispositions

| ID | 代码事实/风险 | 依据 | 本期处置（0.3更新） |
| --- | --- | --- | --- |
| R-01 | 无独立目录请求，Scope是字段/对象 | search/SearchRequest.java；ui/MainWindow.java buildTopBar/doSearch | A实现 |
| R-02 | Top-N后丢目录可能少返回 | search/LuceneSearchService.java search | A将类型前移 |
| R-03 | NAME前缀与ALL组合逻辑不同，EXACT含短语OR，不等于严格全名 | 同类buildUserQuery/buildExactQuery | B，A保持原语义 |
| R-04 | 同步Tika后才写元数据，重建先清索引且非原子 | index/LuceneIndexer.java indexFolders/upsertFile | A披露，C改进 |
| R-05 | 新目录root注册跳过、目录waitStable false、迁入目录无补扫；deletePath仅精确Term | watch/LocalRecursiveWatcher.java；LiveIndexService.java；LuceneIndexer.deletePath | C；A新回归仍阻断 |
| R-06 | 网络快照只含文件，部分扫描仍可能推断删除；失败写后保存快照风险 | watch/NetPollerService.java scan/diff；SnapshotStore | C，需故障注入 |
| R-07 | .exe/.dll被整个跳过；user.dir子树排除 | index/LuceneIndexer.java upsert/shouldSkip | B/C，A不承诺所有名称 |
| R-08 | NRTCachingDirectory只包reader，writer另用FSDirectory | search/LuceneSearchService.java构造；index/LuceneIndexer.java构造 | 不把它当性能已优化证据 |
| R-09 | 平行DocumentBuilder/ContentExtractor/QueryBuilder/QueryExecutor当前未接主链路 | 全局调用点核对 | A实际入口优先；保留Builder同步字段 |
| R-10 | 无服务端分页/总数，桌面100、Web最多200；浏览器排序仅当前批 | MainWindow内部worker；SearchHandler；index.html | A说明返回条数，B独立设计 |
| R-11 | 源管理单改Type可能不保存；索引路径设置未立即重绑服务 | ui/ManageSourcesDialog.java；MainWindow设置 | 后续，A不改索引位置 |
| R-12 | 预览cache仅path+query，网络目录预览先全量排序；存在EDT探测 | MainWindow预览方法 | 后续；A新增路径不做网络存在检查 |
| R-13 | file/text/preview/share仅做有限路径校验，无统一来源授权；LAN暴露管理接口 | web各Handler与WebServer | 独立治理，不宣传目录安全隔离、不扩大监听 |
| R-14 | HTTP score受Locale、JSON控制字符转义不足、路径内联脚本拼接 | SearchHandler.toJson/escapeJson；index.html | A覆盖新增流与序列化必要修正 |
| R-15 | native资源只含web/index，部分regex通配被转义 | resources/META-INF/native-image/.../resource-config.json | A只验JVM，D另验native |
| R-16 | LegacyMigrationTest操作真实目录 | src/test/.../util/LegacyMigrationTest.java | A0固定Surefire/迁移测试/日志隔离，真实home不参与 |
| R-17 | User docs组件化/数据位置/旧预览入口陈旧 | README_CN、README、UsageGuide、PREVIEW_MIGRATION | 随本功能文档修改纠正相关段落 |

静态风险不全部自动加入A。相关新回归P0/P1必须处理，既有风险由PM标明范围与接受理由；不能通过改验收掩盖。代码路径均相对src/main/java/org/abitware/docfinder，resources/test另有标注。

## Dependency assessment

本轮未改pom。下表版本来自2026-10-23分析时官方资料核对，是评审输入，不是自动升级授权或CVE扫描结果。

| 当前 | 可选方向 | 决策 |
| --- | --- | --- |
| Lucene8.11.2 | 8.11.4兼容Java8；当前10.5.2要求Java21 | A不改；D决定Java基线和迁移 |
| Tika2.9.2 | 2.9.4为2.x最终版且已EOL；3.3.3维护线；4.1.0主线且变化较大 | 不把补丁升级称为长期维护解决方案 |
| FlatLaf3.2系列 | 3.7.2，仍支持Java8 | D统一系列与主题/打包回归 |
| SLF4J1.7.36/Logback1.2.11 | SLF4J2.x/受支持Logback；1.2已EOL，新活跃线需更高JDK | 成组升级并验自定义Appender |
| JUnit5.9.3 | 5.14.4支持Java8；6.x要求Java17 | D按选定基线决定 |
| JNativeHook2.2.2 | 官方发布页仍列2.2.2 | 无理由只为新而改 |
| Log4j2.17.1桥/核心 | 核对实际依赖树、选定日志唯一后端 | 不未经树与运行验证删依赖 |

官方参考：
- [Everything名称/目录/内容行为](https://www.voidtools.com/support/everything/searching/)
- [Lucene版本](https://lucene.apache.org/core/downloads.html)、[10.x Java21](https://lucene.apache.org/core/10_5_2/SYSTEM_REQUIREMENTS.html)、[8.x Java8](https://lucene.apache.org/core/8_11_4/SYSTEM_REQUIREMENTS.html)
- [Tika版本及2.x结束维护](https://tika.apache.org/)、[3.3.3维护线](https://tika.apache.org/3.3.3/index.html)
- [NRTCachingDirectory用途](https://lucene.apache.org/core/8_11_4/core/org/apache/lucene/store/NRTCachingDirectory.html)：写出小段缓存，不能从reader包装推断已加速
- [FlatLaf发布](https://github.com/JFormDesigner/FlatLaf/releases/tag/3.7.2)、[Logback维护状态](https://logback.qos.ch/download.html)
- [JUnit5运行要求](https://docs.junit.org/5.14.4/overview.html)、[JNativeHook发布](https://github.com/kwhat/jnativehook/releases)

D开始时重新核对官方资料/依赖树/许可证与解析夹具，不沿用过期“最新”结论。


## 外部评审后补充（0.3）

F-001源码已核对：App打开无commit失败后service=null，Index All原先不重开；0.3以D8最小服务恢复和统一INDEX_UNAVAILABLE补齐。F-002已选非破坏Index All优先，陈旧live docs仍阻断，备份后手动Full Rebuild兜底。F-003开放range重写可能访问全部唯一词，已允许充分条件快路径和顶层reader key缓存；没有运行基准。

官方核验：[Terms.getDocCount](https://lucene.apache.org/core/8_11_2/core/org/apache/lucene/index/Terms.html)、[reader CacheHelper](https://lucene.apache.org/core/8_11_2/core/org/apache/lucene/index/IndexReader.CacheHelper.html)、[Surefire参数](https://maven.apache.org/surefire/maven-surefire-plugin/test-mojo.html)。Surefire网页可能显示新插件版，但workingDirectory/argLine/forkCount在当前3.0.0已存在；本期不升级插件。

## 0.4跟进核验

源码确认MainWindow SearchWorker的null分支返回emptyList、done拼接cause.getMessage、生产直接创建点是App:77及MainWindow:1431/1636；populateResults最后addToHistory，错误不能进入该路径。F-014～022已入0.4设计/任务/验收。保留字符示例C++不预设非法，遵循现有parser实际结果。
Surefire3.0.0官方参数：[版本对应文档](https://maven.apache.org/surefire-archives/surefire-3.0.0/maven-surefire-plugin/test-mojo.html)，argLine为JVM选项，晚绑定仅对其他插件运行时更新属性必要；本方案用预置空属性及早期组合，不升级插件，实际fork探针待A0验证。
