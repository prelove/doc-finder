# 测试报告缺陷修复记录

用户已同意按 Codex-Verification-2026-10-23.md 的顺序执行。基线 HEAD 为 4da19eb，产品范围不变；本轮不提交、归档或推送。执行进度以 tasks.md 第 11 组为准。

## 验证约束

- 每项保留目标 RED/GREEN 命令、退出码及观察，环境错误不算目标 RED。
- Maven 保持 fork、临时 work/home 隔离，仅通过进程参数限制堆；不运行真实业务索引的重建/迁移。
- 其他流程已有的 delivery-status.yaml 和 A7.md 改动保持不动。
- 完成修复不等于用户最终验收；原生选择器、跨平台、native、未执行的实机/性能项不得标为通过。

## 本地原始日志

`.omx/logs/dirscope-remediation-20261023/`；每项完成后在本文件记录结果。

## F01 区域无关的 JSON 分数

- RED：`mvn -B -DargLine=-Xmx256m -Dtest=SearchHandlerTest#shouldSerializeScoresIndependentlyOfFormatLocale test`，退出 1；1 项目标断言失败，实际为 `"score":1,2345`。
- 修复：SearchHandler 使用 Locale.ROOT 格式化分数；新增测试分别覆盖全局/限定请求，finally 恢复 FORMAT 区域。
- GREEN：`mvn -B -DargLine=-Xmx256m -Dtest=SearchHandlerTest test`，退出 0，12 项通过，0 失败/错误/跳过。
- 原始日志：F01-red.log、F01-green.log。保持 Surefire fork；MAVEN_OPTS=-Xmx256m。

## F02 完整重建失败不重发布服务

- 修复：MainWindow.handleFullRebuildFailure() 失效在途打开、置空服务，状态提示不可用；Index All 部分失败恢复路径未改。
- 回归：DirectoryScopeDesktopHarnessTest#shouldNotRepublishServiceAfterFullRebuildFailure（迟到候选被关闭、服务保持 null），通过。

## F03/F04/F07 Web 请求失效、失败清屏、历史

- 修复（index.html）：search() 先递增 token 再判空词，空词清空结果/预览/状态且不发请求；Clear 始终走 search()；业务错误与网络失败清旧结果、选择、预览；仅当前成功响应才 addToHistory。
- 真实浏览器复测（BrowserFixture + Playwright，隔离 fixture，端口为当次值）：B07 成功后 422 → 0 行；B08 失败词 eport AND ( 不入历史；B09 空词后迟到响应 → 0 行；之后正常查询恢复 5 行。均通过。B10 逻辑未改动。

## F05 桌面空词失效 / F06 目录文本即时同步

- F05：doSearch 在空词分支前递增 searchSequence；回归 shouldInvalidateCompletedSearchWhenEmptyQueryIsSubmittedBeforeDone（后台已完成、done 在 EDT 排队）。
- F06：DirectoryScopePanel 为路径文本加 DocumentListener 仅刷新来源/Custom/All 显示，不提交、不查盘；回归 shouldSyncComboDisplayWhileTypingWithoutCommitting。
- 两项均已临时回退修复确认目标 RED，随后恢复并 GREEN。

## 11.8 最终重验

- mvn -B -DargLine=-Xmx768m clean verify：退出 0，BUILD SUCCESS，144 项，0 失败/错误，2 跳过（256m 堆在本机出现 fork OOM，改为 768m；仍保留 fork 隔离）。日志：final-verify.log。
- 未做：M-01～M-06 实机、原生目录选择器、性能 P-00/P-01 重跑、native/Java 8/POSIX；独立修复审查待安排。不据此宣称最终验收。

## 修复报告与独立审查（提交 af6bd77）

- 隔离探针（外部 argLine + 忽略 home）：TestEnvironmentIsolationTest+LegacyMigrationTest 8 项通过，0 跳过。日志 isolation-probe.log。
- 报告失败场景复测：B07/B08/B09 通过；B11a/B11b 由 SearchHandlerTest 区域回归覆盖（未重跑德语区域浏览器场景）。
- 独立只读审查（子代理，未能执行 git）：F01–F04、F06、F07 判定正确修复；无 P0/P1。
- 残余（未在本轮修复，不属于 F01–F07）：
  - P2：indexAllSources() 未像 rebuildAllSources 一样递增 searchSequence/取消在途搜索；doSearch 的 isIndexing/null 提前返回早于 token 递增。当前可达性低（Apply 按钮在索引期间未禁用是入口）。
  - P3：stopIndexServicesForLockRecovery 直接置空 searchService 绕过 setSearchService；handleFullRebuildFailure 自身未递增 searchSequence（目前依赖调用方）；空词提交未收起历史下拉；Web 的 F03/F04/F07 无自动化 DOM 回归，仅浏览器手测证据。
- 结论：缺陷修复完成，未做最终验收；M-01～M-06 等仍待实机。

## 审查残余项跟进（用户批准 option 1）

- P2：indexAllSources 开始时递增 searchSequence 并取消在途搜索；doSearch 把 token 递增提前到 isIndexing/null 提前返回之前。回归 shouldInvalidateCompletedSearchWhenIndexingStartsBeforeDone（未单独做回退 RED，仅 GREEN）。
- P3：空词提交收起历史下拉；WebAssetsOfflineTest 增加对 search() 的静态顺序断言（token 先于空词返回、历史仅在成功后写一次）。这是静态文本断言，不是 DOM 回归，浏览器级自动化仍无。
- 未处理 P3：锁恢复路径绕过 setSearchService；handleFullRebuildFailure 自身不递增 searchSequence。
- 重验：clean verify -Xmx768m 退出 0，145 项，0 失败，2 跳过。注意：本机在 512m/768m 下偶发 fork 启动/运行期 "insufficient memory"（环境内存压力，非用例失败），重跑通过。日志 final-verify2.log。

## 补验记录（基线 4b98dff，Windows 10 / Oracle JDK17，-Xmx768m；原始日志 .omx/logs/dirscope-remediation-20261023/）

- P-00（DirectoryScopeCheckBenchmark，10 万 doc 隔离 fixture，观察记录非断言）：首次检查 444.72 ms；同 reader 重复 p50=0.00 ms、p95=0.03 ms；精确回退查询 178.17 ms；含已删除文档判定 0.04 ms；建索引 3984 ms。日志 P00.log。
- P-01（DirectoryScopedSearchPerfBenchmark，10 万文件 doc + 1001 目录 doc）：unscoped 热查询 p50=12.03 / p95=18.21 ms；scoped p50=7.92 / p95=16.59 ms；门槛 OK（未出现 2× 且 +200 ms 退化）。单机单点观测，不是 SLA。日志 P01.log。
- Java 8 运行时：mvn test -Djvm=D:\Java\java8\bin\java.exe，fork 实际为 JDK 1.8.0_40（surefire 报告 java.home=D:\Java\java8 已核对）；145 项，0 失败/错误，2 跳过，BUILD SUCCESS。日志 java8-test.log。说明：这证明测试在 Java 8 JVM 上可运行，未覆盖 Java 8 下的桌面 GUI 手工验收。
- B11（德语区域，真实 harness 内嵌 Web，-Duser.language=de -Duser.country=DE）：全局与目录限定响应的 score 均为小数点（2.8643），PowerShell ConvertFrom-Json 均可解析（3 条/2 条）。解析器不是浏览器 JSON.parse，但 JSON 语法判定等价；此前失败的是 B11a/B11b，现已不复现。
- 仍未验证：POSIX 主机、native profile、真实 UNC/大规模网络来源、M-01～M-06 实机。
