# kkFileView 集成实现总结

## 问题背景

当前文件预览使用 JitViewer 存在以下问题：
1. 不稳定
2. 有版权问题

需要调研 kkFileView 项目并集成到 DocFinder 中，实现：
- 在 DocFinder 启动时自动启动内置的 kkFileView 服务器
- 避免危险的 exe 文件，但可以使用 jar 包

## 实现方案

### 架构设计

采用**进程隔离**的方式集成 kkFileView：

```
DocFinder (主进程)
    ├─ WebServer (端口 7070)
    │   └─ /api/kkfileview/* → 代理转发
    │
    └─ KkFileViewServer (子进程管理器)
         └─ kkFileView JAR (独立 JVM 进程, 端口 8012)
```

**优点**：
- ✅ 避免类加载器冲突 (kkFileView 使用 Spring Boot)
- ✅ 易于更新升级 (直接替换 JAR 文件)
- ✅ 资源隔离 (独立的 JVM 内存空间)
- ✅ 无需 .exe 文件，仅使用 JAR 包
- ✅ 兼容 Java 8

### 核心组件

#### 1. KkFileViewServer.java
**位置**: `src/main/java/org/abitware/docfinder/web/KkFileViewServer.java`

**功能**：
- 管理 kkFileView 进程的启动和停止
- 检查 JAR 文件是否存在
- 捕获进程输出并记录日志
- 配置端口和工作目录

**关键方法**：
```java
public void start() throws IOException  // 启动服务器
public void stop()                      // 停止服务器
public boolean isRunning()              // 检查运行状态
public boolean isAvailable()            // 检查 JAR 是否存在
public String getBaseUrl()              // 获取访问 URL
```

#### 2. KkFileViewProxyHandler.java
**位置**: `src/main/java/org/abitware/docfinder/web/KkFileViewProxyHandler.java`

**功能**：
- HTTP 代理，转发请求到 kkFileView
- 处理请求头和响应头
- 流式传输大文件
- 错误处理和日志记录

**端点映射**：
```
DocFinder:  /api/kkfileview/onlinePreview?url=...
    ↓
kkFileView: http://127.0.0.1:8012/onlinePreview?url=...
```

#### 3. ConfigManager 配置增强
**位置**: `src/main/java/org/abitware/docfinder/index/ConfigManager.java`

**新增配置项**：
```properties
kkfileview.enabled=false  # 是否启用 kkFileView
kkfileview.port=8012      # kkFileView 监听端口
```

**新增方法**：
- `isKkFileViewEnabled()` / `setKkFileViewEnabled(boolean)`
- `getKkFileViewPort()` / `setKkFileViewPort(int)`

#### 4. App 集成
**位置**: `src/main/java/org/abitware/docfinder/App.java`

**启动流程**：
```java
// 1. 读取配置
ConfigManager cfg = new ConfigManager();

// 2. 如果启用，启动 kkFileView 服务器
if (cfg.isKkFileViewEnabled()) {
    kkFileViewServer = new KkFileViewServer(cfg.getKkFileViewPort(), cfg);
    if (kkFileViewServer.isAvailable()) {
        kkFileViewServer.start();
    }
}

// 3. 启动 Web 服务器并关联 kkFileView
if (cfg.isWebEnabled()) {
    webServer = new WebServer(...);
    webServer.setKkFileViewServer(kkFileViewServer);
    webServer.start();
}

// 4. 注册关闭钩子
Runtime.getRuntime().addShutdownHook(
    new Thread(kkFileViewServer::stop)
);
```

## 使用 Java 8 兼容版本

### 问题
官方 kkFileView 4.4.0 要求：
- Java 21
- Spring Boot 3.5.6

DocFinder 使用：
- Java 8
- 无 Spring Framework

### 解决方案
使用社区维护的 Java 8 兼容分支：

**项目**: https://github.com/jiangchuanso/kkFileView-arm64-jdk1.8

**版本**: 4.4.0 (改为 Java 8 + Spring Boot 2.4.2)

## 安装和使用

### 步骤 1: 构建 kkFileView JAR

```bash
# 克隆 Java 8 兼容版本
git clone https://github.com/jiangchuanso/kkFileView-arm64-jdk1.8.git
cd kkFileView-arm64-jdk1.8

# 构建项目
mvn clean package -DskipTests

# JAR 文件位置: server/target/kkFileView-4.4.0.jar
```

### 步骤 2: 放置 JAR 文件

```bash
# Windows
mkdir %USERPROFILE%\.docfinder\kkfileview
copy kkFileView-4.4.0.jar %USERPROFILE%\.docfinder\kkfileview\kkFileView.jar

# Linux/Mac
mkdir -p ~/.docfinder/kkfileview
cp kkFileView-4.4.0.jar ~/.docfinder/kkfileview/kkFileView.jar
```

**重要**：文件必须命名为 `kkFileView.jar`

### 步骤 3: 启用功能

编辑 `~/.docfinder/config.properties`：

```properties
kkfileview.enabled=true
kkfileview.port=8012
```

### 步骤 4: 重启 DocFinder

查看日志确认启动：
```
INFO  kkFileView server started at http://127.0.0.1:8012
```

## 支持的文件格式

kkFileView 支持 40+ 种格式：

### 办公文档
- Microsoft Office: doc, docx, xls, xlsx, ppt, pptx
- OpenOffice: odt, ods, odp
- WPS: wps, et, dps

### 文档格式
- PDF, OFD (国产标准)
- TXT, Markdown, RTF

### CAD 文件
- DWG, DXF, DWF

### 图片
- JPG, PNG, GIF, BMP, TIFF, SVG, WebP

### 压缩包
- ZIP, RAR, 7Z, TAR, GZIP

### 其他
- HTML, XML, JSON
- 视频: MP4, AVI, MKV
- 3D 模型: STL, OBJ

## API 接口

### 预览文件

```http
GET /api/kkfileview/onlinePreview?url=<文件URL编码>
```

示例：
```bash
curl "http://localhost:7070/api/kkfileview/onlinePreview?url=http%3A%2F%2Fexample.com%2Fdocument.docx"
```

### 检查服务状态

直接访问 kkFileView：
```http
GET http://localhost:8012
```

## 与 JitViewer 对比

| 特性 | JitViewer | kkFileView |
|------|-----------|------------|
| 许可证 | 版权问题 | Apache 2.0 (开源) |
| 格式支持 | 基本 Office/PDF | 40+ 种格式 |
| 稳定性 | 偶尔有问题 | 生产级稳定 |
| 资源占用 | 低 (纯 JS) | 中等 (JVM 进程) |
| 设置复杂度 | 无 (内置) | 需下载 JAR |
| 预览质量 | 良好 | 优秀 |
| 分页支持 | 有限 | 完整支持 |

## 故障排除

### JAR 文件未找到

**错误**: `kkFileView JAR not found at: ...`

**解决**:
- 检查文件路径: `~/.docfinder/kkfileview/kkFileView.jar`
- 确认文件名正确 (区分大小写)
- 检查文件权限

### 端口冲突

**错误**: `Address already in use`

**解决**:
- 修改 `kkfileview.port` 为其他端口
- 检查端口占用: `netstat -ano | findstr :8012`

### 内存不足

**解决**: 修改 `KkFileViewServer.java`，增加 JVM 内存：
```java
command.add("-Xmx2g");  // 增加堆内存到 2GB
```

## 未来改进

1. ✨ 自动下载 JAR 包
2. ✨ UI 切换 JitViewer / kkFileView
3. ✨ 暴露更多 kkFileView 配置选项
4. ✨ 健康监控面板
5. ✨ 负载均衡支持

## 技术亮点

### 1. 进程管理
- 子进程自动启动/停止
- 优雅关闭 (10秒超时)
- 强制终止后备方案
- 输出流重定向到日志

### 2. 代理转发
- 完整 HTTP 代理实现
- 流式传输大文件
- 请求/响应头透传
- 错误处理

### 3. 配置持久化
- 配置文件存储
- 类型安全的访问器
- 端口范围验证

### 4. 生命周期集成
- 与 App 启动同步
- 关闭钩子注册
- MainWindow 引用传递

## 文件清单

```
src/main/java/org/abitware/docfinder/
├── App.java                          # 集成 kkFileView 启动
├── index/
│   └── ConfigManager.java            # 配置管理
├── ui/
│   └── MainWindow.java               # UI 集成
└── web/
    ├── KkFileViewServer.java         # 进程管理器 (新增)
    ├── KkFileViewProxyHandler.java   # HTTP 代理 (新增)
    └── WebServer.java                # 注册代理端点

~/.docfinder/
├── config.properties                 # 配置文件
└── kkfileview/
    ├── kkFileView.jar               # kkFileView JAR (用户提供)
    └── files/                        # 缓存目录 (自动创建)

docs/
└── KKFILEVIEW_INTEGRATION.md        # 英文文档 (新增)
```

## 总结

### 已实现功能 ✅

1. ✅ kkFileView 服务器进程管理
2. ✅ 配置选项 (启用标志, 端口)
3. ✅ HTTP 代理转发
4. ✅ 生命周期集成 (启动/关闭)
5. ✅ 详细文档 (中英文)
6. ✅ Java 8 兼容方案
7. ✅ 无 .exe 文件 (仅 JAR)

### 待完善功能 🚧

1. 🚧 前端 HTML/JS 更新 (调用 kkFileView API)
2. 🚧 菜单项 (启用/禁用 kkFileView)
3. 🚧 UI 配置界面
4. 🚧 测试验证

### 使用建议

**现状**: 基础设施已完成，可以启动和代理 kkFileView

**下一步**:
1. 用户需要下载并放置 kkFileView JAR
2. 启用配置: `kkfileview.enabled=true`
3. 重启 DocFinder
4. 访问 `http://localhost:7070/api/kkfileview/...` 测试

**最佳实践**:
- 建议 kkFileView 占用内存: 512MB ~ 2GB
- 端口选择: 避免常用端口 (8080, 8000 等)
- 日志级别: 设置 DEBUG 可查看 kkFileView 输出
- 定期清理: `~/.docfinder/kkfileview/files/` 缓存目录

## 参考资料

- [kkFileView 官方文档](https://kkview.cn)
- [Java 8 兼容分支](https://github.com/jiangchuanso/kkFileView-arm64-jdk1.8)
- [在线演示](https://file.kkview.cn)
