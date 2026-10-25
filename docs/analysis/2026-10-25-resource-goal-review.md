# 检索新鲜度与扫描资源：目标复验

日期2026-10-25；审查代码 `ad363dacf550573e237cd6e4e3cd332490fd481c`，远端已确认。交付JAR SHA256：`213795F70914A8B8CF46F93E2194819211763573843F07DCD253CB448DF8588D`。

**最新结论：c1b56e6复验仍为CHANGES_REQUESTED；本页前半保留ad363da历史，最新结果与修复任务见文末。**

**结论：CHANGES_REQUESTED，任务目标尚未完成。** 游标补足和若干单实例容量限制有实质改善；结果正确/足量、恢复文件重新出现、当前窗口自动更新及跨生命周期资源边界仍有缺口。不能以现有套件全绿代替目标验收。

## 已验证范围

- 独立Git快照、512MB fork运行 `mvn -B clean verify`：224总数、222通过、0失败/错误、2个POSIX跳过，BUILD SUCCESS。额外argLine探针已启用，因此与实现者3跳过口径差1项。
- 直接运行交付JAR和真实Java8 1.8.0_40，临时索引、配置、模拟阻塞任务均隔离；确认下表的受控复现。非中断任务由统一latch在finally释放，探针最终正常退出；没有用真实NAS断网或真实业务索引做破坏测试。
- 原来的512窗口重复取Top-N循环已改为searchAfter单调游标，1024幽灵及全幽灵回归通过。读取器acquire后的finally释放路径存在。
- 首次探针受Tika冷初始化影响超时，未用作完整通过证据；预热后最终 `results2.log` 正常退出。候选顺序用短幽灵名、长有效名固定，避免切片内同分重排掩盖问题。

RG-01/02同时记录用户本轮新明确的恢复与窗口更新要求；监控未开启的恢复现象不能据此认定“开启watch仍然失效”。本次新增回归、既有缺陷和新增验收闭环分别以各行证据为准。

## 必须修正的目标缺口

| ID | 优先级 | 发现及证据 | 修复要求 |
| --- | --- | --- | --- |
| RG-01 | P1 | 用户报告移出后恢复仍查不到，重启也不恢复；用户确认监控“未开启或不确定”。隔离复现：清理commit后恢复真实文件，重新查询0条，重开服务仍0条 | 自动同步启用状态必须清楚、持久化和恢复；监控开启时CREATE/恢复可靠upsert，关闭时明确静态索引限制并提供受控补账入口。缺失记录无法靠查询存在性校验找回，不能要求用户重建索引绕过 |
| RG-02 | P1 | MainWindow.onIndexCommit:3121仅notifyIndexCommit，不重跑当前查询；真实窗口探针notify=1、search=1、旧行仍1 | 索引实际变更后合并触发当前已生效查询刷新；保持输入草稿、目录/过滤、排序、选中项和预览，过期回调不覆盖新输入；空查询不搜，不形成清理commit→重查的自激循环 |
| RG-03 | P1 | NetPollerService:253的!running跳过写入；MainWindow:697创建专用手动poller后直接pollNowAsync，从不start。实测scanned=1、created=0、index liveDocs=0 | 独立手动轮询能执行真正增改删，区分尚未启动定时器和已关闭服务；补真实手动入口回归，不能仅用先start的测试 |
| RG-04 | P1 | LuceneSearchService:483、659、969使用服务全局searchGeneration。受控并发：A应有2条，B启动后A只1条且hasMore=FALSE，B正常1条 | 取消按调用方/查询会话隔离；不同Web客户端和桌面不可互相取消；真正取消不能返回伪完整成功页 |
| RG-05 | P1 | existencePool:170每服务2线程，close:1188仅shutdownNow。模拟不响应中断I/O，关闭重建4次后8个worker、4个池仍未terminated | 全生命周期有总上限及可恢复退出策略，不能重开服务就另起一组后台I/O线程；测试关闭/重建后的慢I/O，不只测试快文件 |
| RG-06 | P1 | purgeGhostBatch:916-943在全局写锁内同步读来源和stat，绕过250ms/退避。受控provider阻塞于清理阶段时，另一个无关写入被锁阻塞 | 慢I/O不得持全局写锁；使用可取消/受控校验，并在真正写入前复核服务/索引代次或记录版本，保护恢复文件。stat后closed未复核的窗口也须覆盖 |
| RG-07 | P1 | UNCERTAIN返回false后照常计入额度（753、979）。阻塞校验池模拟慢I/O：返回的首项为实际不存在的probe-a.txt；后面有真实有效文件，结果仍被未知项占满且hasMore=TRUE | 未确认项与已验证名额/完成状态分清；保留索引不等于可以把未知项算作已验证足量，需继续处理有效候选或明确校验未完成 |
| RG-08 | P1 | classifyPath:707从parent开始；路径恰是消失来源根时越过边界，实测GHOST而非ROOT_UNREACHABLE。rootsFor:811及purge:922还将来源读取失败吞成empty、导致失去边界 | 来源自身不可达和来源配置失败必须保守；不能爬出来源凭上级存在就清理来源记录；覆盖来源根FOLDER及来源配置失败 |
| RG-09 | P2 | 20秒ghost缓存不验恢复状态。持写协调锁延迟purge后恢复文件，文件真实存在但再次检索0条 | 创建/更新/恢复事件使负缓存失效，或展示前安全复核；恢复后的新记录不能被旧ghost判定遮蔽 |

## 扫描/资源的静态未闭合项

- **注册过载丢任务（P1）**：LocalRecursiveWatcher:77只识别BootstrapTask，但128/196用submit包装成FutureTask，拒绝处理不会通知overflow，容量满时静默丢注册。新建目录自身还在143被跳过，失效key删除映射后也未安排注册恢复；不能把有界队列等同监控完整。
- **停止后仍可写（P1）**：NetPollerService:253只在取得写协调锁前检查running；中断188-199提前释放single-flight，旧root任务还可能运行；69+来源提交也能因4+64容量拒绝而提前释放标志。LiveIndexService取出批次后同样缺锁内closed/代次复核。需要受控持锁停止、重复手动/自动轮询及过载测试。
- **完整快照仍有高峰内存风险（P2）**：NetPollerService:213/219持旧新全量map，284-286再建并集set，最多四来源并行。流式读文件只消除一份文本副本，没有实现分块差分或去除并集，不能宣称扫描内存已独立于来源规模受控。
- **解析和失败恢复未证明（P2）**：LuceneIndexer:442取消Future不自动移除队列项；两个不可中断解析占住worker后idle timeout不生效。容量拒绝又被记成相同size/mtime持续跳过，需验证恢复策略、队列回收和文件句柄，不只是线程数封顶。
- **快照/写失败安全（P2）**：单文件索引失败仍被吞，poller可能保存新基线后不再重试；SnapshotStore根键仍会碰撞，多来源可共用tmp；save失败路径没有临时文件清理。上述均为静态审查发现，尚未在本轮执行故障复现。

## 资源证据的实际边界

ResourceStabilityTest的40轮×2次搜索及6×3次服务启停确实通过。但poller start后立即stop，未等3秒首轮扫描；watch没有制造事件，线程只按两个名称前缀计数，未测句柄、队列峰值、真实扫描、大来源快照或不可中断解析。64MB堆增长容差也不能代替连续多轮稳定趋势。现有结果仅支持快速本地查询/空闲启停，不能覆盖用户的全部扫描资源目标。

## Devin下一批任务

1. 先闭合RG-01～04的业务路径：明确自动同步状态/恢复入库、当前查询自动刷新、真实手动轮询及独立请求取消；补移出→清理→放回→自动恢复的端到端回归。
2. 修正RG-05～09及注册过载/停止代次：所有慢校验/清理都遵守同一资源与取消边界，不能把阻塞转移进写锁或旧服务；避免缓存和未知项吞掉有效结果。
3. 完成真实扫描/积压的资源验证，报告首屏/补满时延、预热后存活堆、线程/句柄、队列/缓存峰值及关闭后的回落。旧B验收保持不变，本轮改动仍未获接受，不再报告“全部完成”。

本机证据：`C:\Users\Administrator\AppData\Local\Temp\docfinder-resource-qa-20261025-9zaldveb\`。完整日志clean-verify.log、baseline-reports；probes/src/GoalProbe.java、UiRefreshProbe.java；最终复现日志probes/results2.log、restoration.log、ui-refresh.log。四份真实config/sources/shares/history哈希均未变。


## c1b56e6复验：真实恢复场景仍未完成

代码 `c1b56e65f83771077aaea64363bd537dc10341be` 与远端已核实；交付JAR SHA256 `4F708F4FA6C068DDFCB0F7466A3DF7DB0BC052415AA0F10AB047D44546B32AF2`。独立Git快照512MB fork执行clean verify：230总数、228通过、0失败/错误、2个POSIX跳过。实现者3跳过与本轮差异为argLine探针已启用。

**本轮主目标仍未通过，不能把“持续调用搜索后的恢复测试通过”当作“无需输入的自动恢复通过”。**

### 实际交付JAR＋Java8隔离复现

| ID | 优先级 | 场景及实际结果 | 必须修订 |
| --- | --- | --- | --- |
| R2-01 | P1 | 真实MainWindow、关闭watch：移出文件→搜索消失→等待删除/自动刷新完全静止3.5秒→放回→再等3.5秒。文件存在，但searchCalls保持3，窗口0行、索引0文档；手动再搜索后才恢复为1行 | 恢复必须有自主触发：有界后台对账/事件机制，不能只依赖搜索kick。不要靠恰好撞上上一轮700ms刷新窗口掩盖问题 |
| R2-02 | P1 | 已purge文件放回后重开服务，连续6次默认全局NAME搜索：文件存在、结果0、sourceLoads=0、墓碑1；显式选择目录后才恢复为1 | lastRootsSnapshot最初为null，零命中查询不会执行rootsFor，kick永远无法启动。首个非空全局查询/启动必须能初始化恢复所需来源，仍保持空查询兼容边界 |
| R2-03 | P1 | 前256个墓碑仍缺失，仅第257个文件恢复；反复搜索10次，目标文件存在但索引仍0，257个墓碑都保留 | 恢复批次使用轮转/游标，批次上限不能永久饿死后部记录；失败/超时来源不能堵住其他来源的恢复 |
| R2-04 | P1 | 真实HTTP同IP两个独立请求，阻塞A后执行B：A应2条却只1条，B正常1条。直接null-tag服务探针已恢复为A=2 | SearchHandler:91按IP设clientTag，仍把同机不同浏览器/标签页或代理后用户合并。用真正的请求会话/标签页身份；没有显式会话的旧调用方保持独立 |
| R2-05 | P1 | 私有清理入口受控65条批次：锁外确认后等写锁，锁内恢复并upsert第65条、等待400ms后放锁；旧清理结束后文件存在，但该新文档又被删为0 | 不能只对等待>300ms的前64条复核。所有删除须有服务/索引代次或记录版本保护；慢I/O移出全局写锁，并覆盖快速恢复及第65条以后 |

以上探针均直接运行交付二进制。R2-05通过反射驱动实际批处理入口并控制写锁，证明生产批次边界，非真实NAS断线测试。进程正常退出、latch与线程均在finally释放；配置/来源/分享/历史四文件哈希未变。

### 本轮已确认改善

- 真正未start的手动poll：scanned=1、created=1、liveDocs=1，RG-03关闭。
- null-tag独立调用：A应2实际2、B=1；全局互取消已改进，但HTTP映射仍需R2-04修正。
- 消失来源根分类已是ROOT_UNREACHABLE，旧的根自身越界判GHOST用例关闭。
- 共享EXISTENCE_POOL的生命周期新回归通过，watch注册execute/新目录根、Tika队列purge及快照并集删除等改进存在；不将这些局部通过等同全部扫描资源已验证。

### 仍有高优先级静态缺口

1. **写锁内慢I/O仍存在**：LuceneSearchService:1074-1076的最多64次复核仍直接stat；:1204-1216恢复持锁执行Files.exists与upsertFile（含文件属性/MIME/Tika解析）。条数限制不等于时间限制。锁外清理:1049也直接运行在每服务ghostCleaner上，绕过共享existence池，慢I/O时跨重开服务仍可能累积其他线程。RG-05/06只部分关闭。
2. **停止后错误快照可被固化**：NetPollerService:298-300的closed只退出lambda，:316仍可能save未应用diff的新基线。pendingRoots只在已运行Callable finally减，取消尚未启动任务不会减；可能永久占single-flight。需真实扫描＋持锁停止/取消回归，不以空闲start/stop代替。
3. **自动刷新会打断用户操作**：refreshResultsIfQueryActive直接doSearch，populateResults清排序、重建表且选第一行。需区分同一查询的后台刷新与用户新查询，保留排序、所选路径、预览和编辑草稿，并避免持续commit永远重启debounce导致刷新饥饿。
4. **未知空态仍不诚实**：全部候选无法验证时，hadUncertain仍可能伴hasMore=FALSE；桌面空列表分支不显示unverified提示，呈现普通No results。未知项不占额度已改善，但不可声称已确认没有结果。
5. **恢复记录完整性/重试**：超过4096或加载时超30天会丢掉墓碑，当前没有完整来源补账兜底；upsert吞错、commit前remove墓碑可能丢恢复依据。范围应明确并有持久补账，而非无声遗忘。Tika失败TTL仍需新的upsert才检查，轮询size/mtime不变时不自动重试；大快照仍两份全量map，慢解析/扫描/句柄峰值未实测。

### 下一步仅针对目标闭环

先修R2-01～03，增加“静止后放回且无任何输入”“重启后仅默认全局搜索”“257个以上墓碑”的真实回归，测试不得反复主动搜索来制造恢复触发。随后修R2-04/05及上述写锁/停止安全、刷新保持与未知状态。保留已修好的独立调用、手动轮询和来源根保护。最后给出真实扫描/慢I/O的资源证据再申请验收，不重建业务索引绕过问题。

原始证据：`C:\Users\Administrator\AppData\Local\Temp\docfinder-resurrection-qa-20261025-l1wo293l\`，clean-verify.log、baseline-reports、summary.json；probes/src下ResurrectionUiProbe.java、ColdRestoreProbe.java、TransportProbe.java、PurgeRaceProbe.java；对应ui-results.log、cold-results.log、transport-results.log、race-results.log。所有探针使用独立工作目录及临时索引，服务器已停止。
