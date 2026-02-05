# DocFinder

_Local file name & content search for Windows / macOS / Linux. Fast UI, read‑only indexing, multilingual search, wildcard filename queries, and live updates._

> UI text is **English**. Code comments are **Chinese**. Minimum runtime: **Java 8**. Build with **Maven**.

---

## Table of Contents
- [Overview](#overview)
- [Key Features](#key-features)
- [Architecture](#architecture)
- [Index Schema & Analyzers](#index-schema--analyzers)
- [Search Semantics](#search-semantics)
- [Indexing & Content Extraction](#indexing--content-extraction)
- [Sources: Local vs Network](#sources-local-vs-network)
- [UI & UX](#ui--ux)
- [Build & Run](#build--run)
- [Configuration](#configuration)
- [Data Locations](#data-locations)
- [Troubleshooting](#troubleshooting)
- [Performance Notes](#performance-notes)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [License](#license)

---

## Overview
DocFinder is a desktop utility that lets you search **file names and file contents** with a responsive, Everything‑style UI. It uses **Lucene** for indexing/search, **Tika** for content extraction, and adds **SmartChinese** / **Kuromoji** analyzers to handle Chinese and Japanese text well. Indexing is **read‑only** (no touching timestamps or contents) and optimized with timeouts and heuristics.

---

## Key Features
- **Search**
  - Free text across **filename** and **content** (multi‑field query).
  - Fielded search: `name:` (filename‑only), `content:` (content‑only).
  - **Wildcard filename** queries: `name:*.xlsx`, `name:report-??.pdf`, `name:"チェックリスト_07.xlsx"`.
  - Leading wildcards enabled (e.g., `*.xlsx`).
  - Filters by **extension** and **modified time**.
  - **Multilingual**: English + Chinese + Japanese analyzers.
  - **Query history**: last 100 queries persisted (dropdown).

- **Results UI**
  - Columns: **Name**, **Path**, **Score**, **Created**, **Last Accessed**, **Size**, **Match** (`name` / `content` / `name + content`).
  - Preview panel (read‑only; smaller font).
  - Right‑click menu: Open, Open With… (remembers last choice), Reveal in Explorer/Finder, Copy Path/Name.
  - Shortcuts: **Enter** (open), **Ctrl+C** (copy path), **Ctrl+Shift+C** (copy name).

- **Indexing**
  - **Read‑only** extraction via Apache Tika; timeouts; size limits.
  - Text‑like detection using extension allowlist, MIME probing, and heuristics; large/irrelevant binaries skipped.
  - **Rebuild Index (Full)** menu to clean rebuild when schema changes.

- **Sources**
  - `Manage Sources…` dialog with **Local/Network** type per folder, **background** detection.
  - **Live Watch** for Local sources (OS WatchService).
  - **Network Polling** for Network sources (periodic snapshot/diff). **Poll Network Sources Now** runs in the background.

- **Platform niceties**
  - Global hotkey (via `jnativehook`) to toggle main window.
  - System tray icon + menu; multi‑size PNG icons; Taskbar/Dock icon set at runtime.
  - Robust Windows path handling (UNC, mapped drives, `¥` on Japanese OS).

---

## Architecture
- **UI**: Swing (English strings). Main window shows search bar, results table, preview, and status bar. Menus: File (Index, Settings), Help (Usage, About). Tray icon and a global hotkey are integrated.
- **Index**: Lucene index stored under `~/.docfinder/index`. A single writer used per operation; multi‑root indexing iterates sources.
- **Extraction**: Apache Tika with an executor + timeout (future cancel). Streams opened with `StandardOpenOption.READ` only.
- **Watchers**:
  - Local: Java NIO `WatchService` for create/modify/delete; coalesced and processed asynchronously.
  - Network: polling scheduler; snapshot/diff; back‑pressure to avoid UI blocking.
- **Persistence**: sources list `~/.docfinder/sources.txt` (`path|0/1`), query history, and app settings stored under `~/.docfinder/`.

---

## Index Schema & Analyzers
**Lucene fields (per file):**
- `path` — `StringField`, **normalized**, stored. Primary key. All updates/deletes use the normalized value.
- `name` — `TextField`, stored. For analyzed filename matching and boosting.
- `name_raw` — `StringField`, **lowercase & NOT analyzed**. Enables exact/wildcard filename search via `name:`.
- `ext` — `StringField`, stored. Used for filtering and wildcard acceleration.
- `mtime_l` — `LongPoint`. Range filter for modified time.
- `mtime` / `ctime` / `atime` — `StoredField` (for display).
- `size` — `StoredField`.
- `mime` — `StringField`, stored (best‑effort).
- `content` — `TextField`, not stored.
- `content_zh` — `TextField`, not stored (SmartChineseAnalyzer).
- `content_ja` — `TextField`, not stored (JapaneseAnalyzer / Kuromoji).

**Analyzers** (via `PerFieldAnalyzerWrapper`):
- `StandardAnalyzer` for `name` and `content`.
- `SmartChineseAnalyzer` for `content_zh`.
- `JapaneseAnalyzer` (Kuromoji) for `content_ja`.

**Path normalization**
- All writes/updates/deletes use `Utils.normalizeForIndex(Path)` so that `path` terms are consistent across platforms and Windows variants (UNC, mapped drives, `¥`).

---

## Search Semantics
- **Multi‑field parsing** with boosts: `name^2.0`, `content^1.2`, `content_zh^1.2`, `content_ja^1.2`.
- **Leading wildcards allowed** in the parser.
- **Filename wildcard** handling:
  - We pre‑extract `name:<pattern>` (supports quotes). If the entire query is just a wildcard like `*.xlsx`, it’s treated as filename wildcard.
  - Patterns go to **`name_raw`** via `WildcardQuery` (or `TermQuery` if no `*`/`?`).
  - If the pattern is of the form `*.ext`, we also add a MUST `ext:<ext>` to narrow the candidate set (big speedup).
- **Prefix boost**: when user typed a short ASCII token without field/space/wildcard, we add a `PrefixQuery(name, token)` as a SHOULD to boost filename starts‑with matches.
- **Filters**: extension OR‑set and modified‑time range are added as MUST clauses.

> If upgrading from older indexes, run **Rebuild Index (Full)** once so all docs have `name_raw`.

---

## Indexing & Content Extraction
- **Read‑only**:
  - Open files with `StandardOpenOption.READ`.
  - Tika runs inside an executor with a configurable timeout (`IndexSettings.parseTimeoutSec`).
  - On timeout/failure, we index metadata only (content fields omitted).
- **What gets parsed** is controlled by `IndexSettings`:
  - `maxFileMB` size cap.
  - `includeExt` document allowlist (e.g., pdf/docx/xlsx/pptx/html…).
  - `parseTextLike` toggle to parse text‑like files.
  - `textExts` for source code / config (java, go, rs, py, js, ts, json, yaml/yml, xml, md, txt, sh, properties…).
  - MIME check (`text/*`, common `application/*`) and **4KB heuristic**: no NUL and ASCII printable ratio ≥ 0.85.

---

## Sources: Local vs Network
- **Storage**: `~/.docfinder/sources.txt` with lines of `path|0/1` (`1 = Network`). Old single‑column files are auto‑upgraded.
- **Detection** (Windows): tries PowerShell `Get-PSDrive`, `net use`, `wmic`, with caching; falls back to `FileStore` type and UNC prefixes. Mapped drives (`J:\`, `M:\`) are treated as Network when resolved to remote targets.
- **Live Watch**: enabled only for **Local** sources (uses NIO `WatchService`).
- **Network Polling**: enabled only for **Network** sources; background snapshots + diffs; `Poll Network Sources Now` is async and updates the status bar when done.

---

## UI & UX
- **Search field** with placeholder hint (`Search…  (e.g. report*, content:"zero knowledge", name:"設計")`) and query history (100 recent, persisted).
- **Results table** with sortable columns; **preview** pane on the right.
- **Menu**
  - `File → Manage Sources…` (manage sources with Local/Network type)
  - `File → Index All Sources` and `File → Rebuild Index (Full)`
  - `File → Indexing Settings…`
  - `Help → Usage Guide`, `Help → About DocFinder`
- **Tray icon** with context menu; **Global hotkey** toggles window.
- **Icons**: load multi‑size PNGs from `src/main/resources/icons/`; Taskbar/Dock icon set via `Taskbar` (Java 9+) or `com.apple.eawt` on macOS.

---

## Build & Run

### Requirements
- Java **8+**
- Maven **3.6+**

### Build
```bash
mvn clean package
```

### Run
```bash
# Shaded/assembly JAR (depending on your packaging)
java -jar target/docfinder-1.0.0.jar
```

> Or launch from IDE. The app uses `~/.docfinder` for its runtime data.

### Maven (key dependencies)
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
</dependencies>
```

> Ensure Lucene/Tika versions are mutually compatible for your chosen major versions.

---

## Configuration
`IndexSettings` main fields:
- `maxFileMB` — parse size cap
- `parseTimeoutSec` — per‑file Tika timeout
- `includeExt` — document‑type allowlist (e.g., pdf, docx, xlsx, pptx, html)
- `parseTextLike` — parse text‑like files
- `textExts` — text/source extensions (txt, md, json, yaml, xml, java, go, rs, py, js, ts, sh, properties…)
- `excludeGlob` — glob patterns to skip (e.g., `**/.git/**`, `**/node_modules/**`)

---

## Data Locations
- **Index**: `~/.docfinder/index/`
- **Sources**: `~/.docfinder/sources.txt` (`path|0/1` where `1 = Network`)
- **History & settings**: `~/.docfinder/…`

---

## Troubleshooting
- **`name:チェックリスト_07.xlsx` returns nothing** → reindex with **Rebuild Index (Full)** to ensure all docs have `name_raw`.
- **“Manage Sources…” seems slow** → detection runs in background; upgrade to latest build if you still see blocking.
- **“Poll Network Sources Now” shows no changes** → verify the folder is marked **Network** and reachable; NAS may reflect updates with delay.
- **Preview empty** → file too large/timeout/unsupported; increase timeout or extend allowlist.
- **Windows yen sign (¥)** → internal normalization handles it; opening uses Explorer‑friendly paths.

---

## Performance Notes
- Wildcard `*.ext` is accelerated with an `ext` MUST clause.
- Prefix boosting improves relevance for short filename tokens.
- Index writer uses a larger RAM buffer for bulk indexing; adjust in code if needed.
- Content extraction uses timeouts and short‑circuit heuristics to skip obvious binaries.

---

## Roadmap
- Path wildcards (`path:`) and regex search.
- Snippet highlighting in preview for content hits.
- UI theme polish (e.g., BeautyEye or similar) as an optional module.
- Export/import of settings and sources.

---

## Contributing
1. Fork & branch from `main`.
2. Build: `mvn clean package` (Java 8+).
3. Code style: keep UI strings **English**, comments **Chinese**; avoid UI blocking (use `SwingWorker` for heavy tasks).
4. Submit PR with a clear description and screenshots if UI changes are involved.

---

## License
_TBD_ (MIT/Apache‑2.0/Proprietary — choose one and add the license fil
