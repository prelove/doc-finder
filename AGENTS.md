# Repository Guidelines

## Project Structure & Module Organization
DocFinder is a Java 8 desktop search utility organized under `src/main/java/org/abitware/docfinder`. Core entry point lives in `App.java`, while feature-specific packages break down as: `index` (Lucene index lifecycle), `search` (query orchestration), `ui` (Swing + FlatLaf screens), `util` (shared helpers), `watch` (background file system monitor), and `web` (embedded HTTP server, search/preview/share handlers). UI assets sit in `src/main/resources/icons`, and web static assets live in `src/main/resources/web`. Generated artifacts land in `target/` (shaded JARs, compiled classes). Keep usage docs and scripts (`README.md`, `UsageGuide.md`, `run-docfinder.bat`) in the repository root.

## Build, Test & Development Commands
- `mvn clean package` - compiles, runs tests, and produces the shaded JAR via `maven-shade-plugin`.
- `mvn -Pnative package` - builds a GraalVM native binary (`target/docfinder` or `target/docfinder.exe`) using the `native` profile; requires GraalVM + native-image and Java 17 toolchain.
- `mvn test` - executes the Maven Surefire phase; add tests before expecting non-noop output.
- `java -jar target/docfinder-1.0.0.jar` - launches the packaged app; accepts standard JVM flags.
- `run-docfinder.bat` - Windows shortcut that wraps the jar invocation with default paths.

## Coding Style & Naming Conventions
Target Java 8 bytecode (`maven.compiler.source/target`). Format with four-space indentation, braces on the same line, and one class per file. Use PascalCase for classes, camelCase for methods and fields, UPPER_SNAKE_CASE for constants. Place new classes in the existing package slices (`index`, `search`, `ui`, `util`, `watch`, `web`) to keep responsibilities cohesive. Run an auto-formatter (IntelliJ default or `mvn fmt:format` if you add Spotless) before committing.

## Testing Guidelines
Create tests under `src/test/java`, mirroring the main package structure. Use the existing JUnit Jupiter setup from `pom.xml`. Name test classes `<ClassName>Test` and individual methods `shouldDescribeBehavior` (for example `web/ShareManagerTest`). Aim to cover Lucene index builders, watcher side-effects, web handlers/share logic, and UI presenters with focused, small tests. Run `mvn test` locally before opening a PR.

## Commit & Pull Request Guidelines
Follow the existing Conventional Commit style (`feat:`, `fix:`, `chore:`) with imperative, lowercase summaries. Keep commits scoped to one concern and reference issue IDs when available. Pull requests should include: a short what/why description, testing evidence (command output or screenshots for UI), and notes about configuration or migration steps. Update `UsageGuide.md` when user-facing behavior shifts.

## Configuration & Data Tips
The app reads and writes runtime data under `~/.docfinder`; do not check that directory into source control. Key files include `config.properties` (app/web settings) and `shares.properties` (web share links). Document new configuration keys in `README.md` and provide safe defaults. When touching watchers or Lucene analyzers, highlight any platform-specific prerequisites (e.g., permissions on network shares) in your PR description.
