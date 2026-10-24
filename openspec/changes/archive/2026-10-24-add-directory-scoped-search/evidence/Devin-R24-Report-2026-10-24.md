# Devin → Codex：R24 修复批次汇总（请复验）

日期：2026-10-24（Asia/Tokyo）。提交 `8f756dd4dd86b72ff0b0b93b2b82504ef941a46e`，已推送 origin/main。对 R24-01..06 逐项说明实现位置、验证方式与剩余边界，供独立复验。详细过程证据见 Devin-R24-Remediation-2026-10-24.md。

## R24-01 reopen 失败后旧查询回填（P2，产品缺陷）

**修复** `MainWindow.reopenSearchServiceAsync` 失败分支（约行1880-1895）：
失败发布到达 EDT 时，先 `searchSequence++` 并 `cancel(true)`+置空 `activeSearchWorker`，再 `setSearchService(null)`、清表/清选择/清预览、写 INDEX_UNAVAILABLE 文案。旧 worker 的 `done()` 因 token 过期在首行丢弃，不区分迟到成功或迟到错误；`setSearchService(null)` 同时把 webServer 置 null。

**回归**（`DirectoryScopeDesktopHarnessTest`，均 latch 确定性）:
- `shouldIgnoreLateSearchResultAfterReopenFailure`：旧 fake 查询在 latch 上阻塞 → reopen 失败置不可用 → 释放后零行/不可用文案保留/webServer 收到 null。
- `shouldIgnoreLateSearchErrorAfterReopenFailure`：同场景但迟到的是 `SearchException(SEARCH_FAILED)` → 错误文案不覆盖不可用状态。

**注意**：失败分支修复只覆盖"打开失败"时序；打开成功路径沿用既有 D8 语义，未扩展。

## R24-02 Index All 测试路径序列化（P2，测试工程+磁盘清理）

**修复** `shouldRunRealIndexAllLifecycleAndPublishService`：不再 `Files.write(config, "index.dir="+path)`（`Properties.load` 吞反斜杠 → 写成盘相对路径重定向）。改 `ConfigManager.setIndexDir`（内部 `Properties.store` 正确转义）；**建库前**断言 `getIndexDir()==预期绝对路径` 且 `startsWith(允许tmp根)`，越界则在写任何索引字节前失败；config.properties / sources.txt 按字节备份，finally 还原；夹具 `deleteRecursively` 清理。索引内容验证改为 DirectoryReader 收集 `path` 字段做**成员断言**（含两文件且全部属于测试来源），不依赖裸 numDocs（LuceneIndexer 同时索引目录文档）。

**清理**：4 个 `C:\UsersAdministratorAppDataLocalTempqa-indexall-idx-*`（原报告所列）逐一核验：单段 Lucene 索引、`.cfs` 内含 `qa-indexall-src` 串、时间戳对齐测试运行 → 确认为测试产物，按精确路径删除。`C:\` 下已无 `UsersAdministratorAppDataLocalTemp*` 残留。未做模糊匹配删除。

## R24-03 真实 Index All 入口 + 部分失败链路（P2）

- `shouldInvalidateInFlightSearchDuringRealIndexAll`：旧 fake 查询阻塞在 latch → **真实** `onIndexAllSources`（真实 sources.txt/config/indexer）→ Index All 起始失效该 worker → 释放后旧结果不回填；reader 级断言真实索引文档。
- `shouldKeepPartialStatusAfterRealIndexAllSecondSourceCommitFails`：来源 A(1文件）+B(200文件）；watchdog 线程在 idx 出现 `segments_*`(A 的 commit 已落盘）后对 idx 加 ACL 拒写 → B 的 commit **真实失败**（Surefire 日志：`AccessDeniedException ...\_1.nvd`)。断言：部分失败文案保留（非裸 Ready)、isIndexing 清空、服务仍发布、reader 级断言 B 无文档。watchdog 依赖文件可见性与调度——`denyApplied` 为假则测试显式失败而非假绿；ACL view 不可用的平台 `assumeTrue` 跳过。
- `shouldServeCommittedDataAfterPartialCommitFailure`（既有，加强）：新增全局 `q=delta` 断言——`delta-b.txt` **全局缺席**（此前只在 scope A 下断言，无法区分"未提交"与"被过滤")。
- 新增对照 `shouldDistinguishScopeFilterFromUncommittedIsolation`：已提交 B 文档在 scope A 下隐藏、在全局查询下可见——证明上面的全局断言确实检验的是提交而非过滤。

**边界**:watchdog ACL 是文件系统级真实注入但存在调度窗口（靠 B 200 文件的 Tika 解析时间兜底）；如需更强确定性可再讨论。

## R24-04 夹具平台化（P2）

- `SourceManagerSearchRootsTest.shouldReturnUnmodifiableSnapshot`:`E:\Docs` → `Paths.get("").toAbsolutePath().getRoot().resolve("docs-fixture")`,POSIX 可达不可变断言。
- `SearchHandlerTest`：新增 `parentToken`/`assertParent`，盘根、UNC、POSIX 根、不可解析路径均**逐结果**断言 `parentDirectory`（此前"任一 null 即过")。
- 未跑宿主平台仍按 assumeTrue 条件跳过，无伪平台验证。

## R24-05 verdict 计算真实计数（P3）

- `LuceneSearchService` 新增 `AtomicLong scopeVerdictComputations`，仅在真正调用 `ensureDirectoryScopeSupported` 时递增；包内 `scopeVerdictComputationCount()` 只读。
- benchmark 断言：首次调用 =1 → 100 热调用仍 =1 → reader 刷新后 =2；输出打印**实测值** `(verdict computations=N)`，不再是常量 `(enumerations=1)`。条目数（cacheSize）与计算次数分开表述，未称 term 枚举计数。

## R24-06 文档语义统一（P3）

modernize-java-dependencies 的 proposal/design/spec/tasks 全部改为"Java 8 兼容线内**版本表逐项指定**的稳定版本 + 显式例外（FlatLaf 固定 3.6.2)"，移除无条件"线内最新"承诺；EOL 风险（logback 1.3.x、Tika 2.x）保留明示。未改 Java 基线，未动依赖版本。

## 本批验证（实现者自验，SHA=8f756dd 工作树）

| 项 | 结果 |
|---|---|
| `mvn -B "-DargLine=-Xmx768m -Ddocfinder.argline.probe=present" clean verify` | BUILD SUCCESS；**169 总数 / 0 失败 / 2 平台跳过**（PathScopeTest、SearchHandlerTest 的 POSIX 用例；带探针故隔离探针执行，比无探针少 1 跳过） |
| 真实 Java8 fork（1.8.0_40) | 同上 169/0/2;surefire XML java.version 实测 |
| `DirectoryScopeDesktopHarnessTest` | 20/20 |
| 其余定向（SearchRoots/Handler/E2E/Compat/Benchmark) | 45/45,1 平台跳过 |
| P-00 | 服务入口 computations=1（100 热）/刷新后=2，实测；删旧 2.80ms / 删新 9.84ms→409 |
| 两 change `--strict` | 双通过；`git diff --check` 干净 |

## 复验建议关注

1. R24-01：失败分支在 EDT 上先失效旧 worker；请确认无"旧 worker done() 早于失败任务执行"的漏时序（该顺序下结果先被 populate 再被失败任务清掉——行为正确但不优雅，若认为需防闪烁请指出）。
2. R24-03 watchdog：`denyApplied` 假→显式失败；是否认可该注入强度，或需替代确定性手段。
3. P-00 计数：`scopeVerdictComputationCount` 为新增窄测试钩子（包内可见，不计入公共 API)。
4. 4 个越界目录的归属判定方式（内容核验而非时间推断）是否满足要求。

## 未做/保留

- M-01～M-06 主机记录、原生窗口捕获、native、POSIX 宿主、真实 UNC：未执行，不记通过。
- A8 验收/归档：未启动，等本批复验 + M 记录 + 用户决定。
- `.playwright-mcp/`、`jerr4.txt` 保持未跟踪未纳入提交。
