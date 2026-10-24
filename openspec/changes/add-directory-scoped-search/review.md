# Review Register and Response

当前包0.4，已收到用户转交的Claude 0.4针对性复核PASS，无新增阻断项。源码/构建/GUI/性能尚未实施或验证；用户已授予实施批准，当前A0交接就绪、任务尚未开始。

## Review identity and provenance

| 轮次 | 实际结论/证据 |
| --- | --- |
| 0.2 | CHANGES_REQUESTED，原文reviews/claude-0.2.txt、历史指纹reviews/baseline-0.2.sha256 |
| 0.3 | 用户本聊天转交的Claude PASS（计划充分性）；reviews/claude-0.3.txt为转存摘要，完整措辞以本聊天用户消息为准，不作逐字节原文声明 |
| 0.3指纹 | reviews/baseline-0.3.sha256；manifest SHA-256为516b5e9fd3fe06fe8be610e21b8a0b7c134fc6600abf14bec50dd608d2764132 |
| 0.3 ZIP | SHA-256为8a450bb47234d417a0a77a5a493deb6ad1e505284b59e5f75b0715aa7607a99f；Claude确认14基线/17归档文件一致 |
| 代码 | HEAD仍9a1c26ad1f3261c16548f55693b3380d5db67790，业务代码未变 |
| 未执行 | Claude未跑测试/构建/GUI/浏览器/基准；本轮也未执行新功能 |
| 当前0.4 | Claude已作针对性复核PASS，非逐行重读全包；F-017/F-020未被其逐行核对，Codex本轮补查文档落点；实施已批准，A0待实际执行 |

## Closed findings from 0.3

下列关闭依据是Claude实际复核，不是Codex自行宣布。关闭只针对规划充分性，实施后仍需测试/独立代码审查。

| ID | 级别 | 0.3复核结果 |
| --- | --- | --- |
| F-001 | P1 | CLOSED_PLAN_REVIEW：统一不可用、服务恢复、sequence与重建503 |
| F-002 | P1 | CLOSED_PLAN_REVIEW：迁移路线/代价/原path与全库门槛 |
| F-003 | P2 | CLOSED_PLAN_REVIEW：充分条件/精确回退/reader缓存/P-00 |
| F-004 | P2 | CLOSED_PLAN_REVIEW：全局/限定语法错误一致 |
| F-005 | P2 | CLOSED_PLAN_REVIEW：坏行容错及全部无效错误 |
| F-006 | P2 | CLOSED_PLAN_REVIEW：路径状态/三类刷新 |
| F-007 | P2 | CLOSED_PLAN_REVIEW：Web边界与422风险知情 |
| F-008 | P2 | CLOSED_PLAN_REVIEW：固定隔离与Codex主机验收 |
| F-009 | P3 | CLOSED_PLAN_REVIEW：仅关键词Explanation |
| F-010 | P3 | CLOSED_PLAN_REVIEW：规范一致/扩展路径边界 |
| F-011 | P3 | CLOSED_PLAN_REVIEW：工作目录排除说明 |
| F-012 | P3 | CLOSED_PLAN_REVIEW：A5a/A5b串行拆包 |
| F-013 | P3 | CLOSED_PLAN_REVIEW：报告纳入指纹、原版保留、ZIP核验 |

## New findings addressed in 0.4

CLOSED_PLAN_REVIEW仅针对Claude明确核对的规划内容；ADDRESSED_IN_PLAN仍为文档已补。两者均不代表代码已修或运行测试通过。F-017/F-020保留后者，不冒充独立复核。

| ID | 级别 | 核验与修订 | 验证落点 | 状态 |
| --- | --- | --- | --- | --- |
| F-014 | P2 | 源码done拼接异常属实；新增共享SearchErrorMessages.forCode，未知固定失败，不透传getMessage/堆栈 | D3、DS-09、A5a 6.1/6.2、headless八Code+fake实窗八Code/未知、M-02 | CLOSED_PLAN_REVIEW |
| F-015 | P2 | 源码null空结果属实；worker局部捕获一次服务，null抛INDEX_UNAVAILABLE；重建失效旧query token | D5、DS-12、6.1/6.2、M-03两种latch时序 | CLOSED_PLAN_REVIEW |
| F-016 | P2 | 明列App:77、MainWindow:1431/1636三处，全部后台入口；生产new仅工厂一处 | D8、6.2/6.3全仓静态搜索/evidence | CLOSED_PLAN_REVIEW |
| F-017 | P3 | 错误不入历史；帮助说明输入保留字符与转义。C++不预断必错，按现有parser特征测试；report (final为非法例 | D3、A3/A5a、M-02/03、UsageGuide | ADDRESSED_IN_PLAN |
| F-018 | P3 | 明确允许pom仅改测试配置；空argLine属性+早期组合保留外部参数、隔离home最后；实际fork探针 | D9、A0、proposal/README/handoff | CLOSED_PLAN_REVIEW |
| F-019 | P3 | 迁移残留补离线/权限不足/部分遍历失败；409消息先检查可达性 | D3/Migration、A4 5.4、M-06 | CLOSED_PLAN_REVIEW |
| F-020 | P3 | 共享解析snapshot提供跳过行号；桌面一次非模态警告指日志、修复清除，不覆盖查询状态，HTTP不披露 | D2/D5、DS-04、A1/A5a、M-01 | ADDRESSED_IN_PLAN |
| F-021 | P3 | 来源下拉用同PathScope等价比较；非法编辑只Custom不报错/查盘 | D5、DS-07、A5a参数化/状态测试 | CLOSED_PLAN_REVIEW |
| F-022 | P3 | Index All整体失败但存在commit也刷新/重开，保留部分失败状态而非Ready；无commit不可用 | D8、DS-12、A5a/A7、M-06 | CLOSED_PLAN_REVIEW |

## Review impact and approval

0.4已收到Claude针对性PASS，F-014/015/016/018/019/021/022明确核对接受；未逐行重读全部材料，F-017/F-020仍需Devin在A1/A5a evidence单列证明，不重新发明产品取舍。
Codex本轮核对F-017在D3、tasks 4.1/6.1/6.3、M-03的解析/错误不入历史要求；F-020在D2/D5、tasks 2.3/6.1、DS-04/07及M-01中有snapshot、一次提示与修复清除。这里只验证材料充分性，未执行特征测试/GUI。
为保持已审包，冻结README/handoff/planning-validation保留打包时状态；当前进度以本文件和delivery-status.yaml为准，它们从一开始就不在manifest内。0.4manifest、design/tasks及ZIP不变，不生成0.5，不再强制整包评审。
用户已明确批准当前指纹，包含A0修改pom现有Surefire/properties测试配置（依赖版本不变）；批准记录见approval.md。后续分批实施不重复索要相同授权；最终验收和发布操作仍需既定确认。

## 0.4 PASS核验记录（2026-10-23）

来源：本聊天用户粘贴“DocFinder 目录检索方案0.4：复核结论”。摘要：PASS；核对指纹/ZIP及七项修订；未逐行重读全部文件，F-017/F-020未逐行核对；未运行测试/构建/GUI/浏览器/基准；实施决定归用户。完整复核措辞保留在用户消息，不声称本摘要为逐字节原件。

Codex实际执行：
- 重新计算manifest SHA-256：2e332e5545db0cf8514856ae19c47701a33b80d7536c43333764498f3efe17de。
- 逐个复算16个冻结输入文件，与manifest一致。
- ZIP SHA-256：05e95ddad139f16dcd8ab153c30652f2fef4fc1705b64e10e66bbcd9c96d18b5，与.sidecar一致。
- 更新台账前，ZIP的19个文件与工作目录逐字节一致；更新后仅review/status可变，不能再声称整树逐字节等同冻结ZIP，但16个正式输入仍完全相同。
- HEAD为9a1c26ad1f3261c16548f55693b3380d5db67790，AGENTS.md与pom.xml哈希不变，OpenSpec严格校验通过。
- 不重跑既有产品测试，不运行任何索引或迁移。

## 用户实施批准（2026-10-23）

用户原文：批准以 0.4 交给 Devin 实施，包含 A0 测试配置修改。

已绑定原0.4 manifest和评审ZIP，见approval.md；G2通过，发布/验收批准未授予。用户已手动打开Devin，Codex提供A0 1.1～1.4分批指令。状态为交接就绪、未声称已派发或开始，实际任务仍0/31。冻结文档的历史待批准文案不再作为当前授权状态，当前状态由批准原文、approval.md、delivery-status.yaml决定。


## 2026-10-24 A7 独立实现复核（最新运行结论）

结论：REMEDIATION_REQUIRED_BEFORE_ACCEPTANCE。原Claude 0.4 PASS是方案充分性结论；此前修复PASS也有其当时版本/范围。本轮c7和4eb各clean verify 149总数/148通过/0失败/1跳过，4eb真实Java8同结果；新增8项中7个目标断言失败、1通过，浏览器22项通过。新增4类运行偏差（QA24-01～04）及静态/证据/管理事项登记tasks第12组，不覆盖历史F编号；未发现新的P0/P1，不等于用户已经接受残余P2。

完整范围、运行/静态的区分、未执行项、修复验收条件见 [Codex-QA-2026-10-24.md](evidence/Codex-QA-2026-10-24.md) 和 [Devin后续任务](evidence/Devin-Followup-2026-10-24.md)。Devin Local负责修复并交证，Codex独立复验；A8最终验收/归档不能先于闭环。冻结0.4 manifest/ZIP只作历史批准输入，不因管理台账变动重写批准记录。

本地最新观察8d41b0c与受测4eb产品/测试/pom树相同；GitHub main本轮实查36139f5。本次QA没有提交/推送或改变产品代码，所有新证据在共享本地仓库可读。


## 4f56d0d QA24修复独立复验（2026-10-24，本日第二轮）

REMEDIATION_REQUIRED_BEFORE_ACCEPTANCE。已核验本地及GitHub main为4f56d0d，常规JDK17/真实Java8各164总数/162通过/0失败/2平台跳过；原8探针全通过。新增3检查2个目标失败、1对照通过，浏览器19项通过。QA24-01/02/03/05/07/09可按本轮范围关闭；04/06/08/10/11部分未闭环。R24-01～06为4个P2和2个P3，无新增P0/P1；静态/运行/工具限制严格区分。完整证据见 [最新独立复验](evidence/Codex-Reverification-2026-10-24.md)，后续见tasks第13组与 [Devin下一批](evidence/Devin-Next-2026-10-24.md)。

重点：旧worker在reopen失败后仍可回填；测试properties路径失真已实际观察；不以fake发布证明真实Index All部分失败全链路，不以cacheSize证明枚举次数。用户最终验收/A8仍未授予。此前方案PASS和历史修复结论继续保留其原有版本及范围。
