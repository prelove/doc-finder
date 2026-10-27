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
DocFinder is a desktop utility that lets you search **file names and file contents** with a responsive, Everything‑style UI. It uses **Lucene** for indexing/search, **Tika** for content extraction, and adds **SmartChinese** / **Kuromoji** analyzers to handle Chinese and Japanese text well. Source extraction opens files read-only and does not deliberately rewrite content or modification time; the OS/NAS may update access time when files are read. Extraction uses timeouts and size limits.

---

## Key Features
- **Search**
  - Recursive directory scopes inside configured sources, with source selection, absolute path entry, Browse/Clear and result shortcuts. Only indexed content is searched; FOLDER can match the selected directory itself.
  - Free text across **filename** and **content** (multi‑field query).
  - Fielded search: `name:` (filename‑only), `content:` (content‑only).
  - **Wildcard filename** queries: `name:*.xlsx`, `name:report-??.pdf`, `name:"チェックリスト_07.xlsx"`.
  - Leading wildcards enabled (e.g., `*.xlsx`).
  - Filters by **extension** and **modified time**.
  - **Multilingual**: English + Chinese + Japanese analyzers.
  - **Query history**: last 100 queries persisted (dropdown).

- **Results UI**
  - Columns: **Name**, **Path**, **Score**, **Created**, **Last Accessed**, **Size**, **Match** (`name` / `content` / `name + content`).
  - Preview panel (read‑only; smaller font), with a top-right toggle to show/hide.
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
- **Index**: Lucene index stored under `./.docfinder/index`. A single writer used per operation; multi‑root indexing iterates sources.
- **Extraction**: Apache Tika with an executor + timeout (future cancel). Streams opened with `StandardOpenOption.READ` only.
- **Watchers**:
  - Local: Java NIO `WatchService` for create/modify/delete; coalesced and processed asynchronously.
  - Network: polling scheduler; snapshot/diff; back‑pressure to avoid UI blocking.
- **Persistence**: sources list `./.docfinder/sources.txt` (`path|0/1`), query history, and app settings stored under `./.docfinder/`.

---

## Index Schema & Analyzers
**Lucene fields (per file):**
- `path` — `StringField`, **normalized**, stored. Primary key. All updates/deletes use the normalized value.
- `name` — `TextField`, stored. For analyzed filename matching and boosting.
- `path_scope` — indexed-only normalized directory boundary; original `path` remains the identity/display value.
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
- Scopes: ALL searches file names and content; NAME searches file names; CONTENT searches content; FOLDER searches folder names. ALL and NAME exclude folders.
- Ordinary FUZZY filename input is matched literally against the raw name: middle fragments (`report` → `AnnualReport2026.PDF`), single characters, CJK and symbols all match without wildcards. Multiple words must all appear in the name. Name hits rank above content hits in ALL (exact name > prefix > substring tiers). Typing is debounced 250ms; truncation is reported as "Showing first N results" when more matches exist.
- EXACT combines a raw full-name term and analyzed phrase matching for the selected fields; it is not exclusively full-filename equality. Explicit field/wildcard syntax retains its own path.
- Directory scopes include descendants and require a configured source. Search does not scan or add a source. A path draft takes effect on Enter/selection; Applied shows the submitted scope.
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
  - `textMaxBytes` applies to all text-like parsing (including MIME/heuristic-detected text files with uncommon extensions).
  - `maxExtractChars` controls maximum extracted characters per file before truncation.
  - MIME check (`text/*`, common `application/*`) and **4KB heuristic**: no NUL and ASCII printable ratio ≥ 0.85.

---

## Sources: Local vs Network
- **Storage**: `./.docfinder/sources.txt` with lines of `path|0/1` (`1 = Network`). Old single‑column files are auto‑upgraded.
- **Detection** (Windows): tries PowerShell `Get-PSDrive`, `net use`, `wmic`, with caching; falls back to `FileStore` type and UNC prefixes. Mapped drives (`J:\`, `M:\`) are treated as Network when resolved to remote targets.
- **Live Watch**: enabled only for **Local** sources (uses NIO `WatchService`). On by default at every launch (a session uncheck is not persisted); when no local source exists or the index is not built yet, the status bar shows a waiting state and it resumes automatically once they are ready; it pauses during a full index rebuild and resumes per intent.
- **Network Polling**: enabled only for **Network** sources; background snapshots + diffs; `Poll Network Sources Now` is async and updates the status bar when done.

---

## UI & UX
- **Search field** with placeholder hint (`Search…  (e.g. report*, content:"zero knowledge", name:"設計")`) and query history (100 recent, persisted).
- **Results table**: click a column to cycle ascending → descending → engine order, or use **Restore default sort**. Restoring preserves the selected result and preview without searching again. New result sets start in engine order; **preview** pane on the right.
- **Menu**
  - `File → Manage Sources…` (manage sources with Local/Network type)
  - `File → Index All Sources` and `File → Rebuild Index (Full)`
  - `File → Indexing Settings…`
  - `File → Enable Web Server` (toggle web interface, default port 7070)
  - `File → Web Server Port…` (change the listen port; occupied ports are rejected before any change, a running server restarts on the new port first and the old one stops only after the new one is up)
  - `Help → Usage Guide`, `Help → About DocFinder`
- **Tray icon** with context menu; **Global hotkey** toggles window.
- **Icons**: load multi‑size PNGs from `src/main/resources/icons/`; Taskbar/Dock icon set via `Taskbar` (Java 9+) or `com.apple.eawt` on macOS.

### Web Interface
- **Built-in web server** provides browser-based access (default: `http://localhost:7070`; port configurable via `File → Web Server Port…`)
- **Self-contained preview system** for viewing files directly in the browser:
  - **PDF files**: Native browser PDF viewer (via iframe)
  - **Text/Code files**: 50+ formats with syntax highlighting (txt, log, md, json, xml, yaml, py, js, java, go, rs, c, cpp, sh, etc.)
  - **Images**: JPG, PNG, GIF, BMP, SVG, WebP, ICO
  - **Charset detection**: Automatic detection for UTF-8, GBK, windows-1252, UTF-16
  - **File size limits**: 512KB preview limit for text files
  - **Download fallback**: All unsupported formats (Office docs, archives, media) provide download button
- **Features**:
  - Dark/light theme toggle
  - Resizable split pane
  - Search history
  - Share links to files
  - Completely offline (no external dependencies)
- **Supported preview formats**:
  - ✅ PDF (native browser viewer)
  - ✅ Text/code files (50+ extensions with syntax highlighting)
  - ✅ Images (common formats)
  - ❌ Office documents (.doc, .docx, .xls, .xlsx, .ppt, .pptx) - download only
  - ❌ Specialized formats (OFD, CAD, archives, media files) - download only

---

## Build & Run

### Requirements
- Java **8+**
- Maven **3.6+**

### Build
```bash
mvn clean package
```

This command compiles the application, runs the Maven test suite, and creates
the executable shaded JAR in `target/`. To run the tests without packaging:

```bash
mvn test
```

Maven runs the tests with their working and home directories under `target/`
to isolate test data from your normal DocFinder data.

Some tests are platform-specific or require a graphical display and may be
skipped when their prerequisites are unavailable. For desktop tests in a
headless Linux environment, use a Java installation with graphical support,
an X server such as Xvfb, and a window manager such as Openbox; run Maven with
`DISPLAY` set to that server. A virtual display supports automated tests but
does not by itself provide an interactive remote desktop.

### Run
```bash
# Executable shaded JAR
java -jar target/docfinder-1.0.0.jar
```

> Or launch from IDE. The app uses `./.docfinder` for its runtime data.

### Maven dependencies

The project uses Lucene for search, Tika for content extraction, FlatLaf for the
UI, and JNativeHook for the global hotkey. See [pom.xml](pom.xml) for the
complete dependency list and exact versions.

---

## Configuration
`IndexSettings` main fields:
- `maxFileMB` — parse size cap
- `parseTimeoutSec` — per‑file Tika timeout
- `maxExtractChars` — max extracted characters per file before truncation
- `includeExt` — document‑type allowlist (e.g., pdf, docx, xlsx, pptx, html)
- `parseTextLike` — parse text‑like files
- `textMaxBytes` — max bytes for text-like files (including unknown extensions detected by MIME/heuristics)
- `textExts` — text/source extensions (txt, md, json, yaml, xml, java, go, rs, py, js, ts, sh, properties…)
- `excludeGlob` — glob patterns to skip (e.g., `**/.git/**`, `**/node_modules/**`)

`ConfigManager` web server settings:
- `web.enabled` — enable/disable web server (default: false)
- `web.port` — web server port (default: 7070)
- `web.bindAddress` — bind address (default: 127.0.0.1)

---

## Data Locations

Paths are relative to the JVM working directory (`user.dir`), which may differ from the JAR directory. `index.dir` can override the index location. Startup can copy legacy data from `~/.docfinder`; it is not the current default.
- **Index**: `./.docfinder/index/`
- **Sources**: `./.docfinder/sources.txt` (`path|0/1` where `1 = Network`)
- **History**: `./.docfinder/history.txt`; **settings**: `./.docfinder/config.properties`
- **Logs**: `./.docfinder/logs/docfinder.log`
- **Web shares**: `./.docfinder/shares.properties` (when using web interface)

---

## Troubleshooting
- **`name:チェックリスト_07.xlsx` returns nothing** → reindex with **Rebuild Index (Full)** to ensure all docs have `name_raw`.
- **"Manage Sources…" seems slow** → detection runs in background; upgrade to latest build if you still see blocking.
- **"Poll Network Sources Now" shows no changes** → verify the folder is marked **Network** and reachable; NAS may reflect updates with delay.
- **Preview empty in desktop app** → file too large/timeout/unsupported; increase timeout or extend allowlist.
- **Web preview not working** → ensure web server is enabled (`File → Enable Web Server`); check browser console for errors; verify file permissions.
- **Web preview shows encoding issues** → charset detection attempts UTF-8, GBK, windows-1252, and UTF-16; some rare encodings may not display correctly.
- **Large text files don't preview** → files over 512KB are truncated for browser performance; download the file to view completely.
- **POI WARN logs (`XSSFDrawing` / `DataFormatter`)** → usually non-fatal format warnings from Office parsing; indexing can still succeed. Default logging suppresses these noisy categories.
- **Windows yen sign (¥)** → internal normalization handles it; opening uses Explorer‑friendly paths.

---

## Performance Notes
- The launcher sets `-Xms256m -Xmx2048m`; use a suitable 64-bit JVM and lower the ceiling on constrained systems. A larger heap does not fix native mmap/system commit exhaustion; check commit/pagefile capacity.
- Old scoped indexes: try Index All Sources first. Deleted/removed/offline-source legacy records can still require a backed-up manual Full Rebuild, which is non-atomic and unavailable for search while running.
- Wildcard `*.ext` is accelerated with an `ext` MUST clause.
- Prefix boosting improves relevance for short filename tokens.
- Index writer RAM buffer is bounded at 32 MB; source metadata and content extraction use reusable helper JVMs with deadlines.
- Content extraction uses timeouts and short‑circuit heuristics to skip obvious binaries.

---

## Roadmap
- A: directory-scoped implementation and automated remediation verified; the user confirmed latest manual acceptance fully successful. Accepted specifications are synchronized and the completed change is archived.
- B: `improve-name-search-experience` accepted on 2026-10-25 and archived; its implementation and subsequent fixes are integrated into `main`.
- C: deleted-result filtering/refill, persistent restoration, automatic desktop refresh, default Local Watch, streaming polling and configurable Web port are implemented. See [current integration status](docs/analysis/2026-10-25-main-closeout.md) for verification and remaining platform limits. Automatic refresh preserves desktop sort/selection/drafts; a new query resets sorting.
- C2: independently queued content enrichment and persisted parse-status presentation remain future work; failed extraction currently retries on later indexing rounds.
- D: selected Java8-compatible dependency upgrades implemented; supported newer-Java stacks and native validation remain separate.
- [Product sequence](docs/roadmap.md) and [delivery status](openspec/changes/archive/2026-10-24-add-directory-scoped-search/delivery-status.yaml) define current gates.

---

## Contributing
1. Fork & branch from `main`.
2. Build: `mvn clean package` (Java 8+).
3. Code style: keep UI strings **English**, comments **Chinese**; avoid UI blocking (use `SwingWorker` for heavy tasks).
4. Submit PR with a clear description and screenshots if UI changes are involved.

---

## License
_TBD_ (MIT/Apache‑2.0/Proprietary — choose one and add the license fil


- **Logs**: unified runtime logs are written to `./.docfinder/logs/docfinder.log`, and can be opened from **Help → View Log**.
