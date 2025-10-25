# Task 8 - Eliminate UI Lag During Startup and Search

## Goal
Identify and remove UI freezes that occur during application startup and while executing searches so menus and controls stay responsive.

## Current Understanding
- Startup initializes indexing services, settings managers, and tray/hotkey hooks on the EDT; long-running work there can block Swing repainting.
- Searches, previews, and background indexing currently contend for shared executors, sometimes running on the EDT and stalling interactions (menus, combo boxes).
- Some tasks fire immediately at launch (tray creation, history load, icon preload) without deferring to background threads.

## Plan
1. Profile startup with Swing EDT diagnostics (e.g., -Dsun.java2d.trace, VisualVM sampler) to identify blocking calls before the main window renders.
2. Audit MainWindow initialization and other UI constructors to ensure expensive work moves off the EDT via SwingWorker or background executors.
3. Review background services (index rebuilds, preview loaders, watchers) for thread contention; introduce bounded executors and queue limits to prevent starvation.
4. Add lightweight progress indicators or status messages when unavoidable background tasks run so the UI communicates ongoing work.
5. Re-test startup and rapid-search scenarios, verifying that menus remain clickable and the UI paints smoothly; capture before/after timing notes.

## Validation
- Application launches to an interactive window without multi-second freezes; menu bar responds immediately.
- Repeated searches avoid UI hangs even under indexing load.
- Thread dumps show no prolonged EDT blocking operations.
