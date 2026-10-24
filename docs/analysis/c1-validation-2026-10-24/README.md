# C1 隔离验证包（2026-10-24，终版加固）

对应诊断：`../c1-index-consistency-2026-10-24.md`（v3，并入本轮实测）。性质：**只读执行验证**——探针直接调用产品类（`LiveIndexService`/`LocalRecursiveWatcher`/`NetPollerService`/`LuceneIndexer`/`SnapshotStore`），不修改产品源码、不接触业务索引、无新增依赖。组件注入通过反射访问产品私有字段与 JDK 内部 API，未改产品；做不到的点明确标注并给出最小测试接缝建议。当前缺陷不要求修成绿。

## 环境与隔离路径

- 主机：Windows，JDK 17（产品字节码 Java 8）；产品 JAR `target/docfinder-1.0.0.jar`。
- QA 根：`E:\idea_projects\doc-finder\target\c1-qa`
  - `work/` = 探针启动 `user.dir` → `AppPaths.getBaseDir()` → `work/.docfinder/{index,snapshots,...}`（隔离索引，非业务索引）
  - `source/` = 测试数据树（s1..s10 各场景独立子树）
  - `home/` 预留
- 隔离守卫：探针启动校验 `user.dir == qaRoot/work`、`indexDir` 以 `qaRoot` 为前缀；所有写/删/移动/ACL 经 `guard()` 断言目标在 QA 根内，守卫失败即 `System.exit(2)`；ACL 在 `finally` 中 `/remove:d` + `/reset /T /C` 还原。运行前清空 `source/`、`index/`、`snapshots/`（均在 QA 根内，经前缀校验）。

## 启动命令

```powershell
javac -encoding UTF-8 -cp "E:\idea_projects\doc-finder\target\docfinder-1.0.0.jar" `
  -d "E:\idea_projects\doc-finder\target\c1-qa\classes" `
  "E:\idea_projects\doc-finder\docs\analysis\c1-validation-2026-10-24\C1ValidationProbe.java"

# 每个场景在 work 目录下启动（user.dir 决定隔离根）；
# --add-opens 仅 inject 场景需要（sun.nio.fs 深层反射），对其他场景无害
cd E:\idea_projects\doc-finder\target\c1-qa\work
java --add-opens java.base/sun.nio.fs=ALL-UNNAMED `
  -cp "..\classes;E:\idea_projects\doc-finder\target\docfinder-1.0.0.jar" `
  C1ValidationProbe <scenario> "E:\idea_projects\doc-finder\target\c1-qa"
```

场景名：`live-newdir | live-movedel | invalid-key | overflow | partial-scan | write-fail | snap-keys | coldstart | dir-record | inject | all`

## 断言方法（加固项）

- **存活判定**：`findDoc`/`countDocs` 遍历 `IndexReader.maxDoc()` 时用 `MultiBits.getLiveDocs(reader)` 过滤"已删除未合并"文档——否则误删类场景会把死文档当存活（本轮曾因此误报，见迭代史）。
- **轮询已执行**：每轮 `pollNowAsync().get()` 返回 `PollStats` 并打印 `scanned/created/deleted`——例如 S5 拒读轮 `scanned=1 deleted=1` 直接证实"扫到 1 个、删了 1 个"，杜绝"轮询静默空转"污染判定（早前 `pollNow()` 在 `running=false` 下静默返回的教训）。
- **组件注入标记**：凡用反射操纵 `key2dir`/`sun.nio.fs` 内部的结果标注 `[component-injected]`，与真实 OS 事件区分。

## 结果矩阵（终版，全部以 liveDocs + 执行断言口径）

| 场景 | 对应缺陷 | 判定 | 实测行为 |
|---|---|---|---|
| S1 live-newdir | D-01b / D-01c | **REPRO×2 / NOREPRO×1** | 新建目录自身无 folder doc（`waitStable` 拒目录）；直接子文件不入索引（目录自身未注册 WatchKey）；`newdir/sub` 深层文件正常入库——与 CR-01 因果拆分一致 |
| S2 live-movedel | D-01a | **REPRO×4 / NOREPRO×1** | `mv→mv2`：旧路径幽灵文档残留、新路径不入库、`mv2` 无 folder doc。随后 `deleteTree(mv2)` 时旧路径 `mv/f1` 文档消失——机制为 WatchKey 跟随目录对象、`key2dir` 保留旧名导致子事件按旧路径上报后精确命中（陈旧映射副作用，**非级联修复**） |
| S3 invalid-key | D-03a/b | **REPRO×2** | 删除被监控子目录 `sd` 重建同名后，父 key 存活并触发 `registerExistingSubDirs(sd)`，但该 helper 跳过 `sd` 自身 → `sd` 永不再获 key、`after.txt` 不入库。本机根因是 D-01b 而非 key 失效 |
| S4 overflow | D-02 | **REPRO×1 / UNSUPP×1** | flood 目录预注册后洪泛：3000 文件创建耗时 24.8s、仅 42 入库，2958 文件永未索引——**事件级丢失在本机真实发生**；丢失机制是否 OVERFLOW 无法区分（无接缝），与 S10 注入结果合并解读 |
| S5 partial-scan | D-07 | **REPRO×4** | poll1 `scanned=2`；ACL 拒读 `blocked` 后 poll2 `scanned=1 deleted=1`——**b1 当轮被真删、部分快照 `{k1}` 照常持久化**；`/remove:d` 后 poll3 `scanned=2 created=1` 恢复。误删+快照固化+条件恢复三段全部复现 |
| S6 write-fail | D-08 | **旁证（待验证）** | 排除文件 `~skip.tmp` 入快照但永不索引 → `o==n` 恒成立。**仅为机理旁证，不等同真实写失败**——真写失败（磁盘满/锁冲突）需产品侧接缝，D-08 判定维持"待验证" |
| S7 snap-keys | R-05 | **REPRO×2** | `keyForRoot` 碰撞：`a\b` 与 `a_b` 共用快照文件互相覆盖；含 `|` 行静默错位解析为截断路径+size=0 |
| S8 coldstart | D-04 | **REPRO×3 / UNSUPP×1** | 关闭期间创建/修改/删除三项全部无对账；`poll.enabled` 持久化属 UI 层，headless 未验证 |
| S9 dir-record | D-06 | **REPRO×3** | 全量索引写 folder doc；轮 pollA `scanned=1` 不建空目录 doc、pollB `deleted=1` 后已删目录 folder doc 残留 |
| S10 inject | D-03a / D-02 | **REPRO×4（组件注入）** | ① 摘除 `wd` 的 `key2dir` 项而真实 WatchKey 存活 → `silent.txt` 事件走 `dir==null→reset+continue` 被静默丢弃、映射不自愈；② 删除被监控 `wd2` 后 `key2dir` 残留陈旧映射（`reset()` 返回值忽略的直接证据）；③ 经 `sun.nio.fs.AbstractWatchKey.signalEvent` 注入 OVERFLOW：入队、静默跳过、**无补偿重扫**、循环继续正常处理真实事件 |

## 探针迭代史（原始证据保留，作废结论已标记）

| 日志 | 状态 |
|---|---|
| `live-newdir.log` / `live-movedel.log` / `invalid-key.log` / `overflow.log` / `partial-scan.log` | **终版**（修正版探针：liveDocs 断言 + flood 预注册 + `pollNowAsync` 真实路径 + PollStats 执行断言） |
| `partial-scan-superseded.log`（18:06）/ `partial-scan-rerun.log`（18:11） | **作废**：18:06 轮探针未过滤已删未合并文档，误报 "blocked doc survived"；更早版本还曾用 `pollNow()` 在 `running=false` 下静默空转；18:11 轮已修 liveDocs 但缺 PollStats 断言。正确结论见终版 `partial-scan.log`（带 scanned/deleted 统计）：D-07 误删确认 |
| `inject.log` 首跑（残留态） | **作废**：s10 场景未清空即重跑，`silent.txt`/`post-overflow.txt` 已被 seed 先行索引；终版 `inject.log` 为干净态结果 |
| `overflow.log` 早前一次运行（18:02） | **已被覆盖**：该轮 3000 文件全部入库（另含残留 27 条）；终版运行同样预注册 flood 目录却实测 2958 丢失——洪泛丢失为平台概率事件，两次结果不一致本身就是"无补偿"的证据 |
| 其余场景日志 | 终版，判定不受 liveDocs 修正影响（检查的是存在性/文件层计数，无删除位参与） |

## 限制

- 真写失败（磁盘满/锁冲突/IO 异常）无产品接缝，S6 仅为快照机理旁证——**D-08 维持待验证**。
- S4 洪泛事件丢失已实测发生，但归因 OVERFLOW 还是其他事件缝隙无法区分；S10 注入仅证明 OVERFLOW 分支无任何补偿动作。
- 未验证 UNC/真实网络盘、POSIX、映射盘 `isLikelyNetwork` 外部探测开销。
- `poll.enabled`/live-watch 开关持久化属 UI 层，headless 探针不覆盖。
- 若需把注入做成单测，最小接缝建议：`LocalRecursiveWatcher` 增加 package-private `onEvent(Kind, Path)`（loopRun 内的事件分发改调它），或将 `ws`/`key2dir` 经构造参数/工厂可替换——均只需产品侧个位数行改动，本轮未实施。
