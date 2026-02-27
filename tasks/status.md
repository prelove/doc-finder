# Tasks Progress Snapshot (2026-02-27)

## Completed / Mostly Completed
- **Task 1 - Stabilize Query Switching**: Search runs asynchronously with token-based stale-result guarding (`SearchWorker` + `searchSequence`).
- **Task 2 - Extend Search Modes and Scopes**: `SearchScope`, `MatchMode`, and folder document indexing are in place.
- **Task 5 - Relocate Persistent Data Storage**: Most runtime files already use `./.docfinder`; this round also migrates history + poll snapshots away from home directory.

## In Progress
- **Task 3 - Stabilize Preview Rendering**: Preview is async, but worker cancellation / stale preview guarding is still incomplete.
- **Task 8 - Eliminate UI Lag**: Several heavy operations are in workers, but startup/search contention still needs profiling and cleanup.

## Not Started / Pending
- **Task 4 - Backend Enhancement Evaluation (Redis/RedisSearch)**
- **Task 6 - GraalVM Native Image Preparation**
- **Task 7 - Web Interface Prototype**

## Current Step
- Hardening indexing concurrency between **local live watch** and **network polling** to reduce lock conflicts.

## Next Immediate Step
1. Add explicit stale-preview cancellation logic (Task 3).
2. Add one-time migration prompt from legacy `~/.docfinder` data (Task 5).
3. Add integration tests for simultaneous local+network polling/index updates (Task 8).

## Latest Improvement
- Network polling now scans roots concurrently (up to 4 threads) and then performs a single serialized Lucene write pass, reducing lock hold time while preserving index consistency.
