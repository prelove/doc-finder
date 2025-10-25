# Task 1 - Stabilize Query Switching

## Goal
Restore deterministic behavior when switching search keywords so new searches supersede previous work and results populate every time.

## Current Understanding
- Users report the first query returns rows but subsequent keyword changes often yield an empty grid despite expected hits.
- MainWindow#doSearch currently runs on the EDT; long Lucene operations block the UI and compete with preview loading SwingWorkers.
- There is no cancellation or sequencing between successive searches, so late responses from an earlier query may clear or overwrite newer data.

## Plan
1. Reproduce by adding temporary logging or diagnostics around doSearch and LuceneSearchService#search to capture timing and row counts.
2. Audit combo-box and filter listeners to ensure they do not trigger duplicate clears when the popup closes.
3. Move Lucene queries onto a dedicated executor or SwingWorker, tracking a monotonically increasing request token; cancel or ignore stale workers when a new query fires.
4. Only apply results to the table when the worker's token matches the latest query, and reset lastQuery and preview state in the same guarded block.
5. Update status messaging or a lightweight progress indicator so users see when a search is running; include a regression test or manual test script.

## Validation
- Rapidly searching A -> B -> C -> A always fills the table with consistent counts.
- UI no longer jitters or freezes during search; status bar reflects active query and completion time.
- Preview pane updates correspond to the selected row of the latest result set.
