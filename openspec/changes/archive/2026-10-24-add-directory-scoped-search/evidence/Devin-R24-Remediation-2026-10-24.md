# Devin：R24 复验残留修复批次（第13组）

日期：2026-10-24（Asia/Tokyo）。基线：`4f56d0d`（Codex 复验后 main）。本批按 evidence/Devin-Next-2026-10-24.md 执行：先恢复可信测试（R24-02），再修产品竞态（R24-01），随后补证/文档（R24-03/04/05/06）。

## 修复内容

| ID | 修复 | 代码位置 |
| --- | --- | --- |
| R24-01 | reopen 失败分支先 `searchSequence++` 并 `cancel(true)` 在途 worker，再 `setSearchService(null)` + 清表 + INDEX_UNAVAILABLE 文案；旧查询的迟到成功和迟到错误都被丢弃 | src/main/java/.../ui/MainWindow.java `reopenSearchServiceAsync` 失败分支 |
| R24-02 | IndexAll 测试不再手写 `index.dir=` 拼接（Properties.load 会吞反斜杠）；改用 `ConfigManager.setIndexDir`（走 Properties.store，正确转义）；建库前断言 `getIndexDir()==预期idx` 且位于 tmp 允许根；事后用真实 DirectoryReader 按 path 成员断言文档；config/sources 按字节备份还原，夹具目录递归清理 | DirectoryScopeDesktopHarnessTest `shouldRunRealIndexAllLifecycleAndPublishService` |
| R24-03a | 真实 `onIndexAllSources` 入口 + 被阻塞在途查询：Index All 起始即失效旧 worker，发布后旧迟到结果不回填；reader 级断言真实索引只含测试来源文档 | `shouldInvalidateInFlightSearchDuringRealIndexAll` |
| R24-03b | 真实部分失败链路：watchdog 线程在 source A 的 `segments_*` 落盘后对索引目录加 ACL 拒写 → source B 的 commit 真实 AccessDenied（日志实证 `AccessDeniedException ...\_1.nvd`）；部分失败文案保留不被 Ready 覆盖；reader 级断言 B 无文档 | `shouldKeepPartialStatusAfterRealIndexAllSecondSourceCommitFails` |
| R24-03c | E2E 部分提交测试增加全局 `q=delta` 断言：delta-b 全局缺席（而非仅被 scope A 隐藏）；新增对照用例证明"已提交 B 被 scope A 隐藏但全局可见" | `shouldServeCommittedDataAfterPartialCommitFailure` + `shouldDistinguishScopeFilterFromUncommittedIsolation` |
| R24-04 | `shouldReturnUnmodifiableSnapshot` 改用宿主绝对根；`SearchHandlerTest` 新增 `parentToken`/`assertParent`，盘根/UNC/POSIX 根逐结果断言 parentDirectory，不再"任一 null 即过" | SourceManagerSearchRootsTest / SearchHandlerTest |
| R24-05 | 新增 `LuceneSearchService.scopeVerdictComputationCount`（AtomicLong，仅在真正调用 `ensureDirectoryScopeSupported` 时递增）；benchmark 断言首次=1、100 热调用仍=1、刷新后=2，输出打印实测计数而非常量 | LuceneSearchService / DirectoryScopeCheckBenchmark |
| R24-06 | modernize proposal/design/spec/tasks 全部改为"版本表逐项指定版本 + 显式例外（FlatLaf 3.6.2）"，移除无条件"线内最新"承诺；tasks 3.1 已勾选 | modernize-java-dependencies 文档 |

## 历史越界目录核对与清理（13.1 附带项）

报告列出的 4 个 `C:\UsersAdministratorAppDataLocalTempqa-indexall-idx-*` 目录逐一核验：

- 均为单段 Lucene 索引（`segments_1` + `write.lock` + `_0.cfs/cfe/si`），与错误 properties 转义导致的"盘相对路径重定向"模式一致；
- 每个 `_0.cfs` 内均含 `qa-indexall-src` 测试路径串 → 确认为测试产物，无业务内容；
- 按精确完整路径删除，未使用模糊匹配批量删除；`C:\` 下已无 `UsersAdministratorAppDataLocalTemp*` 残留。

## 本轮验证（实现者自验，待 Codex 独立复验）

| 步骤 | 命令 | 结果 |
|---|---|---|
| 桌面 harness | `mvn -B "-DargLine=-Xmx768m -Ddocfinder.argline.probe=present" -Dtest=DirectoryScopeDesktopHarnessTest test` | 20/20 通过（含2条R24-01、3条R24-02/03真实入口用例） |
| 其余定向 | `-Dtest=SourceManagerSearchRootsTest,SearchHandlerTest,DirectoryScopeEndToEndTest,DirectoryScopeCompatibilityTest,DirectoryScopeCheckBenchmark` | 45/45 通过，1 项平台跳过（POSIX根断言，Windows宿主） |
| P-00 | `-Dtest=DirectoryScopeCheckBenchmark` | 10万 doc；服务首次 0.88ms；100 热调用 p95=0.00ms，**verdict computations=1（实测断言）**；刷新后首调 0.08ms，再 100 次 p95=0.00ms，computations=2；删旧 2.80ms / 删新 9.84ms→409 |
| 全量 JDK17 | `mvn -B "-DargLine=-Xmx768m -Ddocfinder.argline.probe=present" clean verify` | BUILD SUCCESS，169 总数 / 0 失败 / 2 平台跳过（PathScopeTest、SearchHandlerTest 的 POSIX 用例；带探针时隔离探针实际执行） |
| 真实 Java8 | `mvn -B "-Djvm=D:\Java\java8\bin\java.exe" "-DargLine=-Xmx768m -Ddocfinder.argline.probe=present" test` | BUILD SUCCESS，169/0/2；surefire XML java.version=1.8.0_40 |
| OpenSpec | 两 change `--strict` | 双通过 |
| 空白 | `git diff --check` | 无新增问题（若干测试文件 LF→CRLF 既有提示） |

> 计数口径更正：R24-05 之前 benchmark 打印的是常量 enumerations=1（仅以 cacheSize==1 断言，不能证明计算次数）；现 `verdict computations` 来自 LuceneSearchService 内部 AtomicLong 实测并由断言钉死，"条目数/计算次数"不再混用。

## 仍未执行/保留项

- M-01～M-06 真实主机流程、原生窗口捕获（Codex 工具两次超时）、native、POSIX 宿主、真实 UNC——仍归 Codex/用户，不因本批通过记为完成。
- A8 验收与归档仍待 Codex 对第 13 组独立复验 + 用户决定。
