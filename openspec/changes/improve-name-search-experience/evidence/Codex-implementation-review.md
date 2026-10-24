# B 独立复验（2026-10-24）

复验提交：`81978a23f817dbfaaa517e942a8ac39a2663a03f`。结论：核心功能通过，交付前修正下面两处回归；不增加方案评审轮次。当前工作树中的修复尚不计为已通过。

## 已完成的独立验证

- 隔离 Git 快照 `mvn -B clean verify`：185 项总数，183 通过、0 失败/错误、2 个 POSIX 平台跳过，BUILD SUCCESS。使用 `-DargLine=-Xmx768m -Ddocfinder.argline.probe=present`。
- 同一快照以 `-Djvm=D:\java\java8\bin\java.exe` 再跑完整测试：Java 8 1.8.0_40，同样 185/0/0/2。不是仅启动 JAR 观察。
- Java 8 真实进程，实际 `LuceneIndexer → LuceneSearchService → WebServer → HTTP`：22 场景通过。包含中段/Unicode/多词、名称优先与正文补齐、目录整树及目录自身、过滤先于上限、limit=0/1/5/6/55/1000、截断与错误响应。
- 浏览器真实页面：确认 `report` 的名称与正文分组顺序、ProjectA 包含子目录且排除 ProjectB、`zz` 的 50 条结果与可见 `Showing first 50 results` 提示。
- 10 万文件 + 1001 目录，768MB heap、预热10/取样100，临时补测生产 `name_raw` 不存储的布局。p95：NAME/report 66.49ms、单字符 f 10.90ms、符号 . 38.64ms、强制执行正文补齐的 ALL/common 83.77ms、限定目录 NAME/f 32.79ms、限定目录 ALL/common 57.01ms。本机均低于100ms优化目标，无需 n-gram 或迁移；不承诺其他硬件 SLA。
- `openspec validate improve-name-search-experience --strict` 通过；`git diff --check` 通过。真实 config/sources/shares/history 四文件哈希未变。

## Devin 当前修复任务

1. **BQA-01 / P2，EXACT 路由回归**：真实小索引对比旧 JAR，`foo AND bar` 旧版只匹配 `foo AND bar note.txt`，新版还匹配 `foo bar draft.txt`；`report (` 旧版匹配 `report draft.txt`，新版 INVALID_QUERY。NAME/ALL 都复现。新分类只用于本次普通 FUZZY 名称路径，冻结模式复用原分类规则，并补回归。
2. **BQA-02 / P2，新文本首次 Enter 重复提交**：真实 EDT/输入框 `postActionEvent()`，计数 executor 记录首次提交2次、再次1次。按事件语义修正，补真实输入控件及提交计数测试；不以同文本100ms抑制替代，否则会吞合法的快速再次 Enter。
3. **说明收尾**：tasks.md 当前为非法 UTF-8，需要恢复文字并保留真实勾选；README.md:106/310、README_CN.md:39 尚称 B 未实施/未授权，须同步实际功能及内置帮助。历史 RED 过程不补造；缺少旧过程记录不阻塞交付。

修复及必要交互测试提交后，Codex 按新 SHA 复验，再交用户进行 B 的简短试用验收。A 的既有验收不重开，C1 保持暂停。

## 本机原始证据

`C:\Users\Administrator\AppData\Local\Temp\docfinder-b-qa-20261024-3zl64vxw\`：`clean-verify.log`、`baseline-reports/`、`java8-test.log`、`java8-reports/`、`independent-benchmark.log`、`controls-after.json`；`contracts/` 内含两个旧新对照/桌面探针源码和结果；`web/` 内含真实 HTTP 22 场景结果 JSON、服务日志及 `truncation.png`。测试服务器和临时浏览器页已关闭。

复验快照 JAR SHA256：`99431f23cf973fa3dd62d4618c360180a6bad8ef9a332a1f3dddce3b9c6ad866`。这是被审查的版本，不是修复后交付候选。
