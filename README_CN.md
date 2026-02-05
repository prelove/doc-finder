# DocFinder

_本地文件名与内容搜索工具，支持 Windows / macOS / Linux。快速响应的 UI，只读索引，多语言搜索，通配符文件名查询，实时更新。_

> UI 界面为**英文**，代码注释为**中文**。最低运行环境：**Java 8**。使用 **Maven** 构建。

--- 

## 目录
- [项目概述](#项目概述)
- [主要功能](#主要功能)
- [架构设计](#架构设计)
- [索引结构与分词器](#索引结构与分词器)
- [搜索语法](#搜索语法)
- [索引与内容提取](#索引与内容提取)
- [数据源：本地与网络](#数据源本地与网络)
- [用户界面](#用户界面)
- [构建与运行](#构建与运行)
- [配置说明](#配置说明)
- [数据存储位置](#数据存储位置)
- [故障排除](#故障排除)
- [性能说明](#性能说明)
- [开发路线图](#开发路线图)
- [贡献指南](#贡献指南)
- [许可证](#许可证)

--- 

## 项目概述
DocFinder 是一款桌面实用程序，提供类似 Everything 风格的响应式 UI，用于搜索**文件名和文件内容**。它使用 **Lucene** 进行索引和搜索，**Tika** 进行内容提取，并添加了 **SmartChinese** / **Kuromoji** 分词器来更好地处理中文和日文文本。索引是**只读**的（不会修改时间戳或内容），并通过超时和启发式算法进行优化。

--- 

## 主要功能

### 搜索功能
- **全文搜索**：跨**文件名**和**内容**的自由文本（多字段查询）
- **字段搜索**：`name:`（仅文件名）、`content:`（仅内容）
- **通配符文件名查询**：`name:*.xlsx`、`name:report-??.pdf`、`name:"检查清单_07.xlsx"`
- **支持前导通配符**（例如 `*.xlsx`）
- **过滤器**：按**扩展名**和**修改时间**过滤
- **多语言支持**：英文 + 中文 + 日文分词器
- **查询历史**：保存最近 100 条查询（下拉框）

### 结果界面
- **表格列**：**名称**、**路径**、**评分**、**创建时间**、**最后访问时间**、**大小**、**匹配类型**（`name` / `content` / `name + content`）
- **预览面板**（只读，较小字体）
- **右键菜单**：打开、用其他程序打开…（记住上次选择）、在资源管理器中显示、复制路径/名称
- **快捷键**：**Enter**（打开）、**Ctrl+C**（复制路径）、**Ctrl+Shift+C**（复制名称）

### 索引功能
- **只读提取**：通过 Apache Tika，支持超时和大小限制
- **文本类检测**：使用扩展名白名单、MIME 探测和启发式算法；跳过大型/不相关的二进制文件
- **强制重建**菜单项，用于架构变更时清理重建索引

### 数据源管理
- **索引源对话框**：每个文件夹的**本地/网络**类型，**后台**检测
- **实时监控**：本地源使用操作系统 WatchService
- **网络轮询**：网络源使用定期快照/差异；**立即轮询（网络源）**在后台运行

### 平台特性
- **全局热键**（通过 `jnativehook`）切换主窗口
- **系统托盘图标** + 菜单；多尺寸 PNG 图标；运行时设置任务栏/ Dock 图标
- **健壮的 Windows 路径处理**（UNC、映射驱动器、日文系统上的 `¥`）

--- 

## 架构设计
- **UI**：Swing（英文字符串）。主窗口显示搜索栏、结果表、预览和状态栏。菜单：文件（索引、设置）、帮助（用法、关于）。集成了托盘图标和全局热键
- **索引**：Lucene 索引存储在 `~/.docfinder/index` 下。每次操作使用单个写入器；多根索引迭代源
- **提取**：Apache Tika 配合执行器 + 超时（可取消）。流仅使用 `StandardOpenOption.READ` 打开
- **监控器**：
  - 本地：Java NIO `WatchService` 监控创建/修改/删除；合并并异步处理
  - 网络：轮询调度器；快照/差异；背压以避免 UI 阻塞
- **持久化**：源列表 `~/.docfinder/sources.txt`（`path|0/1`）、查询历史和应用设置存储在 `~/.docfinder/` 下

--- 

## 索引结构与分词器

### Lucene 字段（每个文件）
- `path` — `StringField`，**规范化**，存储。主键。所有更新/删除使用规范化值
- `name` — `TextField`，存储。用于分析文件名匹配和提升
- `name_raw` — `StringField`，**小写且不分析**。通过 `name:` 实现精确/通配符文件名搜索
- `ext` — `StringField`，存储。用于过滤和通配符加速
- `mtime_l` — `LongPoint`。修改时间的范围过滤器
- `mtime` / `ctime` / `atime` — `StoredField`（用于显示）
- `size` — `StoredField`
- `mime` — `StringField`，存储（尽力而为）
- `content` — `TextField`，不存储
- `content_zh` — `TextField`，不存储（SmartChineseAnalyzer）
- `content_ja` — `TextField`，不存储（JapaneseAnalyzer / Kuromoji）

### 分词器（通过 `PerFieldAnalyzerWrapper`）
- `StandardAnalyzer` 用于 `name` 和 `content`
- `SmartChineseAnalyzer` 用于 `content_zh`
- `JapaneseAnalyzer`（Kuromoji）用于 `content_ja`

### 路径规范化
- 所有写入/更新/删除使用 `Utils.normalizeForIndex(Path)`，确保 `path` 术语在平台和 Windows 变体（UNC、映射驱动器、`¥）间保持一致

--- 

## 搜索语法
- **多字段解析**并提升：`name^2.0`、`content^1.2`、`content_zh^1.2`、`content_ja^1.2`
- **解析器中允许前导通配符**
- **文件名通配符**处理：
  - 我们预提取 `name:<pattern>`（支持引号）。如果整个查询只是通配符如 `*.xlsx`，则被视为文件名通配符
  - 模式通过 **`name_raw`** 使用 `WildcardQuery`（如果没有 `*`/`?` 则使用 `TermQuery`）
  - 如果模式是 `*.ext` 形式，我们还添加 MUST `ext:<ext>` 来缩小候选集（大幅提速）
- **前缀提升**：当用户输入不带字段/空格/通配符的简短 ASCII 标记时，我们添加 `PrefixQuery(name, token)` 作为 SHOULD 来提升文件名开头匹配
- **过滤器**：扩展名 OR 集合和修改时间范围作为 MUST 子句添加

> 如果从旧索引升级，请运行一次**强制重建**以确保所有文档都有 `name_raw`

--- 

## 索引与内容提取

### 只读模式
- 使用 `StandardOpenOption.READ` 打开文件
- Tika 在可配置超时的执行器中运行（`IndexSettings.parseTimeoutSec`）
- 超时/失败时，我们只索引元数据（省略内容字段）

### 解析内容由 `IndexSettings` 控制
- `maxFileMB` 大小上限
- `includeExt` 文档白名单（例如 pdf/docx/xlsx/pptx/html…）
- `parseTextLike` 开关解析文本类文件
- `textExts` 源代码/配置（java、go、rs、py、js、ts、json、yaml/yml、xml、md、txt、sh、properties…）
- MIME 检查（`text/*`、常见 `application/*`）和 **4KB 启发式**：无 NUL 且 ASCII 可打印比例 ≥ 0.85

--- 

## 数据源：本地与网络

### 存储
`~/.docfinder/sources.txt` 包含 `path|0/1` 行（`1 = 网络`）。旧的单列文件会自动升级。

### 检测（Windows）
尝试 PowerShell `Get-PSDrive`、`net use`、`wmic`，带缓存；回退到 `FileStore` 类型和 UNC 前缀。映射驱动器（`J:\`、`M:\`）在解析为远程目标时被视为网络。

### 实时监控
仅对**本地**源启用（使用 NIO `WatchService`）。

### 网络轮询
仅对**网络**源启用；后台快照 + 差异；**立即轮询（网络源）**是异步的，完成时更新状态栏。

--- 

## 用户界面

### 搜索框
带有占位符提示（`Search… (e.g. report*, content:"zero knowledge", name:"设计")`）和查询历史（100 条最近记录，持久化）。

### 结果表
可排序列格；右侧**预览**面板。

### 菜单
- `File → Manage Sources…`（管理本地/网络类型的源）
- `File → Index All Sources` 和 `File → Rebuild Index (Full)`
- `File → Indexing Settings…`
- `Help → Usage Guide`、`Help → About DocFinder`

### 托盘图标
带上下文菜单；**全局热键**切换窗口。

### 图标
从 `src/main/resources/icons/` 加载多尺寸 PNG；通过 `Taskbar`（Java 9+）或 macOS 上的 `com.apple.eawt` 设置任务栏/Dock 图标。

--- 

## 构建与运行

### 环境要求
- Java **8+**
- Maven **3.6+**

### 构建
```bash
mvn clean package
```

### 运行
```bash
# 打包的 JAR（取决于打包方式）
java -jar target/docfinder-1.0.0.jar
```

> 或从 IDE 启动。应用使用 `~/.docfinder` 作为运行时数据目录。

### Maven 主要依赖
```xml
<dependencies>
  <!-- Lucene core + queryparser + analyzers -->
  <dependency>
    <groupId>org.apache.lucene</groupId>
    <artifactId>lucene-core</artifactId>
    <version>${lucene.version}</version>
  </dependency>
  <dependency>
    <groupId>org.apache.lucene</groupId>
    <artifactId>lucene-queryparser</artifactId>
    <version>${lucene.version}</version>
  </dependency>
  <dependency>
    <groupId>org.apache.lucene</groupId>
    <artifactId>lucene-analyzers-common</artifactId>
    <version>${lucene.version}</version>
  </dependency>
  <dependency>
    <groupId>org.apache.lucene</groupId>
    <artifactId>lucene-analyzers-smartcn</artifactId>
    <version>${lucene.version}</version>
  </dependency>
  <dependency>
    <groupId>org.apache.lucene</groupId>
    <artifactId>lucene-analyzers-kuromoji</artifactId>
    <version>${lucene.version}</version>
  </dependency>

  <!-- Apache Tika -->
  <dependency>
    <groupId>org.apache.tika</groupId>
    <artifactId>tika-core</artifactId>
    <version>${tika.version}</version>
  </dependency>
  <dependency>
    <groupId>org.apache.tika</groupId>
    <artifactId>tika-parsers-standard-package</artifactId>
    <version>${tika.version}</version>
  </dependency>

  <!-- Global hotkey -->
  <dependency>
    <groupId>com.github.kwhat</groupId>
    <artifactId>jnativehook</artifactId>
    <version>2.2.2</version>
  </dependency>
  
  <!-- FlatLaf 主题 -->
  <dependency>
    <groupId>com.formdev</groupId>
    <artifactId>flatlaf</artifactId>
    <version>3.2</version>
  </dependency>
  <dependency>
    <groupId>com.formdev</groupId>
    <artifactId>flatlaf-intellij-themes</artifactId>
    <version>3.2</version>
  </dependency>
</dependencies>
```

> 确保 Lucene/Tika 版本在所选主要版本中相互兼容。

--- 

## 配置说明
`IndexSettings` 主要字段：
- `maxFileMB` — 解析大小上限
- `parseTimeoutSec` — 每文件 Tika 超时
- `includeExt` — 文档类型白名单（例如 pdf、docx、xlsx、pptx、html）
- `parseTextLike` — 解析文本类文件
- `textExts` — 文本/源扩展名（txt、md、json、yaml、xml、java、go、rs、py、js、ts、sh、properties…）
- `excludeGlob` — 要跳过的 glob 模式（例如 `**/.git/**`、`**/node_modules/**`）

--- 

## 数据存储位置
- **索引**：`~/.docfinder/index/`
- **源**：`~/.docfinder/sources.txt`（`path|0/1`，其中 `1 = 网络`）
- **历史记录和设置**：`~/.docfinder/…`

--- 

## 故障排除
- **`name:检查清单_07.xlsx` 无结果** → 使用**强制重建**重新索引以确保所有文档都有 `name_raw`
- **"Manage Sources…" 似乎很慢** → 检测在后台运行；如果仍然阻塞，请升级到最新构建
- **"立即轮询（网络源）" 显示无变化** → 确认文件夹标记为**网络**且可访问；NAS 可能反映更新延迟
- **预览为空** → 文件过大/超时/不支持；增加超时或扩展白名单
- **Windows 日元符号（¥）** → 内部规范化处理；打开使用资源管理器友好路径

--- 

## 性能说明
- 通配符 `*.ext` 通过 `ext` MUST 子句加速
- 前缀提升提高简短文件名标记的相关性
- 索引写入器为批量索引使用更大的 RAM 缓冲区；如需要可在代码中调整
- 内容提取使用超时和短路启发式算法跳过明显的二进制文件

--- 

## 开发路线图
- 路径通配符（`path:`）和正则表达式搜索
- 内容命中的代码片段高亮预览
- UI 主题优化（例如 BeautyEye 或类似）作为可选模块
- 设置和源的导出/导入

--- 

## 贡献指南
1. 从 `main` Fork & 创建分支
2. 构建：`mvn clean package`（Java 8+）
3. 代码风格：UI 字符串保持**英文**，注释使用**中文**；避免 UI 阻塞（对繁重任务使用 `SwingWorker`）
4. 提交 PR 并附上清晰描述；如果涉及 UI 更改，请提供截图

--- 

## 许可证
_待定_（MIT/Apache‑2.0/专有 — 选择一个并添加许可证文件）

---

## 快速入门

1. **添加文件夹**：`File → Manage Sources…` → **Add…**。**Type** 列显示 **Local** / **Network**（自动检测，可手动修改）
2. **构建索引**：`File → Index All Sources`（或 `Rebuild Index (Full)` 执行一次全量重建）
3. **搜索**：在顶部搜索框输入关键词或语法（见下文）
4. **打开文件**：双击或按 **Enter**。右键可打开菜单；右侧**预览面板**显示文本内容（只读）

> 索引与解析是只读的（read‑only），不会修改文件时间戳或内容；解析有超时保护。

### 搜索示例
- `kubernetes` → 命中文件名或正文
- `name:设计` → 仅文件名搜索
- `content:"零知识证明"` → 仅内容搜索
- `name:"检查清单_07.xlsx"` → 精确文件名匹配
- `*.pdf` → 所有 PDF 文件
- `report* AND content:"架构"` → 文件名包含 report 且内容包含架构

### 快捷键
- **Enter**：打开选中文件
- **Ctrl+C**：复制完整路径
- **Ctrl+Shift+C**：复制文件名
- **Ctrl+Alt+Space**：切换主窗口（全局热键）

--- 

## 技术栈

### 核心技术
- **Java 8+** - 基础运行环境
- **Apache Lucene 8.11.2** - 索引和搜索引擎
- **Apache Tika 2.9.2** - 内容提取和解析
- **Apache Maven 3.6+** - 项目构建工具

### UI 框架
- **Java Swing** - 主要 UI 框架
- **FlatLaf 3.2** - 现代 Look and Feel
- **FlatLaf IntelliJ Themes 3.2** - IntelliJ 风格主题

### 多语言支持
- **SmartChineseAnalyzer** - 中文分词
- **JapaneseAnalyzer (Kuromoji)** - 日文分词
- **StandardAnalyzer** - 英文分词

### 系统集成
- **JNativeHook 2.2.2** - 全局热键支持
- **Java NIO WatchService** - 文件系统监控
- **系统托盘 API** - 系统托盘集成

--- 

## 项目结构

```
src/main/java/org/abitware/docfinder/
├── App.java                    # 主入口点，托盘图标，全局热键
├── index/                      # 索引和源管理
│   ├── ConfigManager.java      # 配置持久化
│   ├── IndexSettings.java      # 索引配置
│   ├── LuceneIndexer.java      # 核心索引逻辑
│   └── SourceManager.java      # 源文件夹管理
├── search/                     # 搜索功能
│   ├── LuceneSearchService.java # 主搜索实现
│   ├── SearchService.java      # 搜索接口
│   ├── SearchRequest.java      # 查询请求对象
│   ├── SearchResult.java       # 结果数据结构
│   └── SearchHistoryManager.java # 查询历史管理
├── ui/                         # 用户界面
│   ├── MainWindow.java         # 主应用窗口
│   ├── ManageSourcesDialog.java # 源管理对话框
│   ├── GlobalHotkey.java       # 系统级热键处理
│   ├── IconUtil.java           # 图标管理
│   └── ThemeUtil.java          # 主题切换
├── util/                       # 工具类
│   ├── SingleInstance.java     # 单实例强制
│   └── Utils.java              # 通用工具
└── watch/                      # 文件系统监控
    ├── LiveIndexService.java   # 实时索引协调
    ├── LocalRecursiveWatcher.java # 本地文件夹监控
    ├── NetPollerService.java   # 网络文件夹轮询
    └── SnapshotStore.java      # 网络共享快照
```

--- 

## 重构说明

本项目已完成组件化重构，将大型类拆分为职责单一的组件：

### UI 组件重构
- **MainWindow.java** (~1000行) → 6个专门的UI组件
  - `SearchPanel` - 搜索输入和过滤器
  - `ResultsPanel` - 结果表格和操作
  - `PreviewPanel` - 文件预览
  - `StatusBarPanel` - 状态栏
  - `MenuBarPanel` - 菜单栏
  - `SearchWorker` - 后台搜索任务

### 搜索服务重构
- **LuceneSearchService.java** (~500行) → 3个查询处理组件
  - `QueryBuilder` - 查询构建逻辑
  - `QueryExecutor` - 查询执行和结果处理
  - `LuceneSearchServiceRefactored` - 轻量级服务接口

### 索引服务重构
- **LuceneIndexer.java** (~600行) → 3个内容处理组件
  - `ContentExtractor` - 文本内容提取
  - `DocumentBuilder` - Lucene文档构建
  - `LuceneIndexerRefactored` - 核心索引操作

### 重构优势
- **可维护性提升** - 从3个大型类拆分为15个专门组件
- **职责分离清晰** - UI、搜索、索引逻辑完全分离
- **扩展性增强** - 新功能可通过添加组件实现
- **测试友好** - 每个组件都可独立测试
- **向后兼容** - 保持所有原有功能不变
