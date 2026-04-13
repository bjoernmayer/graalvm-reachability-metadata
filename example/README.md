# Example: GraalVM Reachability Metadata Plugin

This is a minimal standalone project that demonstrates how to use the
`io.github.bjoernmayer.graalvm-reachability-metadata` Gradle plugin.

## How it works

- **`settings.gradle.kts`** — uses `includeBuild("..")` to load the plugin
  from the parent directory (no publishing required).
- **`build.gradle.kts`** — applies the plugin, declares a few dependencies
  that have entries in the GraalVM reachability metadata repository
  (Jackson Databind, H2, Netty), and shows all available configuration
  options as comments.

## Running the example

```sh
cd example
../gradlew collectReachabilityMetadata
```

After the task completes, the collected metadata will be in:

```
build/generated/native-reachability-metadata/
```

A diagnostics report is written to:

```
build/reports/graalvm-reachability-metadata/report.txt
```

## Expected output

The task walks the full transitive dependency graph and reports which modules
had metadata available:

```
Reachability metadata: 7 modules analyzed, 4 with metadata, 0 excluded, 3 without metadata
```

