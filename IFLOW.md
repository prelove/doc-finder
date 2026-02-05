# DocFinder - iFlow Context Guide

## Project Overview
DocFinder is a cross-platform desktop file search utility written in Java 8, providing fast local and network file name/content search with a responsive UI. It uses Apache Lucene for indexing, Apache Tika for content extraction, and supports multilingual text analysis (English, Chinese, Japanese).

## Technology Stack
- **Language**: Java 8 (minimum requirement)
- **Build Tool**: Maven 3.6+
- **UI Framework**: Swing with FlatLaf theme library
- **Search Engine**: Apache Lucene 8.11.2
- **Content Extraction**: Apache Tika 2.9.2
- **Global Hotkey**: JNativeHook 2.2.2
- **Multilingual Support**: SmartChineseAnalyzer + Kuromoji (Japanese)

## Architecture
```
src/main/java/org/abitware/docfinder/
├── App.java                    # Main entry point, tray icon, global hotkey
├── index/                      # Indexing and source management
│   ├── ConfigManager.java      # Configuration persistence
│   ├── IndexSettings.java      # Index configuration
│   ├── LuceneIndexer.java      # Core indexing logic
│   └── SourceManager.java      # Source folder management
├── search/                     # Search functionality
│   ├── LuceneSearchService.java # Main search implementation
│   ├── SearchService.java      # Search interface
│   ├── SearchRequest.java      # Query request object
│   ├── SearchResult.java       # Result data structure
│   └── SearchHistoryManager.java # Query history management
├── ui/                         # User interface
│   ├── MainWindow.java         # Primary application window
│   ├── ManageSourcesDialog.java # Source management dialog
│   ├── GlobalHotkey.java       # System-wide hotkey handler
│   ├── IconUtil.java           # Icon management
│   └── ThemeUtil.java          # Theme switching
├── util/                       # Utility classes
│   ├── SingleInstance.java     # Single instance enforcement
│   └── Utils.java              # General utilities
└── watch/                      # File system monitoring
    ├── LiveIndexService.java   # Real-time indexing coordination
    ├── LocalRecursiveWatcher.java # Local folder monitoring
    ├── NetPollerService.java   # Network folder polling
    └── SnapshotStore.java      # Network share snapshots
```

## Key Features
- **Multilingual Search**: Supports English, Chinese, and Japanese text analysis
- **Wildcard Filename Queries**: `name:*.xlsx`, `name:report-??.pdf`
- **Content Search**: Full-text search with field-specific queries (`content:`, `name:`)
- **Live Updates**: Real-time monitoring for local folders, polling for network shares
- **Global Hotkey**: System-wide hotkey (Ctrl+Alt+Space) to toggle window
- **System Tray**: Background operation with tray icon menu
- **Read-only Indexing**: Never modifies file timestamps or content

## Build and Run Commands

### Build
```bash
mvn clean package
```

### Run
```bash
# From command line
java -jar target/docfinder-1.0.0.jar

# Windows batch script
run-docfinder.bat
```

### Development
```bash
mvn test                    # Run tests
mvn compile                 # Compile only
mvn clean install          # Install to local repository
```

## Configuration and Data
- **Index Directory**: `~/.docfinder/index/`
- **Sources File**: `~/.docfinder/sources.txt` (format: `path|0/1` where 1=Network)
- **Settings**: `~/.docfinder/` directory
- **JVM Options**: `-Xms256m -Xmx1024m -Dfile.encoding=UTF-8`

## Code Conventions
- **UI Language**: English strings only
- **Code Comments**: Chinese comments in source code
- **Package Structure**: Feature-based packages (index, search, ui, util, watch)
- **Naming**: PascalCase for classes, camelCase for methods/fields, UPPER_SNAKE_CASE for constants
- **Indentation**: 4 spaces, braces on same line
- **Single Class Per File**: Each public class in its own file

## Search Syntax
- **Basic**: `keyword` - searches filename and content
- **Filename Only**: `name:report*` or `name:"exact name.pdf"`
- **Content Only**: `content:"search phrase"`
- **Wildcards**: `name:*.pdf`, `name:file-??.txt`
- **Fielded**: Combine with boolean logic, field-specific searches
- **Filters**: Extension and modification time range filters

## Development Workflow
1. **Single Instance**: App enforces single instance via port-based locking
2. **Background Operations**: All heavy operations (indexing, searching) run on background threads
3. **UI Thread Safety**: SwingUtilities.invokeLater for UI updates
4. **Error Handling**: Graceful degradation with timeouts and fallbacks
5. **Cross-platform**: Handles Windows path quirks (UNC, mapped drives, yen sign)

## Testing Approach
- **Unit Tests**: Create under `src/test/java` mirroring main package structure
- **Test Naming**: `<ClassName>Test` with methods `shouldDescribeBehavior`
- **Coverage Focus**: Lucene operations, file watchers, UI presenters
- **Manual Testing**: Use provided task files for regression testing

## Current Development Tasks
The project includes 8 development tasks in `tasks/` directory:
1. **Task 1**: Stabilize Query Switching - Fix search result consistency
2. **Task 2-8**: Additional development tasks (see individual task files)

## Troubleshooting Notes
- **Empty Results**: Run "Rebuild Index (Full)" after schema changes
- **Network Sources**: Ensure proper network share permissions
- **Preview Issues**: Check file size limits and timeout settings
- **Path Issues**: Windows paths normalized internally (handles ¥, UNC, mapped drives)

## Performance Considerations
- **Wildcard Optimization**: `*.ext` queries automatically include `ext` filter
- **Prefix Boosting**: Short tokens get filename prefix boost
- **Memory Management**: Configurable RAM buffer for bulk indexing
- **Timeout Protection**: Content parsing with configurable timeouts
- **Background Processing**: Non-blocking UI during heavy operations
