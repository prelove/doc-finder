# Design

## Context

动机见 proposal.md，外部契约见 specs/directory-scoped-search/spec.md。本文是唯一技术设计；tasks.md 同时是 Superpowers 实施计划，不再复制到另一设计/计划文件。

代码基线 HEAD：9a1c26ad1f3261c16548f55693b3380d5db67790。该 SHA 不包含未提交文档；评审还须记录文档包版本与文件 SHA-256。实施前重新核对调用点和工作树。

| 相对 src/main/java/org/abitware/docfinder 的位置 | 事实/设计影响 |
| --- | --- |
| search/SearchRequest.java | 只有三/五参数构造，新增兼容六参数入口 |
| search/LuceneSearchService.java | acquire reader；Top-N 后排除目录；异常吞成空列表；须修正 |
| index/LuceneIndexer.java | 实际文件/目录写入；path 兼作主键/展示，不能改其规范 |
| index/content/DocumentBuilder.java | 平行构建器尚未接入主链路；保留则同步字段 |
| index/SourceManager.java | sources.txt 为 path 或 path\|0/1；原读取吞 I/O，需严格搜索入口 |
| ui/MainWindow.java | 活跃桌面搜索/worker/预览；不能只改未使用组件 |
| util/AppPaths.java | 数据在 user.dir/.docfinder，测试必须隔离工作目录 |

## Goals / Non-Goals

**Goals:** 同一目录条件贯穿真实后台、桌面和 Web，默认整树，来源校验一致，错误可见，需求—任务—证据可追溯。

**Non-Goals:** proposal 排除项均适用；不改原 path 主键，不新增虚假来源索引完成标志，不重构整个窗口/监听架构。

## Decisions

### D1. 路径与字段

新增 util/PathScope.java，方法均 public static：

```java
String normalizeIndexedPath(Path path);
String normalizeDirectory(String directoryPath);
String descendantPrefix(String normalizedDirectory);
```

索引路径必须 toAbsolutePath().normalize()；查询去两端空白，空值/空白返回 null，非空必须为宿主平台合法绝对路径，拒绝 NUL、E:Docs、Windows 缺盘符根相对路径。词法 normalize 消除 .、..；Windows 复用 Utils.canonicalizeWinPathString 的已有字符兼容，统一为 /、Locale.ROOT 小写；POSIX 保留大小写。不得 toRealPath/exists/isDirectory 或探测 UNC 在线性。Windows扩展命名空间（两个反斜杠后跟问号或点再跟反斜杠，例如扩展盘符/UNC/设备路径）本期不支持：查询明确INVALID_DIRECTORY，来源行按D2坏行处理，索引规范函数不得静默映射为普通路径。参数化测试证明两规范函数对相同受支持绝对路径一致；扩展路径支持移后续评估。

descendantPrefix 保留已有尾 /，否则补 /，不折叠 UNC 开头 //。范围字段、来源比较和查询共用同一规范。

文件和目录新增 StringField("path_scope", normalizedPath, Store.NO)，保留原 path 等字段。覆盖实际 LuceneIndexer 全量/upsert 及保留的 DocumentBuilder 两种文档入口。独立字段避免改变原更新/打开行为；查询 FILTER 避免结果后过滤漏项。

### D2. 来源覆盖与注入

新增 search/SourceRootProvider.java：

```java
@FunctionalInterface
public interface SourceRootProvider {
    List<Path> loadRoots() throws IOException;
}
```

SourceManager 增加 public List<Path> loadSearchRoots() throws IOException；委托包级 static List<Path> readSearchRoots(Path sourcesFile) throws IOException，便于临时文件测试。读取 path 或 path|0/1，忽略注释/空行，返回独立不可变列表；缺文件/只有空白注释为空列表；文件读取失败为IOException。逐行非法路径、相对来源路径、多余列或非法network标记记录WARN（行号+原因）后跳过，剩余有效绝对来源继续；存在数据行但全部无效则IOException。日志不写口令/正文，HTTP不返回坏行详情。保留旧格式，不运行网络探测，不把文件I/O吞成空列表。其他既有读写不重写。

为桌面展示同一次解析的坏行提示，增加SourceManager内SearchRootsSnapshot：不可变List<Path> roots及List<Integer> skippedLineNumbers；public SearchRootsSnapshot loadSearchRootsSnapshot() throws IOException和包级static SearchRootsSnapshot readSearchRootsSnapshot(Path sourcesFile) throws IOException返回它。原loadSearchRoots/readSearchRoots委托同一解析器仅取roots，provider不变，不另写解析规则。全部无效/不可读仍抛错；Web不获得skippedLineNumbers。
桌面刷新显示一次非模态固定警告“Some source entries were skipped. See Help > View Log.”；具体行号/原因只在日志。下一次没有坏行的刷新清除。警告独立于搜索状态，不覆盖当前错误或扩大范围。

LuceneSearchService 新增构造 LuceneSearchService(Path, IndexSettings, SourceRootProvider)。旧构造保留，默认 provider 懒调用 new SourceManager().loadSearchRoots()；仅非空目录请求使用，每次读取一次快照。测试注入临时根，不读取真实配置。

规范目录 D 和来源 R，允许 D.equals(R) 或 D.startsWith(descendantPrefix(R))；仅包含 R 的更大父目录拒绝。重叠来源任一覆盖即允许，不改原去重。文件不可读或数据行全部无效为 SOURCE_CONFIG_UNAVAILABLE；有效列表空/不覆盖为 DIRECTORY_NOT_INDEXED；无目录请求不加载 provider。

来源配置、字段兼容和扫描完整性是三件事。此约束是产品范围，不是文件读取授权。

### D3. 请求与错误

SearchRequest 的 public final String directoryPath 和六参数构造顺序保持；三/五参数兼容，构造只保存去两端空白的输入快照，不做I/O：
SearchRequest(String query, int limit, FilterState filter, SearchScope scope, MatchMode matchMode, String directoryPath)。

新增 search/SearchException extends RuntimeException，嵌套 Code，构造(Code,String)、(Code,String,Throwable)，public Code getCode()。从0.2的DirectoryScopeException改名是因为现在统一覆盖全局查询错误；尚无已实现接口需要迁移。

| Code | HTTP | 固定英文消息 |
| --- | --- | --- |
| INVALID_DIRECTORY | 400 | Enter a valid absolute server directory path. |
| DIRECTORY_NOT_INDEXED | 422 | This directory is outside configured sources. Add a source first. |
| SOURCE_CONFIG_UNAVAILABLE | 503 | Search sources could not be loaded. Check configuration and retry. |
| INDEX_REBUILD_REQUIRED | 409 | Check that sources are reachable, then run File > Index All Sources to update the index. If old entries remain, back up the index before using File > Rebuild Index (Full). |
| SCOPE_CHECK_FAILED | 503 | Directory search capability could not be checked. Retry later. |
| SEARCH_FAILED | 500 | Search failed. See the application log. |
| INVALID_QUERY | 400 | Invalid search query. Check the search syntax and retry. |
| INDEX_UNAVAILABLE | 503 | Search index is unavailable. Run File > Index All Sources in the desktop app, or retry after initialization or rebuilding. |

新增search/SearchErrorMessages.java，public static String forCode(SearchException.Code code)返回上表固定英文消息，null/未知统一SEARCH_FAILED；桌面和HTTP共用。
错误为{error,code}，日志保留cause；桌面worker对ExecutionException内已知SearchException按Code映射，不用其任意getMessage()作UI文案，未知异常也用固定SEARCH_FAILED；HTTP同样不输出原始异常/堆栈。当前错误清结果/预览，陈旧/cancel回调不改状态。
headless SearchErrorMessagesTest覆盖八Code精确文案；DesktopHarness用fake依次抛八Code及未知异常，断言实际statusLabel不含原始消息/堆栈。
有/无目录的ParseException统一INVALID_QUERY/400，按输入错误记录WARN或DEBUG，不能冒充IOException/500。两种模式语法与成功评分不变；INVALID_QUERY不addToHistory、不改已保存历史；UsageGuide说明report (final等非法输入现在报400而非空结果，C++等保留字符输入是否需转义以现有parser特征测试为准，不一概认定非法；其他旧无限定内部故障处理不在本期扩大。
非空词且serviceSupplier为null统一INDEX_UNAVAILABLE/503，桌面相同操作提示。允许替换旧的“still initializing”消息，不声称精确区分初始化/不存在/重建；仅凭null无法区分。不新增持久化状态机。
空词优先返回原空成功响应，不校验来源、语法或服务状态。有效服务下目录/来源校验优先于语法解析；多错误请求按该顺序处理。

### D4. 查询与兼容

固定顺序：空词返回 → 规范目录 → 读取/校验来源快照 → refresh/acquire searcher → 检查同 reader 字段覆盖 → 关键词/既有条件/类型/目录查询 → Top-N → 转换 → finally release。

范围为：
```text
(path_scope == D AND kind == folder)
OR PrefixQuery(path_scope, descendantPrefix(D))
```
至少命中一个分支，整体用 FILTER 加入。相等分支仅 folder，防止误输文件路径就把该文件作为目录自身返回；不在线判断它是不是目录。文件路径或不存在路径没有后代时仅零索引命中。

FOLDER 保留原 kind=folder 条件；其他模式用查询内 MUST_NOT kind=folder 替代 Top-N 后 continue。目录不加分，不改 NAME/ALL 前缀语义。无目录结果仅有意纠正原类型占位导致的少返回。detectMatchType仅解释关键词/名称匹配查询，不把目录/类型/时间等FILTER带入Explanation文本扫描，防POSIX目录名含name:误标；不借此重做整个命中来源系统。

包级 static void ensureDirectoryScopeSupported(IndexSearcher searcher)保留为同reader的精确判定入口：
1. 空live reader直接通过。逐leaf，numDocs()==0可跳过；Terms非空且getDocCount()==maxDoc()是充分通过条件（含未merge删除文档），不能拿numDocs比较。
2. docCount不等、缺Terms或统计不可用不直接拒绝，可能只有已删除旧文档。降级为原MatchAll MUST及开放TermRangeQuery(path_scope) MUST_NOT，精确定位一个缺字段live doc；这不保证重写只访问一个term。
3. 缺字段为INDEX_REBUILD_REQUIRED，读取失败为SCOPE_CHECK_FAILED。判断覆盖当前reader全部live docs，不只当前词/来源。

服务实例内按顶层reader.getReaderCacheHelper().getKey()缓存SUPPORTED/REBUILD_REQUIRED；使用同步Map保证并发，注册close listener释放条目，服务close清理，并禁止关闭后迟到检查重新发布缓存；listener只移除对应key，不清空其他活跃reader。必须是reader key，不能用core key（删除变化需失效）；cache helper为null不缓存。缓存不包含来源校验/输入错误/I/O错误，不持有searcher/reader引用；acquire保证计算期reader存活，listener注册失败则不缓存。
同reader重复请求复用判定，刷新/删除产生新reader key重新检查。A4先做10万文档微基准比较原精确查询、快路径、首次/重复reader检查，保留实测报告；本轮没有基准结果，不能将理论推演当加速证明。

已有有效commit且0 live docs返回零；源内零命中也返回零，不推断磁盘为空或完整覆盖。没有commit/服务不可用按D3返回INDEX_UNAVAILABLE，不为查询建库。首次Index All Sources后由D8恢复服务。

### D5. 桌面体验

新增 ui/components/DirectoryScopePanel，组合来源下拉（含 All sources）、路径、Browse、Clear、范围说明。接口：

```java
DirectoryScopePanel(Runnable onScopeChanged);
String getDirectoryPath();
void setDirectoryPath(String directoryPath);
void setSourceDirectories(List<String> directories);
void clearDirectory();
void setScopeControlsEnabled(boolean enabled);
void setSourceWarning(boolean hasSkippedRows);
```

路径文本是编辑值唯一事实来源，worker用已提交快照，范围标签显示“当前已提交范围”。来源下拉仅快捷设置：空路径显示All sources，用PathScope.normalizeDirectory对编辑路径与来源进行等价比较，匹配显示该来源，其余显示Custom（不可作为真实来源提交）；输入中的非法路径只显示Custom，不自动报错/提交/访问磁盘。
选All sources等同Clear，清空并回调一次；选具体来源设置文本并回调一次；手工编辑仅更新编辑状态不提交，Enter才提交；Browse确认一次，取消不变。setDirectoryPath/setSourceDirectories是程序设置，不提交；clearDirectory回调一次。
来源刷新触发：窗口启动、Manage Sources关闭（包括Cancel，兼容既有标记缺陷）、Index All/Full Rebuild完成。后台加载带generation，只应用最新快照；setSourceDirectories不改文本/已提交范围、不发查询，被移除来源保留当前路径并显示Custom，下一次查询由后台拒绝，不自动扩大到All。标签沿用英文：Search in、All sources、Browse、Clear、Includes all subfolders、Searches indexed content only。重启为空，不持久化新历史。

MainWindow 后台加载来源并在 EDT 更新；关闭/刷新防迟到回写；加载失败可见，后台始终最终校验。浏览仅选目录；用户主动使用原生选择器可能访问磁盘，不把它与查询阶段无网络探测混淆。

doSearch 在 EDT 取得目录与条件快照；每次提交含清空词均先推进 sequence/取消旧请求；worker 不回读输入，成功/错误都先核对 token；当前错误清空旧结果/预览。服务null时按D3可恢复提示，不再永久声称仍初始化。MainWindow的searchService字段用volatile安全发布，发布操作仍在EDT；worker实际执行时只读一次到局部变量；null必须抛SearchException(INDEX_UNAVAILABLE)，不能return emptyList或检查后再次读字段。Full Rebuild开始同时推进searchSequence并取消旧worker，防迟到查询覆盖重建状态。
harness分两例：当前token未失效、排队后服务清空再释放worker，应显示INDEX_UNAVAILABLE而非No results；实际重建失效旧token，迟到成功/错误不得覆盖重建状态。目录控件及动作遵守既有 indexing 禁用。

右键增加目录 Search in this folder、文件 Search in containing folder；由实际 Path.getParent 获取父目录，保留关键词/条件，设置后统一提交一次。排序后的 view 行号必须正确映射实际结果；根自身重复动作不循环。

### D6. Web 与适配

GET /api/search 新增可选 directory，正确 URL 编码；成功仍 {count, results}，count 只是返回数；默认参数/limit 上限200不变。每条结果兼容新增 parentDirectory（字符串或null），由服务器按宿主 Paths.get(r.path).getParent() 计算，不访问磁盘；无法解析/无父路径时为null。Web文件快捷动作只用该字段，不按浏览器OS或字符串切割猜父目录；null时禁用该动作，结果仍展示。目录快捷动作直接用原path。

index.html 增 Server directory、Clear、当前整树范围和索引说明，不加服务器浏览或来源修改 API，不向前端下发全部来源。Enter/Clear/结果快捷搜索都保留其他条件；目录/文件分别可继续限定到自身/父目录，增加 Copy path。剪贴板失败提供可选文本/明确失败，不伪报成功。

任何提交先失效旧 token，空词清空不请求；成功/失败均检查 token，当前失败清空旧结果及预览。路径使用文本 DOM，事件 addEventListener，不拼入 innerHTML 或内联脚本。

SearchHandler 补全 JSON 控制字符转义并以 Locale.ROOT 格式化 score，保证特殊路径和非英文区域合法 JSON；不引入 JSON 依赖，不扩大为全 Web 重构。

### D7. 协作单一事实来源

README 为入口，spec 管行为，design 管技术，tasks 管实施完成度，acceptance 管证据，review 管发现闭环，delivery-status.yaml 只管关口状态。冲突先统一文档，实施者不得自行挑一份。

OpenSpec 管变更；Superpowers 提供设计/计划/TDD/调试/验证方法。工具或技能不可用时按 handoff 的等价步骤执行，不强制安装、不绕过关口。Claude 外部评审与本轮内部自检分别记录。

### D8. 首次建库与重建后的服务恢复（F-001）

新增MainWindow公开入口 public void reopenSearchServiceAsync(Path indexDir, IndexSettings settings)，在EDT发起，内部后台打开Lucene，EDT通过现有setSearchService发布；禁止在done()/EDT直接new LuceneSearchService。仅为受控harness提供protected SearchService openSearchService(Path, IndexSettings) throws IOException工厂钩子，默认创建真实Lucene服务，测试子类可用latch控制打开/关闭；不新增全局框架。
生产入口清单（0.3行号仅定位）：App.java:77启动、MainWindow.java:1431添加单文件夹完成、MainWindow.java:1636完整重建完成，三处全部改reopenSearchServiceAsync，不能漏添加文件夹分支。生产全仓仅protected openSearchService工厂允许一处new LuceneSearchService；测试/基准可直接构造，不计生产检查。
App启动改为调用该入口，不再自己直接向Web发布独立候选；统一服务打开sequence，关闭窗口/开始重建/新打开请求使旧候选失效，迟到候选必须close，不能覆盖新服务。
Index All Sources在finally完成writer清理后，无论整体成功或失败都尝试后台刷新已有service或打开缺失service；只要有有效commit就恢复桌面/Web，无需重启/强制重建。没有有效commit则打开失败、保持INDEX_UNAVAILABLE，不为恢复建空索引；不得从扫描计数/整体异常推断没有提交。
增加private reopenSearchServiceAsync(Path, IndexSettings, String completionStatus)重载，public两参数委托；completionStatus只来自固定安全文案。部分成功后可查已提交数据，但保留“Indexing failed or partially completed. Search uses available committed data. See the application log.”，setSearchService的Ready不得覆盖；原始异常只记日志。Full Rebuild失败仍不可用，不自动承诺部分索引完整。setSearchService对同实例不关闭，替换时同步Web引用；关闭/失败显示D3消息。
Full Rebuild开始前使待打开候选失效并setSearchService(null)，停止接收新的Web搜索（空词仍空成功）；新非空请求503 INDEX_UNAVAILABLE，避免把清库期显示为无匹配。已运行请求可完成或明确失败，不承诺原子切换。完成后后台重开并统一发布；失败保持不可用且显示实际失败，不自动退回已关闭旧服务。

### D9. 固定测试隔离与验收责任（F-008）

A0必须交付测试隔离，而非每次复制checkout。允许仅修改既有pom的Surefire配置（不升级/新增依赖）：forkCount=1、reuseForks=true、workingDirectory为已存在的project.build.testOutputDirectory（target/test-classes），在properties显式预置空argLine，现有Surefire配置argLine固定为：${argLine} -Duser.home="${project.build.directory}/test-home"。使用早期属性组合保留命令行-DargLine；当前无覆盖率代理，不引入晚绑定工具。隔离home参数最后，防外部参数覆盖，允许保留-Xmx等；实际user.dir/home守卫仍须通过。
A0用-DargLine="-Xmx256m -Ddocfinder.argline.probe=present"测试，断言probe进入fork、隔离home/work正确，保存JVM参数，不能只看effective-pom；test-only守卫检查实际user.dir/home都在本次target，失败则禁止迁移测试。
LegacyMigrationTest使用JUnit @TempDir的home/work，保存/恢复系统属性，明确禁并行；新增真实迁移夹具（源缺失、复制/已存在不覆盖、sentinel），不调用真实home。src/test/resources/logback-test.xml关闭业务FILE appender，仅控制台或临时路径，避免类加载先写真实日志。
A0起先在隔离副本验证这些测试配置，之后标准仓库cwd的mvn test/clean verify走固定隔离；核对真实.docfinder之前/之后清单、哈希、mtime无变化。常规不再复制checkout。nofork忽略workingDirectory/argLine，不受此安全保证；如诊断必须另建临时副本/明确隔离，不作为默认完成证据。
GUI/Web主机验收M-01～M-06由Codex在可用真实桌面/浏览器执行，必要时用户辅助原生选择器；Devin只负责可复现harness、夹具、命令与自动测试交接。缺环境不是通过，也不是Devin开发包无限期等待：实现包交付后转“等待验收环境”，Codex安排执行；G4/G5仍需实际证据。

## Risks / Trade-offs

- 旧索引重建成本且当前非原子：显式提示、维护时段操作，不自动删除或承诺失败无损回退。
- 词法路径不合并symlink/junction/映射盘/UNC别名；Windows特殊大小写敏感目录仍折叠，macOS等默认大小写不敏感卷仍按POSIX词法保留大小写。扩展命名空间本期明确拒绝，不声称跨平台物理身份等价。
- 来源更新用单请求快照；删除来源不自动清理旧索引，无目录查询保持原全局语义。
- reader检查快路径和实例缓存已获本设计许可；先做P-00微基准，再做P-01退化观察，不以推演替代实测。
- user.dir子树现有索引排除策略不改，选工作目录内路径可能无索引结果；README/UsageGuide须说明，不能声称磁盘目录为空。
- watcher/poller 既有缺陷：A 验证受控 upsert/commit；持续同步可靠性归 C，新回归仍阻断。
- Web手输服务器路径/结果缩小是用户确认的A阶段边界，不下发完整来源列表；422与其他状态差异仍会泄露覆盖关系，用户已知情接受此增量，绝不等于无泄漏。默认localhost不扩大，LAN不受认证/权限保证。
- native 资源配置已有缺口：A 标准交付 JVM JAR，native 不纳入完成声明。

## Migration Plan

1. 升级后普通查询继续；限定查询发现旧记录返回409，先推荐File → Index All Sources非破坏upsert：保留旧索引可用记录，但会重新解析仍在来源内的文件，代价非零，且不清理已删除/已移除来源残留；离线、权限不足或部分遍历失败同样可留旧记录，应先查可达性。
2. 无陈旧缺字段live docs时Index All即可补齐；存在任何残留（即使不命中当前词、不在当前来源）仍全库409。全库门槛保留，不能只检索当前范围的新字段而静默漏旧数据。
3. 只有用户维护时段确认来源、备份后，才手动File → Rebuild Index (Full)兜底。它先deleteAll/commit、再扫描/Tika重解析，非原子，期间全库搜索不可用，崩溃可留下空/部分索引；现有锁恢复路径可能删除整个索引目录。查询/迁移提示不得自动触发重建或强制解锁。
4. 备选权衡：直接复用原path可免迁移但大小写/分隔符/词法边界不一致，不能满足本规范；按来源局部放宽门槛也不能识别缺path_scope的旧记录，存在漏查，均不采用。专用字段+先upsert再手动重建是用户确认路线。
5. 迁移夹具覆盖无残留/已删除文件或移除来源旧文档/离线、拒绝访问或部分失败未更新旧文档/前一来源commit而后一来源失败；实际执行M-06记录Index All、完整重建及Web503/恢复。回退旧应用可忽略字段，但再次写旧格式会重新阻断；程序回退不能恢复被重建删除的数据。

6. 用户验收及交付授权后才归档/提交/合并/推送，排除无关 AGENTS.md 改动。
