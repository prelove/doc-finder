# Tasks: modernize-java-dependencies

唯一实施进度来源。目标：Java 8 基线不变，全部可升级依赖/插件升到 Java 8 兼容最新稳定版。

## Global Constraints

- maven.compiler.source/target 保持 1.8；不新增/删除依赖坐标；不改产品代码行为。
- 沿用既有 Surefire fork/test-classes/test-home 隔离；不触真实 `~/.docfinder`。
- 不提交/合并/推送/归档，除非用户另行批准。

## 1. 依赖与插件升级

- [x] 1.1 pom.xml 版本升级：FlatLaf 三件 3.2→3.6.2；slf4j-api 1.7.36→2.0.17；logback-classic 1.2.11→1.3.16；log4j-to-slf4j/log4j-core 2.17.1→2.23.1；tika-core/tika-parsers-standard-package 2.9.2→2.9.4；junit-jupiter 5.9.3→5.11.4；compiler 3.11.0→3.14.1；shade 3.5.0→3.6.1；surefire 3.0.0→3.5.5；native-maven-plugin 0.10.3→0.10.6。Lucene/jnativehook 不动。
- [x] 1.2 `mvn -B clean verify "-DargLine=-Xmx768m"` 全绿；核对日志无 SLF4J provider 警告、fork 隔离生效、`target 1.8`。
- [x] 1.3 记录证据与未验证项（真实 Java 8 JVM 上以 -Djvm 重跑、native 构建不在本环境验证）。

## 2. 交付

- [ ] 2.1 用户确认本变更结果；与 add-directory-scoped-search 的 A8 一并走授权交付，不单独归档。
