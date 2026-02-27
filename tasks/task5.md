# Task 5 - Relocate Persistent Data Storage

## Goal
Store index, configuration, and history files inside an application-controlled directory instead of the user's home folder to prevent bloat in system profiles.

## Current Understanding
- SourceManager and ConfigManager hard-code the base directory to ~/.docfinder.
- Installations ship as portable jars or exe wrappers, so we can provision a sibling data directory (e.g., DocFinderData) relative to the executable or a configurable path.
- Existing installs may already have indexes in the old location; we need a migration strategy or a one-time rebuild prompt.

## Plan
1. Introduce a DataDirectories utility that resolves the data root (defaulting to <appRoot>/data with overrides via environment variable or config file).
2. Update SourceManager, ConfigManager, indexer, history manager, and any other file IO to use the new utility.
3. On startup, detect legacy data under ~/.docfinder; offer to migrate files or trigger a rebuild into the new location.
4. Ensure directories are created with appropriate permissions and handle cross-platform paths (Windows UNC, macOS sandbox, Linux XDG).
5. Refresh documentation (README, UsageGuide, AGENTS) and packaging scripts to reference the new storage path.

## Progress Update (2026-02-27)
- ✅ `AppPaths` has been introduced and most runtime data already writes under `./.docfinder`.
- ✅ `SourceManager` and `ConfigManager` are already on `AppPaths`.
- ✅ This update moves remaining runtime artifacts (`history.txt`, network poll snapshots) from `~/.docfinder` to `./.docfinder`.
- ⚠️ Legacy migration from `~/.docfinder` is still pending; we currently start fresh in the new location if old files exist only in home.

## Next Step
1. Add one-time migration logic from `~/.docfinder` to `./.docfinder` (copy + conflict handling + rollback messaging).
2. Add a small startup notice to confirm the active runtime data directory.
3. Add tests for path resolution and migration scenarios.

## Validation
- Fresh installs write all data under the new directory and leave the home directory untouched.
- Migration preserves index state or triggers a controlled rebuild without errors.
- Unit or integration tests (where feasible) cover the new path resolution logic.
