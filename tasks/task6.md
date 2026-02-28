# Task 6 - GraalVM Native Image Preparation

## Goal
Prepare the project to compile into a standalone native executable with GraalVM for faster startup and deployment.

## Current Understanding
- The application depends on Swing, Lucene, Tika, and jnativehook, and each may need explicit reflection or resource configuration for native image builds.
- The current Maven build produces a shaded jar via maven-shade-plugin but has no GraalVM configuration.
- Native image builds can dramatically increase size and must include icons, theme resources, and Tika parser metadata explicitly.

## Plan
1. Audit the codebase for reflection, dynamic class loading, and JNI usage; list the required GraalVM configuration files such as reflect-config.json, resource-config.json, and jni-config.json.
2. Add a Maven profile (for example the name native) that wires in org.graalvm.buildtools:native-maven-plugin with starter options for Windows, macOS, and Linux targets.
3. Verify third-party dependencies (Lucene analyzers, Tika parsers, FlatLaf themes) under native image builds and provide fallbacks if certain parsers are unavailable.
4. Write build documentation describing how to install GraalVM, run the command mvn -Pnative native:compile, and bundle the resulting binary with assets.
5. Measure startup time, memory usage, and functional parity between JVM and native builds; note any regressions.

## Progress Update (2026-02-28)
- ✅ Added `src/main/resources/META-INF/native-image/org.abitware.docfinder/reflect-config.json` – covers App, FlatLaf, Lucene codecs, Tika, Logback, JNativeHook, CJK analyzers.
- ✅ Added `resource-config.json` – includes logback.xml, META-INF/services, Tika MIME db, icons, SmartChinese/Kuromoji dictionaries, and the web UI HTML resource.
- ✅ Added `jni-config.json` – covers JNativeHook and AWT peer factories.
- ✅ Added `native` Maven profile in `pom.xml` using `native-maven-plugin 0.10.3`; upgrades source/target to Java 17 for GraalVM compatibility.
- ⚠️ Full native image compilation requires GraalVM 21+ and is not verified in CI. The JVM/shaded-JAR path is unaffected.

## Build Instructions
```bash
# Install GraalVM 21+ and native-image component, then:
mvn -Pnative package
# Output: target/docfinder (Linux/macOS) or target/docfinder.exe (Windows)
```

## Validation
- ✅ pom.xml `native` profile added and parses correctly (`mvn help:all-profiles`).
- ✅ Configuration JSON files placed under `META-INF/native-image/` so native-image agent auto-discovers them.
- ⬜ Functional native build verification deferred until GraalVM toolchain is available in CI.
