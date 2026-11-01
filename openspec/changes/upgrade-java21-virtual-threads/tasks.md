# Tasks: upgrade-java21-virtual-threads

执行环境统一：JDK 21（~/tools/jdk21）、离线 Maven（新坐标先用 ~/tools/seed-repo.sh 播种）、测试须 Xvfb+Openbox + C.utf8 + 双端 IPv4 强制（配方见 ~/workspace/pilot/docfinder-recon.md §1）。基线：JDK 8 下 Tests run 300 / Failures 0 / Errors 0 / Skipped 10。

## 1. 平台与依赖升级（Devin 车道）

- [x] 1.1 pom：`<release>21</release>`、项目版本 3.0.0、native profile 对齐 21；compiler/surefire/shade/native 插件按 proposal 表升版本（surefire 保留测试隔离配置）
- [x] 1.2 pom：运行时与测试依赖按 proposal 表全量升级（含 Lucene 工件改名 lucene-analysis-*、Tika 4、logback 1.6.6、JUnit 6.1.3）；复核 log4j-api exclusions
- [x] 1.3 用 seed-repo.sh 播种新版本坐标，离线 `mvn -o test-compile` 通过（主代码与测试代码均可编译；API 破坏点如需改代码，仅做编译期最小适配并逐项记录清单，语义迁移留待第 2 阶段）

## 2. API 迁移（Codex 车道）

- [x] 2.1 Lucene 8 → 10 API 迁移（分析器、IndexWriter 配置、查询解析等破坏点），行为对齐点写中文注释
- [x] 2.2 Tika 2 → 4（连带 PDFBox 3）API 迁移，提取语义与超时/降级行为不变
- [x] 2.3 JDK 21 全量测试通过：Tests run ≥ 300、Failures 0、Errors 0、Skipped ≤ 10 且均为条件跳过（附 Surefire 汇总证据）

- [x] 2.4 索引格式迁移（第 4 阶段交叉审查新增，发布阻断）：Lucene 10 打不开 Lucene 8 索引（含 Tombstones 的 deleted-paths-index），Full Rebuild 也无法自愈。需启动期格式守卫（旧目录改名备份 *.lucene8.bak-<ts>、进入待重建状态）、indexReady 格式校验、Full Rebuild 一并重置 Tombstones、二进制 fixture 测试与 README/UsageGuide 升级说明

## 3. 虚拟线程改造（设计 Codex → 实现 Devin）

- [x] 3.1 设计说明定稿：背压上限数值与依据、助手池容量调整、pinning 盘点结论（产出设计说明文件）
- [x] 3.2 LuceneIndexer 全量索引并行化：虚拟线程分发 + 信号量背压；索引等价性测试（同语料并行/串行文档数与抽样查询一致）
- [x] 3.3 ContentExtractor 超时包裹改虚拟线程，超时/取消语义保持
- [x] 3.4 WebServer 执行器改虚拟线程 + 并发上限信号量，停机行为不变；补针对性测试
- [x] 3.5 全量测试复跑通过（口径同 2.3），关键改造点中文注释齐备

## 4. 交叉审查（多 AI 互审）

- [x] 4.1 Codex 审查第 1、3 阶段 diff（正确性、背压与 pinning、注释质量），结论落文件
- [x] 4.2 Devin（高档模型）审查第 2 阶段 diff（API 迁移行为对齐、遗漏的破坏点），结论落文件
- [x] 4.3 修复轮（两份审查合并清单，见 ~/workspace/pilot/docfinder-fix-spec.md）：索引格式守卫与重建安全（双 B1）、异常路径 abort/许可/lease 归还（S1–S3）、错误摘要（S7）、检测器单例与 getDecodeAs/转码（Devin S1/S3/S4/S2 注释）、Multi-Release 清单（Devin S7）、NetPoller 完成语义（Devin S5）、测试强化（双 S6/熔断/重叠/竞态）与 nit 收口、串并行基准与 JFR 证据
- [x] 4.4 Devin（免费档）按两份报告清单复核修复 + 终验全量复跑

## 5. 验收与发布（beauty 主导）

- [x] 5.1 终验：JDK 21 全量测试对基线核对；固定语料索引等价性 + 索引耗时对比（串行基线 vs 并行）记录在案
- [ ] 5.2 推送 upgrade/java21 开 PR，审查结论并入 PR 说明，合并至 main
- [ ] 5.3 用户验收后 openspec archive 本变更（规格增量并入主规格库）
- [ ] 5.4 发布 v3.0.0：tag + GitHub Release，附 docfinder-3.0.0.jar
- [ ] 5.5 Java 8 归档发布：tag v2.0.1-java8-final + Release，附 JDK 8 构建的最终包

- [x] Fix round F1 (owner: Codex): validate and back up Lucene 8 main/recovery indexes, require rebuild, preserve real writer locks; verify binary fixture and upgrade instructions.
- [x] Fix round F8/F12 (owner: Codex): smoke-test shaded Java 21 jar, MR manifest, PDF/charset paths; record fixed-corpus timing/RSS and JFR pinning evidence.

> 完成注记（2026-11-01）：4.4 实际由 Codex 复核（用户 2026-10-31 晚调整走量顺序：Codex 走量优先），终验由 beauty 与夜间巡检执行，最终结论 PASS（全量 383/0/0/10、package 与 MANIFEST 核对通过）。5.2/5.4/5.5 于 2026-11-01 发布流程中完成，证据见 GitHub PR 与 Releases；5.3 归档随发布完成后执行。
