import org.gradle.kotlin.dsl.check
import org.gradle.kotlin.dsl.invoke
import org.gradle.kotlin.dsl.provideDelegate
import org.gradle.kotlin.dsl.test

plugins {
    idea
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)

    alias(libs.plugins.ktlint)
    alias(libs.plugins.plugin.publish)
}

dependencies {
    implementation(libs.graalvm.reachabilityMetadata)

    testImplementation(gradleApi())
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// region Functional-test source set (Gradle TestKit)

val functionalTestSourceSet: SourceSet =
    sourceSets.create("functionalTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }

configurations["functionalTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["functionalTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

tasks {
    val functionalTest by registering(Test::class) {
        description = "Runs Gradle TestKit functional tests."
        group = "verification"
        testClassesDirs = functionalTestSourceSet.output.classesDirs
        classpath = functionalTestSourceSet.runtimeClasspath
        useJUnitPlatform()
    }

    check { dependsOn(functionalTest) }

    test {
        useJUnitPlatform()
    }
}

// endregion

group = "io.github.bjoernmayer"
version = "1.0.0"

gradlePlugin {
    website = "https://github.com/bjoernmayer/graalvm-reachability-metadata"
    vcsUrl = "https://github.com/bjoernmayer/graalvm-reachability-metadata"

    testSourceSets(functionalTestSourceSet)

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
