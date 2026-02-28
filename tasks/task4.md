# Task 4 - Evaluate Full-Text Backend Enhancements

## Goal
Assess whether integrating Redis and RedisSearch would improve search performance versus continuing to optimize the existing Lucene and Tika stack.

## Current Understanding
- Lucene already supports content and filename queries with analyzers tuned for CJK languages.
- Packaging currently ships as a standalone jar or exe with all dependencies embedded; introducing Redis would add an external service footprint.
- The team reports sufficient RAM and is open to off-heap memory usage, so Lucene's configurable caching and memory-mapped directories may suffice.
- Startup time and query latency are more critical than distributed deployments in the current desktop use case.

## Plan
1. Capture baseline metrics: index size, query latency for common workloads, incremental update cost, and preview responsiveness with Lucene.
2. Review RedisSearch capabilities (highlighting, incremental indexing, resource usage) and outline what embedding Redis locally would entail (packaging, service lifecycle, Windows support).
3. Prototype configuration changes to Lucene (e.g., DirectoryReader warming, RAMDirectory for hot segments, tuned analyzers) and compare with RedisSearch expectations.
4. Document trade-offs, including operational complexity, memory consumption, deployment impact, and feature parity (wildcards, history, analyzers).
5. Recommend whether to stay with Lucene plus targeted optimizations or pursue a Redis-backed mode, and list follow-up performance tasks.

## Progress Update (2026-02-28)
- ✅ Evaluation report written to `docs/lucene-vs-redissearch.md`.
- ✅ Recommendation: stay with Lucene; RedisSearch introduces Windows/CJK/wildcard gaps and requires an external daemon.
- ✅ Actionable Lucene optimizations (NRT reader refresh, TieredMergePolicy tuning, optional in-memory directory) documented as follow-up subtasks in the report.

## Validation
- ✅ Report delivers baseline metrics, feature-gap matrix, RedisSearch weaknesses, and a clear stay-with-Lucene decision.
- Decision documented. No Redis dependency added.
- Follow-up Lucene optimization subtasks listed in docs/lucene-vs-redissearch.md §7.
