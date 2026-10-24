# Proposal: Java 8 兼容线依赖现代化

文档1.0；2026-10-24（Asia/Tokyo）。roadmap.md 中的 D 阶段缩小实施版：用户明确保留 Java 8 基线（源码/字节码/运行时），因此 Lucene 9+/Tika 3+/logback 1.4+/log4j 2.24+ 等要求 Java 11+ 的升级不在本变更范围。

## 目标

在不改变任何产品行为的前提下，把当前可升级的依赖与构建插件升到**其 Java 8 兼容线内的最新稳定版本**，降低陈旧组件的已知问题暴露，并为后续升级 Java 基线留出清晰边界。注意：这只是线内升级，不是"全部最新"——logback 1.3.x 与 Tika 2.x 上游本身已 EOL（但仍是最后支持 Java 8 的线），FlatLaf 上游也已发布比 3.6.2 更新的版本；EOL 风险保留并在下文如实登记，本变更不宣称消除。

## 升级范围

| 组件 | 当前 | 目标 | 说明 |
| --- | --- | --- | --- |
| flatlaf / flatlaf-intellij-themes / flatlaf-extras | 3.2 | 3.6.2 | 3.x 全线兼容 Java 8；上游已有 3.7.x，本次未追最新 |
| slf4j-api | 1.7.36 | 2.0.17 | 修正与 jcl-over-slf4j 2.0.10（Tika 传递依赖）既有的版本错位 |
| logback-classic | 1.2.11 | 1.3.16 | 1.3.x 是最后支持 Java 8 的线（上游已 EOL），含 CVE-2025-11226 修复；1.4+ 需 Java 11 |
| log4j-to-slf4j / log4j-core | 2.17.1 | 2.23.1 | 2.23.x 是最后支持 Java 8 的线 |
| tika-core / tika-parsers-standard-package | 2.9.2 | 2.9.4 | 同线补丁升级，Java 8 兼容；Tika 2.x 上游已 EOL，EOL 风险保留 |
| junit-jupiter | 5.9.3 | 5.11.4 | JUnit 5 全线兼容 Java 8 |
| maven-compiler-plugin | 3.11.0 | 3.14.1 | |
| maven-shade-plugin | 3.5.0 | 3.6.1 | |
| maven-surefire-plugin | 3.0.0 | 3.5.5 | 保留既有 fork/隔离 argLine 配置 |
| native-maven-plugin | 0.10.3 | 0.10.6 | native profile 内，Java 17 工具链不变 |

## 非目标

- 不升级 Java 基线：maven.compiler.source/target 保持 1.8，Java 8 运行时支持保留。
- 不升 Lucene（8.11.2 不动）与 Tika 大版本；不新增/删除依赖项。
- 不改任何产品代码行为；测试隔离结构（fork、test-classes work、test-home）保持。
- jnativehook 2.2.2 已是上游最终版本，不动。

## 验收

- `mvn -B clean verify` 在升级后全绿（当前基线 149 项，0 失败/错误，2 跳过）。
- 编译仍输出 `[debug target 1.8]`；可在真实 Java 8 JVM 上以 `-Djvm=` 运行测试。
- 日志链路仍工作：logback 1.3.16 + slf4j 2.0.x 提供绑定，log4j-to-slf4j 与 jcl-over-slf4j 路由正确。
- `openspec validate modernize-java-dependencies --strict` 通过。
