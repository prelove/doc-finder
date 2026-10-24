# Devin Local 下一批：关闭本次复验残留

共享仓库：`E:\idea_projects\doc-finder`。先读 [Codex-Reverification-2026-10-24.md](Codex-Reverification-2026-10-24.md)、tasks第13组与delivery-status。已核验远端main为`4f56d0d`，上一轮8探针全通过，但新增11项总计9通过/2失败。仍在A7，不执行A8归档，不开始B/C新能力。

按现有Superpowers流程执行：目标失败回归→最小修复→通过→证据→独立复核。OpenSpec tasks为唯一勾选台账，acceptance记录实际验证范围。你不是共享仓库唯一写入者；先读git status，保留无关.playwright-mcp与jerr4.txt，不整文件覆盖、不重写历史指纹。下面代码修复和补证属于既有契约，不需要再次申请相同实施许可。

## 批次1：先恢复可信测试，再修产品竞态

1. **R24-02 / QA24-06，测试隔离**：`DirectoryScopeDesktopHarnessTest.shouldRunRealIndexAllLifecycleAndPublishService`禁止直接拼Windows路径到properties。改为ConfigManager.setIndexDir/Properties.store；建库前断言readback==预期idx且实际路径处于允许QA根，越界则在写入前失败。使用@TempDir或可证明受控根并正确清理；用真实reader检查目标idx的文档，而不只看fake服务发布。可复用 [独立探针补丁](reverify-20261024/independent-probes.patch) 中round-trip失败用例。
2. 核对报告列出的4个`C:\UsersAdministrator...qa-indexall-idx-*`历史索引。逐个验证绝对路径、测试特有内容和非业务用途，记录后只清理确认的精确目录；不要按模糊匹配全盘删除。无法证明归属则保留并报告。Codex本轮错误位置仍在一次性QA副本，不需触业务数据。
3. **R24-01 / QA24-04，MainWindow**：当前reopen失败分支setSearchService(null)与清表前后须让在途旧worker失效；包括旧成功和旧异常。保持EDT/sequence/close候选规则、Web null发布以及固定错误文案。目标复现：旧fake查询latch阻塞→新索引打开失败→确认不可用→释放旧查询→仍零行、无选择/预览、状态仍不可用、历史不被旧结果污染。补丁已有成功迟到的确定性RED，另加迟到异常保护。

批次1交：具体SHA/差异，两个目标RED/GREEN、路径守卫结果、清理记录或保留原因。不得只在原弱测试上报164全绿就关闭。

## 批次2：让证据确实证明承诺

4. **R24-03 / QA24-06**：真正调用Index All入口，控制在途查询时序；让来源A实际commit、B实际失败，验证真实服务/Web可查已提交内容且部分失败文案不被Ready覆盖。现有通过条件仅fake reopened不是全部证明。E2E对未提交B须发全局或合法B范围查询；另保留A范围边界断言。Codex对照已证明“B提交后在A范围也不可见”，因此旧断言不能证明未提交隔离。
5. **R24-04 / QA24-08**：来源不可变snapshot用例的E:\\Docs改宿主绝对路径；Windows专例显式条件；盘根/UNC/POSIX根逐结果核对parent，不以响应任一null通过。没有POSIX宿主可写未运行，不用假OS属性冒充平台验证，不擅自安装环境。
6. **R24-05 / QA24-11**：cacheSize不是计算次数。给真实verdict计算入口加窄测试计数钩子，断言首次1、重复100次不增、reader刷新后再算；helper/null/I/O/close仍按原规则。若涉及精确枚举还需对应计数，别把verdict次数叫term枚举。否则撤回枚举声明并把未完成门槛留开，不能打印常量充实测。同步benchmark输出、tasks12.11和修复证据。
7. **R24-06 / QA24-10**：EOL明示可保留；proposal/design/spec/tasks的无条件“最高Java8兼容线最新”改为具体指定版本兼容升级及例外，消除与FlatLaf未追已有更新的内部矛盾。不为改文档擅自升级版本或Java基线。两change台账中的旧SHA/旧149结果标历史，最新状态指独立复验报告。

## 回交与之后的任务

完成第13组后，在最终产品SHA交完整clean verify、带argLine隔离探针、真实Java8 test、修复定向结果、P-00/P-01、两个OpenSpec strict validate及diff检查；逐条说明通过/失败/跳过，分开产品与测试工程问题。报告不可把缓存条目数当枚举计数，不可把源码条件测试当真实POSIX/UNC宿主验证。

Codex后续负责独立复验与M-01～06剩余主机记录。当前窗口截取工具连续两次超时，本轮没有点击级桌面截图；准备隔离harness和可复现步骤，不能因此代签M通过。若需增强harness，只加受控的最小测试钩子，不向产品暴露测试API。修复闭环后才形成A8候选，用户最终验收与归档授权保持独立。当前指令文件已写入共享本地仓库，不声称已通过Devin UI发送。
