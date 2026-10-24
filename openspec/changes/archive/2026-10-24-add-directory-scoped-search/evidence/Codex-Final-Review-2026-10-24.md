# A/R24最新成果与提交独立复验

日期：2026-10-24（Asia/Tokyo）。受测HEAD与本轮GitHub main实查均为 `0e8de6dcefe155fb7d8e938df332ea3d14942ca4`。**R24-01～06可按本轮范围关闭；未发现新的A实现P0/P1/P2缺陷。A0～A7实现交付、修复闭环及自动验证成立，正式M完整记录与用户A8仍待完成。** 名称体验B方案另行评审，本轮结论为CHANGES_REQUESTED，未批准实施。

## 构建与验证证据

Windows10/JDK17/Maven3.9.12及真实Java8 `1.8.0_40`。本轮始终在Git archive一次性副本及独立work/home/source/index执行。根：`C:\Users\Administrator\AppData\Local\Temp\docfinder-finalqa-20261024-5q25y5l9`，原始日志/Surefire XML保留在该根；可提交摘要见 [finalqa-20261024](finalqa-20261024/verification-summary.json)。

| 受测对象/执行项 | 实际结果 |
| --- | --- |
| 原始0e8de6d，clean verify | 169总数，167通过，0失败/错误，2平台跳过，exit0 |
| 原始0e8de6d，真实Java8 fork | 同上169/167通过/0失败/2跳过，XML验证运行时及隔离属性 |
| 原8探针+2个EDT先后检查+watchdog重复5次+EXACT特征 | 16/16通过、0失败/错误/跳过；补充测试只在副本，未合入产品测试 |
| P-00/P-01 | 2项通过，P-00真实verdict计算1→1→2；P-01门槛OK |
| 本地帮助文案修订后的源码，clean verify | 再跑169/167通过/0失败/2跳过，exit0 |
| 帮助文案修订后，真实Java8 fork | 再跑169/167通过/0失败/2跳过，exit0 |
| 最新真实Web页面 | 8项主流程通过：整树、文本预览、真实clipboard回读、错误清结果/选择/预览、FOLDER根自身、源外拒绝、Clear、排序后的父目录快捷 |

两平台跳过为POSIX-only路径和POSIX根；带 `-Ddocfinder.argline.probe=present` 所以隔离探针执行。桌面harness20项在两轮完整构建及Java8均执行，不是headless跳过。构建有既有shade重叠资源/module-info警告，未运行独立漏洞扫描/native。

命令：`mvn -B "-DargLine=-Xmx768m -Ddocfinder.argline.probe=present" clean verify`；Java8增加 `-Djvm=D:\java\java8\bin\java.exe`。性能用 `-Dtest=DirectoryScopeCheckBenchmark,DirectoryScopedSearchPerfBenchmark`；副本内定向探针用 `-Dtest=DirectoryScopedSearchTest#codex*,DirectoryScopeDesktopHarnessTest#codex*,CodexScopeDisplayProbeTest`，其中watchdog临时改RepeatedTest(5)，结束后恢复源码。

原HEAD JAR SHA-256：`99fba76d1b1112ea96100679504ae4f797d3864affac4a1b5715f6b60df12b76`。帮助修订变体JAR：`9eac540be14c0266f229b4d3d73daf9b2b0d911aca85a61390f72110f46b956c`。后者是0e8de6d加本地MainWindow帮助HTML文案差异，见 [help-copy.diff](finalqa-20261024/help-copy.diff)，不是已提交新SHA；查询/索引行为和依赖版本未被Codex修改。

## 用户特别指定的三项裁决

### 1. R24-01 EDT先后顺序：通过，当前不增防闪烁功能

失败任务在EDT内先失效查询代次并取消worker，再null发布/清结果/固定状态；旧done在失败之后执行时被token检查丢弃。成功及异常迟到的正式latch测试通过，独立阻塞旧成功探针也通过。

旧done先于失败任务执行时，旧服务在该时刻仍是已发布服务，先显示旧结果，随后失败任务清空是有效状态转换。本轮新增 `codexFinalShouldClearAlreadyCompletedOldResultWhenOpenFails` 明确先等旧结果已呈现，再触发失败，断言最终零行、服务null、旧服务关闭且不可用提示保留，通过。两个回调都在EDT，不能在失败清理内部相互穿插。短暂旧态/可能闪烁不构成错误索引回填；现有设计不承诺打开开始即隐藏旧结果，不为此扩大修改。

### 2. R24-03 watchdog：接受为Windows真实I/O故障验证，保留调度限制

连续5次实际调用Index All，A提交后ACL拒写、B真实写入/commit失败，部分失败文案和reader级A存在/B无文档断言全部通过；错误日志片段见 [watchdog-faults.txt](finalqa-20261024/watchdog-faults.txt)。原完整套件及Java8也通过该场景。

它依赖调度，200个B文件只增加注入窗口，不是同步屏障；`denyApplied`、部分失败状态及B无提交断言使未注入或过晚注入通常表现为失败，而非默认放行。现有强度可用于受控Windows集成回归，不声称任意机器稳定零抖动，也不把200文件数量当正确性证明。若后续CI出现抖动，优先换窄测试屏障并在finally中停止/join watchdog、显式检查ACL恢复，作为测试维护，不重新推翻已修产品。UI恢复的factory仍为受控fake，真实HTTP/服务恢复另由E2E验证，完整M-06点击链路仍待补。

### 3. 越界目录归属与清理：依据合理；独立确认四个精确路径现已不存在

此前Codex已看到4个与错误properties规则吻合的特定目录。本次实施者进一步记录单段Lucene结构、cfs内qa-indexall-src测试路径及时间对应，较单凭时间更能支持测试产物归属判断。按这些精确目录清理的处理合理。

Codex逐一复查四个既知完整路径均不存在（JSON记录）。由于内容已删除，本轮不能重新读取当时的cfs或独立重演删除过程；操作细节仍来源于两份Devin报告，不伪造“已复算删除前哈希/完整审计”。未发现需要再次清理的同名遗留。当前测试用ConfigManager.setIndexDir、建库前路径读回/允许根守卫、真实reader成员断言及配置字节恢复，独立运行通过。

## 其余成果与提交

| 提交/成果 | 复核结果 |
| --- | --- |
| 8f756dd/da3db49 | R24修复和报告；01～06代码/用例/计数/文档均已核对。R24-04宿主路径及逐结果parent断言通过；05计算计数确在真实计算入口递增；06已统一指定版本及例外 |
| 824fb4d / 748f297 | 删除未接线ui.workers类，当前src无引用、完整构建通过；当前活跃MainWindow内部SearchWorker保留，无目录请求回退路径新增 |
| a0b6caf | 启动堆上限改2GB，不等于证明native OOM已修。hs_err_pid9580记录native mmap申请约444MiB失败，当时AvailPageFile仅361MiB；日志Command Line也未证明按旧脚本-Xmx1024m启动。保留现有参数，纠正注释/文档因果；没有对真实业务重新建库或承诺不再崩溃 |
| 93c2981 | 进度快照含旧HEAD/“未推送/未建B change”口径，已按最新实查更新；实库冒烟仅是实施者转述，不作为Codex业务数据运行证据 |
| 0e8de6d | B提案符合产品方向，但存在核心查询逻辑和契约矛盾，见B review，不予直接实施 |
| README/README_CN/UsageGuide/内置帮助 | 当前A操作、Applied草稿、迁移、实际数据位置、EXACT真实语义、现有预览/API及名称中段通配替代方法已同步；B未来行为明确标未实现；内置帮助编译/套件通过，弹窗视觉未验 |

## 性能与隔离

P-00 service首次0.58ms，100热调用p95=0.00ms且computations=1；刷新后首次0.07ms，100热调用p95=0.00ms且computations=2；删旧/删新2.30/18.05ms，后者409。P-01 global/scoped热p95=21.02/20.19ms，未触发退化门槛。它们是A合成夹具数据，不是B通配子串性能或所有硬件SLA。

真实仓库config.properties/sources.txt/shares.properties/history.txt四控制文件前后SHA相同；未声称全业务索引逐字节验证。所有索引/ACL操作均为系统临时夹具，不读取业务来源内容。副本变体与原始HEAD的证据分别记录。

## M、OpenSpec关口与下一步

| M | 当前状态 |
| --- | --- |
| M-01 | 原选择器确认/取消、Applied与组件测试通过；本轮native AX仅暴露窗框，截图再次FrameArrived timeout。来源管理/坏行/完整右键/长路径视觉点击记录尚缺 |
| M-02 | 八Code/未知映射、原清理及重开失败探针通过；完整真实窗口八错误/预览交互记录尚缺 |
| M-03 | 受控EDT/Index All/latch时序通过；原生输入/关闭窗口全清单未全补，保持范围记录 |
| M-04 | 本轮8项真实浏览器流程通过，含真实clipboard回读；特殊路径/409/503/区域/真实根/复制拒绝全表未在本轮全部重跑 |
| M-05 | 以前受控竞态通过；web代码与静态资源在4f56d0d和0e8de6d的Git tree相同，本轮不冒称重新注入。范围与旧记录关联保存 |
| M-06 | 真实Index All、部分提交/失败、E2E套件及5次压力通过；首次建库/完整重建成功失败/打开分享的全套点击级记录仍待补 |

实施任务52/56；剩余9.1、9.3、10.1、10.2不因R24关闭自动勾选。Completeness：实现交付已核验、M/A8未完成；Correctness：本次范围内169套件/16探针均绿，未见新A缺陷，未覆盖项如表；Coherence：源码/索引语义一致，用户文档修正完成。native/POSIX宿主/真实UNC/真实大网络未验；不可给出全项archive-ready结论。

下一步双线推进：Codex补/核实A主机记录后形成A8候选；Devin仅修订B0.2方案，关闭B-01～09，交Claude复核及用户批准。B评审和A补验可并行；B产品实现仍未授权。本轮未执行归档、提交、推送或业务重建。
