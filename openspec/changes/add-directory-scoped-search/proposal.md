# Proposal

## Why

用户需要在已配置索引来源内选择目录，递归检索文件名、正文或目录名称，并快速打开、定位结果。现有 Lucene/Tika 基础可复用，但请求、桌面和 Web 均没有独立目录条件。

## What Changes

- 单目录默认递归，目录名称模式包含所选目录自身与后代；不增加递归开关。
- 目录必须等于已配置来源或位于其内部；范围外提示先添加来源。查询不扫描、添加来源或自动建立索引。
- 新增规范化范围字段，保留原路径标识；目录与类型过滤先于 Top-N，修复目录挤占文件名额。
- 桌面增加来源选择、路径输入、Browse/Clear、范围说明和从结果继续缩小范围；Web 提供服务器路径输入、清除、范围说明和结果快捷操作。
- 区分无匹配、来源配置错误、初始化、旧索引和查询失败；旧索引先提示File → Index All Sources非破坏补字段；陈旧记录仍阻断时，用户备份后手动Full Rebuild兜底，不自动破坏索引。
- 来源已配置、字段兼容和磁盘/正文覆盖完整性分别表述，不把零命中当作索引完整的证明。

## Capabilities

### New Capabilities

- `directory-scoped-search`：整树目录范围、来源覆盖、路径规则、旧索引兼容、桌面/Web 和错误契约。

### Modified Capabilities

无。主规格库为空；延续已有未实施 change，不另建重复 capability。

## Impact

- 后台：SearchRequest、LuceneSearchService、LuceneIndexer、保留的 DocumentBuilder、SourceManager、App、固定错误消息映射与窄范围辅助类；修复首次Index All后未恢复服务及重建Web可用性。
- 桌面：实际 MainWindow 链路和轻量目录控件，不迁移整套历史拆分组件。
- Web：SearchHandler、index.html；新增可选 directory，成功仍为 count/results。
- 兼容：保留旧构造、关键词和评分语义；有意纠正类型过滤过晚导致的少返回；有/无目录非法语法统一400，不再吞为空结果；null服务503文案改为准确的不可用提示。
- 迁移：旧/混合索引非空目录查询要求迁移字段；无目录查询不检查新字段。
- 依赖：A阶段保持Java8与pom现有依赖版本，允许必要测试配置修订，不新增或升级依赖；独立升级方案见 roadmap.md。
- 测试基础：A0固定Surefire/迁移测试隔离，允许修改既有测试配置但不新增/升级依赖；常规不用每次复制工作树。
- 升级提示：非破坏Index All仍需重新解析；若兜底Full Rebuild，将先清空再解析，非原子、期间全库不可搜，中途崩溃可留空/部分索引，旧锁恢复还可能删除索引目录，必须维护时段备份。
- 验证：临时 Lucene、HTTP、GUI/Web 交互、端到端、性能观察与使用文档。

## Non-goals

不实现任意未配置目录扫描、多目录、仅当前层、排除目录、空词浏览、目录正文聚合、名称子串语义变更、即时输入搜索、分页/总命中数、MFT/USN、Redis、原子重建或全部 watcher/poller 修复。不把查询范围作为下载/预览/分享权限，不新增服务器目录树接口。完整产品路线见 roadmap.md。

## Review boundary

文档包 0.4 / 2026-10-23。本轮授权为编写可交接方案，实施未批准。Claude已对0.3按计划充分性PASS，F-001～013关闭，无残留P0/P1。0.4补新增9项非阻断实施细节及测试，不改变用户五项决策；最终实施批准仍未授予。OpenSpec artifacts done 仅表示文件齐备，不能代替 delivery-status.yaml 引用的用户批准记录。
