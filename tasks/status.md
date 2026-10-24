# Tasks Progress Snapshot

## Current Snapshot (2026-10-24)

### Directory-Scoped Search change (openspec `add-directory-scoped-search`)

- **Implementation**: A0–A7 all delivered; section 11 (F-findings) and section 12 (QA24) closed; section 13 R24-01..06 remediated and committed (`8f756dd`, report `da3db49` — both pushed to origin).
- **Field verification on live data**: user's scoped search returned empty — root cause was the production index predating the `path_scope` field (8672 docs, 0 scope terms), plus a mid-indexing JVM OOM crash (`hs_err_pid9580.log`, native mmap failure at -Xmx1024m). Full rebuild on the current build resolved it; scoped search confirmed working end-to-end.
- **Cleanup this round** (local commits, unpushed): `824fb4d` remove dead `ui.workers.SearchWorker`/`SearchExecutor` that silently dropped `directoryPath`; `748f297` remove dead `IndexingManager`; `a0b6caf` launcher heap `-Xmx2048m` headroom for index runs.
- **Verification**: `mvn test` green on `a0b6caf` — 169 tests / 0 failures / 3 platform skips.

### Open items / next step

1. **Codex independent reverify** of R24 fixes on the new HEAD (`a0b6caf`, includes cleanup commits) — delivery-status `next_action` owner: Codex.
2. **M-01~M-06 host acceptance** still open (runbook: `openspec/changes/add-directory-scoped-search/evidence/M-Acceptance-Runbook.md`); user's manual testing already exercised scoped search successfully but formal records are pending.
3. **A8**: user acceptance + delivery authorization (push/PR/archive) — 3 local commits ahead of `origin/main`.
4. `modernize-java-dependencies`: implemented, R24-06 wording resolved; pending combined acceptance with the scoped-search change.
5. Housekeeping: `jerr4.txt` is untracked junk in repo root (decide delete vs ignore); `.playwright-mcp/` and `hs_err_pid*.log` already gitignored.

### Roadmap candidates (per `openspec/.../roadmap.md`)

- **B** improve-name-search-experience (substring name match, debounce, ranking) — next proposed change, not yet created.
- **C1/C2** index consistency & watcher lifecycle; **D** Java/dependency modernization — separate changes, user picks priority.

---

## Historical Snapshot (2026-02-28)

## Completed
- **Task 1 - Stabilize Query Switching**: Search runs asynchronously with token-based stale-result guarding (`SearchWorker` + `searchSequence`).
- **Task 2 - Extend Search Modes and Scopes**: `SearchScope`, `MatchMode`, and folder document indexing are in place. `SearchBarPanel` exposes scope + mode dropdowns.
- **Task 3 - Stabilize Preview Rendering**: Worker cancellation + stale-callback guard are done; fallback reasons distinguish timeout / binary / unsupported-empty cases; per-selection preview cache added; configurable `previewTimeoutSec` now exposed in Indexing Settings and wired into `extractTextHead()`.
- **Task 4 - Evaluate Full-Text Backend Enhancements (Redis/Lucene)**: Evaluation report written to `docs/lucene-vs-redissearch.md`. Decision: stay with Lucene. Actionable Lucene optimizations (NRT reader refresh, TieredMergePolicy, optional in-memory directory) documented as follow-up subtasks.
- **Task 5 - Relocate Persistent Data Storage**: `AppPaths` + `./.docfinder` fully in use; `LegacyMigration` utility performs one-time copy of `~/.docfinder` → `./.docfinder` at startup (background thread).
- **Task 6 - GraalVM Native Image Preparation**: `reflect-config.json`, `resource-config.json`, and `jni-config.json` added under `META-INF/native-image/`; `native` Maven profile added to `pom.xml`. Build instructions documented in `tasks/task6.md`.
- **Task 7 - Web Interface Prototype**: Embedded `com.sun.net.httpserver` web server added (`web` package). REST endpoints `/api/search` and `/api/preview`; dark-themed HTML UI at `/`. Disabled by default (`web.enabled=false` in `config.properties`).
- **Task 8 - Eliminate UI Lag**: `LuceneSearchService` init moved off the EDT; main window shows immediately and receives the search service via `setSearchService()` once the index opens.

## Bug Fixes Applied
- **Compilation error**: `FilterState.mtimeFrom/mtimeTo` → `fromEpochMs/toEpochMs` in `showSearchPropertyDialog`.
- **NPE**: `liveWatchToggle`/`netPollToggle` fields in `MainWindow` were never assigned. Fixed by exposing them from `MenuBarPanel` via getters and wiring in `buildMenuBar()`.
- **Resource leaks**: `LuceneIndexer` not closed in `indexAllSources()`, `chooseAndIndexFolder()`, and all `IndexingManager` methods. Fixed with try-with-resources.

## Tests
- JUnit 5 added to `pom.xml` with surefire plugin.
- 18 unit tests covering `FilterState`, `SearchRequest`/`SearchScope`/`MatchMode`, `AppPaths`, `LegacyMigration`.

## Follow-up Subtasks (from Task 4 evaluation) – COMPLETED (2026-02-28)
1. ✅ **NRT reader refresh** – `SearchService.notifyIndexCommit()` added (default no-op). `LuceneSearchService` overrides it to call `searcherManager.maybeRefresh()`. `LiveIndexService` and `NetPollerService` accept an `onAfterCommit: Runnable` callback and invoke it after each commit. `MainWindow` wires `MainWindow::onIndexCommit` callback; `App.java` supplies settings-aware `LuceneSearchService`.
2. ✅ **TieredMergePolicy tuning** – `LuceneIndexer` now sets `TieredMergePolicy(maxMergedSegmentMB=512, segmentsPerTier=10)` on the `IndexWriterConfig`, reducing segment count and improving query speed after bulk/incremental indexing.
3. ✅ **NRTCachingDirectory** – New `IndexSettings.nrtCacheMaxMB = 32` field (persisted via `ConfigManager`). `LuceneSearchService(Path, IndexSettings)` wraps `FSDirectory` with `NRTCachingDirectory(delegate, 5 MB merge cap, nrtCacheMaxMB)` when the value is > 0, caching recently-written small segment files in RAM to reduce disk I/O for NRT reads.

## Tests Added (2026-02-28)
- `IndexSettingsTest` – verifies `nrtCacheMaxMB = 32` default and other defaults.
- `SearchServiceNotifyTest` – verifies default no-op and override of `notifyIndexCommit()`.

## Milestone Summary
All 8 planned tasks are now complete. The application features:
- Deterministic async search with scope/mode controls
- Stable preview rendering with stale-result guards
- Portable data storage with legacy migration
- Responsive startup (index opens off-EDT)
- GraalVM native image build foundation
- Optional localhost web interface prototype
