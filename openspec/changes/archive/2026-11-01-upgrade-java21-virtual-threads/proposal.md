# Proposal: 平台升级 Java 21 + 虚拟线程索引改造

## Why

DocFinder 当前基线为 Java 8（2026-10-24 的 modernize-java-dependencies 变更已在 Java 8 兼容线内做完依赖现代化，并明确登记了 EOL 风险：logback 1.3.x、Tika 2.x、Lucene 8.11 均为上游终止维护线）。Java 8 平台本身亦早已停止公共更新。本次变更正式跨越该边界：运行时与编译基线升至 Java 21 LTS，全部依赖升至当前稳定线，并利用 Java 21 的虚拟线程改造索引主链路的串行瓶颈（单线程递归遍历 + 同步等待提取结果），提升全量索引效率。升级完成后，本版本即新的 main 主干基线（版本 3.0.0），Java 8 线以 v2.0.1-java8-final 归档封存。

## What Changes

- **平台**：`maven.compiler.source/target 1.8` 改为 `<release>21</release>`；项目版本 1.0.0 → 3.0.0；`native` profile 的 source/target 一并对齐 21。
- **依赖全量升级**（版本表以 2026-10-31 Maven Central 实测为准）：
  - Lucene 8.11.2 → 10.6.0（analyzers-common/smartcn/kuromoji 工件改名为 lucene-analysis-*；queryparser 名称不变）
  - Tika 2.9.4 → 4.2.0（tika-core 与 tika-parsers-standard-package；传递的 PDFBox 随之 2.0.34 → 3.0.8）
  - logback-classic 1.3.16 → 1.6.6；log4j-to-slf4j / log4j-core 2.23.1 → 2.26.1；slf4j-api 2.0.17 → 2.0.20
  - FlatLaf 三件套 3.6.2 → 3.7.2；jnativehook 维持 2.2.2（已是最新）
  - JUnit Jupiter 5.11.4 → 6.1.3（test）
  - 插件：maven-compiler-plugin 3.14.1 → 3.16.0；maven-surefire-plugin 3.5.5 → 3.6.1（保留既有测试隔离配置）；maven-shade-plugin 3.6.1 → 3.6.2；native-maven-plugin 0.10.6 → 1.1.18
  - pom 中针对 Lucene/Tika 的 log4j-api exclusions 在升级后复核传递依赖树再定去留。
- **API 迁移**：Lucene 8 → 10 与 Tika 2 → 4 的破坏性 API 变更在代码侧完成迁移，产品可观察行为不变。
- **虚拟线程改造**（详见 design.md）：全量索引遍历/提取分发并行化（虚拟线程 + 背压上限）；ContentExtractor 的每文件单线程超时包裹改虚拟线程；内嵌 Web 服务器执行器改虚拟线程执行器。
- **规格取代**：build-modernization 规格的 MOD-01（Java 8 兼容边界）由本变更修改为 Java 21 基线；新增 indexing-concurrency 规格。

## Impact

- 受影响代码：pom.xml；index、search、web、watch、util 包中与 Lucene/Tika/线程设施相关的类。
- 索引数据：Lucene 10 与 Lucene 8 的索引格式不保证互读；首次启动新版本时按既有机制重建索引属可接受行为（应用本就支持全量重建），但不得损坏用户源文件与配置。
- 不在范围内（Non-goals）：不新增产品功能；不改 UI 行为与外观；不改 helper-JVM 隔离架构本身（仅允许调整其池容量参数）；不做 GraalVM native 产物的正式发布（profile 仅对齐版本，能编译即可）。
