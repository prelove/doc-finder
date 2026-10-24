# Acceptance and Evidence

批准版本0.4；当前验收快照2026-10-24：c7与4eb常规clean verify各149项（148通过、0失败/错误、1平台跳过），4eb真实Java8同结果；新增8个定向探针中7失败/1通过，22个浏览器检查通过。A7修复批次已完成——QA24-01..11 全部实施并以本轮 `clean verify`（164项：161通过、0失败/错误、3平台跳过）与定向45项回归验证；等待Codex复验。A8未通过。详见 [Codex独立验证](evidence/Codex-QA-2026-10-24.md) 与 [修复复验记录](evidence/Devin-QA24-Remediation-2026-10-24.md)；此前用户冒烟与旧报告保留为历史，不替代本轮失败闭环。

## Traceability

| DS | 自动验证（拟新增/扩展） | 任务包 | 交互 | 当前结果 |
| --- | --- | --- | --- | --- |
| DS-01 整树/根自身/兄弟 | DirectoryScopedSearchTest、DirectoryScopeEndToEndTest | A3/A7 | M-01/M-04 | 常规套件通过；真实Web整树/根自身/兄弟检查通过；桌面全清单未完成 |
| DS-02 路径 | PathScopeTest、HTTP编码测试 | A1/A6 | M-01/M-04 | 常规套件及Web特殊路径编码通过；真实选择器确认/取消通过；POSIX/UNC宿主未跑 |
| DS-03 条件/Top-N/类型 | LuceneSearchServiceTest、DirectoryScopedSearchTest | A0/A3 | M-01 | 常规过滤/Top-N通过；显式name命中解释已修（QA24-03 GREEN，2026-10-24复验） |
| DS-04 来源/离线/状态 | SourceManagerSearchRootsTest、DirectorySourceValidationTest（含好坏行混合）、无提交HTTP fixture | A1/A3/A6 | M-02/M-04 | 常规来源套件/真实422通过；读取故障→SOURCE_CONFIG_UNAVAILABLE 已修并加ACL故障测试（QA24-05 GREEN） |
| DS-05 旧/混合/刷新 | DirectoryScopeCompatibilityTest | A4 | M-06 | 兼容套件通过；两种删除反例（NoMergePolicy钉统计）、close交错 latch、P-00服务缓存入口测量均已补（QA24-07/08/11） |
| DS-06 实际全量/upsert | LuceneIndexerPathScopeTest、DocumentBuilderTest、EndToEnd | A2/A7 | M-06 | 常规实际indexer/upsert通过；ACL拒写真实部分失败+真实IndexAll生命周期已补（QA24-06），M-06主机迁移待补 |
| DS-07 桌面 | DirectoryScopePanelTest（含路径等价/坏行警告）、DesktopHarness | A5a | M-01/M-02/M-03 | 选择器通过；已应用范围独立显示已修（QA24-02 GREEN），M-01/03尚未全完成 |
| DS-08 Web | SearchHandlerTest、WebAssetsOfflineTest（仅资源） | A6 | M-04/M-05 | 本轮22个浏览器检查通过（含受控故障）；持久索引内容提示已加（QA24-09）；M-04真实409/503等尚未全完成 |
| DS-09 错误 | SearchErrorMessagesTest、DesktopHarness八Code/未知消息映射、HTTP映射 | A3/A4/A5a/A6 | M-02/M-04 | 常规映射通过；桌面不可用/失败残留清除已修（QA24-01 GREEN） |
| DS-10 空词/兼容 | provider计数=0、旧请求回归 | A0/A3/A6 | M-03/M-05 | 常规空词/token及本轮Web迟到/清空通过；真实Index All入口时序已补（QA24-06 harness） |
| DS-11 快捷定位/复制 | EDT动作/服务器parentDirectory/前端受控响应测试 | A5a/A5b/A6 | M-01/M-04 | Web排序/快捷/复制分支通过；clipboard受控验证，桌面全流程/真实根仍待补 |
| DS-12 首次建库/重建恢复 | DesktopHarness、HTTP unavailable/恢复与集成测试 | A5a/A6/A7 | M-02/M-06 | 常规恢复测试通过；旧服务重开失败unpublish已修（QA24-04）+真实部分失败ACL证明已补（QA24-06） |

实现者不得以“测试类存在/代码看起来支持”标通过。每行引用真实报告或观察记录；平台skip、仅stub、未跑GUI均单列。

## Fixture and safety

A0必须交付design D9的固定隔离：既有Surefire fork、target/test-classes工作目录、target/test-home、test-only日志、迁移@TempDir与实际属性守卫，不新增依赖。
首次验证这些配置仍在一次性临时源码副本完成；确认可用后，日常任务直接从仓库运行mvn test/clean verify，不再每次复制工作树或手动argLine。
默认fork测试报告记录实际user.dir/home，并用-DargLine探针证明保留外部JVM选项且隔离home仍有效；守卫必须证明不落真实.docfinder；A0及完整构建前后核对真实目录文件清单/哈希/mtime无变，日志也不能例外。
nofork不应用Surefire workingDirectory/argLine，不能沿用本保证或当默认通过。资源不足诊断另建临时副本/隔离进程，保留失败证据，不伪报标准构建成功。
source/index是系统临时兄弟目录，不放测试user.dir及其下；HTTP用127.0.0.1:0、超时、关闭executor，不启动真实ShareManager。来源provider和sources.txt均用夹具。

真实GUI/Web验收仍使用独立临时work/home，source/index为兄弟，启动隔离JVM而非业务工作目录；这是主机验收一次性fixture，不是每次单测复制整个checkout。Devin交harness、JAR、夹具创建/清理步骤、实际SHA/启动命令，Codex执行；用户必要时辅助原生选择器。
Devin没有GUI时任务6.3/7.3/8.3可交“自动验证及验收包”，M项仍未执行；任务9.1～9.3的验收关不能因此通过。Codex明确安排环境/执行者，不让Devin空等。任何无法完成的实机项状态为WAITING_FOR_HOST_ACCEPTANCE。

所有清理先核对绝对路径仅在本次fixture根，保存证据后清理；不得删除真实业务目录。

基本数据：
```text
source/
  ProjectA/report.txt             正文 alpha contract
  ProjectA/sub/deep/contract.txt  正文 alpha contract
  ProjectA/sub/note.md            正文 beta
  ProjectAB/outside.txt           正文 alpha contract
  ProjectB/high-01..05.txt        范围外高分
  中文 空格&资料/[draft]/a.txt    正文 alpha
```
目录记录含ProjectA本身与后代；特殊路径另含单引号、+、#。来源provider以fixture根注入；来源解析用临时sources.txt。旧索引由原生writer省略字段，混合加入不命中词的旧live doc，删除后不强制merge；无索引提交和有效commit但0 live docs为两个独立fixture。UNC term测试不需要真实共享。

## Manual acceptance

执行责任：M-01/M-02/M-03/M-06由Codex在Windows真实主机安排并记录，原生选择器需用户辅助时登记具体步骤；M-04/M-05由Codex在可用浏览器执行。Devin提供可复现夹具和harness并修缺陷，不承担其环境不存在的原生GUI验收。用户最终接受不等于替代这些记录。


| ID | 操作 | 必须观察到的结果 |
| --- | --- | --- |
| M-01 桌面主流 | 隔离启动→选来源/粘贴/浏览子目录→编辑Custom/All→关闭来源管理增删后刷新→搜索→取消→排序后快捷→Clear | 深层命中、根自身FOLDER可命中、兄弟不混入；每次确认一次请求，取消0；保留关键词条件；范围可见；刷新不改文本/自动提交；等价路径匹配来源不误Custom，坏行只提示一次指向日志，修复清除；删除来源后Custom且下次拒绝；Clear恢复全局；重复根动作不循环 |
| M-02 桌面错误 | 输入相对/源外目录；旧索引；配置读取失败；索引禁用期；源码提交后刷新 | fake八Code/未知异常逐一断言固定文案无原始消息/堆栈；坏行混合仍可查且有日志，全部无效/不可读报错，非法语法有/无目录均400；当前失败清旧结果/预览；不自动扫描/重建/全局回退；禁用期不能发起范围动作；源内零命中只说明索引 |
| M-03 桌面竞态 | harness延迟A成功/错误，先完成B再释放A；清空词后释放A；关闭后释放来源加载 | 旧回调不改B/空词/已销毁窗口；保持token的排队worker遇null报INDEX_UNAVAILABLE，真实重建旧token失效，非法查询不入历史；请求目录是提交快照；正确释放线程 |
| M-04 Web主流 | Network核对中文/空格/+、&、#；Clear；目录/父目录快捷；复制允许/拒绝；八类错误 | directory完整还原；服务器parentDirectory对盘根/UNC/POSIX根正确；null动作禁用；无脚本/错行；复制失败不伪报成功；旧API结构兼容 |
| M-05 Web竞态 | 受控fetch/浏览器拦截延迟A成功/失败，先完成B，再清空词 | 两种迟到响应均无回写；空词提交0请求；当前错误清旧结果/预览，撤销拦截后真实搜索正常 |
| M-06 迁移回归 | 全新数据→加来源→Index All后无需重启可查；旧索引分别Index All无残留恢复/有陈旧/离线或拒绝访问/部分失败残留仍409→备份后手动Full Rebuild；抽查预览/打开/分享 | 首次或部分commit但整体失败后桌面/Web可查已提交记录、保留部分失败提示不被Ready覆盖；旧残留门槛符合D4；完整重建期间新Web非空请求503而非零结果，成功恢复、失败不可用；原path与旧功能无新回归；非原子/解析代价明确 |

每项记录系统/JVM、代码SHA、操作顺序、请求/响应或最终路径、截图/日志、执行者、通过/失败。stub自动测试不替代真实页面/选择器。无法执行保留未完成。

桌面harness由Devin用当前代码package并交Codex，Codex在独立临时work/home运行，Windows示例：
`java "-Duser.home=<临时home>" -cp "<临时checkout>/target/test-classes;<临时checkout>/target/docfinder-1.0.0.jar" org.abitware.docfinder.ui.DirectoryScopeDesktopHarness`。其工作目录为临时work；test-classes和JAR使用受测绝对路径；POSIX classpath用冒号。替换模板为绝对路径并记录实际值，确认JAR是当前受测代码。

## P-00 Capability microbenchmark

2026-10-24 复验后由 QA24-11 补全：真实服务缓存入口（`checkScopeSupport`）与 raw helper（`ensureDirectoryScopeSupported`）分开测量；首次、同 reader 重复 100 次、刷新后首次与重复 100 次均有样本；删旧→通过、删新→409 两个场景计时；枚举/计算次数由 `scopeSupportCacheSize` 断言钉死（每 reader key 恰好 1 次计算，关闭后 key 清理）。原始数字见 [Devin-QA24-Remediation-2026-10-24](evidence/Devin-QA24-Remediation-2026-10-24.md) 与 [p00-service-cache.txt](evidence/qa-20261024/p00-service-cache.txt)。原生Lucene writer建10万唯一path_scope文档，分全新、混合、删旧、删新、全删leaf；先原开放range、再统计快路径、再reader缓存。
记录首次、同reader重复、刷新后首次，至少100次热样本，p50/p95、term枚举/精确查询调用次数及可得分配统计；不可得指标写明，不杜撰。比较numDocs与maxDoc的两个反例必须有正确性测试。helper=null、I/O失败不缓存、并发refresh/close和key清理用测试证明。
top=1不约束重写成本；普通单测不设脆弱耗时阈值。没有实测不得声称缓存收益；效果不符先反馈Codex/Claude，不自行换方案。

## P-01 Performance

A阶段为观察并设退化门槛，不宣称Everything级速度。固定夹具分别1万/10万文件元数据及目录记录、其中1万小文本正文；记录来源数、live docs、字符量、存储、CPU/RAM/JVM/参数、索引大小及fixture生成方式。
同机各模式和范围：冷首次单列；热查询预热10次、采样至少100次，记录p50/p95、返回数、错误数；记录来源读取和reader检查代价，索引生成耗时与查询耗时分开。
与同一实现/同样词的无目录查询比较；若连续重复测量中限定p95超过无目录2倍且绝对增加超过200ms，作为P1性能调查，不能无解释放行。阈值是本包拟定的退化审查门槛，不是对所有用户硬件的响应SLA。优化导致规格/索引改动须回评审，不默默加缓存。
首次名称可用时间、持续同步延迟不是A阶段承诺；B/C须建立单独指标。旧docs中的性能数字没有本轮可复现实测，不作为证明。

## Evidence format

每个A包在 evidence/A<n>.md 记录（实施时创建，不预填成功）：
```text
Task IDs / DS IDs:
Code HEAD + working diff / package fingerprint:
Changed files and behavior:
Fixture and actual work/home/index paths:
RED: command, exit, exact intended failure (environment failure excluded)
GREEN: command, exit, test counts, skipped platforms, report references
Manual: M-ID, actual observations, screenshot/log references
Static review: reviewer, findings, corrections, rerun
Not tested / risk / blocker:
Next task and dependencies:
```
A0既有特征测试可写无RED；固定隔离属于测试基础的必要交付，不改生产数据逻辑；A8以用户实际确认及最终提交/PR为证据。证据只记录可核验内容，删除敏感正文/口令；保留必要文件名/path证明时使用夹具。

## Completion gates

- 自动测试、M-01～M-06、P-00/P-01、JVM clean verify和关联回归均有证据，无未关闭P0/P1/未决契约。
- Java编译为类型检查；仓库无专用lint/static-analysis插件，记录人工静态审查，不擅自新增依赖或谎称已执行。
- `openspec validate add-directory-scoped-search --strict`与本次文件空白检查通过；全树AGENTS.md既有EOF空白单列，不能掩盖新增问题。
- 原生native、非宿主平台、真实大规模网络轮询未验证则明确列出，不称全平台通过。
- Codex确认所有真实主机记录后形成验收候选；用户接受后才能G5；交付目标/操作另有授权才G6。验收不能只看任务完成百分比。

## Baseline provenance

2026-10-23此前分析会话，Java17/Maven3.9.12，有限堆-Xms32m -Xmx256m、-DforkCount=0执行43项既有测试全绿，0失败/错误/跳过。该轮已核对旧home/.docfinder不存在，未发生旧数据迁移；此事实不能代替下轮隔离。没有新功能测试、GUI/Web/native或P-01结果。

## 2026-10-24 最新 M/P 快照（覆盖旧“待执行”概括，不删除历史）

实际版本/JAR/环境/步骤见 [独立报告](evidence/Codex-QA-2026-10-24.md) 和 [主机记录](evidence/M-Acceptance-Runbook.md)。M-01部分通过但范围显示失败；M-02残留旧结果失败；M-03仅部分自动时序通过；M-04范围/编码/快捷/复制/400/422/预览通过，完整409/503与真实根等未全跑；M-05规定受控浏览器竞态通过；M-06常规集成通过但实际部分失败与完整主机迁移未完成。8个新增探针7失败/1通过不计入149项既有套件，不混合为“全绿”。

P-01本轮global/scoped热p95=18.46/22.21ms，未触发退化调查门槛；单机合成夹具，不是SLA。P-00服务缓存入口已按QA24-11补证（100热样本/刷新/删旧删新/枚举计数，见Devin-QA24-Remediation-2026-10-24）。Java8真实fork通过；native、POSIX宿主、真实UNC、大网络来源未执行。四个真实配置/来源/分享/历史控制文件前后SHA相同，未声称全业务索引逐字节验证。

2026-10-24 修复批次：QA24-01..11 全部修复并验证（`clean verify` BUILD SUCCESS：Tests run: 164, Failures: 0, Errors: 0, Skipped: 3——PathScopeTest POSIX 用例、TestEnvironmentIsolationTest、SearchHandlerTest POSIX 根断言均按平台跳过）。定向45项回归全绿。待 Codex 复验后 A8 才可推进；A8保持待用户决定。
