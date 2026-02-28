# Tasks Progress Snapshot (2026-02-28)

## Completed
- **Task 1 - Stabilize Query Switching**: Search runs asynchronously with token-based stale-result guarding (`SearchWorker` + `searchSequence`).
- **Task 2 - Extend Search Modes and Scopes**: `SearchScope`, `MatchMode`, and folder document indexing are in place. `SearchBarPanel` exposes scope + mode dropdowns.
- **Task 3 - Stabilize Preview Rendering**: Worker cancellation + stale-callback guard are done; fallback reasons distinguish timeout / binary / unsupported-empty cases; per-selection preview cache added; configurable `previewTimeoutSec` now exposed in Indexing Settings and wired into `extractTextHead()`.
- **Task 5 - Relocate Persistent Data Storage**: `AppPaths` + `./.docfinder` fully in use; `LegacyMigration` utility performs one-time copy of `~/.docfinder` → `./.docfinder` at startup (background thread).
- **Task 8 - Eliminate UI Lag**: `LuceneSearchService` init moved off the EDT; main window shows immediately and receives the search service via `setSearchService()` once the index opens.

## Bug Fixes Applied
- **Compilation error**: `FilterState.mtimeFrom/mtimeTo` → `fromEpochMs/toEpochMs` in `showSearchPropertyDialog`.
- **NPE**: `liveWatchToggle`/`netPollToggle` fields in `MainWindow` were never assigned. Fixed by exposing them from `MenuBarPanel` via getters and wiring in `buildMenuBar()`.
- **Resource leaks**: `LuceneIndexer` not closed in `indexAllSources()`, `chooseAndIndexFolder()`, and all `IndexingManager` methods. Fixed with try-with-resources.

## Tests Added
- JUnit 5 added to `pom.xml` with surefire plugin.
- 18 unit tests covering `FilterState`, `SearchRequest`/`SearchScope`/`MatchMode`, `AppPaths`, `LegacyMigration`.

## Not Started / Pending (Lower Priority)
- **Task 4 - Backend Enhancement Evaluation (Redis/RedisSearch)**: Research/documentation task.
- **Task 6 - GraalVM Native Image Preparation**: Build tooling task.
- **Task 7 - Web Interface Prototype**: Lowest priority.

## Next Steps (if desired)
1. Task 4: Write a concise benchmark/recommendation document comparing Lucene vs RedisSearch.
2. Task 6: Add GraalVM `reflect-config.json` and a `native` Maven profile.
3. Task 7: Prototype a lightweight embedded HTTP server (Undertow/Jetty) with a REST search endpoint.
