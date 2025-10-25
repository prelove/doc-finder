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

## Validation
- A native image build succeeds on at least one platform (Windows preferred) with core search, preview, and indexing features working.
- Documentation explains how the team can reproduce the build locally or in CI.
- Startup and footprint comparisons justify keeping the native build path.
