# Delivery and AI Handoff

文档包0.4；实施方式已由用户指定为 Devin；本轮仅形成评审包，无须再选择执行工具。

## Roles

| 角色 | 输入 | 负责 | 不代替 |
| --- | --- | --- | --- |
| 用户/产品负责人 | 当前文档、评审结论、演示/证据 | 范围决策、实施批准、最终验收、交付目标 | 模型通过不能代用户接受 |
| Codex/产品与项目经理 | 需求、Claude发现、Devin增量 | 澄清边界、依赖/批次、进度风险、规格一致、证据复核、返工安排 | 不把自述成功当质量保证 |
| Claude/独立评审者 | 指纹固定的文档包与必要源码 | 挑战方案、检查可执行性/风险/验收，输出结构化发现 | 不实施、不批准范围、不隐式扩大任务 |
| Devin/实施者 | 已批准文档与任务批次 | TDD实施、局部自测、进度与证据、演示、修复 | 不改产品范围、不自验收、不擅自发布 |
| 独立实现 reviewer | 实际diff、规格、测试证据 | 验实现与规格一致性，发现回归/缺测试 | 代码评审不等于业务验收 |

已收到用户转交0.2评审和0.3 PASS：0.2附件原文已保存，0.3本聊天保留完整原文并转存摘要；没有直接连接Claude/Devin、GitHub任务或远端工单。本包通过完整目录/仓库交接；状态只根据收到的真实输出更新，不暗示后台已启动或持续监控。获得可访问实施分支/PR后，Codex可按授权读取核验；需要外部消息/自动调度时另按实际授权配置。

## Gates

| 关口 | 进入/退出证据 | 推进负责人 |
| --- | --- | --- |
| G0 PLAN_REVIEW | 当前文档+指纹，交Claude；退出需真实评审输出 | Codex |
| G1 PLAN_RECONCILED | 每个发现有处理/理由，阻断项为0；实质修改契约则相应重审，材料重新指纹 | Codex+Claude |
| G2 APPROVED_FOR_IMPLEMENTATION | 用户明确批准当前指纹/版本、A范围和Devin执行；记录原始确认链接/文本与日期 | 用户 |
| G3 IMPLEMENTING | 每批按依赖测试/证据，失败先修，不提前勾后续任务 | Devin，Codex追踪 |
| G4 ACCEPTANCE_CANDIDATE | A0～A7证据完整，阻断评审闭环，JVM构建和GUI/Web验证成立 | Codex+独立reviewer |
| G5 ACCEPTED | 用户确认验收结果和风险接受，记录版本/实际SHA | 用户 |
| G6 DELIVERED | 用户授权的提交/PR/合并/推送/归档实际完成，最终SHA核对 | 按授权执行 |

回退规则：实质行为或验收变化回G1/G2；0.4仅细化既有契约，无新增取舍，按review.md影响分析不强制整包重审；纯实现缺陷回原任务；证据缺失回对应测试步骤；环境阻断保留任务未完成并安排可验证替代，替代不能改变规格。未执行必须写未执行。

## 批次与进度

默认顺序：A0 → A1 → A2 → A3 → A4 → A5a/A6 → A5b → A7 → A8。A5a/A6可在接口冻结后独立进行；A5b依赖A5a并由同一UI写入者负责；同一人实施可串行，不为并行强加团队。
每包预计一次会话可完成；若超过约半天仍无可验证产出，Devin报告原因和拆分建议，由Codex调整批次而非编造完成日期。先用A0/A1实际耗时估算剩余，不预设虚假总工期。

Devin在开始/结束一个A包、阻断发生、影响范围变更和准备交验时提交：
- 当前代码SHA/工作树及文档指纹、任务号、状态；
- 改动文件和行为；
- RED/GREEN命令、退出码、测试数量、报告路径；
- 真实手工观察、未覆盖项、下一任务与依赖。

Codex每次复核后更新review/status，核对tasks勾选，给出已验证内容/剩余风险/下一批次。进度按tasks已验证框统计，不使用“写了代码≈80%”估计。无外部新证据不伪造进展。

## Claude optional delta check prompt

0.3已PASS，0.4不强制整包重审；如用户希望快速确认补丁，可复制下文只核F-014～022与跨文档一致性。任何新P0/P1另行闭环。

```text
请作为独立架构/产品/可测试性评审者，只评审，不写业务代码、不实施、不提交。
项目：DocFinder；change：openspec/changes/add-directory-scoped-search，文档包0.4。
这是0.3已PASS后的0.4非阻断补充核对；先读reviews/claude-0.3.txt（本聊天转存摘要）、review.md、decisions.md，再读README，核对review-baseline.sha256和代码HEAD，再依次读proposal、spec、design、tasks、acceptance、analysis、roadmap、handoff；必要时核实真实源码调用链。
用户已确定只在配置来源内限定目录、默认涵盖整棵子树，当前由你评审、Devin未来实施、Codex协调。
F-001～013已经你在0.3接受关闭，无须无变化重复审；重点看F-014/015/016的错误映射/null worker/三创建点与F-018的argLine组合，并核对其余新增项；D8服务恢复是否可实施、D9测试隔离是否足够、reader缓存生命周期与反例是否正确，再核对：根自身语义；来源覆盖/离线/空索引；Windows/UNC边界；旧/混合live docs；Top-N前过滤；真实MainWindow；两端异步竞态；快捷动作；HTTP/JSON；迁移风险；Java8与依赖边界；任务测试/证据是否足以完成验收；是否不必要扩大范围。
禁止把后续B/C/D当本期要求；不要声称未执行的性能/GUI/native已通过。
按review.md格式输出：审阅指纹/代码版本、PASS或CHANGES_REQUESTED、F编号/严重程度、文件段落、具体问题、触发例、建议修正、验证方式。
P0/P1及未决产品契约阻断实施；P2/P3要有明确处置，不可隐瞒。
不要直接改规格或把评审通过当作用户批准；不要启动Devin。
```

## Devin implementation prompt

仅在G2实际通过后发送；未通过时可以读取方案，但不得编码。交接人需填入真实批准记录，不能以此模板占位启动。

```text
请在DocFinder实施已批准的change：openspec/changes/add-directory-scoped-search。
先读README、delivery-status.yaml和implementation_approval引用的真实记录，核对文档指纹及当前源码差异；无批准/有阻断发现立即报告，不编码。
你负责A阶段；Java8与pom依赖版本保持；允许A0改现有Surefire和空argLine测试属性，不实现roadmap的B/C/D，不改原path标识，不重构全部UI，不把范围当权限，不触碰真实.docfinder。
按tasks从A0开始，一包一包TDD：目标失败→最小实现→定向通过→差异审查→同步使用说明→evidence，完成才勾对应框。
工具可用则OpenSpec apply配合Superpowers executing-plans/TDD；没有工具时按同等步骤，不擅自安装工具、改变仓库配置或放宽验证。
A0必须先固定测试隔离，之后使用默认fork；GUI由Codex安排实机验收，你交harness与证据，不能因环境缺失伪报通过。
每包报告SHA、任务、文件、命令/退出码/报告、手工结果、风险和下一步。环境失败不算RED；GUI未跑保持未完成。
需求/接口/依赖需要改变时交Codex协调；修业务错误按systematic-debugging处理，不能静默改规格。
完成A0～A7后交候选，不自称用户已验收。提交/PR/推送/归档按明确授权；未授权不执行。不要带入用户原有AGENTS.md修改。
```

## 等价工作法与返工

- brainstorming：核对用户目标与范围，未决分支先决策。
- writing-plans：按tasks固定接口/文件/依赖/验证，不另写一套漂移计划。
- test-driven-development：目标RED、最小实现、GREEN，重构保持行为。
- systematic-debugging：复现→定位→最小修复→关联回归；记录根因。
- requesting-code-review：把diff和证据交独立评审，不仅给“已完成”总结。
- verification-before-completion：先运行、读结果、记录限制再声明。
- OpenSpec verify：逐条DS对照实现与证据；validate只是结构校验。
- finishing/archive：用户接受与交付授权后处理，证据保留，不能自动发布。

## 范围变更控制

发现新需求先登记review：影响DS/接口/迁移/依赖/验收吗？若是，由Codex修改所有受影响文档、重生成指纹，Claude评估影响、用户批准后继续相关任务。变量命名等不改变契约的局部实现选择可由Devin决定并记录，不层层请求许可。
已通过任务因规格变更失效，取消相应勾选并重测；禁止保留过期“通过”。

## 当前交接状态

用户已明确五项选择；Claude对0.3按计划充分性PASS并关闭F-001～013。0.4纳入F-014～022非阻断细化，G2实施授权仍false。旧项关闭是计划评审关闭，绝不代表代码已实现；新增项为ADDRESSED_IN_PLAN，实际测试仍未执行。
