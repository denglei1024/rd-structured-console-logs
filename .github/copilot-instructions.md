# Copilot Instructions

## Build and test commands

- Use JDK 21. The Gradle build targets JetBrains Rider 2026.1.1 through the IntelliJ Platform Gradle Plugin. Set `localRiderPath` or `RIDER_HOME` if you want to develop against a local Rider install instead of downloading the IDE dependency.
- Full build with tests: `.\gradlew.bat build`
- Full test suite: `.\gradlew.bat test`
- Single test class: `.\gradlew.bat test --tests "com.github.denglei1024.structconsolelog.parser.StructuredLogParserTest"`
- Build the distributable plugin ZIP: `.\gradlew.bat buildPlugin`
- Launch Rider with the plugin in a sandbox for manual testing: `.\gradlew.bat runIde`
- Plugin-specific verification tasks: `.\gradlew.bat verifyPlugin` and `.\gradlew.bat verifyPluginProjectConfiguration`

## High-level architecture

- `src/main/resources/META-INF/plugin.xml` is the composition root. It registers the `StructuredLogProjectService`, the `Log Explorer` tool window, and the `StructConsoleLogProjectActivity` startup hook.
- `StructConsoleLogProjectActivity` eagerly creates the project service on project open so Run/Debug listeners are active even before the tool window is opened.
- `StructuredLogProjectService` is the runtime state owner. It subscribes to `ExecutionManager.EXECUTION_TOPIC`, creates one session per `ProcessHandler`, buffers output separately for each `LogStream`, turns incoming text into `StructuredLogEntry` objects, and publishes immutable `StructuredLogSessionSnapshot` views to the UI.
- `StructuredLogParser` is the shared parsing layer used by both the service and the UI. It strips ANSI/control codes, repairs common mojibake, parses plain-text / JSON / `key=value` logs, detects continuation lines for stack traces and multiline SQL, and can extract executable SQL from `Executed DbCommand` logs.
- `StructConsoleLogPanel` is a Swing/JB UI over service snapshots. It owns session selection, search and level filters, column visibility, and the details pane. SQL actions in the details pane are driven by `parser.extractSqlLog(entry.rawText)`.
- `src/main/kotlin/com/github/denglei1024/structconsolelog/model/StructuredLogModels.kt` defines the shared enums and data objects that keep parser, service, and UI aligned.

## Key conventions

- Keep `StructuredLogProjectService` as the single source of truth for captured sessions and entries. New UI features should consume `getSnapshot()` and service listener updates instead of attaching additional process listeners.
- Preserve the `StructuredLogEntry` split between `message`, `rawText`, `fields`, `level`, and `parsedTimestamp`. The table intentionally shows only the first message line, while the details pane and SQL copy action depend on full sanitized `rawText`.
- Do not bypass parser sanitization when handling console text. Both parsing and display rely on `sanitizeForDisplay` / `sanitizeForParsing` so ANSI fragments and mis-decoded UTF-8 text are normalized consistently.
- Multi-line stack traces and SQL are modeled as continuations of the previous entry through `StructuredLogParser.isContinuationLine(...)`; changes in parsing should preserve this aggregation behavior rather than emitting separate rows.
- Parser behavior is the main tested surface today. Add or update coverage in `StructuredLogParserTest` when changing parsing, sanitization, continuation detection, or SQL reconstruction.
- The plugin is named **Log Explorer**, but the implementation still lives under the `com.github.denglei1024.structconsolelog` package and related `StructConsoleLog*` / `StructuredLog*` types. Follow the existing package and type naming unless you are doing an intentional repository-wide rename.
