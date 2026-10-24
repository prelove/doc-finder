# Product Decisions after Claude Review

版本0.4；日期2026-10-23（Asia/Tokyo）。

## Evidence

来源：本聊天中Codex提交的五项产品取舍问题及用户回复。用户原文：
> 按这组推荐方案修订，仍待 Claude 复核和最终实施批准

这是方案修订确认，不是实施/发布批准。此记录随新指纹交Claude复核；实施关仍false。以下逐条对应当时问题，不扩展用户授权。

## Confirmed choices

| ID | 对应评审 | 用户确认内容 | 规范落点 |
| --- | --- | --- | --- |
| REV-DEC-01 | F-002 | 先File → Index All Sources非破坏补字段；陈旧记录仍阻断再备份后手动Full Rebuild | proposal迁移、D3/D4/Migration、DS-05、A4/M-06 |
| REV-DEC-02 | F-004 | 非法查询有/无目录统一INVALID_QUERY/400，不再吞为零结果 | D3、DS-09、A3/A6 |
| REV-DEC-03 | F-005 | 坏行记行号原因并跳过，有效来源继续；文件不可读或全部数据行无效报错 | D2、DS-04、A1 |
| REV-DEC-04 | F-007 | A阶段Web手输服务器路径/结果缩小，无来源列表；知情接受422泄露覆盖关系 | D6/Risks、DS-08、README |
| REV-DEC-05 | DEC-03/F-001等 | FOLDER可命中选定目录自身，默认包括所有子目录 | DS-01、D4、A3 |

REV-DEC-05不等于目录正文聚合；空词不浏览、匹配规则仍适用。

## Technical revisions under PM coordination

F-001采用准确的通用INDEX_UNAVAILABLE，不虚构“精确识别初始化”状态；补首次Index All后台重开、桌面/Web统一发布和重建期503。F-003采用统计充分条件+精确回退+reader key缓存，先行微基准。F-006固定路径唯一编辑值和来源刷新。F-008固定测试隔离、指定Codex主机验收。以上是为实现已讨论功能的方案修订，仍需Claude复核，不代表代码已修。

## Migration notice

非破坏Index All不会先清库，但会重新解析有效来源内文件，不能承诺秒级迁移；删除文件和移除来源的旧live docs不自动消失，可能仍全库409。完整重建非原子，会先清空再解析、期间不可搜，崩溃可留空/部分索引，旧锁恢复可能删索引目录；查询不自动触发任何兜底。实际维护操作由用户在备份和核对来源后手动执行。


## 0.3 PASS后的技术细化

用户转交Claude PASS（计划充分性）且无新增决策。Codex按PM权限确定：八Code固定映射；worker null抛错/捕获单次服务引用；三个创建点归并；错误不入历史；保留外部argLine但隔离home最后；坏行桌面一次警告；来源下拉规范比较；Index All部分commit也尝试恢复，保留失败状态。它们实现已有契约，不改迁移路线、Web边界、Java/依赖版本或根自身语义，不需重新选择。当前仍等待用户对0.4指纹的最终实施批准。
