package io.github.bjoernmayer.graalvm

import org.graalvm.reachability.DirectoryConfiguration
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.util.ArrayDeque

/**
 * Cacheable Gradle task that collects GraalVM reachability metadata for every
 * resolved dependency on a given classpath configuration.
 *
 * For each dependency on the classpath (typically `runtimeClasspath`), the task
 * queries the [ReachabilityMetadataRepositoryService] for matching metadata
 * entries and copies them into [outputDir]. The resulting directory can then be
 * passed to the GraalVM `native-image` tool via `-H:ConfigurationFileDirectories`.
 *
 * The task is marked [@CacheableTask][CacheableTask] so Gradle can skip execution
 * or restore outputs from the build cache when inputs have not changed.
 *
 * All inputs are wired as [org.gradle.api.provider.Provider]-based properties so
 * the task is fully compatible with the Gradle configuration cache — no
 * [org.gradle.api.Project] references are used at execution time.
 */
@CacheableTask
abstract class CollectReachabilityMetadataTask : DefaultTask() {
    /**
     * Root component of the resolved dependency graph.
     *
     * Wired from the plugin as a lazy [Provider] obtained from
     * `configuration.incoming.resolutionResult.rootComponent` at configuration
     * time. This avoids accessing [org.gradle.api.Project] at execution time
     * (which is forbidden by the configuration cache).
     *
     * Marked [@Internal] because the derived [resolvedCoordinates] property
     * is used as the actual cache key for dependency changes.
     */
    @get:Internal
    abstract val rootComponent: Property<ResolvedComponentResult>

    /**
     * Handle to the shared [ReachabilityMetadataRepositoryService] that provides
     * the unpacked metadata repository. Marked [@Internal] because it is a
     * service reference, not a cacheable input.
     */
    @get:Internal
    abstract val repositoryService: Property<ReachabilityMetadataRepositoryService>

    /** Module coordinates ("group:artifact") to skip during metadata collection. */
    @get:Input
    abstract val excludes: SetProperty<String>

    /**
     * Per-module metadata version overrides ("group:artifact" → config version).
     * When present, the override version is used for the repository query instead
     * of the version resolved from the dependency graph.
     */
    @get:Input
    abstract val moduleToConfigVersion: MapProperty<String, String>

    /** Directory where the collected metadata files are written. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /**
     * Derived [@Input] that captures the sorted set of resolved GAV coordinates.
     *
     * This makes the task cache-correct: [rootComponent] itself is [@Internal],
     * but this derived property gives Gradle a stable, comparable snapshot of
     * what is on the classpath so that up-to-date checks and build caching work
     * correctly when dependencies change.
     */
    @get:Input
    val resolvedCoordinates: Set<String>
        get() =
            collectComponents(rootComponent.get())
                .mapNotNull { it.moduleVersion }
                .map { "${it.group}:${it.name}:${it.version}" }
                .toSortedSet()

    /**
     * Main task action: walks the resolved dependency graph and copies matching
     * reachability metadata into [outputDir].
     */
    @TaskAction
    fun run() {
        val output = outputDir.get().asFile.toPath()

        // Remove stale metadata from previous runs so removed dependencies
        // don't leave behind orphaned files.
        output.toFile().deleteRecursively()
        output.toFile().mkdirs()

        val repo = repositoryService.get().repository
        val excludeSet = excludes.getOrElse(emptySet())
        val versionOverrides = moduleToConfigVersion.getOrElse(emptyMap())

        collectComponents(rootComponent.get()).forEach { component ->
            val id = component.moduleVersion ?: return@forEach
            val groupArtifact = "${id.group}:${id.name}"

            // Skip explicitly excluded modules
            if (groupArtifact in excludeSet) {
                return@forEach
            }

            // Use overridden version if configured, otherwise the resolved version
            val version = versionOverrides[groupArtifact] ?: id.version
            val gav = "${id.group}:${id.name}:$version"

            // Query the metadata repository and copy any matching config files
            val configs: Set<DirectoryConfiguration> =
                repo.findConfigurationsFor { it.forArtifacts(gav) }

            DirectoryConfiguration.copy(configs, output)
        }
    }

    /**
     * Performs an iterative breadth-first traversal of the resolved dependency
     * graph starting from [root].
     *
     * Returns a [LinkedHashSet] of all unique [ResolvedComponentResult] nodes
     * encountered, preserving discovery order. Iterative (queue-based) to avoid
     * stack overflow on deeply nested dependency trees.
     */
    private fun collectComponents(root: ResolvedComponentResult): Set<ResolvedComponentResult> {
        val visited = LinkedHashSet<ResolvedComponentResult>()
        val queue = ArrayDeque<ResolvedComponentResult>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val current = queue.poll()
            if (!visited.add(current)) continue

            // Enqueue only successfully resolved transitive dependencies
            current.dependencies
                .filterIsInstance<ResolvedDependencyResult>()
                .forEach { queue.add(it.selected) }
        }

        return visited
    }
}
