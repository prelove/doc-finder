# DocFinder — Usage Guide

_Local file name & content search. Fast, read‑only, and multilingual._

> UI is **English**. This guide explains how to use it (中文说明穿插其间)。

---

## 1) Quick Start

1. **Add folders**: `File → Manage Sources…` → **Add…**. The **Type** column shows **Local** / **Network** (自动检测，可手动改)。
2. **Build index**: `File → Index All Sources`（旧索引先用此方式补字段；Full Rebuild需先备份并安排维护时段）。
3. **Search**: 在顶部搜索框输入关键词或语法（见下文）。
4. **Open files**: 双击或按 **Enter**。右键可打开菜单；右侧**预览面板**显示文本内容（只读）。

> 索引与解析是只读的（read‑only），不主动改写源文件内容或修改时间；操作系统/NAS可能因读取更新访问时间；解析有超时保护。

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
- **目录范围（Search in）**：搜索框下方可选择一个已配置来源作为检索范围，或直接输入该来源内的绝对路径后按 Enter；`Clear` / `All sources` 恢复全局搜索。选择 `All sources` 时路径框与 Browse / Clear 置灰；选择 `Custom` 或某个来源后才可输入、浏览。索引或重建进行期间，范围控件和 Filters（含 Ext/From/To/Apply）一并禁用。范围包含所选目录的全部子目录，仅检索已索引内容。范围之外的目录会提示先添加来源，而不会自动扫描或建库。
- 路径编辑不会立即更改查询范围；提交前看Applied提示，Enter、来源选择或Browse确认才提交。
- ALL/NAME返回文件，FOLDER返回目录；默认（Fuzzy）普通输入按**字面名称子串**匹配：`report` 可命中 `AnnualReport2026.pdf`（含中段），中文、日文、单字符及 `spring-core` 这类带符号词均可直接输入；多个词之间为 AND（名称须包含全部词）。整名命中排在最前，其次前缀、中段子串；名称命中整体排在仅正文命中之前。`name:` 通配/字段语法与 EXACT 行为不变，EXACT 仍包含分词短语匹配、不做中段子串。
- 键入停顿约 250ms 自动搜索；Enter、下拉历史、范围/模式切换、过滤应用会立即搜索一次。结果过多时状态栏提示 "Showing first N results"。
- 非法查询语法（例如不匹配的括号 `report (final`）现在会明确提示语法错误，而不是静默返回空结果，且不会记入查询历史。含 `+`、`:` 等保留字符的输入行为以实际分词器为准；需要字面匹配时可使用双引号短语（`"a+b"`）或 `name:` 字段语法。
- 旧索引在限定目录搜索时会提示先执行 **Index All Sources** 补齐内部字段；存在残留旧记录时才需备份后手动 **Rebuild Index (Full)**。

> 精确匹配通过不分词字段 **`name_raw`** 实现；若升级后首次使用 `name:` 搜不到，请执行一次 **Rebuild Index (Full)** 以重建索引。
> 当前目录范围检索使用内部字段 `path_scope`（不存储）。旧索引在限定目录搜索时会提示先执行 **Index All Sources**；存在残留旧记录时才需要备份后手动 **Rebuild Index (Full)**。

---

## 3) Filters（过滤）

- **Extension**: 选择一个或多个文件扩展名（如 `pdf, docx, xlsx, md`）。
- **Time range**: 按最后修改时间范围（`mtime`）过滤。

---

## 4) Results & Preview（结果与预览）

- 表格列：**Name**, **Path**, **Size**, **Created**, **Last Accessed**, **Match**（`name` / `content` / `name + content`）。
- 桌面与Web点击同一列表头依次为**升序→降序→默认顺序**，也可点 **Restore default sort** 直接恢复。默认顺序是本次搜索引擎返回的顺序，不等于分数降序；恢复不重新搜索，保留当前关键词、范围、过滤与选中结果及预览。新结果自动清除手动排序。
- `Size` 列右对齐，并按真实字节数进行数值排序（不是按字符串排序）；显示会自动换算为 KB/MB/GB。
- 预览窗（右侧）：默认宽度约为窗口的 1/4（可拖拽调整）。
- 预览开关：状态栏右侧的 👁 按钮可快速显示/隐藏。
- 编码提示：预览会自动检测常见编码（UTF-8/UTF-16、Shift-JIS、GBK、Big5、EUC-KR 等），多语言混排文件也尽量兼容。
- 右键菜单：**Open**, **Open With…**（记住上次选择）, **Reveal in Explorer/Finder**, **Show Properties**（高级检索详情）, **Copy Path**, **Copy Name** 等。
- 范围快捷操作：**Search in this folder**（仅目录结果；把范围设为该目录并保留当前关键词/过滤）、**Search in containing folder**（仅文件结果；范围设为该文件所在目录）。两者均按当前排序映射到正确结果行，且索引/搜索进行中不可用；再次对已生效范围执行不会产生重复提交。
- 快捷键：
  - **Enter**：Open
  - **Ctrl+C**：Copy Path
  - **Ctrl+Shift+C**：Copy Name

---

## 5) Manage Sources（数据源）

- `File → Manage Sources…`：
  - **Add…**：选择文件夹。
  - **Type**：Local / Network（自动检测，后台异步刷新；可手动修改）。
  - **Re-detect Type**：重新检测所有行。
  - **OK**：保存到 `./.docfinder/sources.txt`（格式：`path|0/1`，`1=Network`）。

> Windows 下支持 UNC（`\\server\share`）与映射盘（如 `J:\`、`M:\`）；检测通过 PowerShell `Get-PSDrive`、`net use`、`wmic` 等并做缓存。

---

## 6) Building / Updating Index（建立与更新索引）

- **Index All Sources**：对所有源进行索引/更新。
- **Rebuild Index (Full)**：先清空再重建、非原子，期间不可搜索；先尝试Index All Sources补字段，仍有旧记录阻断时再备份并在维护时段手动执行。
- **Indexing Settings…**：调整解析上限、超时和 include/exclude 规则（含 `maxExtractChars` 与 `textMaxBytes`）。
- **Read‑only parsing**（只读解析）：使用 Apache Tika，带超时与大小上限；文本类文件通过扩展名、MIME 与启发式判定，未知扩展名但可识别为文本也会尝试索引。

> 文本判定：首 4KB 无 NUL 且可打印 ASCII 比例高（≥0.85）。

---

## 7) Live Updates（实时与轮询）

- **Live Watch（本地）**：对 **Local** 源使用 OS `WatchService` 增量更新。
- **Network Polling（网络）**：对 **Network** 源按间隔轮询；
  - **Poll Network Sources Now**：立即轮询，**后台**执行，不会阻塞 UI；完成后状态栏显示统计（scanned/created/modified/deleted）。

> 配置轮询间隔见设置；Live Watch 与 Polling 可独立开关。

---

## 8) Global Hotkey & Tray（全局热键与托盘）

- **Global Hotkey**：使用 `jnativehook` 注册；默认唤起/隐藏主窗口（具体组合键见代码/设置）。
- **System Tray**：托盘图标支持点击/菜单，快速进入主要功能（Windows/macOS/Linux）。

---

## 9) Web Interface & Document Preview（网页界面与文档预览）

### 9.1) Web Server

DocFinder 提供了内置的 HTTP 服务器，允许通过浏览器访问搜索界面。

- **启用 Web Server**：`File → Enable Web Server`（勾选菜单项即启动服务器）
- **打开 Web 界面**：`File → Open Web Interface…`（在默认浏览器中打开）
- **默认端口**：`7070`（可在配置文件中修改）
- **访问地址**：http://127.0.0.1:7070

Web 界面功能与桌面应用基本一致，支持搜索、过滤、预览等核心功能。

- **目录范围（Server directory）**：搜索栏中的目录输入框可填入服务器上的绝对路径（须位于已配置来源内），按 Enter 或 Search 提交；`Clear` 恢复全部来源。范围包含所选目录的全部子目录，当前范围会显示在状态栏。结果超出单页上限时状态栏显示 "Showing first N results"。每条结果提供快捷操作：目录行 `📂`（在此目录内搜索）、文件行 `📁↰`（在所在目录内搜索，无父目录时禁用）、`📋`（复制路径，剪贴板不可用时弹出手动复制框，不虚报成功）。
- **错误响应**：搜索错误返回 `{"error": <固定英文文案>, "code": <错误码>}`，例如 `INVALID_DIRECTORY`(400)、`DIRECTORY_NOT_INDEXED`(422)、`INDEX_REBUILD_REQUIRED`(409)、`INDEX_UNAVAILABLE`(503) 等；界面不会显示内部异常细节。

### 9.2) 当前预览能力

- PDF使用浏览器内置查看器；文本/代码使用文本预览；图片直接显示。
- Office、归档、CAD等页面不支持渲染的格式提供下载回退；Tika可提取正文不等于能渲染原始版式。
- 当前代码没有JitViewer/kkFileView切换、对应服务器菜单或viewer配置API，无需安装额外预览服务。

### 9.3) API Endpoints

- `GET /api/search`：q/scope/mode/ext/limit/directory；成功为 `{results,count}`，每条含parentDirectory。count是返回条数，当前没有精确总命中/分页承诺。
- `GET /api/preview`：预览路由；`GET /api/file`：文件内容/下载；`GET /api/text`：文本预览。
- `/api/share`：分享管理；`/share/`：分享页面；`/web/`及`/`：内置静态资源。
- Web目录字段为服务器绝对路径，目前不提供服务器目录浏览器或完整来源列表。

---

## 10) Tips & Notes（贴士）

- **Japanese Windows（¥ 路径）**：内部路径已规范化；打开前会转换为 Explorer 可识别的形式。
- 文件名中的全角斜杠 `／` 会被保留，不会被替换成目录分隔符。
- **Performance**：为 `*.ext` 的通配增加了 `ext` MUST 过滤；可适度调整索引的 RAM buffer 与并发。
- **Privacy**：所有数据均在本地，索引与解析均为只读。
- **Query History**：下拉保留最近 **100** 条查询，持久化保存。
- 从历史记录中选择查询时，会重新执行一次检索（不是结果缓存回放）。

---

## 11) Troubleshooting（排障）

- `name:チェックリスト_07.xlsx` 无结果：
  - 确认索引包含 `name_raw` 字段；执行 **Rebuild Index (Full)**。
- “Manage Sources…” 打开慢：
  - 新版已后台检测 Local/Network；若仍卡顿，请确认已更新到最新构建。
- “Poll Network Sources Now” 无变化：
  - 确认该源被标记为 **Network**，并具备访问权限；网络设备可能存在索引延迟。
- 内容预览为空：
  - 可能超时/文件过大/格式不受支持；可提升超时或加入扩展名白名单。

---

## 12) Keyboard Shortcuts（快捷键总览）

- **Enter**：Open selected item
- **Ctrl+C**：Copy Path
- **Ctrl+Shift+C**：Copy Name
- （更多全局热键见设置/源码）

---

## 13) Data Locations（数据位置）

- Index: `./.docfinder/index/`
- Sources: `./.docfinder/sources.txt`（`path|0/1`）
- Query history & app settings: `./.docfinder/…`
- 以上相对于JVM工作目录user.dir；index.dir可覆盖索引位置，旧home目录只用于兼容迁移。

---

## 14) FAQ

**Q: 会修改文件吗？**
> 不主动改写源文件内容或修改时间；操作系统/NAS可能因读取更新访问时间。Tika解析有超时与长度限制。

**Q: 支持哪些文件类型？**
> 常见文档（pdf/docx/xlsx/pptx/html/txt/markdown 等）与大量文本类（json/yaml/xml/java/go/rs/py/js 等）。可通过设置扩展 allowlist/文本类判定扩展。

**Q: Office（Excel/Word/PPT）里的文本框内容能被抽取吗？**
> 一般情况下 Tika/POI 会尽力抽取工作表/正文及部分形状文本（best effort），但复杂模板、兼容模式对象或非常规绘图结构可能存在漏提取。

**Q: 日志里出现 `XSSFDrawing` / `DataFormatter` WARN，是否影响检索？**
> 这类日志大多是 POI 对特殊格式的非致命提示（例如旧日期值、AlternateContent 形状），通常不影响读索引流程；当前已在日志层降噪处理。

**Q: 如何加速 `name:*.ext`？**
> 已内置 `ext` 过滤优化，确保通配后缀能快速命中。

---

_Enjoy lightning‑fast local search!_


## 15) Logs

- Use **Help → View Log** to open a live, auto-scrolling log viewer.
- Viewer defaults to **tail -f 1000 lines** for readability/performance.
- Use **Show More (+1000)** to load more history and **Reset Tail** to return to 1000.
- Use **Clear** to clear current viewer text quickly (does not delete the log file).
- Log file path: `./.docfinder/logs/docfinder.log`.


## 当前交付与排障边界（2026-10-24）

- 指定目录检索已实现，用户已确认最新版手工验收完全成功；自动测试与用户报告分别保留证据范围。B名称体验已在 `codex/name-search` 分支实现（字面子串/多词AND/双列表排序/防抖/截断提示），待最终验收。
- INDEX_REBUILD_REQUIRED：先Index All Sources；检查离线/权限不足来源及删除/移除来源的旧记录，残留仍阻断时备份后手动Full Rebuild。重建期间非空搜索不可用。
- 部分Index All失败可查已提交数据，不表示全部来源已完成；服务不可用时旧结果会清除。
- 启动脚本上限2GB Java堆；native mmap失败应查hs_err、系统提交容量/分页文件，资源不足时降低堆上限，提高Xmx不能保证修复。
- native、POSIX宿主、真实UNC整链路尚未验；词法UNC测试不等于真实共享测试。
