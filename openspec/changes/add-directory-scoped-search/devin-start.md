# Devin Local — Start A0 only

用户已批准DocFinder 0.4的A阶段实施，含A0测试配置。执行模式已明确为Devin Local，直接使用本机E:\idea_projects\doc-finder。你是实施者；Codex是产品/项目/质量协调者，Claude已对0.4方案针对性PASS。当前只执行A0（tasks 1.1～1.4），完成后返回证据等待Codex复核，不自行跨入A1。

## Read and verify

1. 在本机E:\idea_projects\doc-finder直接工作；所有资料已位于E:\idea_projects\doc-finder\openspec\changes\add-directory-scoped-search。无需上传/解压ZIP、创建Cloud任务或从远端克隆。仓库远端仅供背景参考，源码基线9a1c26ad1f3261c16548f55693b3380d5db67790，不能据此reset当前工作树。
2. 先读approval.md、当前delivery-status.yaml、devin-start.md；再读README、spec、design D9、tasks A0、acceptance及review。原冻结文档的“待批准”是历史快照，用户批准已在approval.md生效；不要因此再次索要同一授权。
3. 校验review-baseline.sha256文件自身SHA-256为2e332e5545db0cf8514856ae19c47701a33b80d7536c43333764498f3efe17de，并校验列出的16个文件。handoff-baseline.sha256校验当前本地交接材料；以前ZIP是历史传输快照，不是Local启动前提。源码若有变化，报告实际差异与影响，不能盲目reset/丢弃他人修改。
4. 遵守仓库AGENTS.md和已批准范围；OpenSpec/Superpowers可用就按executing-plans/TDD执行，不存在就按文档等价步骤，不安装新依赖或工具、不另造漂移规格。

## A0 deliverable

- 固定现有Surefire隔离：forkCount=1、reuseForks=true、workingDirectory为target/test-classes；properties预置空argLine，配置按design D9组合保留外部参数，隔离user.home指向target/test-home并放在最后。Java8、依赖及插件版本不变。
- 新建test-only logback-test.xml，避免测试类加载写真实业务日志。
- LegacyMigrationTest改用@TempDir隔离home/work、保存恢复属性、禁止并行；覆盖源缺失、正确复制、不覆盖已有数据及sentinel。不改变生产LegacyMigration/AppPaths语义。
- 新增TestEnvironmentIsolationTest/受控probe，验证fork实际user.dir/home都在隔离target，外部-DargLine探针仍有效；禁止只看effective-pom。
- 新增真实临时Lucene特征测试LuceneSearchServiceTest，锁住当前合法搜索模式/评分、扩展名/时间/limit和空词行为。当前尚无provider接口，不假造未读provider的断言；不固化已知Top-N后类型过滤缺陷为正确行为。
- 测试数据source/index为系统临时兄弟目录，不能在user.dir子树。正式代码修改仍在当前本地仓库；首次证明测试隔离配置时允许用本机临时安全源码副本（只是测试安全措施，不是Cloud模式），验证后才在普通仓库cwd运行mvn test，核对真实.docfinder清单/哈希/mtime未变。不访问/复制真实业务数据。
- nofork不受Surefire workingDirectory/argLine保证，不能以其成功替代默认fork成功。

## Verify and report

先核实隔离，再跑定向：
mvn -B "-Dtest=TestEnvironmentIsolationTest,LegacyMigrationTest,AppPathsTest,LuceneSearchServiceTest" test
另用-DargLine中的-Xmx256m与-Ddocfinder.argline.probe=present核验实际fork；按当前shell正确引用参数。
最后mvn -B test，记录命令、退出码、测试数、失败/错误/skip、报告位置、实际JVM参数/目录。基线特征测试可首次即绿；新隔离/迁移行为先目标失败再最小实现，环境失败不算RED。

在E:\idea_projects\doc-finder\openspec\changes\add-directory-scoped-search\evidence\A0.md记录：
- 文档指纹、实际HEAD/工作树、改动文件和diff；
- 每项测试的RED/GREEN或既有特征测试说明；
- fork参数和目录证据、真实.docfinder前后对比；
- 未覆盖平台/环境、风险、未完成项；
- A0 1.1～1.4逐项判定及下一步建议。

只在验证成立后勾对应任务；失败先按systematic-debugging定位并重跑，不能降低标准或虚报成功。成功零匹配/异常吞空等后续修复不在A0提前做。
输出证据包后停在A0→A1交接点，向Codex报告，不自行开始A1，不声称产品已完成。

## Limits

本批不实现目录字段/过滤/UI/Web，不触碰真实索引/来源/分享，不重建、不强制解锁、不改依赖、不提交/创建PR/推送/合并/归档。保留用户原有AGENTS.md修改。若执行环境无法满足某项测试或必须扩大权限，报告具体障碍、已尝试方法和影响，不把环境失败当作目标RED。


## Local reference paths

- 工作目录：E:\idea_projects\doc-finder；仓库约定：同目录AGENTS.md。
- 批准：openspec/changes/add-directory-scoped-search/approval.md。
- 当前状态：同change的delivery-status.yaml。
- 需求：specs/directory-scoped-search/spec.md；设计design.md（A0重点D9）；任务tasks.md第1组；验收acceptance.md。
- 评审：review.md；协作handoff.md；五项决策decisions.md。
- 首批证据：同change的evidence/A0.md；A0完成只回报本地证据/差异，不要求Cloud会话URL。
