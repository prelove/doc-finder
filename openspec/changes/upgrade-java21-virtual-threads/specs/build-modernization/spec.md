# build-modernization Specification (delta)

## REMOVED Requirements

### Requirement: MOD-01 Java 8 兼容边界

**Reason**: 本变更正式跨越 Java 8 平台边界，该兼容边界要求被下方的 Java 21 平台基线要求取代。

**Migration**: 采用 ADDED 的「MOD-01 Java 21 平台基线」作为新的平台边界；依赖选型口径由「Java 8 兼容线内最新」改为「Java 21 兼容的当前稳定版本（以 proposal 版本表为准）」。

## ADDED Requirements

### Requirement: MOD-01 Java 21 平台基线

升级后的全部运行时与测试依赖 SHALL 支持 Java 21；编译基线 SHALL 为 `<release>21</release>`；maven.compiler.source/target 的 1.8 设置 SHALL 移除。依赖版本 SHALL 按本变更 proposal 版本表逐项指定的当前稳定版本采用（个别组件显式例外须在 proposal 中登记理由）。

#### Scenario: 边界执行

- **WHEN** 依赖或插件的候选版本与 Java 21 不兼容，或要求更高 JDK
- **THEN** 不采用该版本，选择与 Java 21 兼容的最新稳定版本并在 proposal 版本表中登记

#### Scenario: 平台核验

- **WHEN** 在 JDK 21 下执行 `mvn -B verify`
- **THEN** 编译产物类文件版本为 65（Java 21），构建成功

## MODIFIED Requirements

### Requirement: MOD-02 行为不变

升级 SHALL 不改变任何可观察的产品行为；既有自动测试套件 SHALL 全部通过且不新增跳过。测试口径以 JDK 8 基线为准：Tests run ≥ 300、Failures 0、Errors 0、Skipped ≤ 10 且均为条件跳过（Assumptions）。

#### Scenario: 回归

- **WHEN** 升级后在 JDK 21 下运行全量测试套件
- **THEN** BUILD SUCCESS，测试总数不低于基线 300，0 失败/错误，跳过数不超过基线且均为条件跳过
