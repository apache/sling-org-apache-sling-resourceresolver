# Security

<!-- sling-security-default:start -->
The threat model for this project is https://github.com/apache/sling/blob/master/docs/threat-model.md .
<!-- sling-security-default:end -->

## Project and build baseline

- Java version: **17** (`sling.java.version`).
- Current development version: **2.0.5-SNAPSHOT** (after `org.apache.sling.resourceresolver-2.0.4` release).
- Parent POM: **org.apache.sling:sling-bundle-parent:66**.
- Standard layout: implementation in `src/main/java`, tests in `src/test/java`.

## Common commands

- Full build and verification: `mvn clean verify`
- Run tests: `mvn test`
- Build bundle artifact: `mvn clean package`

## Current implementation patterns

- In `MapEntries`, listener lifecycle ordering is race-safe:
  - initialize handlers needed by `onChange` before listener registration,
  - unregister listener before disposing dependent handlers.
- Concurrency regressions around `MapEntries` constructor/dispose listener windows are covered in `MapEntriesTest`.
- Prefer `StandardCharsets.US_ASCII` over string-based charset names and checked `UnsupportedEncodingException` handling in URI helper code.
- Web console servlet (`ResourceResolverWebConsolePlugin`) handles `IOException` in `doGet`/`doPost` internally, logs failures, and returns HTTP 500 on rendering/redirect errors.
