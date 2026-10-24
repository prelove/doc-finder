# DocFinder 目录检索交接包

版本0.4；2026-10-23（Asia/Tokyo）。Claude对0.3按计划充分性PASS，F-001～013关闭、无残留P0/P1。0.4实施已获用户批准并完成：A0–A7及Codex报告缺陷F01–F07修复全部落地（HEAD 36139f5，147项自动测试通过），用户冒烟手测报告无问题；当前仅剩A8用户最终验收与授权交付。

## 目标与产品选择

仅在配置来源内选目录，默认覆盖自身与全部子目录；名称/正文找文件、目录名找目录。范围外提示先添加来源；空词不浏览；FOLDER含根自身；Web手输服务器路径/从结果缩小、不下发来源列表，422覆盖探测风险已确认。
迁移Index All优先、陈旧记录兜底手动Full Rebuild；语法错误两种模式统一400；来源坏行容错。这五项已确认，不重新询问，见decisions.md。

Java8与pom依赖版本不变；A0允许修改pom现有Surefire/空argLine测试属性及测试日志/迁移隔离配置，绝不表示pom文件不能改，也不授权依赖升级。

## 升级提醒

Index All非破坏upsert仍需重解析；已删除/移除来源及离线/权限/部分遍历失败均可留下旧live docs、继续全库409。先检查可达性，备份后再由用户维护时段手动File → Rebuild Index (Full)兜底。
Full Rebuild先清空再解析、非原子、期间不可搜，失败可留空/部分索引，旧锁恢复可能删目录；不自动触发。部分成功的Index All可恢复已有commit，但必须保留失败提示，不能称完整覆盖。

## 单一事实来源

| 文件 | 用途 |
| --- | --- |
| [proposal.md](proposal.md) | A范围/兼容/迁移代价 |
| [specs/directory-scoped-search/spec.md](specs/directory-scoped-search/spec.md) | DS-01～12唯一行为契约 |
| [design.md](design.md) | 统一接口、错误、路径、服务生命周期/隔离 |
| [tasks.md](tasks.md) | 10包31步唯一实施清单；A0–A7与第11组已完成，A8待用户 |
| [acceptance.md](acceptance.md) | 自动测试、Codex主机验收与P-00/P-01 |
| [review.md](review.md) | 原13项关闭、新9项逐项处理及是否再审说明 |
| [decisions.md](decisions.md) | 用户已确认的五项选择 |
| [handoff.md](handoff.md) | Devin执行提示、可选Claude补丁核对 |
| [delivery-status.yaml](delivery-status.yaml) | 关口状态；实施授权已授予，交付授权未授予 |
| [analysis.md](analysis.md) / [roadmap.md](roadmap.md) | 源码依据、风险、后续B/C/D |
| [planning-validation.md](planning-validation.md) | 文档检查，非功能通过 |
| [reviews/claude-0.2.txt](reviews/claude-0.2.txt) | 0.2外部原文 |
| [reviews/claude-0.3.txt](reviews/claude-0.3.txt) | 用户本聊天0.3 PASS的转存摘要，完整原文以用户消息为准 |
| review-baseline.sha256 | 冻结当前输入，含planning-validation；review/status为可变进度不入指纹 |

历史baseline-0.2/0.3只对各自旧ZIP核验，不能拿旧manifest验证0.4。ZIP另有外部SHA-256；当前材料仍本地未提交，单给仓库URL不会含本包。

## 0.4补充范围

桌面八Code固定映射、null worker抛不可用、三个创建点归并、错误不入历史、外部argLine保留、离线迁移残留、坏行桌面提示、下拉规范比较、部分commit恢复均已明确任务/测试。未将后续B/C/D并入A，未改业务代码或实际pom。

## 下一步

当前下一步：完成全功能独立实现审查与最终clean verify后，由Codex补齐M-01～M-06正式记录形成验收候选；用户接受（A8）并指定交付目标后才可归档/PR/合并。POSIX主机、native构建、真实UNC/大规模网络来源仍未验证，单列不合并。
没有自动联系Claude/Devin或发布。OpenSpec结构通过不等于功能、性能或用户验收通过。

