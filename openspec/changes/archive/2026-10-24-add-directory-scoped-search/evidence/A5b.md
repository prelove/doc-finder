# A5b Evidence — 桌面结果快捷操作（Search in this folder / containing folder）

## 范围

tasks.md 第 7 节（7.1–7.3，DS-11）。仅 `MainWindow.java` 与新增测试；未改预览/表格结构。

## 改动文件

- `src/main/java/org/abitware/docfinder/ui/MainWindow.java`
  - 右键菜单新增两项：`Search in this folder`（仅目录行，范围为目录本身）、`Search in containing folder`（仅文件行，范围为 `Path.getParent`）。
  - 复用 `getSelectedResult()` 已有的 view→model 行转换（`convertRowIndexToModel` + `currentResults`），排序下仍取到正确结果。
  - `parentOfSafe(...)` 包裹 `Paths.get(...).getParent()`：无父/根路径返回 `null`，弹起时禁用对应项。
  - 索引/搜索进行中（`isIndexing`）两项禁用。
  - `searchInDirectory(String)`：程序化 `setDirectoryPath`（不触发面板回调）后单次 `doSearch()`，保证恰好一次提交；重复执行幂等。
  - 两个菜单项提升为字段 `searchInThisFolderItem` / `searchInContainingItem` 供 harness 点击。
- `src/test/java/org/abitware/docfinder/ui/DirectoryScopeQuickActionsTest.java`（新）
- `UsageGuide.md`（右键菜单段补两行说明）
- `tasks.md`（7.1–7.3 勾选）
- 本文件

## RED / GREEN

- RED：7.1 测试在菜单项/字段不存在时编译失败（`searchInThisFolderItem` 等未声明），实现后转绿。
- GREEN：`mvn -B "-Dtest=DirectoryScopeQuickActionsTest" test` → **5 tests, 0 fail, 0 err, 0 skip**（本机有显示环境，未跳过）。
- 全量：`mvn -B test` → **120 tests, 0 fail, 0 err, 2 skipped**（2 个为既有预期跳过：POSIX-only 与 argLine 探针）。

## 测试用例覆盖（DirectoryScopeQuickActionsTest）

1. `shouldMapSortedViewRowToFolderAndCommitOnce`：按 Name 倒序排，选中视图中第 3 行（模型里的目录结果）→ 点击项，断言面板提交路径 = 目录路径、`SearchService.search` 恰好多调用 1 次、`request.directoryPath` 正确。
2. `shouldUseParentForFileRow`：文件行 → `containing folder` 提交其父目录。
3. `shouldHandleQuotedPathWithoutBreaking`：路径含单引号 `Sub'Dir` 正常解析提交。
4. `shouldDisableActionsWhileIndexing`：`isIndexing=true` 时两项禁用；恢复后弹起重新计算启用态。
5. `shouldRepeatOnRootItselfWithoutLooping`：重复点击不改变已生效范围（幂等、无循环提交）。

## 未自动验证 / 待 Codex 实机

- M-01 快捷部分的**真实鼠标右键点击**、菜单视觉位置、与 Open/Reveal 等既有项的并排交互：本机 harness 以 `doClick()` 驱动逻辑路径，真实手势与系统外观待实机确认。
- 盘根/UNC 根等极端路径的弹起禁用态仅覆盖 `parent==null` 分支的单元级逻辑，未在真实 UNC 环境点击。
- GUI 测试在有显示环境下运行；CI headless 中 `DirectoryScopeQuickActionsTest` 与 `DirectoryScopeDesktopHarnessTest` 一样由 `assumeFalse(isHeadless)` 自动跳过。

## 风险与说明

- 动作仅在选中目录/文件行时启用；未选行时两项均禁用。
- 关键词、扩展名、mtime、match mode 等现有搜索条件原样保留（只改 directory scope）。
- Codex 在 A5a 阶段补的“Full Rebuild 失败→恢复服务”分支未被本批改动。
- 无新增依赖、无 Java 9+ API、无提交/推送。
