# Task 3 - Stabilize Preview Rendering

## Goal
Ensure the preview pane reliably shows content or an explicit fallback whenever a search result is selected.

## Current Understanding
- MainWindow#loadPreviewAsync spawns a new SwingWorker for every selection change without cancelling earlier workers.
- The worker reads up to 60k characters via extractTextHead, builds a snippet, and updates the JEditorPane on completion.
- Rapid row changes or slow parsers may allow an older worker to finish last, overwriting the preview with stale or empty output.
- Binary or oversized files currently produce a generic "No text content" message; we should clarify the reason when possible.

## Plan
1. Introduce a cancellable preview worker handle; cancel the previous worker before launching a new one and ignore the callback if the selection changed.
2. Propagate more context from extractTextHead (e.g., parse timeout, binary detection) so the preview can explain why content is unavailable.
3. Guard UI updates with SwingUtilities.invokeLater and null-check the selection row to avoid races when results are refreshed mid-load.
4. Add minimal caching for the currently visible row to avoid re-reading files when the user toggles between preview and back.
5. Verify preview behavior with text, binary, large, and folder results (after Task 2) to ensure meaningful messaging.

## Validation
- Switching rows quickly never leaves the preview blank; it either shows the correct snippet or a descriptive fallback.
- Preview content aligns with the selected row even during rapid searching.
- No exceptions are logged when previewing unsupported formats.
