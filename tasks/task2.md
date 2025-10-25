# Task 2 - Extend Search Modes and Scopes

## Goal
Introduce a scope selector at the left of the search bar so users can choose combined (name + content), name-only, content-only, or folder-only searches, and pair it with an exact versus fuzzy match toggle.

## Current Understanding
- The UI currently renders only a history combo box (queryBox) and does not expose scope controls.
- LuceneSearchService hard-codes a MultiFieldQueryParser across name, content, content_zh, and content_ja fields and has no concept of directories.
- The indexer skips directory entries (the visitor returns when attrs.isDirectory() is true), so folder search requires adding directory documents.
- Exact versus fuzzy semantics will likely map to term queries versus analyzer-backed queries; we must preserve wildcard and prefix handling.

## Plan
1. Define a SearchScope enum (ALL, NAME, CONTENT, FOLDER) and a MatchMode enum (FUZZY, EXACT), and pass them through a new SearchRequest DTO consumed by SearchService.
2. Update MainWindow's top bar to add a left-aligned scope combo box and an adjacent toggle (for example a second combo or checkbox) for exact match, defaulting to ALL + FUZZY.
3. Teach the indexer to persist directory records with fields for path, name, an empty ext, a flag such as kind=folder, and skip content extraction; add migration of existing indexes or an auto-rebuild prompt.
4. Adjust Lucene query composition to honor the selected scope and match mode (term queries for exact matches, analyzer-backed queries for fuzzy) while still supporting advanced syntax when users type prefixes such as name: or content:.
5. Extend result rendering to label folder hits appropriately and ensure preview handles folders (for example disable preview or show metadata).
6. Verify with manual tests covering each scope and mode combination and update README or Usage docs with the new controls.

## Validation
- Scope selector switches results without requiring the user to rewrite queries.
- Exact mode returns only literal matches (no tokenization), while fuzzy mode behaves like current analyzer-based search.
- Folder-only mode lists indexed directories, and the UI treats them safely (open and reveal actions work on directories).
