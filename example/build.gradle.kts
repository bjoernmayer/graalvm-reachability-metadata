plugins {
    java
    id("io.github.bjoernmayer.graalvm-reachability-metadata")
}

repositories {
    mavenCentral()
}

// Dependencies chosen because they have entries in the GraalVM reachability
// metadata repository, so running `collectReachabilityMetadata` produces
// visible output.
dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    implementation("com.h2database:h2:2.1.214")
    implementation("io.netty:netty-buffer:4.1.80.Final")
}

// ── Plugin configuration (all settings are optional) ────────────────────────
reachabilityMetadata {
    // Version of the GraalVM reachability metadata repository to download.
    // Default: "1.0.0"
    // repositoryVersion = "1.0.0"

    // Which Gradle configuration's resolved dependencies to analyze.
    // Default: "runtimeClasspath"
    // classpathConfigurationName = "runtimeClasspath"

    // Where to write the collected metadata.
    // Default: layout.buildDirectory.dir("generated/native-reachability-metadata")
    // outputDir = layout.buildDirectory.dir("generated/native-reachability-metadata")

    // Modules to exclude from metadata collection ("group:artifact").
    // excludes.add("com.example:some-lib")

    // Override the metadata version used for a specific module.
    // moduleToConfigVersion.put("com.example:some-lib", "1.2.3")

    // Fall back to the latest available metadata when the exact version is untested.
    // Default: true
    // useLatestConfigWhenVersionIsUntested = true
}
