# 当前阶段：B用户短试用（2026-10-25）

Devin实施及最后收尾已完成，Codex已按 `2c47ab2490f87e4c7124c7979bf2fec645049943` 独立定向复验通过。进度23/25，仅待用户接受8.1及交付归档8.2；不再安排新增开发或方案评审。

交付JAR：`E:\idea_projects\doc-finder\target\docfinder-1.0.0.jar`。SHA256：`C5740ED4AF7DF04FC77561461EAAEDF645E8C4702C56774227B48CC07421CB82`。远端origin/codex/name-search已核实包含该产品提交；后续进度文档提交不改变被验收代码/JAR。

## 用户短试用

1. 在已索引来源内选择目录（含子目录），输入名称中段及多词，确认能找到预期文件；快速键入和Enter后结果稳定。
2. 点击同一列体验升序→降序→默认顺序，再点Restore default sort，确认回到原始搜索顺序，所选文件与预览保持。
3. 排序后清空输入、输入无匹配词，再重新搜索，确认旧排序箭头和按钮状态不会残留。

用户确认当前版本通过后，Codex将实际版本与接受记录写入8.1，再办理既定交付归档8.2。Devin本阶段仅处理试用反馈；C1继续暂停。native/POSIX/真实UNC与100万观察为已声明边界。

证据：OpenSpec的evidence/Codex-implementation-review.md，最新段落记录两套32项定向测试、实际JAR桌面探针及浏览器空态/复位验证。
