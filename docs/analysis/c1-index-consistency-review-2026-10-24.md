# C1只读诊断复核与后续安排

日期：2026-10-24；复核基线 `faa5e6d`。产品源码相对诊断引用的 `91cc637` 没有变化。结论：**ACCEPT_DIRECTION_WITH_DOCUMENTATION_CHANGES**。目录生命周期、溢出/失效、扫描完整性及失败写入语义值得纳入C1候选；原报告部分因果与恢复范围需修订，不能把未执行的矩阵当运行时复现。

本轮仅阅读源码与Java8官方契约，未启动watcher、未执行新测试、未访问业务索引。原诊断由Devin负责修订；本文件不替它修改或冒称C1实现已批准。B0.3文档修订可继续，不需要等待C1方案立项。

## 认可的主要路径

- D-01：deletePath只删精确path，目录删除不能由该API级联删除后代。目录整体移动时旧记录/新位置补扫确有缺口。
- D-02：OVERFLOW被continue丢弃，无重扫/对账入口；但触发概率要实测，不能写5000个文件“必现”。
- D-03：reset返回值被忽略、无效key留在key2dir，根目录丢失后没有显式恢复。重建子目录的事件因果需要拆开，见下文。
- D-04：启动无对账；poll.enabled写入后缺少启动读取接线；缺省OFF与设置未生效、冷启动补齐是不同产品/可靠性事项，建议分别记录。
- D-05/08：Live批次先从pending取走，失败缺少补偿；upsert/delete吞单点错误，NetPoll仍可能提交文件系统快照，下次相同size/mtime不重试。不能把扫描/尝试数当索引成功数。
- D-06：网络快照只收文件，无法完整维护目录文档。
- D-07：部分扫描没有完整性标记，却对缺失记录执行删除并保存快照，存在误删索引项风险。

上述为可由当前路径支持的静态结论，运行时的触发条件、平台差异和恢复效果仍需将来隔离验证。

## 必须修订的事实与因果

### CR-01：补上新目录自身未注册、未索引（原D-01/03/06不完整）

`LocalRecursiveWatcher.loopRun`遇ENTRY_CREATE目录，调用`registerExistingSubDirs(child)`（约144–148行）；helper的preVisitDirectory明确跳过`root`（约98–100行）。启动场景有单独registerDir(root)，但新建场景没有对应的registerDir(child)。因此新建目录自身可能没有监听key，其直接子文件变化缺少监控；helper仍可能注册更深目录，不能笼统说整树完全不注册。

`LiveIndexService.flushPending`对CREATE/MODIFY先waitStable（约113行），而waitStable对`Files.isDirectory(p)`直接false（约188行），故正常目录CREATE/MODIFY不会调用upsertFile的目录分支。原报告“新目录仅upsert目录本身”的叙述不成立，目录自身也可能缺索引记录。

请分别列成“新目录自身注册”和“目录元数据/子树补扫”两项，矩阵新增：创建空目录→FOLDER记录；待注册完成后向新目录直接写文件；已填充子树整体移入。不能仅验证已有子树更深目录的事件。

### CR-02：无效key与父目录事件不能混为一谈

父目录的key若仍有效，子目录重建仍可能由父key产生ENTRY_CREATE；不能说该事件必然随死掉的子key一起消失。当前新目录重新注册又受到CR-01的“跳过自身”影响，应分离两个原因。根目录自身消失、父级未被监听、原key失效的恢复缺口则单独验证。

### CR-03：恢复范围改为条件化，不写“永远/只能Full Rebuild”

- 本地停机期间变更没有自动对账，不代表只有完整重建能恢复。手动Index All Sources可补创建/修改的现存文件；删除/移出旧记录是否残留要另看清理/对账机制。
- D-07错误删除后，下一次完整且成功的网络扫描若重新看到该文件，会走`o==null && n!=null`的新增upsert分支（NetPollerService约255行），因此不能断言下轮永远无法发现。风险是当前错误删除、错误状态保存及恢复依赖后续成功扫描。
- D-08属于另一类：文件已进入保存快照但索引写入失败，size/mtime不变时diff没有变化，可能持续漏索引直到元数据变化或补偿/重索引。不要把它与D-07混写。

### CR-04：D-09/D-10不能列成已证实错误结果

D-09自身已承认“终态仍正确”，应降为待实测合并语义观察，不作为P2已证实缺陷。若要升级，先给出最终磁盘状态与索引确实不一致的事件序列。

D-10“重复register产生两个WatchKey”不符合一般注册契约：同一文件系统对象已在同一WatchService注册时，register返回代表既有注册的key；不能仅从调用两次推出双key。别名/不同provider对象身份另列待实测，不等同于一般重复调用。[Java8 Watchable契约](https://docs.oracle.com/javase/8/docs/api/java/nio/file/Watchable.html)。reset无效的意义可按[WatchKey契约](https://docs.oracle.com/javase/8/docs/api/java/nio/file/WatchKey.html)表述。

### CR-05：SnapshotStore分隔符与平台夹具准确化

键替换带来的碰撞风险成立。但路径含`|`时split常得到大于3段，当前仅检查`sp.length<3`，未必“跳过”，还可能将路径截断、size/mtime错位或解析成0。矩阵应覆盖格式损坏，而非只断言读不到。

Windows文件名不允许`|`；POSIX下可用合法来源根中的子文件名含`|`验证。若来源根本身含`|`，还受sources.txt格式约束，应分开说明，不能拿非法Windows路径当通用夹具。

### CR-06：内存队列类型与故障注入矩阵

LiveIndexService.pendingChanges为受pendingLock保护的LinkedHashMap（约39行），不是与watcher.pending同型ConcurrentHashMap。两者无上限的方向成立；另有flusher不断向single-thread worker提交批次，积压可能转移到executor无界队列，不能只观察map大小。此处先列结构风险，未测时不承诺延迟或内存规模。

OVERFLOW用可控事件注入/测试接缝作为确定性主用例，文件洪泛作为平台集成观察；冷启动测试优先关闭隔离测试进程/实例，而非kill任意Java进程。无专门watch/poller/snapshot测试类的事实可以保留，不等于已测得代码执行覆盖率为0。

## 当前任务队列

1. **Devin继续完成B0.3方案**：N02-01～06全量同步；只分开名称/正文两个列表还不自动证明名称列表内部多词整名/前缀/子串的Top-N次序，需按原复核要求给出结构保证。不要提交半成品或改产品代码。
2. B0.3交出后，Codex/Claude评审期间，Devin按CR-01～06修订原C1诊断，改正事实、级别与验收矩阵，保留“只读、未执行新测试”。完成文档可单独提交，不夹带B草案或其他人的暂存内容。
3. C1修订文档接受后再准备独立OpenSpec变更和产品范围；当前不创建空change，不开始C1实现。C2解析队列/原子切换及长期D升级不因这份诊断自动启动。

原诊断：[c1-index-consistency-2026-10-24.md](c1-index-consistency-2026-10-24.md)。本评审不改变已验收归档的A阶段结论。


## 用户最新优先级与有限复跑

用户要求以检索功能交付为主，C1追加实验暂停，保留成果即可。Codex已在独立临时根复跑D-07：确认当轮误删索引项、部分快照保存及权限恢复后补回；排除文件样例仅是排除策略对照，不能证明真实写失败。ACL比较中有效ACE/owner/group/保护状态相同且无deny残留，SDDL文本不同不继续作为字节形式问题追查。此处不升级C1产品实现，也不阻塞当前B实装。
