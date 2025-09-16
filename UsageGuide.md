# DocFinder — Usage Guide

_Local file name & content search. Fast, read‑only, and multilingual._

> UI is **English**. This guide explains how to use it (中文说明穿插其间)。

---

## 1) Quick Start

1. **Add folders**: `File → Index Sources…` → **Add…**. The **Type** column shows **Local** / **Network** (自动检测，可手动改)。
2. **Build index**: `File → Index All Sources`（或 `Force Rebuild` 做一次全量重建）。
3. **Search**: 在顶部搜索框输入关键词或语法（见下文）。
4. **Open files**: 双击或按 **Enter**。右键可打开菜单；右侧**预览面板**显示文本内容（只读）。

> 索引与解析是只读的（read‑only），不会修改文件时间戳或内容；解析有超时保护。

---

## 2) Search Basics（搜索）

- 直接输入会在 **文件名（name）+ 内容（content）** 中检索：
  - `kubernetes` → 命中文件名或正文。
- 字段搜索（Fielded）：
  - **Filename only**: `name:設計`，`name:"チェックリスト_07.xlsx"`
  - **Content only**: `content:"zero knowledge"`
- **通配符（Wildcard，用于文件名）**：
  - `name:*.xlsx`、`name:report-??.pdf`、`name:"2024* report?.docx"`
  - 同时会自动添加 `ext:` 过滤以加速 `*.ext` 的检索。
- 允许 **前导通配**（`*.xlsx`），也可直接输入 `*.xlsx`（无字段时默认当作文件名通配）。
- 多语言支持：英文（Standard）、中文（SmartChinese）、日文（Kuromoji）。

> 精确匹配通过不分词字段 **`name_raw`** 实现；若升级后首次使用 `name:` 搜不到，请执行一次 **Force Rebuild** 以重建索引。

---

## 3) Filters（过滤）

- **Extension**: 选择一个或多个文件扩展名（如 `pdf, docx, xlsx, md`）。
- **Time range**: 按最后修改时间范围（`mtime`）过滤。

---

## 4) Results & Preview（结果与预览）

- 表格列：**Name**, **Path**, **Score**, **Created**, **Last Accessed**, **Size**, **Match**（`name` / `content` / `name + content`）。
- 预览窗（右侧）：只读文本，字体略小。
- 右键菜单：**Open**, **Open With…**（记住上次选择）, **Reveal in Explorer/Finder**, **Copy Path**, **Copy Name** 等。
- 快捷键：
  - **Enter**：Open
  - **Ctrl+C**：Copy Path
  - **Ctrl+Shift+C**：Copy Name

---

## 5) Index Sources（数据源）

- `File → Index Sources…`：
  - **Add…**：选择文件夹。
  - **Type**：Local / Network（自动检测，后台异步刷新；可手动修改）。
  - **Re-detect Type**：重新检测所有行。
  - **OK**：保存到 `~/.docfinder/sources.txt`（格式：`path|0/1`，`1=Network`）。

> Windows 下支持 UNC（`\\server\share`）与映射盘（如 `J:\`、`M:\`）；检测通过 PowerShell `Get-PSDrive`、`net use`、`wmic` 等并做缓存。

---

## 6) Building / Updating Index（建立与更新索引）

- **Index All Sources**：对所有源进行索引/更新。
- **Force Rebuild**：清空并重建（字段结构变更后建议执行一次）。
- **Read‑only parsing**（只读解析）：使用 Apache Tika，带超时与大小上限；文本类文件通过扩展名、MIME 与启发式判定。

> 文本判定：首 4KB 无 NUL 且可打印 ASCII 比例高（≥0.85）。

---

## 7) Live Updates（实时与轮询）

- **Live Watch（本地）**：对 **Local** 源使用 OS `WatchService` 增量更新。
- **Network Polling（网络）**：对 **Network** 源按间隔轮询；
  - **Poll Now**：立即轮询，**后台**执行，不会阻塞 UI；完成后状态栏显示统计（scanned/created/modified/deleted）。

> 配置轮询间隔见设置；Live Watch 与 Polling 可独立开关。

---

## 8) Global Hotkey & Tray（全局热键与托盘）

- **Global Hotkey**：使用 `jnativehook` 注册；默认唤起/隐藏主窗口（具体组合键见代码/设置）。
- **System Tray**：托盘图标支持点击/菜单，快速进入主要功能（Windows/macOS/Linux）。

---

## 9) Tips & Notes（贴士）

- **Japanese Windows（¥ 路径）**：内部路径已规范化；打开前会转换为 Explorer 可识别的形式。
- **Performance**：为 `*.ext` 的通配增加了 `ext` MUST 过滤；可适度调整索引的 RAM buffer 与并发。
- **Privacy**：所有数据均在本地，索引与解析均为只读。
- **Query History**：下拉保留最近 **100** 条查询，持久化保存。

---

## 10) Troubleshooting（排障）

- `name:チェックリスト_07.xlsx` 无结果：
  - 确认索引包含 `name_raw` 字段；执行 **Force Rebuild**。
- “Index Sources…” 打开慢：
  - 新版已后台检测 Local/Network；若仍卡顿，请确认已更新到最新构建。
- “Poll Now” 无变化：
  - 确认该源被标记为 **Network**，并具备访问权限；网络设备可能存在索引延迟。
- 内容预览为空：
  - 可能超时/文件过大/格式不受支持；可提升超时或加入扩展名白名单。

---

## 11) Keyboard Shortcuts（快捷键总览）

- **Enter**：Open selected item
- **Ctrl+C**：Copy Path
- **Ctrl+Shift+C**：Copy Name
- （更多全局热键见设置/源码）

---

## 12) Data Locations（数据位置）

- Index: `~/.docfinder/index/`
- Sources: `~/.docfinder/sources.txt`（`path|0/1`）
- Query history & app settings: `~/.docfinder/…`

---

## 13) FAQ

**Q: 会修改文件吗？**
> 不会。索引读取使用只读句柄，Tika 解析有超时保护，不会触碰内容或修改时间戳。

**Q: 支持哪些文件类型？**
> 常见文档（pdf/docx/xlsx/pptx/html/txt/markdown 等）与大量文本类（json/yaml/xml/java/go/rs/py/js 等）。可通过设置扩展 allowlist/文本类判定扩展。

**Q: 如何加速 `name:*.ext`？**
> 已内置 `ext` 过滤优化，确保通配后缀能快速命中。

---

_Enjoy lightning‑fast local search!_

