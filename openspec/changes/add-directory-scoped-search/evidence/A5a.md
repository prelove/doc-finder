# A5a Evidence — 桌面面板与建库服务恢复

- **Tasks**: 6.1–6.3 · **DS**: DS-07/09/10/12 · **HEAD**: 9a1c26ad1f3261c16548f55693b3380d5db67790

## 改动文件

**新增**
- `search/SearchErrorMessages.java` — 8 Code 固定英文文案，null/未知→SEARCH_FAILED
- `ui/components/DirectoryScopePanel.java` — D5 接口全量（onScopeChanged/getDirectoryPath/setDirectoryPath/setSourceDirectories/clearDirectory/setScopeControlsEnabled/setSourceWarning）+ 测试用 getter
- 测试：`SearchErrorMessagesTest`(3)、`DirectoryScopePanelTest`(10)、`ui/DirectoryScopeDesktopHarnessTest`(6)

**修改**
- `ui/MainWindow.java`：
  - `searchService` → `volatile`；`setSearchService` 同实例不关闭
  - SearchWorker：六参构造（directoryPath 快照）、`doInBackground` 局部捕获服务、`null`→抛 `SearchException(INDEX_UNAVAILABLE)`（不再返回空列表）
  - `done()` ExecutionException：SearchException→`SearchErrorMessages.forCode`，未知→SEARCH_FAILED；当前错误清结果/预览；`getMessage()` 不进状态栏
  - `reopenSearchServiceAsync(Path, IndexSettings)` 公开 + 包内三参（completionStatus）：`openServiceSequence` 防迟到候选（seq 不符→候选 close，不发布）；打开失败→INDEX_UNAVAILABLE 文案
  - `protected openSearchService(Path, IndexSettings)` 工厂钩子——生产唯一 `new LuceneSearchService`
  - 三处创建点替换：App 启动、chooseAndIndexFolder done、rebuildAllSources done → 均走 reopen
  - `indexAllSources` done：成功/部分失败都 reopen；部分失败保留固定失败文案（Ready 不覆盖）；`rebuildAllSources` 开始时 openServiceSequence++/searchSequence++/取消 worker/setSearchService(null)；锁恢复路径同样失效候选
  - `refreshSourceDirectoriesAsync`（generation 防迟到）：启动、三类索引完成、reopen 发布后触发；坏行/读失败 → `setSourceWarning(true)`，下次干净刷新清除
  - `setIndexingUiEnabled` 禁用面板控件；`dispose` 使打开/来源加载候选失效
  - 修复预存缺陷：历史加载前 `suppressQueryActions=true`——此前非空历史 `addItem` 会在 filterBar 构建前触发 doSearch → `fromField` NPE（harness 实测复现）
- `App.java`：启动索引打开改为 `win.reopenSearchServiceAsync`，Web 由 setSearchService 内部同步（不再独立发布候选）
- `UsageGuide.md`：Search in 面板用法、语法错误提示+不入历史、保留字符引号转义、path_scope 迁移提示

## RED → GREEN

- 面板/文案先行实现后即绿（新行为，无旧实现可 RED）
- Harness 首轮 3 处问题定位并修复：①历史加载 NPE（真实预存缺陷，已修生产代码）②FakeService 被 cancel 中断提前释放导致 worker2 提前执行（测试改为吞中断直到 latch）③阻塞中的首个 reopen 在放行后读到 nextService（测试改为按调用次序固定返回）
- 终态：`mvn -B "-Dtest=DirectoryScopeDesktopHarnessTest" test` → **6/6**；`DirectoryScopePanelTest+SearchErrorMessagesTest` → **13/13**
- 全量 `mvn -B test` → exit 0，**115 tests, 0 fail/error, 2 skipped**（POSIX-only、argLine 探针）

## 生产 `new LuceneSearchService` grep 证据

```
Select-String 'LuceneSearchService\(' src/main → 唯一命中：
MainWindow.java:1855  openSearchService 工厂内
（其余 13 处均在 src/test，不计生产检查）
```

## Harness 覆盖（自动，需显示环境；headless 自动跳过）

- 8 个 Code 逐一映射固定文案 + 未知异常→SEARCH_FAILED；状态栏无原始消息/堆栈
- latch 阻塞服务 + 置 null：worker 显示 INDEX_UNAVAILABLE 而非 "No results"（F-015 时序）
- INVALID_QUERY 查询不进历史（combo 模型大小不变）
- 阻塞的首个 open 迟到→候选 close，新 open 胜出（F-016/D8）
- 部分失败 completionStatus 保留失败文案（Ready 不覆盖）
- 打开失败 → INDEX_UNAVAILABLE 文案

## 待 Codex 实机（不标通过）

- M-01/M-02/M-03：真实窗口中面板交互、Browse 原生选择器、右键菜单（A5b 范围）、索引中禁用表现
- M-06：真实首次 Index All→服务恢复、部分 commit 后恢复、Full Rebuild 期间 Web 503/恢复
- 复现命令：`mvn -B "-Dtest=DirectoryScopeDesktopHarnessTest" test`（需交互式显示会话）；手工冒烟：`java -jar target/docfinder-1.0.0.jar`（需先 package）

## 未覆盖 / 风险

- `setSourceWarning` 的"修复后清除"经自动测试；真实坏行文件提示属 M-02
- 面板 Browse 按钮未自动点击（JFileChooser 不可自动）
- A5b 右键快捷操作、A6 Web 未开始
