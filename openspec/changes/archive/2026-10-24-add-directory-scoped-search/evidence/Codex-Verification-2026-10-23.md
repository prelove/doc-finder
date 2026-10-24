# 已提交目录检索功能：独立测试报告

## 1. 结论

**继续开发：有条件 GO，仅进入缺陷修复与补验阶段。最终验收、归档和发布：NO-GO。**

已提交的核心目录过滤、递归正文查询、中文检索及正常的桌面/Web 自动回归具备可用基础；不需要推翻当前架构。但本轮在既有测试全绿之外，复现了 4 类问题，并通过独立代码审查识别另 3 项待动态验证的问题。因此不能将“140 项自动测试没有失败”解释为“已满足全部验收要求”。

本轮没有修改产品代码、修复缺陷、创建 Git 提交或推送。报告交付不等于用户已验收，也不修改已批准的产品范围。

## 2. 受测版本与范围

| 项目 | 记录 |
| --- | --- |
| 受测 HEAD | `4da19eb4fb7e407cb6d80963d91c48462088a08b` |
| 功能提交 | `f52dc97` 后端与测试；`ca98939` 桌面；`4da19eb` Web |
| 功能差异基线 | `28e355a..4da19eb`，36 个文件，4260 行新增、99 行删除 |
| 构建产物 | `target/docfinder-1.0.0.jar`，本轮 clean verify 生成 |
| JAR SHA-256 | `47fa777a731de99f46fdce365bb4e711907a03fc13cfb2af1e57b7432980be8f` |
| 操作系统/运行时 | Windows 10 amd64；Oracle Java 17（17+35-LTS-2724）；Maven 3.9.12；默认区域 ja_JP |
| 字节码检查 | 构建目标 1.8；抽查 SearchRequest 的 major version 为 52；不代表实际执行了 Java 8 运行时测试 |
| 对照规格 | 本 change 当前 0.4 规格 DS-01～DS-12，以及 design.md、acceptance.md |

范围包括：路径规范、来源校验、索引字段、目录/类型过滤、旧/混合索引、错误契约、桌面组件及异步测试、HTTP、真实浏览器正向与异常交互。性能、native 和跨平台结果不沿用旧报告冒充本轮结果。

测试开始时工作树干净。测试期间另有流程更新了 `delivery-status.yaml`、`evidence/A7.md`；本轮保留这些改动，没有覆盖。受测 `src` 和 `pom.xml` 无未提交差异，HEAD 未改变。旧 A7 的 133 项测试数及旧 SHA 不作为本轮版本证明。

## 3. 安全隔离与证据

- Maven 保持 Surefire fork，工作目录为 `target/test-classes`，home 为 `target/test-home`；没有使用 `forkCount=0` 绕开隔离。
- 迁移测试使用 TempDir，并检查工作目录、home 和 argLine；测试日志使用测试专用配置。
- 浏览器真实链路使用本轮临时 BrowserFixture：`HttpServer → SearchHandler → LuceneSearchService → LuceneIndexer`，HTML/JS 和 handler 来自本轮 JAR。
- fixture 只绑定 `127.0.0.1:0`；source、index、work、home 位于 `.omx/logs/dirscope-verification-20261023/browser-fixture/` 的隔离子目录，source 与 work 为兄弟。
- 显式注入临时来源 provider，不启动 App、watcher 或 ShareManager；未在真实业务索引上运行重建或迁移。fixture 不是文件系统权限沙箱，只操作生成的测试文件。
- 后端静态资源和查询是真实实现；B09/B10 的网络顺序采用 Playwright 拦截控制，不能称为真实网络抖动实测。
- 本轮没有建立真实业务数据全量前后哈希/mtime 快照，因此不宣称已独立证明所有真实数据字节均未变化；安全依据是隔离配置、守卫与显式 fixture 路径。

本地原始证据目录：`.omx/logs/dirscope-verification-20261023/`。包含 `clean-verify.log`、`clean-verify-reports/`、`isolation-probe.log`、`BrowserFixture.java` 及临时 fixture。原始日志目录由 Git 忽略；本报告包含长期阅读所需的版本、结果、复现和判定摘要。

## 4. 本轮构建与自动测试

### T01：干净构建、全部测试及打包

```bat
set MAVEN_OPTS=-Xmx256m&& mvn -B -DargLine=-Xmx256m clean verify
```

结果：**退出码 0，BUILD SUCCESS，耗时 04:26；140 项，0 failures、0 errors、2 skipped，即 138 项实际通过。**

堆上限是测试进程参数，不是产品代码修改。该命令保留 fork、工作目录和 home 隔离。此前检查会话中默认堆曾因系统内存不足无法启动；本轮明确使用有限堆，不据此声称默认资源配置也已成功。

主要测试分布：

| 测试类/范围 | 本轮数量 | 结果及证明边界 |
| --- | ---: | --- |
| PathScopeIndexFieldTest | 4 | 通过：实际索引字段相关断言 |
| SourceManagerSearchRootsTest / DirectorySourceValidationTest | 6 / 3 | 通过：来源读取、坏行与覆盖校验 |
| DirectoryScopeCompatibilityTest | 11 | 通过：兼容性/刷新等已有用例；不能替代所有并发时序的独立审查 |
| DirectoryScopedSearchTest / LuceneSearchServiceTest | 10 / 4 | 通过：目录范围及原有搜索特征 |
| DirectoryScopePanelTest | 10 | 通过：组件状态；不等于原生选择器验收 |
| DirectoryScopeDesktopHarnessTest / DirectoryScopeQuickActionsTest | 6 / 5 | 本轮通过且未跳过；使用受控服务，不是完整实机建库验收 |
| DirectoryScopeEndToEndTest | 8 | 通过：真实 indexer/service/HTTP 链路 |
| SearchHandlerTest / WebAssetsOfflineTest | 11 / 3 | 通过：HTTP 合约及静态资源断言；不证明浏览器状态正确 |
| ShareManagerTest | 15 | 通过：既有分享单测；没有进行真实浏览器分享流程 |
| PathScopeTest | 7 | 6 通过、1 POSIX 平台用例跳过 |
| TestEnvironmentIsolationTest | 3 | 2 通过、1 外部 argLine 探针条件跳过，随后由 T02 补跑 |
| 其余既有请求、错误消息、设置、时间/扩展名、路径和迁移测试 | 34 | 通过 |

### T02：保留外部 JVM 选项且隔离 home 的专项探针

```powershell
$env:MAVEN_OPTS = '-Xmx256m'
mvn -B '-Dtest=TestEnvironmentIsolationTest,LegacyMigrationTest' `
  '-DargLine=-Xmx256m -Ddocfinder.argline.probe=present -Duser.home=ignored-home' test
```

结果：**退出码 0，8 项通过，0 失败、0 错误、0 跳过。** 该 8 项与 T01 有重叠，不作为 8 个新增独立功能用例计算。

### T03：构建/规格辅助检查

- 本轮 OpenSpec 严格校验通过：`openspec validate add-directory-scoped-search --strict`。该结果只证明规范结构有效，不代表验收通过。
- 功能提交范围 `git diff --check 28e355a..HEAD` 未输出空白问题；更早的 AGENTS.md EOF 问题不归因到这三个功能提交。
- 本轮完成了打包和 Java 编译检查，没有配置或声称执行额外 lint/静态分析插件，也没有新增依赖。

## 5. 真实浏览器与 HTTP 专项

fixture 数据为 ProjectA、ProjectA/sub/deep、ProjectAB、ProjectB，以及 `资料 空格&#+[草稿]`；共 5 个含 alpha 的小型 UTF-8 文本，其中 A 根与嵌套文本包含“合同”。

正常区域服务使用临时端口 10278；区域复测使用端口 11163。端口为当次运行值，不是产品配置。测试页面为真实 `DocFinder Web`，不是重新实现的演示页面。

| ID | 场景/实际链路 | 预期 | 实际 | 结果 |
| --- | --- | --- | --- | --- |
| B01 | 真实页面、CONTENT、alpha、无目录 | 5 项 | HTTP 200，5 行 | PASS |
| B02 | ProjectA 递归限定 | A 根与深层两项，不混入 ProjectAB/B | HTTP 200，仅 alpha-A.txt、alpha-nested.txt | PASS |
| B03 | 中文、空格、&、#、+、方括号路径 | URL 完整还原、1 项 | decoded directory 与输入一致，alpha-special.txt | PASS |
| B04 | 目录 Clear | 保留词，恢复全局 | 目录为空，5 项 | PASS |
| B05 | FOLDER 模式查询选中根 ProjectA | 按 0.4 允许根自身，不混入 ProjectAB | 仅返回 ProjectA | PASS |
| B06 | ProjectA 中正文搜索“合同” | 2 项 | HTTP 200，根文件与嵌套文件 | PASS |
| B07 | 先成功 2 行，再请求未配置目录 | 422，并清空旧结果 | 422 DIRECTORY_NOT_INDEXED，但仍显示旧 2 行 | **FAIL** |
| B08 | 提交 report AND ( | 400，失败不入历史 | 400 INVALID_QUERY，但 localStorage 历史包含该词 | **FAIL** |
| B09 | 拦截 A 响应，清空词并 Enter，再释放 A | 0 行，旧响应失效 | 输入为空，仍显示 late-A.txt 一行 | **FAIL** |
| B10a | 非空请求 A/B，先完成 B 再释放 A 成功 | 保持 B | B-new.txt 保留，无 A-old.txt | PASS |
| B10b | 非空请求 A/B，先完成 B 再释放 A 错误 | 保持 B，不出现旧错误 | B-new.txt 保留，无 Error 状态 | PASS |
| B11a | 德语区域，真实全局 HTTP 查询 | 合法 JSON | HTTP 200，score 使用小数逗号，JSON.parse 失败 | **FAIL** |
| B11b | 德语区域，真实限定 HTTP 查询 | 合法 JSON | HTTP 200，score 使用小数逗号，JSON.parse 失败 | **FAIL** |

专项共 **13 个场景，8 通过、5 失败，对应 4 类动态复现问题**；不能与 JUnit 数量混成一个“通过率”。

关键原始观察：

```text
B07: before=2, HTTP=422, code=DIRECTORY_NOT_INDEXED, actualVisibleRows=2
B08: HTTP=400, code=INVALID_QUERY,
     docfinder-search-history contains "report AND ("
B09: queryValue="", actualVisibleRows=1, lateVisible=true
B11a: "score":0,1564,"ctime":...
      JSON.parse: Expected double-quoted property name in JSON at position 198
B11b: "score":0,1357,"ctime":...
      JSON.parse: Expected double-quoted property name in JSON at position 185
```

B11 通过相同 fixture、同一 JAR，只增加 `-Duser.language=de -Duser.country=DE` 运行，未更改查询实现。小数逗号错误同时影响全局与目录查询；它是当前代码仍存在的序列化问题，不宣称一定由这次目录功能新增引入。

## 6. 独立审查及缺陷清单

独立只读审查覆盖实际 MainWindow/App 服务发布、目录组件、快捷动作、SearchHandler、Web 脚本和相应测试，对照当前规格；未执行修复。以下 4 项有本轮动态复现，另 3 项为代码路径/时序审查确认，仍应补故障注入或确定性回归，不能伪称已经实机复现。

| ID | 级别 | 问题与位置 | 证据状态 | 对应规格 |
| --- | --- | --- | --- | --- |
| F01 | P1 | SearchHandler.java:140，默认区域格式化 score 导致非法 JSON | **动态复现 B11a/B11b** | DS-08 |
| F02 | P1 | MainWindow.java:1679，Full Rebuild 异常分支仍尝试 reopen/publish 服务；有 commit 时可能重新开放空/部分索引，与失败保持不可用契约不同 | **代码审查确认；真实失败注入未执行** | DS-12 |
| F03 | P2 | index.html:1000、1306，空词提前返回/目录 Clear 空词不提交，未使旧 token 失效 | **动态复现 B09** | DS-08、DS-10 |
| F04 | P2 | index.html:1030、1037，当前业务/网络错误只改状态，未清空旧结果及快捷动作 | **动态复现业务错误 B07；网络错误分支仅代码核对** | DS-08 |
| F05 | P2 | MainWindow.java:1903、1927，空词分支早于 sequence 增加；已完成但 done 尚在 EDT 排队的任务无法靠 cancel 失效 | **代码审查确认；该 EDT 时序尚未动态注入** | DS-07 |
| F06 | P2 | DirectoryScopePanel.java:65、165，用户仅编辑文本时没有监听更新来源/Custom/All 显示 | **代码审查确认；原生编辑场景未验收** | DS-07 |
| F07 | P2 | index.html:1004，发送前即 addToHistory，失败查询仍被保存 | **动态复现 B08** | DS-09 |

路径前缀分别为：`src/main/java/org/abitware/docfinder/web/`、`src/main/java/org/abitware/docfinder/ui/`、`src/main/java/org/abitware/docfinder/ui/components/`，Web 脚本为 `src/main/resources/web/index.html`。行号对应受测 HEAD，不承诺后续修复后仍相同。

F02 特别需要区分：已批准方案允许 **Index All 部分失败但有 commit 时恢复可用部分**；这不能直接套用到 **Full Rebuild 失败仍应不可用**。现有恢复 helper 测试或手工切换服务引用，不证明实际完整重建异常分支符合规格。

F05 的窗口是“后台已完成、EDT done 尚未运行”。普通的“后台还在阻塞，然后 cancel”测试不足以覆盖这个窗口。

审查未发现的其他问题不等于不存在；本轮没有完成整个后端缓存/索引生命周期的逐路径独立审计。不将现有自动测试结果升级为全代码无缺陷保证。

## 7. 规格覆盖判断

| 规格 | 本轮证据/判断 |
| --- | --- |
| DS-01 | 单测 + B02/B05 验证了主要整树/根自身边界 |
| DS-02 | Windows 自动测试与 B03 通过；POSIX 专属用例跳过，真实 UNC 服务未测试 |
| DS-03 | 已有查询组合/数量/评分自动回归通过；浏览器 CONTENT 正向通过 |
| DS-04 | 来源自动测试、真实 422 响应符合契约；不能据此认定错误后的 UI 符合契约 |
| DS-05 | 11 项兼容测试通过；真实大规模来源和全部并发时序不在本轮证明范围 |
| DS-06 | 字段及真实 indexer→HTTP 自动链路通过 |
| DS-07 | 组件/桌面自动用例通过，但 F05/F06 未闭环，原生选择器等 M 项未通过验收 |
| DS-08 | 正向真实链路通过，但 F01/F03/F04 为已复现失败，不满足完整契约 |
| DS-09 | 错误码自动测试通过，但 Web F07 违反失败历史契约 |
| DS-10 | HTTP 空词自动检查通过；Web 空词提交失效行为由 B09 证明仍有缺陷 |
| DS-11 | 快捷动作自动用例通过；真实剪贴板拒绝/手动回退未执行 |
| DS-12 | 部分自动恢复/可用性用例通过；实际 Full Rebuild 失败路径存在 F02，不能验收 |

## 8. 未验证项与流程风险

- M-01～M-06 **没有全部完成**。本轮补了 M-04 正向/部分异常及 M-05 的受控时序证据，但其中存在失败；不将这些 M 项整体标绿。
- 原生目录选择确认/取消、完整可见桌面流程、真实首次建库与实际 Full Rebuild 故障注入仍待验收。
- 桌面现有 `DirectoryScopeDesktopHarnessTest` 有效，但当前提交没有 acceptance 中示例所引用的 `DirectoryScopeDesktopHarness.main` 可持续手动入口；不能直接声称已交付该复现命令。
- P-00/P-01 本轮没有重跑；旧 A7 的性能数字不能替代本轮测量，也不作为发布性能结论。
- native profile、真实 Java 8 运行时、POSIX 主机、真实 UNC/大规模网络监控未验证。
- OpenSpec 规划结构通过，不代表任务已完成。tasks 9.1/9.3 已勾选但仍写着实机/独立审查待办；必须按实际结果拆分或重新打开，不按勾选百分比验收。
- mutable delivery-status/A7 与旧冻结文案仍有不一致。该问题影响证据可信度，不是新发现的功能故障；本轮没有覆盖其他流程正在更新的台账。

## 9. 是否继续，以及继续做什么

**应继续，但下一阶段是修复与验证，不是增加功能。** 当前正向结果支持保留架构；动态失败和 P1 路径不支持发布。

建议顺序，每一步独立完成 RED → 最小修复 → GREEN → 证据 → 审查：

1. **F01 区域无关 JSON**：增加德语/小数逗号区域回归，检查完整响应可解析；格式化明确绑定 Locale.ROOT，测试后恢复区域设置。该项先做，因为会使整个 Web 搜索在部分机器不可用。
2. **F02 重建失败语义**：用临时索引注入实际 Full Rebuild 异常，确认失败期间/之后 503；修正完整重建分支，同时保留 Index All 部分提交恢复用例。
3. **F03/F05 请求失效**：两端所有提交先处理 token/sequence，再处理空词；补旧成功、旧错误、done 排队、Clear 的确定性用例，空词不发新搜索。
4. **F04/F07 当前失败状态**：补成功→422/400/网络异常的 DOM 回归；错误清旧结果/选择/预览，只有当前成功请求才进入历史。
5. **F06 目录编辑显示**：编辑只同步 Custom/All/来源显示，不自动查询、不读磁盘、不改变已提交范围；新增实际编辑事件回归。
6. **重验并验收**：重跑 clean verify、隔离探针及本报告全部失败场景；完成 M-01～M-06，核对性能要求和风险；同步状态与当前代码 SHA，再提交用户验收。

上述缺陷关闭、未验证项有明确结论且用户接受前，不归档、不合并推送，不启动多目录/排除目录等新范围。需要改变已批准的错误或重建语义时，先走 OpenSpec 规范更新确认，而不是通过改验收标准把失败改成成功。

## 10. 本轮交付边界

交付：本报告、原始构建/隔离证据、浏览器场景实际观察和缺陷复现步骤。当前源码保持受测提交；临时 fixture 的停止端点仅存在于本地测试辅助程序，不是产品 API。

本报告是“测试发现问题后的继续建议”，不是修复完成声明，也不是最终用户验收记录。
