# Preview System Migration Plan

## Overview
Migrating from kkFileView + jitViewer to vue-office + Highlight.js for document preview.

## Implementation Strategy

### Phase 1: Backend (✅ Complete)
- ✅ Removed kkFileView Java code
- ✅ Removed jitViewer files
- ✅ Cleaned up configuration
- ✅ Updated WebServer endpoints

### Phase 2: Frontend Preview System (✅ Complete)

#### Technology Stack
- **Native Browser APIs** - for maximum compatibility and offline operation
  - Native PDF rendering via iframe
  - Native image display via img element
  - Custom text viewer with charset detection (UTF-8, GBK, windows-1252, UTF-16)
  - Simple built-in syntax highlighting using regex patterns

#### Supported File Formats

| Format | Technology | Status |
|--------|------------|--------|
| PDF | Native iframe | ✅ Supported |
| Text/Code (50+ formats) | Custom viewer + syntax highlighting | ✅ Supported |
| Images (JPG, PNG, GIF, etc.) | Native img element | ✅ Supported |
| Office (DOCX/XLSX/PPT) | - | ❌ Download only |
| Old Office (DOC/XLS/PPT) | - | ❌ Download only |
| OFD, CAD, Archives | - | ❌ Download only |

#### File Type Classification

```javascript
// Supported preview formats
const TEXT_EXTS = ['txt','log','md','markdown','csv','tsv','json','jsonl','xml','yaml','yml',
                   'toml','ini','cfg','conf','properties','sh','bash','zsh','bat','cmd','ps1',
                   'py','pyw','js','ts','jsx','tsx','java','kt','go','rs','c','cpp','h','hpp',
                   'cs','vb','rb','php','html','htm','css','scss','sass','less','sql','r',
                   'gitignore','dockerfile','makefile','gradle','mvnw','lock','editorconfig',
                   'gitattributes','eslintrc','babelrc','prettierrc','env'];

const IMAGE_EXTS = ['jpg','jpeg','png','gif','bmp','svg','webp','ico'];

// Unsupported formats - download only
const DOWNLOAD_ONLY = ['ofd','dwg','dxf','zip','rar','7z','tar','gz','bz2','xz',
                       'mp4','avi','mkv','mov','wmv','flv','webm','mp3','wav','flac','aac',
                       'doc','xls','ppt','docx','xlsx','pptx','exe','dll','so','dylib','bin','dat'];
```

#### Self-Contained Implementation

No external dependencies required! All code is embedded in the HTML files:
- Inline CSS for theming and styling
- Inline vanilla JavaScript (ES5 compatible)
- Native browser APIs only
- Works completely offline

#### Preview Logic Flow

```javascript
function loadPreview(filePath, fileName) {
  var ext = getFileExtension(fileName);

  try {
    // 1. Check if format is supported
    if (isTextFile(ext)) {
      loadTextPreview(filePath, fileName, ext);
    } else if (isImageFile(ext)) {
      loadImagePreview(filePath, fileName);
    } else if (ext === 'pdf') {
      loadPdfPreview(filePath, fileName);
    } else if (isDownloadOnly(ext)) {
      showDownloadOnlyMessage(fileName, ext);
    } else {
      showUnsupportedMessage(fileName, ext);
    }
  } catch (error) {
    console.error('Preview error:', error);
    showErrorMessage(error, fileName);
  }
}
```

#### Error Handling

All preview functions should:
1. Catch and log errors with full details (filename, format, error message)
2. Show user-friendly error messages in the preview pane
3. Always offer download option as fallback
4. Log to console for debugging

```javascript
function showErrorMessage(error, fileName) {
  previewPane.innerHTML = `
    <div class="error-message">
      <h3>Preview Failed</h3>
      <p>Could not preview file: <strong>${escapeHtml(fileName)}</strong></p>
      <p class="error-details">${escapeHtml(error.message)}</p>
      <button onclick="downloadFile()">Download File</button>
    </div>
  `;
  console.error('Preview error:', {
    fileName,
    error: error.message,
    stack: error.stack
  });
}
```

#### Unsupported Format Messages

```html
<div class="unsupported-format">
  <h3>Preview Not Available</h3>
  <p>The file format <strong>.${ext}</strong> is not supported for preview.</p>
  <p>Supported formats: PDF, DOCX, XLSX, and text/code files.</p>
  <button onclick="downloadFile()">Download File</button>
</div>
```

## Implementation Steps (✅ All Complete)

### Step 1: Update index.html ✅
- ✅ Removed jit-viewer and kkFileView JavaScript code
- ✅ Implemented self-contained preview system with inline CSS/JS
- ✅ Added preview router logic with format detection
- ✅ Added comprehensive error handling with friendly messages
- ✅ Preserved all original functionality (search, theme, share, history)

### Step 2: Update preview.html ✅
- ✅ Rewrote with same self-contained preview system
- ✅ Simplified for standalone preview page usage
- ✅ Consistent behavior with index.html

### Step 3: Testing ✅
- ✅ Compilation successful (mvn clean compile)
- Ready for runtime testing with various file formats

### Step 4: Documentation
- ✅ Updated PREVIEW_MIGRATION.md with implementation details
- Future: Update README.md with usage examples

## Migration Benefits

1. ✅ **No external server required** - everything runs in browser
2. ✅ **No license issues** - no third-party dependencies
3. ✅ **Offline-first** - works without internet access (critical requirement met)
4. ✅ **Simpler deployment** - no external downloads or setup
5. ✅ **Faster startup** - no JVM subprocess or kkFileView server
6. ✅ **Lower memory usage** - no separate process
7. ✅ **Better compatibility** - uses native browser APIs
8. ✅ **Self-contained** - all code embedded in HTML files

## Known Limitations

1. ❌ Office formats (.doc, .docx, .xls, .xlsx, .ppt, .pptx) not supported for preview (download only)
2. ❌ Specialized formats (OFD, CAD, etc.) not supported (download only)
3. ⚠️ Text files limited to 512KB for preview (larger files require download)
4. ⚠️ PDF preview quality depends on browser's built-in PDF viewer
5. ⚠️ Requires modern browser with JavaScript enabled

## Fallback Strategy

For all unsupported or failed previews:
- Show clear message about why preview failed
- Always offer download button
- Log detailed error info to console for debugging

## Technical Implementation Details

### Charset Detection
Multi-step detection with fallback chain:
1. BOM detection (UTF-8, UTF-16LE, UTF-16BE)
2. UTF-8 with fatal flag (validates encoding)
3. GBK (Chinese encoding)
4. windows-1252 / ISO-8859-1 fallback

### Syntax Highlighting
Simple regex-based highlighting for:
- Keywords (function, class, const, let, var, if, else, etc.)
- Strings (single and double quotes)
- Comments (//, #, <!-- -->)
- Numbers (integers and decimals)

### File Size Limits
- Text files: 512KB preview limit
- Uses ReadableStream API for efficient streaming
- Shows truncation notice if file exceeds limit

### Error Handling
Three message types:
1. **Error messages** - for failed previews (network, parse errors)
2. **Unsupported format** - for file types with no preview support
3. **Download only** - for formats that cannot be previewed (archives, media, Office docs)
