# Tasks: modernize-java-dependencies

唯一实施进度来源。目标：Java 8 基线不变，全部可升级依赖/插件升到其 Java 8 兼容线内 proposal 版本表逐项指定的稳定版（个别组件显式不追上游最新，如 FlatLaf 3.6.2；EOL 线风险保留并登记）。

## Global Constraints

- maven.compiler.source/target 保持 1.8；不新增/删除依赖坐标；不改产品代码行为。
- 沿用既有 Surefire fork/test-classes/test-home 隔离；不触真实 `~/.docfinder`。
- 不提交/合并/推送/归档，除非用户另行批准。

## 1. 依赖与插件升级

- [x] 1.1 pom.xml 版本升级：FlatLaf 三件 3.2→3.6.2；slf4j-api 1.7.36→2.0.17；logback-classic 1.2.11→1.3.16；log4j-to-slf4j/log4j-core 2.17.1→2.23.1；tika-core/tika-parsers-standard-package 2.9.2→2.9.4；junit-jupiter 5.9.3→5.11.4；compiler 3.11.0→3.14.1；shade 3.5.0→3.6.1；surefire 3.0.0→3.5.5；native-maven-plugin 0.10.3→0.10.6。Lucene/jnativehook 不动。
- [x] 1.2 `mvn -B clean verify "-DargLine=-Xmx768m"` 全绿；核对日志无 SLF4J provider 警告、fork 隔离生效、`target 1.8`。
- [x] 1.3 记录证据与未验证项：真实Java8已由实现者及2026-10-24 Codex独立重跑；Codex带argLine探针为149总数/148通过/0失败/1跳过；native仍未执行，见upgrade-verification及目录检索Codex-QA报告。

## 2. 交付

- [x] 2.1 【2026-10-24用户组合验收及独立验证闭环】用户确认本变更结果；与 add-directory-scoped-search 的 A8 一并走授权交付，不单独归档。


## 3. 2026-10-24 独立验证后的管理补正

- [x] 3.1 QA24-10/R24-06：proposal/design/spec/tasks已统一为"指定版本兼容升级+显式例外"，无条件的"线内最新"承诺已移除；Logback1.3/Tika2仍EOL、FlatLaf3.6.2非上游最新均保留明示。未改变Java8基线，未升级依赖。
- [x] 3.2 【2026-10-24用户组合验收及独立验证闭环】与目录检索tasks第12组闭环后，核对最终受测SHA、远端实查SHA及A8状态，不把旧用户冒烟或方案PASS当升级后正式验收。

独立运行：4eb417b在JDK17 clean verify与真实Java8 test各149总数/148通过/0失败/1平台跳过；c7只跑基线构建，新增偏差探针在4eb执行；本轮不作依赖升级引入偏差的因果判断。证据：[Codex-QA-2026-10-24](../2026-10-24-add-directory-scoped-search/evidence/Codex-QA-2026-10-24.md)。当前验收仍需目录检索修复/复验与用户决定。


## 4f56d0d独立复验补正

JDK17 clean verify和真实Java8均164总数/162通过/0失败/2平台跳过（带隔离探针）。EOL已明示；3.1仍未关闭：“最高兼容线内最新”与已写明FlatLaf未追更新的例外仍有内部矛盾，统一为指定版本兼容升级及明确例外（R24-06/目录检索tasks13.6）。不因改文档再升级依赖。3.2及A8仍需目录检索残留闭环。最新证据：[Codex-Reverification-2026-10-24](../2026-10-24-add-directory-scoped-search/evidence/Codex-Reverification-2026-10-24.md)。


## 组合验收通过（2026-10-24）

用户确认最新版手动验收完全成功，依赖过渡版随A接受；指定版本/Java8验证/残留EOL边界不变。完整原文与来源见 [用户验收记录](../2026-10-24-add-directory-scoped-search/evidence/User-Acceptance-2026-10-24.md)。本批6/6任务完成，交付归档与A一并收尾。
