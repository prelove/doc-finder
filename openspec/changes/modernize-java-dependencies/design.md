# Design: Java 8 兼容线依赖现代化

文档1.0；2026-10-24。

## D1 版本选型原则

每个组件选"支持 Java 8 的最高稳定小版本线"内的最新发布版；不引入新依赖坐标，不删除既有依赖。所有版本均为发布超过 7 天的稳定版。注意所选线本身可能已上游 EOL：logback 1.3.x 与 Tika 2.x 均 EOL，FlatLaf 上游已出 3.7.x——本变更是 Java 8 约束内的线内升级，残余维护/EOL 风险保留并登记（QA24-10）。

- 日志栈对齐到 slf4j 2.0.x：slf4j-api 2.0.17 + logback-classic 1.3.16（自带 SLF4JServiceProvider，2.x 绑定）。log4j-to-slf4j 是 SLF4J 的调用方适配器而非 provider，2.23.1 与 slf4j 2.x 兼容。jcl-over-slf4j 由 Tika 传递 2.0.x，与 api 2.0.17 对齐——当前 pom 中 api 1.7.36 与传递的 jcl 2.0.10 已存在错位，本升级顺带修正。
- FlatLaf 3.6.2：3.x 与 3.2 API 兼容；主题包与 extras 同版本联动。
- Tika 2.9.4：同线补丁，Parsers 与 AutoDetectParser API 不变。
- Surefire 3.5.5：沿用既有 forkCount/workingDirectory/argLine 隔离配置；`-Djvm=` 仍可用于 Java 8 fork 验证。

## D2 风险与验证

- 主要风险是日志路由静默失效（slf4j 1.7→2.0 的 provider 机制变化：1.7 用 StaticLoggerBinder，2.0 用 ServiceLoader）。验证方式：测试日志与 logback.xml 正常输出、无 "SLF4J: Failed to load" 警告。
- Shade 合并 META-INF/services 的 ServicesResourceTransformer 必须保留——logback 1.3 的 provider 发现依赖它。
- 不改源码；若升级暴露编译告警/错误，先记录再最小修复，不顺手重构。

## D3 回退

全部改动集中于 pom.xml 单文件；回退 = git revert。
