package io.github.bjoernmayer.graalvm

import io.github.bjoernmayer.graalvm.GraalvmReachabilityMetadataPlugin.Companion.CACHE_DIR
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

/**
 * Gradle plugin that automatically collects GraalVM reachability metadata for a
 * project's dependencies.
 *
 * When applied, the plugin:
 * 1. Registers a `reachabilityMetadata` DSL extension ([ReachabilityMetadataExtension])
 *    with sensible defaults.
 * 2. Creates a dedicated resolvable configuration (`reachabilityMetadataRepositoryZip`)
 *    that downloads the GraalVM reachability metadata repository zip artifact.
 * 3. Registers a shared [ReachabilityMetadataRepositoryService] build service that
 *    unpacks and caches the repository under `$GRADLE_USER_HOME/[CACHE_DIR]/<version>`.
 * 4. Registers the `collectReachabilityMetadata` task ([CollectReachabilityMetadataTask])
 *    that walks the resolved classpath and copies matching metadata into the build directory.
 *
 * Usage in a consumer build script:
 * ```kotlin
 * plugins {
 *     id("io.github.bjoernmayer.graalvm-reachability-metadata")
 * }
 *
 * reachabilityMetadata {
 *     repositoryVersion.set("0.11.5")
 *     excludes.add("com.example:some-lib")
 * }
 * ```
 */
class GraalvmReachabilityMetadataPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        // 1. Register the DSL extension with default values
        val extension =
            target.extensions
                .create("reachabilityMetadata", ReachabilityMetadataExtension::class.java)
                .apply {
                    repositoryVersion.convention("0.11.5")
                    classpathConfigurationName.convention("runtimeClasspath")
                    outputDir.convention(target.layout.buildDirectory.dir("native-reachability-metadata"))
                    excludes.convention(emptySet())
                    moduleToConfigVersion.convention(emptyMap())
                }

        // 2. Create a resolvable-only configuration for fetching the repository zip.
        //    This configuration is not consumable by other projects — it exists solely
        //    to resolve the single metadata zip artifact from a Maven repository.
        val repoZipConfig =
            target.configurations.create("reachabilityMetadataRepositoryZip") {
                it.description = "Resolves org.graalvm.buildtools:graalvm-reachability-metadata:<ver>:repository@zip"
                it.isCanBeConsumed = false
                it.isCanBeResolved = true
            }

        // Wire a dependency whose version tracks the extension property, so changing
        // `repositoryVersion` in the DSL automatically adjusts the resolved artifact.
        target.dependencies.addProvider(
            repoZipConfig.name,
            extension.repositoryVersion.map { ver ->
                "org.graalvm.buildtools:graalvm-reachability-metadata:$ver:repository@zip"
            },
        )

        // 3. Register a shared build service that unpacks the repository zip once and
        //    caches the result per version under $GRADLE_USER_HOME.
        val cacheBase = File(target.gradle.gradleUserHomeDir, CACHE_DIR)

        val repoService =
            target.gradle.sharedServices.registerIfAbsent(
                "reachabilityMetadataRepositoryService",
                ReachabilityMetadataRepositoryService::class.java,
            ) { spec ->
                // Lazily resolve the zip file — only evaluated when the service is first used
                spec.parameters.repositoryZip.set(
                    target.layout.file(target.providers.provider { repoZipConfig.singleFile }),
                )
                // Cache directory is keyed by version so different versions don't collide
                spec.parameters.unpackDir.set(
                    target.layout.dir(extension.repositoryVersion.map { ver -> File(cacheBase, ver) }),
                )
                spec.parameters.forceUnpack.convention(false)
            }

        // 4. Register the main task that walks the dependency graph and copies metadata
        target.tasks.register("collectReachabilityMetadata", CollectReachabilityMetadataTask::class.java) { task ->
            task.group = "build"
            task.description = "Copies GraalVM reachability metadata for all runtimeClasspath dependencies"

            // Wire the root component of the resolved dependency graph as a lazy
            // provider. This is evaluated at execution time but does NOT require a
            // Project reference, making it configuration-cache compatible.
            task.rootComponent.set(
                extension.classpathConfigurationName.flatMap { configName ->
                    target.configurations
                        .getByName(configName)
                        .incoming.resolutionResult.rootComponent
                },
            )

            // Declare the shared service dependency so Gradle can coordinate access
            task.repositoryService.set(repoService)
            task.usesService(repoService)

            // Forward extension properties to the task
            task.excludes.set(extension.excludes)
            task.moduleToConfigVersion.set(extension.moduleToConfigVersion)
            task.outputDir.set(extension.outputDir)
        }
    }

    private companion object {
        /** Cache directory path relative to `$GRADLE_USER_HOME`. */
        const val CACHE_DIR = "graalvm-reachability-metadata/repositories"
    }
}
