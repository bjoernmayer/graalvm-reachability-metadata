package io.github.bjoernmayer.graalvm

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

/**
 * DSL extension exposed as `reachabilityMetadata { … }` in consumer build scripts.
 *
 * Allows users to configure which version of the GraalVM reachability metadata
 * repository to download, which classpath to analyze, where to output the
 * collected metadata, and optional exclusions or version overrides.
 *
 * All properties use Gradle [Property] types so they are lazy and
 * configuration-cache compatible. Defaults are applied by the plugin in
 * [GraalvmReachabilityMetadataPlugin.apply].
 */
abstract class ReachabilityMetadataExtension {
    /**
     * Version of the `org.graalvm.buildtools:graalvm-reachability-metadata` artifact
     * to resolve (e.g. "1.0.0"). This determines which repository zip is downloaded.
     */
    abstract val repositoryVersion: Property<String>

    /**
     * Name of the Gradle [org.gradle.api.artifacts.Configuration] whose resolved
     * dependencies will be analyzed for reachability metadata (default: "runtimeClasspath").
     */
    abstract val classpathConfigurationName: Property<String>

    /** Directory where matched reachability metadata files are copied to. */
    abstract val outputDir: DirectoryProperty

    /**
     * Set of module coordinates ("group:artifact") to skip when collecting metadata.
     * Matching modules will not have their metadata copied to [outputDir].
     */
    abstract val excludes: SetProperty<String>

    /**
     * Optional per-module version overrides for the metadata lookup.
     *
     * Key:   module coordinate in "group:artifact" format.
     * Value: the metadata config version to use instead of the resolved artifact version.
     *
     * This is useful when the reachability metadata repository contains metadata
     * under a different version than the artifact version on the classpath.
     */
    abstract val moduleToConfigVersion: MapProperty<String, String>

    /**
     * When `true`, if an exact version match is not found in the metadata
     * repository, the latest available configuration is used instead.
     *
     * This mirrors the behaviour of the official `native-build-tools` plugin and
     * is useful for new library versions that have not been tested yet but are
     * likely compatible with the latest available metadata.
     *
     * Default: `true`.
     */
    abstract val useLatestConfigWhenVersionIsUntested: Property<Boolean>
}
