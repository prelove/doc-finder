# Spec Delta: build-modernization

## Purpose

在不改变产品行为与 Java 8 基线的前提下，把全部可升级依赖与构建插件升到其 Java 8 兼容线内 proposal 版本表逐项指定的稳定版本（个别组件显式不追最新，如 FlatLaf 3.6.2）。所选线本身可能已是上游 EOL 线（logback 1.3.x、Tika 2.x），EOL 风险如实登记，不宣称消除。

## ADDED Requirements

### Requirement: MOD-01 Java 8 兼容边界

升级后的全部运行时与测试依赖 SHALL 支持 Java 8 运行；编译输出 SHALL 保持 target 1.8。maven.compiler.source/target SHALL 保持 1.8，不得借升级引入仅 Java 11+ 可用的库版本。

#### Scenario: 边界执行

- **WHEN** 依赖或插件的候选版本要求 Java 11+（Lucene 9+/Tika 3+/logback 1.4+/log4j 2.24+）
- **THEN** 不采用该版本，选择同组件最后一条 Java 8 兼容线内的指定稳定版本（以版本表为准，允许显式例外不追最新）

### Requirement: MOD-02 行为不变

升级 SHALL 不改变任何可观察的产品行为；既有自动测试套件 SHALL 全部通过且不新增跳过。

#### Scenario: 回归

- **WHEN** 升级后运行 `mvn -B clean verify`
- **THEN** BUILD SUCCESS，测试总数不减少，0 失败/错误

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
