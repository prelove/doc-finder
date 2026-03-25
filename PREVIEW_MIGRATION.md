# Preview System Migration Plan

## Overview
Migrating from kkFileView + jitViewer to vue-office + Highlight.js for document preview.

## Implementation Strategy

### Phase 1: Backend (✅ Complete)
- ✅ Removed kkFileView Java code
- ✅ Removed jitViewer files
- ✅ Cleaned up configuration
- ✅ Updated WebServer endpoints

### Phase 2: Frontend Preview System (In Progress)

#### Technology Stack
- **vue-office** (@js-preview packages) - for Office documents and PDF
  - `@js-preview/docx` - DOCX preview
  - `@js-preview/excel` - XLSX preview
  - `@js-preview/pdf` - PDF preview
- **Highlight.js** - for code and text files with syntax highlighting

#### Supported File Formats

| Format | Library | Status |
|--------|---------|--------|
| PDF | vue-office/pdf | ✅ Supported |
| DOCX | vue-office/docx | ✅ Supported |
| XLSX/XLS | vue-office/excel | ✅ Supported |
| Text/Code | Highlight.js | ✅ Supported |
| Old Office (DOC/XLS/PPT) | - | ❌ Download only |
| OFD, CAD, Archives | - | ❌ Download only |

#### File Type Classification

```javascript
// Supported preview formats
const OFFICE_FORMATS = ['pdf', 'docx', 'xlsx', 'xls'];
const TEXT_FORMATS = ['txt', 'log', 'md', 'markdown', 'csv', 'tsv', 'json', 'jsonl',
                      'xml', 'yaml', 'yml', 'toml', 'ini', 'cfg', 'conf', 'properties',
                      'sh', 'bash', 'zsh', 'bat', 'cmd', 'ps1', 'py', 'pyw', 'js', 'ts',
                      'jsx', 'tsx', 'java', 'kt', 'go', 'rs', 'c', 'cpp', 'h', 'hpp',
                      'cs', 'vb', 'rb', 'php', 'html', 'htm', 'css', 'scss', 'sass',
                      'less', 'sql', 'r', 'gitignore', 'dockerfile', 'makefile'];

// Unsupported formats - download only
const DOWNLOAD_ONLY = ['doc', 'xls', 'ppt', 'pptx', 'ofd', 'dwg', 'dxf', 'zip',
                       'rar', '7z', 'tar', 'gz', 'mp4', 'avi', 'mkv'];
```

#### CDN Integration

```html
<!-- Highlight.js for code/text -->
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github-dark.min.css">
<script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js"></script>

<!-- vue-office for documents -->
<script src="https://unpkg.com/@js-preview/docx@latest/dist/index.umd.js"></script>
<script src="https://unpkg.com/@js-preview/excel@latest/dist/index.umd.js"></script>
<script src="https://unpkg.com/@js-preview/pdf@latest/dist/index.umd.js"></script>
<link rel="stylesheet" href="https://unpkg.com/@js-preview/docx@latest/dist/index.css">
<link rel="stylesheet" href="https://unpkg.com/@js-preview/excel@latest/dist/index.css">
<link rel="stylesheet" href="https://unpkg.com/@js-preview/pdf@latest/dist/index.css">
```

#### Preview Logic Flow

```javascript
async function loadPreview(filePath, fileName) {
  const ext = getExtension(fileName).toLowerCase();

  try {
    // 1. Check if format is supported
    if (TEXT_FORMATS.includes(ext)) {
      await loadTextPreview(filePath, ext);
    } else if (ext === 'pdf') {
      await loadPdfPreview(filePath);
    } else if (ext === 'docx') {
      await loadDocxPreview(filePath);
    } else if (['xlsx', 'xls'].includes(ext)) {
      await loadExcelPreview(filePath);
    } else if (DOWNLOAD_ONLY.includes(ext)) {
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

## Implementation Steps

### Step 1: Update index.html
- Remove jit-viewer and kkFileView JavaScript code
- Add vue-office and Highlight.js CDN links
- Implement new preview router logic
- Add format detection and error handling
- Update viewer toggle UI (remove it, single viewer now)

### Step 2: Update preview.html
- Same changes as index.html but for standalone preview page
- Ensure consistent behavior

### Step 3: Testing
- Test each supported format
- Test unsupported format handling
- Test error scenarios (network failures, corrupt files)
- Test download functionality

### Step 4: Documentation
- Update README.md with new preview capabilities
- Create user guide for supported formats
- Document error messages

## Migration Benefits

1. ✅ **No external server required** - everything runs in browser
2. ✅ **No license issues** - all open source (Apache 2.0 / MIT)
3. ✅ **Better code preview** - syntax highlighting with 190+ languages
4. ✅ **Simpler deployment** - no JAR files to download
5. ✅ **Faster startup** - no JVM subprocess
6. ✅ **Lower memory usage** - no separate process

## Known Limitations

1. ❌ Old Office formats (.doc, .xls, .ppt) not supported
2. ❌ Specialized formats (OFD, CAD, etc.) not supported
3. ⚠️ Large files may be slow in browser
4. ⚠️ Requires modern browser with JavaScript enabled

## Fallback Strategy

For all unsupported or failed previews:
- Show clear message about why preview failed
- Always offer download button
- Log detailed error info to console for debugging
