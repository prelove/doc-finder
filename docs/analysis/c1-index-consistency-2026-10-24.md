# C1 只读诊断：索引一致性（本地监控 / 网络轮询 / 冷启动对账）

日期：2026-10-24；v2 按 Codex 复核 CR-01～06 修订（[复核意见](c1-index-consistency-review-2026-10-24.md)）；v3 并入隔离验证实测结论（见 §8 与 [验证包](c1-validation-2026-10-24/README.md)）。基线：产品源码同 `91cc637`/`faa5e6d` 无差异。性质：**只读诊断**——静态部分未启动 watcher、未访问业务索引；运行时结论全部来自隔离 QA 根下的探针执行（复现/未复现/不支持分列于验证包）。每项标注实际调用路径与行号，并区分"已证实缺陷（代码路径可直接推出错误结果）/ 静态风险 / 待实测假设"。不设延迟/规模 SLA。

## 0. 系统拓扑（事实）

- `MainWindow` 两个菜单开关：`Enable Live Watch (Local)`、`Enable Network Polling`（`MainWindow.java:48,50,190-191`；`MenuBarPanel.java:117-131`）。
- 本地源（`SourceEntry.network==false`）→ `LiveIndexService`（`MainWindow.java:649-667`）→ `LocalRecursiveWatcher` 事件 → 去抖 500ms（`LocalRecursiveWatcher.java:160-175`）→ `pendingChanges`（`LinkedHashMap`+`pendingLock`，`LiveIndexService.java:38-39`）→ 700ms flush → `LuceneIndexer.upsertFile/deletePath` + `commit` → `onAfterCommit` → `MainWindow.onIndexCommit` → `searchService.notifyIndexCommit()`（`LiveIndexService.java:98-129`；`MainWindow.java:2976-2981`）。
- 网络源 → `NetPollerService.start(minutes)`（`MainWindow.java:529-543`；间隔 `poll.minutes` 默认 10，`ConfigManager.java:154-158`）→ 每周期全树 `walkFileTree` 建"文件-only 快照" → `applySnapshotDiff` → `commit` → `SnapshotStore.save` → `onAfterCommit`（`NetPollerService.java:122-163`）。另有手动 `Poll Network Sources Now`（`MainWindow.java:583-631`）。
- 写串行化：全局公平 `ReentrantLock`（`IndexWriteCoordinator.java:10-33`），live/net/手动索引共用。
- 来源类型链路：`sources.txt` 每行 `path|0/1`；旧单行格式经 `Utils.isLikelyNetwork` 判定（`SourceManager.java:43-66,84-114`；`Utils.java:72-98`：UNC 前缀→true；映射盘经 PowerShell `Get-PSDrive`/`net use`/`wmic DriveType=4`（`Utils.java:60-68`，各 2s 超时）；FileStore 类型含 cifs/smb/webdav 兜底）。`loadEntries()`（旧格式行）会真发外部命令探测；`loadEntriesFast()` 旧行默认 local（`SourceManager.java:116-134`）；`loadSearchRoots()` 严格只读不探测（149-155）。

## 1. 已证实缺陷（静态可推出错误结果；运行时复现状态见验证包）

### D-01a（P1）目录删除/移出不级联：子树文档残留
`LiveIndexService` 收到目录 `DELETE` → `indexer.deletePath(dir)`（`LiveIndexService.java:110-112`）→ `LuceneIndexer.deletePath` 仅 `deleteDocuments(Term("path", exact))`（`LuceneIndexer.java:156-164`）。子树内全部文件/子目录文档留在索引中 → 搜索返回不存在路径。级联删除可用现有 `path_scope` 前缀实现，无需新索引结构。

### D-01b（P1）新建目录自身不注册监听，直接子文件脱监（CR-01 修正）
`loopRun` 对 `ENTRY_CREATE` 目录调 `registerExistingSubDirs(child)`（`LocalRecursiveWatcher.java:144-148`）；该 helper 的 `preVisitDirectory` **跳过 `root` 本身**（91-100 行），启动场景另有 `registerDir(root)`（81 行）而**新建场景没有对应 `registerDir(child)`** → 新建目录自身可能没有监听 key，其直接子文件缺少监控；更深子目录仍被注册，不能笼统说整树失监。

### D-01c（P1）目录元数据/文档可能完全不进索引（CR-01 修正）
`flushPending` 对 CREATE/MODIFY 先 `waitStable(path,2000)`（`LiveIndexService.java:113`），而 `waitStable` 对 `Files.isDirectory(p)` 直接 `return false`（188-190 行）→ 目录的 CREATE/MODIFY 永远走不到 `upsertFile` 的 `indexDirectory` 分支（`LuceneIndexer.java:142-147`）。live watch 路径下**目录文档不产生**（除非经全量索引/手动 Index All）；DELETE 又只删 dir 自身（D-01a）→ folder doc 生命周期断裂。

### D-02（P1）OVERFLOW 静默丢弃，无任何补偿
`LocalRecursiveWatcher.java:135-137`：`if (kind == OVERFLOW) continue;`。溢出期间变更丢失，无重扫/降级/日志标记。**触发概率为平台事件**（原稿"5000 文件必现"表述已删）——验证包 S4/S10 实测：同机两次 3000 文件洪泛一次全入库、一次丢 2958（事件级丢失真实发生，归因 OVERFLOW 还是其他缝隙不可分）；sun.nio.fs 反射注入 OVERFLOW 证明该分支静默跳过、零补偿、循环存活。机制归因未确证，但"丢失会发生 + OVERFLOW 分支无补偿"两段证据互相咬合。

### D-03（P1 拆分为两部分，CR-02 修正）
- **D-03a**：`key.reset()` 返回值忽略（`LocalRecursiveWatcher.java:156`）：reset 返回 false（目录被删/卷卸载/被取消）后 key 失效，`key2dir` 残留；`dir==null` 分支（127-131）同样只 reset 不清理。**根目录自身丢失、父级未监控场景下无显式恢复路径**。**【组件注入已复现】**（验证 S10）：摘除存活 key 的 `key2dir` 项后其事件全部静默丢弃、映射不自愈；删除被监控目录后 `key2dir` 确实残留陈旧映射。
- **D-03b（本机已复现，归因 D-01b）**：被删后重建的同名子目录——验证 S3 实测：父目录 key 存活、`ENTRY_CREATE` 确实触发 `registerExistingSubDirs(child)`，但该 helper 跳过 `child` 自身 → 重建目录永不再获 key、其文件不入索引。即本机上失败走"父 key 存活"分支，根因是 CR-01 跳过自身缺陷而非 key 失效；D-03a（根丢失）分支未实测。

### D-04（P1）冷启动无自动对账 + 开关持久化缺口（CR-03 修正：恢复范围条件化）
- **无自动对账**：LiveIndexService 不做启动 diff；watcher 关闭期间（正常退出、崩溃、toggle off）的本地源变更无自动补偿。**但不等于"只能 Full Rebuild"**：手动 `Index All Sources`（`indexFolders(full=false)`）可 upsert 现存的创建/修改文件；删除/移出旧记录是否残留取决于清理/对账机制——当前增量索引不做清理，残留仍在。
- **`poll.enabled` 只写不读**：`setPollingEnabled`（`MainWindow.java:544,551`）写入 config，但全仓无 `isPollingEnabled()` 调用方（grep 验证）→ 重启后 NetPoller 必为 OFF，需用户每次手动开。Live Watch 无持久化键。缺省 OFF 与"设置未生效"属不同产品/可靠性事项，分列。
- 网络源相对有快照 diff 机制（前提：用户记得开 poller；且快照只含文件，见 D-06）。

### D-05（P2）单点写失败无补偿（CR-03 修正措辞）
- `waitStable` 异常路径落 `return true`（`LiveIndexService.java:183-201`）→ 当稳定继续 upsert；`upsertFile`/`deletePath` 内部吞 `Throwable`（`LuceneIndexer.java:148-150,161-163`）。
- **失败无重试队列**：live 批次先 `takeBatch` 取走再应用，失败后 pending 不回填；且 `touched=true` 仍 commit+notify——**"尝试"被当"完成"**，扫描/尝试数不能当索引成功数。
- 网络侧同型：`safeUpsert`/`safeDelete` 吞单文件错误（`NetPollerService.java:271-283`）。

### D-06（P1）网络快照只含文件：目录增删对 NetPoller 不可见
`scanOneRoot` 仅 `!attrs.isDirectory()` 入 `newSnap`（`NetPollerService.java:211-219`）→ 网络盘目录文档一经写入永不更新：新建目录无 doc、删除/重命名目录的 folder doc 残留。文件层 diff 正确；FOLDER scope 语义受污染。

### D-07（P1）部分扫描无完整性标记 → 可能误删并固化错误快照（恢复条件化，CR-03）
`walkFileTree` 抛错 → catch 返回**部分 newSnap**（`NetPollerService.java:232-234`）；`visitFileFailed` 对拒读文件 CONTINUE（227-230）。`applySnapshotDiff` 对 `o!=null && n==null` 一律 `safeDelete`（261-263）→ 部分可读轮询会把读不到的文件当删除抹掉；`store.save` 在 commit 成功后照常执行（146-148）→ 错误快照固化。**恢复条件化**：下一次**完整且成功**的扫描重新看到该文件会走 `o==null && n!=null` 的 upsert 分支（258-260）恢复——风险是删除已发生、错误状态已保存、恢复依赖后续成功扫描，不能断言永久丢失也不能当无害。根整体不存在时被跳过（127-131），该分支反而安全。**【已复现】**（验证 S5）：ACL 拒读子树 → 当轮误删文档 + 部分快照持久化 → ACL 恢复后下轮重新入库，三段全部实测命中。

### D-08（P1）已入快照但写索引失败 → 静默持续漏索引（与 D-07 不同类，CR-03）
文件已进入保存快照但 `upsertFile`/`deletePath` 失败被吞（或文件属 `isExcluded` 永不可索引——快照仍记录它）：`size/mtime` 不变时 `o==n`，diff 永远不再触碰 → 持续漏索引直到元数据变化、补偿机制或重建。快照保存顺序本身正确：commit 失败则 save 跳过（`NetPollerService.java:135-160`），不会把整体失败写成完成；缺口在单点失败粒度。**【旁证/待验证】**（验证 S6）：排除文件入快照永不索引、`o==n` 永久漏检的机理已复现，但真实写失败注入需产品接缝——本项维持旁证而非已复现缺陷。

## 2. 待实测观察项（原缺陷降级，CR-04/06）

- **O-01（原 D-09）** `merge` 对同路径快速事件序列有损合并（DELETE→CREATE→MODIFY 等）——"终态仍正确"的多数情形成立；仅在给出"最终磁盘状态与索引确实不一致"的事件序列后才升级为缺陷。pendingChanges 类型：`LinkedHashMap`+synchronized（非 ConcurrentHashMap，已修正）。
- **O-02（原 D-10）** 同一文件系统对象在同一 WatchService 重复 `register` 按 Java8 Watchable 契约**返回代表既有注册的 key**，不能从调用两次推出双 key；别名/不同 provider 对象身份的异常情形另列待实测。
- **O-03（原 R-03 修正）** live 积压不止 map：flusher 持续向单线程 worker 提交批次，积压可转移到 executor 无界队列，只观察 map 大小不足以评估。

## 3. 静态风险（结构存在、触发条件需实测）

- **R-01** 网络盘/UNC 被用户误标 local 时 WatchService 行为未定义（注册失败或静默无事件），接线侧按 `network` 标志分流仅在标志正确时成立。
- **R-02** `LocalRecursiveWatcher.pending` 与 `LiveIndexService.pendingChanges` 均无上限（前者 `ConcurrentHashMap`、后者 `LinkedHashMap`+lock），海量事件下内存/积压行为未测（不承诺规模）。
- **R-04** `onIndexCommit` 只刷 NRT reader，不主动通知 UI 结果集——搜索中恰逢提交的可见性语义依赖既有 reopen/token 机制。
- **R-05** `SnapshotStore.keyForRoot` 分隔符替换（`SnapshotStore.java:18-25`）：`F:\a\b` 与 `F:\a_b` 存在键碰撞。路径含 `|` 时 `split("\\|",-1)` 得到 >3 段——代码只判 `<3` 跳过（36 行），**更可能把路径截断、size/mtime 错位或解析成 0**，不是"读不到"。Windows 文件名不允许 `|`（来源根含 `|` 还受 sources.txt 格式约束，分列）；POSIX 合法路径可含 `|`。
- **R-06** `isLikelyNetwork` Windows 分支起外部进程（PowerShell/cmd/wmic，各 2s 超时）：`loadEntries()` 在 UI worker 调用（`MainWindow.java:522`）；wmic 在新 Windows 已弃用可能不存在。

## 4. 测试覆盖现状（事实）

`src/test/java` 无 watch/poller/snapshot/liveindex 测试类（find 验证）。**注：无专门测试类 ≠ 执行覆盖率为 0**，仅表示无钉住该包行为的测试；A 阶段 harness 不覆盖 watcher 生命周期。

## 5. 可复现验收矩阵（C1 用例候选；注入方式与限制见验证包）

| ID | 注入 | 期望（修复后）/ 现状推断 |
|---|---|---|
| C1-01 | live watch ON；建 `dir/` 内 3 文件→索引→删 `dir` | 期望：子树文档全消；推断：仅 dir doc 删（D-01a） |
| C1-02 | 同上将 `dir` 移到 `dir2` | 期望：旧消失+新可查；推断：双向污染（D-01a/b/c） |
| C1-03 | 受控事件注入 OVERFLOW（接缝）+ 文件洪泛平台观察 | 期望：溢出后重扫补齐；推断：静默丢（D-02） |
| C1-04 | 删被监控子目录后重建同名；区分父 key 存活/根丢失两子例 | 期望：恢复监控；推断：根丢失不可恢复，子目录视父 key（D-03a/b） |
| C1-05 | 优雅关闭隔离实例→改文件→重启 | 期望：对账捕获；推断：无对账+开关复位（D-04，优先关进程而非 kill 任意 Java 进程） |
| C1-06 | upsert 期间单文件写失败 | 期望：重试/告警；推断：吞错照常（D-05/D-08） |
| C1-07 | 网络源建空目录→poll；删目录→poll | 期望：folder doc 同步；推断：目录永不更新（D-06） |
| C1-08 | 子树部分拒读→poll→恢复 ACL→poll | 期望：拒读不判删；推断：当轮误删、下轮完整扫描恢复（D-07 条件化） |
| C1-09 | `netPollToggle` ON→重启应用 | 期望：恢复或明示复位；推断：复位 OFF（D-04） |
| C1-10 | 新目录自身注册：建空 dir→FOLDER 记录；注册完成后向其直写文件；已填充子树整体移入 | CR-01 三项子用例 |
| C1-11 | `a\b` vs `a_b` 双根快照键、`|` 路径行格式往返 | 键碰撞+格式错位验证（R-05/CR-05） |
| C1-12 | A↔B 交换改名、大批次积压 | 终态一致性观察（O-01/O-03，不设 SLA） |

## 6. 依赖关系与建议 C1 范围

- `path_scope` 提供子树级索引定位 → 级联删除可复用 `PrefixQuery(path_scope)`。
- `SnapshotStore`+`applySnapshotDiff` 模式可泛化为启动对账（目录须入快照，与 D-06 同一改点）。
- 重试/失败队列无基础设施，需新建。
- 建议优先级：D-01a/b/c、D-02、D-03a、D-04、D-06、D-07、D-08 核心；D-05 失败语义次之；O-01/O-02/R-05 边界随主修复顺带；R-01/R-02/R-04/R-06 进验收矩阵实测。

## 7. 不在本诊断范围

本轮仍为只读：运行时行为以 `c1-validation-2026-10-24/` 的隔离探针结果为准（复现/未复现/不支持分列）；UNC/真实网络盘、POSIX 未验证；不给 SLA；验收矩阵经隔离夹具执行后才可宣称修复。

## 8. 隔离验证结论索引（2026-10-24 已执行）

探针与原始日志：`c1-validation-2026-10-24/`（README 含启动命令、隔离路径、探针迭代史）。隔离 QA 根 `target\c1-qa`，索引 `work\.docfinder\index`，全部写删/ACL 经前缀守卫且 ACL 已还原。

| 缺陷 | 场景 | 实测判定 | 说明 |
|---|---|---|---|
| D-01a | S2 | **复现**（含一处机制修正） | 移动后旧路径幽灵+新路径不入库+无 folder doc；随后删除已移动子树时旧路径文档被清——机制为 WatchKey 跟随目录对象、`key2dir` 保留旧名导致子事件按旧路径上报后精确删除，**非级联修复** |
| D-01b/c | S1 | **复现** | 新目录无 folder doc、直接子文件脱监；`newdir/sub` 深层文件正常入库（与 CR-01 因果一致） |
| D-02 | S4 + S10 | **丢失已观测 + 分支无补偿（注入证实）** | 3000 文件洪泛本机丢 2958（事件级丢失真实发生，机制归因未确证）；组件注入 OVERFLOW 证实静默跳过、零补偿、循环存活 |
| D-03a | S10 | **复现（组件注入）** | 摘除存活 key 的 `key2dir` 项 → 事件全部静默丢弃、映射不自愈；删除被监控目录后 `key2dir` 残留陈旧映射（reset() 返回值忽略的直接证据） |
| D-03b | S3 | **复现** | 重建同名子目录不恢复监控；本机根因为 D-01b 而非 key 失效 |
| D-04 | S8 | **复现** | 关闭期间创建/修改/删除三项全部无对账；`poll.enabled` 持久化属 UI 层未验证 |
| D-06 | S9 | **复现** | 轮询不建空目录 doc、已删目录 folder doc 残留 |
| D-07 | S5 | **复现** | 拒读子树当轮误删（`PollStats: scanned=1 deleted=1`）+ 错误快照持久化 + ACL 恢复后下轮条件恢复，三段全中 |
| D-08 | S6 | **旁证/待验证** | 排除文件入快照永不索引、`o==n` 永久漏检机理复现；排除文件不等同真实写失败，真注入需产品接缝 |
| R-05 | S7 | **复现** | `a\b` 与 `a_b` 快照文件碰撞（数据互相覆盖）；含 `|` 行静默错位解析 |

未执行/未覆盖：O-01 交换改名、O-03 积压规模、R-01 UNC 误标、R-02 海量事件内存、R-04 搜索可见性、R-06 外部探测开销（均为规模/平台项，见验证矩阵 C1-12 等待办）。断言口径已加固：`MultiBits.getLiveDocs` 过滤已删未合并文档、`PollStats` 断言轮询真实执行、组件注入结果单独标记。探针迭代中的误报/残留态结果均作废留痕（`partial-scan-superseded.log`、`inject.log` 首跑）。最小测试接缝建议见验证包 README 限制节。
