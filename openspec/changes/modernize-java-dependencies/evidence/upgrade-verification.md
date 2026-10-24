# Evidence: modernize-java-dependencies 升级验证

日期 2026-10-24（Asia/Tokyo）。基线 HEAD：bdec8aa（升级前）；本变更只改 pom.xml 版本号，无源码改动。

## 版本落地

| 组件 | 旧 | 新 | Java 8 兼容线依据 |
| --- | --- | --- | --- |
| flatlaf / intellij-themes / extras | 3.2 | 3.6.2 | 3.x 全线支持 Java 8 |
| slf4j-api | 1.7.36 | 2.0.17 | 顺带修正与 jcl-over-slf4j 2.0.x（Tika 传递）的错位 |
| logback-classic | 1.2.11 | 1.3.16 | 最后支持 Java 8 的线（含 CVE-2025-11226 修复） |
| log4j-to-slf4j / log4j-core | 2.17.1 | 2.23.1 | log4j 最后支持 Java 8 的线（2.24 起需 17） |
| tika-core / parsers-standard-package | 2.9.2 | 2.9.4 | 2.x 线补丁版本 |
| junit-jupiter | 5.9.3 | 5.11.4 | JUnit 5 全线 Java 8 |
| maven-compiler-plugin | 3.11.0 | 3.14.1 | |
| maven-shade-plugin | 3.5.0 | 3.6.1 | ServicesResourceTransformer 保留（logback provider 发现依赖） |
| maven-surefire-plugin | 3.0.0 | 3.5.5 | fork/隔离 argLine 配置不变 |
| native-maven-plugin | 0.10.3 | 0.10.6 | native profile（Java 17 工具链）不变 |

未动：Lucene 8.11.2（9.x 需 Java 11+）、jnativehook 2.2.2（上游最终版）、maven.compiler 1.8。

## GREEN 验证（升级后 HEAD）

- `mvn -B clean verify "-DargLine=-Xmx768m"`：BUILD SUCCESS，**149 项，0 失败/错误，2 跳过**（与升级前基线完全一致——MOD-02 成立）。
- 字节码：`target/classes/.../App.class` 与 `MainWindow.class` major version = 52（Java 8），MOD-01 成立。
- 日志：测试与 GUI harness 输出经 logback 正常，无 SLF4J provider/绑定警告，MOD-03 成立（logback 1.3.16 的 ServiceLoader 绑定生效；shade 的 ServicesResourceTransformer 保留）。
- `openspec validate modernize-java-dependencies --strict`：通过。
- 真实 `~/.docfinder`：fork 隔离配置未变，无访问。

## Not tested / risk

- GraalVM native 构建未执行（无 GraalVM 环境）；native-maven-plugin 0.10.6 与 0.10.3 同线，native profile 仅在本机有 GraalVM 时使用。
- 用户实机 UI 冒烟（FlatLaf 3.2→3.6.2 的外观细节差异）建议在 M 记录轮次一并核对。

## Java 8 兼容性实证（用户要求复核后补验）

两层验证，均通过：

1. **字节码全量扫描**：`dependency:build-classpath` 解析出的全部 111 个 jar（含全部传递依赖）逐个抽查 class 主版本号，全部 ≤52（Java 8）；multi-release JAR 的高版本类只存在于 `META-INF/versions/`，Java 8 下不加载。
2. **真实 Java 8 运行时全套测试**：`mvn -B test "-Djvm=D:\Java\java8\bin\java.exe" "-DargLine=-Xmx768m"` → 149 项，0 失败/错误，2 跳过；surefire XML 报告确认 fork JVM `java.home=D:\Java\java8`、`java.version=1.8.0_40`。含真实构造 MainWindow/加载 FlatLaf 3.6.2 的 GUI 测试。
3. 首轮无 -Xmx 运行因宿主内存压力 fork OOM（环境性，与之前 JDK17 下偶发同因），加 -Xmx768m 后通过；非兼容性问题。


## 2026-10-24 Codex 独立验证补充

受测4eb417b（8d41b0c产品/测试/pom相同）：JDK17 clean verify及真实Java8 1.8.0_40 fork test均149总数、148通过、0失败/错误、1 POSIX跳过；本轮带argLine探针所以比未带探针运行少一个skip。JAR SHA-256=a69e51ff33d4739ed604fda0f900c029dbeac21deceadb23176fc839ebf6aa39。完整报告及脚本见 [目录检索独立QA](../../add-directory-scoped-search/evidence/Codex-QA-2026-10-24.md)。没有native/正式App的Java8全流程验证或漏洞扫描。

QA24-10文档意见：不能声称本次“全部最新”或“消除EOL风险”；[Logback官方](https://logback.qos.ch/download.html)列1.3.x为EOL，[Tika官方](https://tika.apache.org/)列2.x为EOL，[FlatLaf发布](https://github.com/JFormDesigner/FlatLaf/releases/latest)本轮已有3.7.2。保留Java8是既定边界，残余维护风险需写明，交Devin统一修订proposal/design/spec/tasks，而非擅自升级Java大版本。运行测试通过不等于安全维护支持恢复。
