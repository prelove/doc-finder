# kkFileView Integration Guide

## Overview

DocFinder now supports integration with kkFileView as an alternative to JitViewer for file preview. kkFileView is a powerful open-source document preview solution that supports a wide variety of file formats including Office documents, PDFs, CAD files, images, videos, and more.

## Why kkFileView?

- **No Copyright Issues**: Open source with Apache 2.0 license
- **More Stable**: Production-ready document preview solution
- **Broader Format Support**: Supports 40+ file formats including Office, PDF, CAD, 3D models, etc.
- **Better Quality**: High-quality document rendering with proper pagination

## Architecture

DocFinder embeds kkFileView as a subprocess rather than importing it as a library. This approach:

1. **Avoids Classloader Conflicts**: kkFileView uses Spring Boot which could conflict with DocFinder's classpath
2. **Easier Updates**: Replace the kkFileView JAR without recompiling DocFinder
3. **Resource Isolation**: kkFileView runs in its own JVM with separate memory
4. **Clean Shutdown**: Process lifecycle is managed independently

## Setup Instructions

### Step 1: Download kkFileView JAR

You need a Java 8 compatible version of kkFileView. We recommend using the fork that maintains Java 8 compatibility:

**Option A: Build from Source (Recommended)**

```bash
# Clone the Java 8 compatible fork
git clone https://github.com/jiangchuanso/kkFileView-arm64-jdk1.8.git
cd kkFileView-arm64-jdk1.8

# Build the project
mvn clean package -DskipTests

# The JAR will be at: server/target/kkFileView-*.jar
```

**Option B: Use Pre-built JAR**

If available from the kkFileView community, download a pre-built JAR for Java 8.

### Step 2: Place kkFileView JAR

Copy the kkFileView JAR to the expected location:

```bash
# Windows
copy kkFileView-4.4.0.jar %USERPROFILE%\.docfinder\kkfileview\kkFileView.jar

# Linux/Mac
cp kkFileView-4.4.0.jar ~/.docfinder/kkfileview/kkFileView.jar
```

**Important**: The file must be named exactly `kkFileView.jar` and placed in `~/.docfinder/kkfileview/` directory.

### Step 3: Enable kkFileView in DocFinder

1. Start DocFinder
2. Go to **File → Preferences** (or similar menu - will be added)
3. Check **"Enable kkFileView Server"**
4. Configure the port (default: 8012)
5. Click **Save** and restart DocFinder

Alternatively, you can manually edit the configuration file:

```bash
# Edit ~/.docfinder/config.properties
# Add these lines:
kkfileview.enabled=true
kkfileview.port=8012
```

### Step 4: Verify Installation

After restarting DocFinder, check the logs to confirm kkFileView started:

```
INFO  kkFileView server started at http://127.0.0.1:8012
```

If you see an error like "JAR not found", double-check the file path from Step 2.

## Configuration Options

### config.properties Settings

Located at `~/.docfinder/config.properties`:

| Property | Default | Description |
|----------|---------|-------------|
| `kkfileview.enabled` | `false` | Enable/disable kkFileView server |
| `kkfileview.port` | `8012` | Port for kkFileView to listen on |

### Runtime Directories

- **JAR Location**: `~/.docfinder/kkfileview/kkFileView.jar`
- **Working Directory**: `~/.docfinder/kkfileview/`
- **File Cache**: `~/.docfinder/kkfileview/files/` (auto-created by kkFileView)

## Usage

### Web Interface

Once kkFileView is enabled, the DocFinder web interface will automatically use it for document preview:

1. Start DocFinder with web interface enabled
2. Open browser to `http://localhost:7070` (or your configured port)
3. Search for files
4. Click on a document to preview - kkFileView will render it

### API Endpoint

DocFinder proxies kkFileView requests through the web server:

```
GET /api/kkfileview/onlinePreview?url=<encoded-file-url>
```

Example:
```bash
curl "http://localhost:7070/api/kkfileview/onlinePreview?url=http://example.com/document.docx"
```

## Supported File Formats

kkFileView supports 40+ formats including:

### Office Documents
- Microsoft Office: doc, docx, xls, xlsx, ppt, pptx
- OpenOffice/LibreOffice: odt, ods, odp
- WPS Office: wps, et, dps

### Documents
- PDF, OFD (Chinese standard)
- RTF, TXT, Markdown

### CAD Files
- DWG, DXF, DWF

### Images
- JPG, PNG, GIF, BMP, TIFF, SVG, WebP

### Archives
- ZIP, RAR, 7Z, TAR, GZIP

### Others
- HTML, XML, JSON
- Video: MP4, AVI, MKV, etc.
- 3D Models: STL, OBJ, etc.

See [kkFileView documentation](https://github.com/kekingcn/kkFileView) for the complete list.

## Troubleshooting

### kkFileView Server Won't Start

**Problem**: DocFinder logs show "kkFileView JAR not found"

**Solution**:
- Verify the JAR is at: `~/.docfinder/kkfileview/kkFileView.jar`
- Check file permissions (must be readable)
- Ensure filename is exactly `kkFileView.jar` (case-sensitive on Linux/Mac)

### Port Conflict

**Problem**: "Address already in use" error

**Solution**:
- Change `kkfileview.port` to a different port (e.g., 8013)
- Check if another application is using port 8012:
  ```bash
  # Linux/Mac
  lsof -i :8012

  # Windows
  netstat -ano | findstr :8012
  ```

### Out of Memory Errors

**Problem**: kkFileView crashes with OOM

**Solution**:
Edit `KkFileViewServer.java` to increase JVM memory:
```java
command.add("-Xmx2g");  // Increase heap to 2GB
```

### Preview Not Working

**Problem**: Files don't preview in the web interface

**Solution**:
1. Check kkFileView server is running: `http://localhost:8012`
2. Test kkFileView directly: `http://localhost:8012/onlinePreview?url=<file-url>`
3. Check browser console for errors
4. Verify the file format is supported by kkFileView

## Development Notes

### Architecture Components

1. **KkFileViewServer.java**: Manages kkFileView subprocess lifecycle
2. **KkFileViewProxyHandler.java**: HTTP proxy from DocFinder to kkFileView
3. **ConfigManager.java**: Configuration persistence
4. **App.java**: Integration with DocFinder startup

### Process Management

- kkFileView runs as a child process of DocFinder
- Starts automatically if enabled in config
- Stops gracefully on DocFinder shutdown
- Output logged to DocFinder's logger (DEBUG level)

### Security Considerations

- kkFileView runs on localhost only (127.0.0.1)
- No external network access by default
- File access limited to files DocFinder can read
- Port configurable to avoid conflicts

## Comparison: JitViewer vs kkFileView

| Feature | JitViewer | kkFileView |
|---------|-----------|------------|
| License | Copyright concerns | Apache 2.0 (clean) |
| Format Support | Basic Office/PDF | 40+ formats |
| Stability | Occasional issues | Production-ready |
| Resource Usage | Low (pure JS) | Medium (JVM process) |
| Setup Complexity | None (bundled) | Requires JAR download |
| Quality | Good | Excellent |
| Pagination | Limited | Full support |

## Future Enhancements

Potential improvements for future versions:

1. **Auto-download**: Automatically fetch kkFileView JAR on first run
2. **UI Toggle**: Switch between JitViewer and kkFileView in runtime
3. **Advanced Config**: Expose more kkFileView configuration options
4. **Health Monitoring**: Dashboard showing kkFileView status and metrics
5. **Multi-instance**: Support multiple kkFileView servers for load balancing

## Additional Resources

- [kkFileView GitHub](https://github.com/kekingcn/kkFileView)
- [Java 8 Compatible Fork](https://github.com/jiangchuanso/kkFileView-arm64-jdk1.8)
- [kkFileView Documentation](https://kkview.cn)
- [Supported Formats List](https://file.kkview.cn)

## Support

For issues related to:
- **DocFinder Integration**: Open an issue in the DocFinder repository
- **kkFileView Functionality**: Check the [kkFileView Issues](https://github.com/kekingcn/kkFileView/issues)
