# Tasks Progress Snapshot

## Current Snapshot (2026-10-24)

- **Observed source/remote**: 0e8de6d at local HEAD and independently queried origin main. New local documentation/help updates are uncommitted.
- **A implementation**: R24-01..06 independently closed. JDK17 clean verify and real Java8 each 169 total /167 pass /0 fail /2 platform skips; current help-copy variant separately rerun with same result. 16 additional checks pass, including 5 watchdog injections.
- **Host acceptance**: user subsequently confirmed latest manual acceptance fully successful and asked to continue submission/delivery. PASS_USER_REPORTED; A8 accepted. Earlier NOT_EXECUTED and native-tool limits are historical observations, not current user acceptance status.
- **Dependency modernization**: selected Java8-compatible versions verified with A; remaining upstream EOL risks documented. No blanket latest/security guarantee.
- **Cleanup commits**: unused ui.workers classes removed; source references and full build checked. Launcher keeps -Xmx2048m, with corrected native-memory/commit guidance; raising Xmx is not a proven fix for the recorded native mmap failure.
- **Live-data report**: implementer reported a legacy index without path_scope and later scoped-search recovery. Codex did not scan or rebuild business data; this report is not a replacement for formal M records.
- **B proposal**: improve-name-search-experience exists, 0/22 implementation tasks. Codex CHANGES_REQUESTED (3 P1, 6 P2 plan issues); revise0.2, Claude review, user approval before coding.
- **Documentation**: README/README_CN/UsageGuide and in-app help synchronized with current scope, actual data locations/preview routes, existing EXACT semantics and future-feature boundary.
- **Housekeeping**: .playwright-mcp remains local untracked artifacts; no jerr4.txt currently reported by git. Leave unrelated/runtime artifacts untouched.

### Next actions

1. Codex completes authorized commit/push, main-spec sync and archive of accepted A/dependency changes.
2. Devin revises B proposal only against [B review](../openspec/changes/improve-name-search-experience/review.md); no B implementation permission is inferred.
3. Claude reviews revised B plan, user approves its actual fingerprint and implementation scope.
4. A8 is accepted; record actual delivery SHA and archive locations, then continue B plan revision without new implementation authorization.

Latest A evidence: [final review](../openspec/changes/add-directory-scoped-search/evidence/Codex-Final-Review-2026-10-24.md). PM handoff: [next dispatch](../openspec/changes/add-directory-scoped-search/evidence/Next-Dispatch-2026-10-24.md).

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
