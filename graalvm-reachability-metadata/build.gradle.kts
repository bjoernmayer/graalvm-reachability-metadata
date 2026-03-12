plugins {
    idea
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)

    alias(libs.plugins.ktlint)
    alias(libs.plugins.plugin.publish)
}

dependencies {
    implementation(libs.graalvm.reachabilityMetadata)
}

group = "io.github.bjoernmayer"
version = "0.0.4"

gradlePlugin {
    website = "https://github.com/bjoernmayer/graalvm-reachability-metadata"
    vcsUrl = "https://github.com/bjoernmayer/graalvm-reachability-metadata"

    plugins {
        create("graalvmReachabilityMetadata") {
            id = "io.github.bjoernmayer.graalvm-reachability-metadata"
            displayName = "GraalVM Reachability Metadata Plugin"
            description =
                "Automatically collects GraalVM reachability metadata for your project's dependencies by downloading the official metadata repository and copying matching entries into the build directory."
            tags = listOf("graalvm", "native-image", "reachability-metadata", "native", "reflection")

            implementationClass = "io.github.bjoernmayer.graalvm.GraalvmReachabilityMetadataPlugin"
        }
    }
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.BELLSOFT)
    }
}

ktlint {
    version.set(
        libs.versions.ktlint
            .asProvider()
            .get(),
    )
}

idea {
    // Going through decompiled class files is no fun (no code navigation),
    // hence, we instruct Gradle to download the actual sources
    module {
        isDownloadJavadoc = false
        isDownloadSources = true
    }
}
