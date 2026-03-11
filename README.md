# GraalVM Reachability Metadata Gradle Plugin

A Gradle plugin that automatically
collects [GraalVM reachability metadata](https://www.graalvm.org/latest/reference-manual/native-image/metadata/) for
your project's dependencies.

When building GraalVM native images, libraries that use reflection, JNI, dynamic proxies, or resource loading need
metadata so `native-image` knows what to include.
The [GraalVM reachability metadata repository](https://github.com/oracle/graalvm-reachability-metadata) provides this
metadata for many popular libraries. This plugin downloads the repository, walks your dependency graph, and copies the
relevant metadata into your build directory — ready to be passed to `native-image`.

## Motivation

The official [GraalVM Native Build Tools](https://github.com/graalvm/native-build-tools) Gradle plugin already includes
a metadata repository feature. However, it has known issues with
the [Gradle configuration cache](https://docs.gradle.org/current/userguide/configuration_cache.html) and does not work
reliably in multi-module projects. These limitations can cause broken builds or force you to disable the configuration
cache entirely — negating one of Gradle's most impactful performance features.

This plugin was created to work around those problems by re-implementing the metadata repository functionality from
scratch with full configuration cache compatibility and correct behavior in multi-module setups.

## Features

- **Automatic metadata collection** — Resolves your `runtimeClasspath` and copies matching reachability metadata for
  every dependency.
- **Cached repository unpacking** — The repository zip is downloaded once and unpacked under `$GRADLE_USER_HOME`, keyed
  by version. Subsequent builds reuse the cache.
- **Cacheable task** — The `collectReachabilityMetadata` task is `@CacheableTask`, so Gradle can skip it or restore
  outputs from the build cache when inputs haven't changed.
- **Configurable** — Exclude specific modules, override metadata versions per module, or change which classpath
  configuration is analyzed.

## Usage

Apply the plugin in your `build.gradle.kts`:

```kts
plugins {
    id("io.github.bjoernmayer.graalvm-reachability-metadata")
}
```

That's it. The plugin registers a `collectReachabilityMetadata` task that you can run:

```sh
./gradlew collectReachabilityMetadata
```

The collected metadata will be written to `build/native-reachability-metadata/` by default.

## Configuration

All settings are optional. Defaults are shown below:

```kts
reachabilityMetadata {
    // Version of the GraalVM reachability metadata repository to download
    repositoryVersion.set("0.11.5")

    // Which configuration's dependencies to analyze
    classpathConfigurationName.set("runtimeClasspath")

    // Where to copy the collected metadata
    outputDir.set(layout.buildDirectory.dir("native-reachability-metadata"))

    // Modules to exclude from metadata collection ("group:artifact")
    excludes.set(emptySet())
    // Example:
    // excludes.add("com.example:some-lib")

    // Override the metadata version used for specific modules ("group:artifact" -> "version")
    moduleToConfigVersion.set(emptyMap())
    // Example:
    // moduleToConfigVersion.put("com.example:some-lib", "1.2.3")
}
```

### Passing metadata to `native-image`

Point the GraalVM native image tool at the output directory:

```kts
graalvmNative {
    binaries {
        named("main") {
            configurationFileDirectories.from(tasks.named("collectReachabilityMetadata"))
        }
    }
}
```

### Bundling metadata into the JAR

Alternatively, you can embed the metadata directly into your JAR so it is available at build time and on the classpath
without extra configuration:

```kts
tasks.named("jar") {
    from(tasks.named("collectReachabilityMetadata"))
}
```

This automatically wires up task dependencies — Gradle will run `collectReachabilityMetadata` before `jar` and include
the metadata files in the JAR output.

## Developing

Checkout this repository next to some project where you want to use it. Then in the `settings.gradle.kts` of said
project, add this:

```kts
// settings.gradle.kts

pluginManagement {
    includeBuild("../graalvm-reachability-metadata")
}
```

Then, you can just apply the plugin like usual:

```kts
// build.gradle.kts

plugins {
    id("io.github.bjoernmayer.graalvm-reachability-metadata")
}
```

Changes to the plugin source will be picked up automatically — no publishing step required.

### Building

```sh
./gradlew :plugin:build
```

### Running checks

```sh
./gradlew :plugin:check
```

This runs compilation, tests, and ktlint.
