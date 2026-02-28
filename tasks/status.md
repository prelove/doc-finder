# Tasks Progress Snapshot (2026-02-28)

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
