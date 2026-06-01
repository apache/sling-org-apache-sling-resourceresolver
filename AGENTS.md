# Project Overview

`org.apache.sling.resourceresolver` is an OSGi bundle that implements the Apache Sling `ResourceResolverFactory` and `ResourceResolver` APIs. It manages resource resolution, URL mapping (vanity paths, `/etc/mapping`), alias handling, resource provider lifecycle, observation/event routing, and namespace mangling. The bundle acts as the central dispatcher that aggregates `ResourceProvider` OSGi services into a unified resource tree. Requires Java 17+.

# Core Commands

```bash
# Build and package (skips tests)
mvn -q clean package -DskipTests

# Compile only
mvn -q compile

# Run full test suite
mvn -q test

# Run a single test class
mvn -q test -Dtest=MapEntriesTest

# Run a single test method
mvn -q test -Dtest=MapEntriesTest#testVanityPath

# License / RAT check
mvn apache-rat:check

# Spotless code formatting check
mvn spotless:check

# Apply Spotless auto-formatting
mvn spotless:apply

# OSGi baseline compatibility check (run after changing APIs)
mvn bnd-baseline:baseline

# Full verify (tests + checks)
mvn verify
```

No dev server — this is an OSGi bundle; deploy to a running Sling instance via `mvn sling:install` (requires a running Sling at `http://localhost:8080`).

# Project Layout

```
src/
  main/java/org/apache/sling/resourceresolver/impl/
    ResourceResolverFactoryActivator.java   # OSGi DS component; main entry point
    ResourceResolverFactoryImpl.java        # ResourceResolverFactory implementation
    ResourceResolverImpl.java               # ResourceResolver per-request object
    CommonResourceResolverFactoryImpl.java  # Shared factory logic
    ResourceResolverFactoryConfig.java      # OSGi metatype config interface
    FactoryPreconditions.java               # Startup gate (required providers)
    FactoryRegistrationHandler.java         # Registers factory when ready
    VanityPathConfigurer.java               # Vanity path feature toggle
    ResourceTypeUtil.java                   # Resource type helpers
    mapping/                                # URL mapping: MapEntries, aliases, vanity
    providers/                              # ResourceProvider tracker, storage, tree
    providers/stateful/                     # Per-resolver authenticated provider wrappers
    observation/                            # ResourceChangeListener whiteboard & bridge
    helper/                                 # URI, iterators, RedirectResource, StarResource
    params/                                 # URL parameter parsing
    legacy/                                 # Adapters for old ResourceProvider SPI
    console/                                # Felix Web Console plugin
  test/java/                                # Mirrors main package structure
  test/resources/                           # Test OSGi configs, mock data
bnd.bnd                                     # OSGi manifest instructions
pom.xml                                     # Maven build; parent: sling-bundle-parent
```

# Development Patterns & Constraints

- **Java 17**, OSGi R7, OSGi DS (Declarative Services) with `org.osgi.service.component.annotations`.
- All public OSGi components use `@Component`, `@Reference`, `@Activate`/`@Modified`/`@Deactivate` from `org.osgi.service.component.annotations`. Never use Felix SCR annotations.
- Metatype configs declared via `@ObjectClassDefinition` interfaces (e.g., `ResourceResolverFactoryConfig`).
- Internal packages are under `.impl.*` — do not expose them in `Export-Package`. The `bnd.bnd` controls OSGi headers.
- Spotless enforces formatting. Run `mvn spotless:apply` before committing. Indentation: 4 spaces.
- `@NotNull`/`@Nullable` from `org.jetbrains.annotations` for null-safety contracts on public APIs.
- No static mutable state in OSGi components — components must be thread-safe.
- Baseline plugin enforces binary compatibility; bumping exported API requires a version increment per semantic versioning.

# Git Workflow

- Branch from `master`; branch names: `feature/<issue>-short-desc`, `fix/<issue>-short-desc`.
- Commit messages: imperative mood, reference JIRA issue if applicable (`SLING-XXXXX short description`).
- PRs target `master`. CI runs via Jenkins (`Jenkinsfile` at root).
- Apache CLA required for external contributors; see [contributing guide](https://sling.apache.org/contributing.html).

# Testing Guidelines

- **Framework**: JUnit 5 (Jupiter) + JUnit 4 via `junit-vintage-engine`. Mockito 5 for mocks. `osgi-mock` (JUnit 4 and JUnit 5 variants) for OSGi component testing.
- Test files mirror `src/main/java` under `src/test/java`.
- Prefer `osgi-mock` (`OsgiContext`/`MockOsgi`) for component integration; use plain Mockito for unit-level.
- Coverage reports: `mvn jacoco:report` (if jacoco is wired; check parent POM). Surefire reports land in `target/surefire-reports/`.
- Run a specific test: `mvn test -Dtest=ClassName` or `mvn test -Dtest=ClassName#methodName`.

# Gotchas

- `MapEntries` is initialized lazily and asynchronously on first use; tests that exercise URL mapping must ensure it is fully initialized before asserting.
- `javax.jcr` and `org.apache.sling.commons.metrics` imports are `resolution:=optional` — code that uses them must guard with null/availability checks.
- The bundle parent POM (`sling-bundle-parent`) pins many plugin versions; don't override plugin versions in `pom.xml` unless necessary.
- Spotless will fail the build if formatting is off — always run `mvn spotless:apply` after bulk edits.
- OSGi baseline checks will fail if you change method signatures in non-internal packages without bumping the package version in `bnd.bnd` / `pom.xml`.
- `ResourceResolverImpl` is not an OSGi component — it is instantiated per-request by the factory. Don't add `@Component` to it.
