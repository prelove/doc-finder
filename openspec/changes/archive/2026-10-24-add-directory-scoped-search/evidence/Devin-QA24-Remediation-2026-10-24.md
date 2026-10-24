# Devin A7 修复复验记录（QA24-01..11）—— 2026-10-24

范围：仅修复 Codex-QA-2026-10-24 报告指出的运行时失败与证据缺陷；不改变设计、不新增能力。
验证对象为修复后的最新工作树；下文所有数字来自本轮实际运行，不复用旧记录。

## 修复摘要（QA24-01..11）

| 编号 | 处理 | 关键实现 |
|---|---|---|
| QA24-01 | 完成 | `MainWindow`：INDEX_UNAVAILABLE / 完整重建失败时清空结果行、选择与预览，仅保留固定失败提示。 |
| QA24-02 | 完成 | `DirectoryScopePanel`：草稿文本与已提交范围分离显示，编辑/打字不自动提交。 |
| QA24-03 | 完成 | `LuceneSearchService`：显式 `name:` 与 name+content 组合查询按实际命中字段标注 match source；目录 scope 仍为 FILTER 不参与解释。 |
| QA24-04 | 完成 | 新索引重开失败时旧服务被关闭且不对外发布（unpublish），UI 状态与不可用提示一致。 |
| QA24-05 | 完成 | `SourceManager`：仅 `NoSuchFileException` 视为空来源；目录不可读/访问拒绝/普通读错一律抛出 IOException → `SOURCE_CONFIG_UNAVAILABLE`。 |
| QA24-06 | 完成 | 部分提交证明改为 **ACL 拒写索引目录→commit 真实失败**（原 upsert-不存在文件证明无效）；新增真实 `onIndexAllSources` 生命周期测试与旧 live doc 迁移残留 409 测试。 |
| QA24-07 | 完成 | `LuceneSearchService` 增加 `closed` gate + `scopePublishLock`；latch 测试证明 close 后无迟到 verdict 发布。 |
| QA24-08 | 完成 | 路径相关测试改主机绝对路径 + 平台守卫（Windows/UNC/POSIX 分别断言）；删除反例用 `NoMergePolicy` 钉住 maxDoc=3/numDocs=2/docCount=2 叶统计。 |
| QA24-09 | 完成 | `index.html` 持久提示"仅搜索已索引内容；目录范围覆盖整棵子树且必须位于已配置来源内"。 |
| QA24-10 | 完成 | modernize-java-dependencies 文档统一改为"Java 8 兼容线内最新"，明示 logback 1.3.x/Tika 2.x EOL 与 FlatLaf 3.6.2 非上游最新，不再宣称消除 EOL 风险。 |
| QA24-11 | 完成 | P-00 分开测量真实服务缓存入口与 raw helper；见下节原始数据与场景/枚举计数。 |

## P-00 测量（QA24-11，最终 SHA 本轮运行）

原始输出：[qa-20261024/p00-service-cache.txt](qa-20261024/p00-service-cache.txt)
fixture：10 万唯一 path_scope 文档，leaf=2；JDK17 surefire fork，-Xmx768m。

```
index build: 3838 ms | leaves=2 maxDoc=100000 numDocs=100000
raw helper first call: 508.82 ms        # 冷路径（含首次段读取）
raw same-reader repeat(20): p50=0.00 p95=0.03 ms
raw exact fallback (no missing): 196.48 ms
raw deleted-doc check: 0.06 ms
service first call: 0.71 ms             # 真实缓存入口（leaf 充分条件快路径，温缓存）
service cache-hit x100: p50=0.00 p95=0.00 ms  # scopeSupportCacheSize 钉死枚举=1/reader
service first call after refresh: 0.06 ms     # 新 reader key 重新计算 1 次
service cache-hit after refresh x100: p50=0.00 p95=0.00 ms
service check, old doc deleted: 1.79 ms
service check, new doc deleted (legacy live): 10.53 ms -> 409
```

说明与限制：同一测试方法内顺序执行，service 入口数字处于温缓存上下文，不能与 raw 冷路径直接比绝对差；缓存收益由"每 reader 仅 1 次枚举（cacheSize 断言）+ 100 次热调用 p95≈0"证明，不以 top1 计时当 O(1) 宣称。

## 本轮验证命令与结果（最终 SHA）

| 步骤 | 命令 | 结果 |
|---|---|---|
| 定向回归 | `mvn -B test "-Dtest=DirectoryScopeCompatibilityTest,DirectoryScopeDesktopHarnessTest,DirectoryScopeEndToEndTest,SourceManagerSearchRootsTest" "-DargLine=-Xmx768m"` | 45/45 通过 |
| 全量 | `mvn -B clean verify "-DargLine=-Xmx768m"` | BUILD SUCCESS，Tests run: 164, Failures: 0, Errors: 0, Skipped: 3（PathScopeTest POSIX 用例、TestEnvironmentIsolationTest、SearchHandlerTest POSIX 根断言） |
| P-00 | `mvn -B test "-Dtest=DirectoryScopeCheckBenchmark"` | 通过，数字见上 |
| Java 8 实机 | `mvn -B test "-Djvm=D:\Java\java8\bin\java.exe" "-DargLine=-Xmx768m"` | BUILD SUCCESS，164/0失败/3跳过；surefire XML 确认 java.version=1.8.0_40 |
| OpenSpec | `openspec validate add-directory-scoped-search --strict` / `modernize-java-dependencies --strict` | 双通过 |
| 空白检查 | `git diff --check` | 无新增空白问题（DirectoryScopePanel 已有 LF→CRLF 提示为既有换行提示） |

## 仍未执行/保留项

- M-01～M-06 真实主机验收流程（GUI 交互、native picker 点击级）——Codex/用户。
- POSIX 宿主运行（单测含 POSIX 条件断言但宿主为 Windows）。
- 真实 UNC 网络来源、native 构建（需 Java 17/GraalVM 独立流程）。
- 完整功能独立 review / A8 用户验收 / 归档 / 提交与推送授权（10.1/10.2）。
