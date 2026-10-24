# Implementation Approval — DocFinder 0.4

日期2026-10-23（Asia/Tokyo）；批准人：本聊天用户。
原始批准原文：
> 批准以 0.4 交给 Devin 实施，包含 A0 测试配置修改。

用户随后说明已手动打开Devin，要求提供分阶段执行指令。此记录是用户消息的转存，不是模型代批；本次批准已经生效，无须重复询问相同授权。

## Approved baseline

- 产品包：0.4，change add-directory-scoped-search。
- 冻结清单review-baseline.sha256的SHA-256：2e332e5545db0cf8514856ae19c47701a33b80d7536c43333764498f3efe17de。
- 原评审ZIP：docfinder-directory-search-plan-0.4-20261023.zip。
- 原评审ZIP SHA-256：05e95ddad139f16dcd8ab153c30652f2fef4fc1705b64e10e66bbcd9c96d18b5。
- 源码基线：9a1c26ad1f3261c16548f55693b3380d5db67790。
- 仓库：https://github.com/prelove/doc-finder.git。

## Scope

批准Devin实施冻结A阶段任务；包括A0仅修改pom既有Surefire/properties测试配置及测试隔离，Java8和依赖版本不变。Codex负责分批下达/证据复核/主机验收协调，用户最终接受。

当前首批只下达A0，任务1.1～1.4。整体A阶段已获授权；后续A1等由Codex按依赖与证据放行，不重复要求用户批准同一范围。任务复选框仍全部未完成，批准不等于执行完成。
不授权B/C/D扩围、依赖升级、真实用户数据迁移/扫描/重建/删除，或尚未接受的提交/PR/推送/合并/归档。遇新范围/必要接口语义改变按既有变更控制处理。

## Snapshot versus current state

原README、proposal、tasks、handoff、planning-validation中的“待批准”是打包时快照，为保护已评审指纹不改写。权限状态以用户本次原文、本approval.md及当前delivery-status.yaml为准；行为仍以冻结spec/design/tasks为准，不借批准扩大范围。
授权交接ZIP是原0.4正式输入加当前批准/台账/启动指令，不替换原评审ZIP或产品版本。handoff-baseline.sha256校验该传输副本，不能替代原评审指纹。尚未声称Devin任务已派发或代码已开始。

## Acceptance and delivery

Devin每包提交真实命令/退出码/测试数量/报告、差异及风险。Codex核验后放行下一包；GUI/Web由Codex安排，Devin交harness。功能/性能/用户验收尚未发生。交付发布授权单独保留，用户未接受前不得自行宣称完成。


## 执行环境澄清（用户最新指示）

用户明确：使用Devin Local模式，直接在E:\idea_projects\doc-finder开发，不使用Cloud。材料已在本地change目录，上传ZIP/远端克隆不是前提；执行授权和首批A0范围不变。原授权ZIP保留为历史快照，当前Local以devin-start.md和delivery-status.yaml为操作入口。
