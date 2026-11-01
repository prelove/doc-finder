# build-modernization Specification

## Purpose

在不改变产品行为与 Java 8 基线的前提下，把全部可升级依赖与构建插件升到其 Java 8 兼容线内 proposal 版本表逐项指定的稳定版本（个别组件显式不追最新，如 FlatLaf 3.6.2）。所选线本身可能已是上游 EOL 线（logback 1.3.x、Tika 2.x），EOL 风险如实登记，不宣称消除。

## Sources

已验收版本与方案记录见 [归档变更](../../changes/archive/2026-10-24-modernize-java-dependencies/proposal.md)；当前依赖版本以仓库pom.xml为准，历史版本/风险以归档方案为准。

## Requirements

### Requirement: MOD-02 行为不变

升级 SHALL 不改变任何可观察的产品行为；既有自动测试套件 SHALL 全部通过且不新增跳过。测试口径以 JDK 8 基线为准：Tests run ≥ 300、Failures 0、Errors 0、Skipped ≤ 10 且均为条件跳过（Assumptions）。

#### Scenario: 回归

- **WHEN** 升级后在 JDK 21 下运行全量测试套件
- **THEN** BUILD SUCCESS，测试总数不低于基线 300，0 失败/错误，跳过数不超过基线且均为条件跳过

### Requirement: MOD-03 日志链路完整

升级后 slf4j/logback/log4j 桥接 SHALL 正常路由：logback 1.3.x 经 SLF4J 2.x ServiceLoader 绑定；log4j-to-slf4j 与 jcl-over-slf4j 无 provider 冲突；启动与测试日志正常输出，无绑定缺失/多绑定警告。

#### Scenario: provider 解析

- **WHEN** 应用或测试 JVM 初始化日志
- **THEN** logback 生效且控制台无 "Failed to load class org.slf4j.impl.StaticLoggerBinder" 或等价 provider 警告

### Requirement: MOD-04 可回退

全部变更 SHALL 集中于 pom.xml（及本 change 文档）；回退 SHALL 通过单次 git revert 完成，无数据迁移或残留格式变更。

#### Scenario: 回退

- **WHEN** 升级后发现问题
- **THEN** revert 本变更提交即恢复全部旧版本，无需要清理的索引或配置文件变化

### Requirement: MOD-01 Java 21 平台基线

升级后的全部运行时与测试依赖 SHALL 支持 Java 21；编译基线 SHALL 为 `<release>21</release>`；maven.compiler.source/target 的 1.8 设置 SHALL 移除。依赖版本 SHALL 按本变更 proposal 版本表逐项指定的当前稳定版本采用（个别组件显式例外须在 proposal 中登记理由）。

#### Scenario: 边界执行

- **WHEN** 依赖或插件的候选版本与 Java 21 不兼容，或要求更高 JDK
- **THEN** 不采用该版本，选择与 Java 21 兼容的最新稳定版本并在 proposal 版本表中登记

#### Scenario: 平台核验

- **WHEN** 在 JDK 21 下执行 `mvn -B verify`
- **THEN** 编译产物类文件版本为 65（Java 21），构建成功
