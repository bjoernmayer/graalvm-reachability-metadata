package io.github.bjoernmayer.graalvm

import org.graalvm.reachability.DirectoryConfiguration
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.services.ServiceReference
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.ArrayDeque

/**
 * Cacheable Gradle task that collects GraalVM reachability metadata for every
 * resolved **module** dependency on a given classpath configuration.
 *
 * For each external module dependency on the classpath (typically
 * `runtimeClasspath`), the task queries the
 * [ReachabilityMetadataRepositoryService] for matching metadata entries and
 * copies them into [outputDir].  The resulting directory can then be passed to
 * the GraalVM `native-image` tool via `-H:ConfigurationFileDirectories`.
 *
 * Project components (the root project itself, included builds, composite
 * builds) are automatically skipped — only external module dependencies
 * (identified by [ModuleComponentIdentifier]) are considered.
 *
 * The task uses the rich [org.graalvm.reachability.Query] API to:
 * - apply per-module **forced config versions** via
 *   [org.graalvm.reachability.Query.ArtifactQuery.forceConfigVersion],
 * - optionally fall back to the **latest available config** when a version is
 *   untested via [org.graalvm.reachability.Query.useLatestConfigWhenVersionIsUntested].
 *
 * All inputs are wired as [org.gradle.api.provider.Provider]-based properties
 * so the task is fully compatible with the Gradle **configuration cache** — no
 * [org.gradle.api.Project] references are used at execution time.
 *
 * The task is marked [@CacheableTask][CacheableTask] so Gradle can skip
 * execution or restore outputs from the build cache when inputs have not
 * changed.
 */
@CacheableTask
abstract class CollectReachabilityMetadataTask : DefaultTask() {
    // ── Inputs: dependency graph ────────────────────────────────────────

    /**
     * Root component of the resolved dependency graph.
     *
     * Wired from the plugin as a lazy [Provider] obtained from
     * `configuration.incoming.resolutionResult.rootComponent` at configuration
     * time.  This avoids accessing [org.gradle.api.Project] at execution time
     * (which is forbidden by the configuration cache).
     *
     * Marked [@Internal] because the derived [resolvedCoordinates] property is
     * used as the actual cache key for dependency changes.
     */
    @get:Internal
    abstract val rootComponent: Property<ResolvedComponentResult>

    /**
     * Derived [@Input] that captures the **sorted** set of resolved module GAV
     * coordinates.
     *
     * This makes the task cache-correct: [rootComponent] itself is [@Internal],
     * but this derived property gives Gradle a stable, comparable snapshot of
     * what is on the classpath so that up-to-date checks and build caching work
     * correctly when dependencies change.
     *
     * Only external module components ([ModuleComponentIdentifier]) are
     * included; project components are excluded because they never contribute
     * external reachability metadata.
     */
    @get:Input
    val resolvedCoordinates: Set<String>
        get() =
            collectModuleComponents(rootComponent.get())
                .map { "${it.group}:${it.module}:${it.version}" }
                .toSortedSet()

    // ── Inputs: repository & query configuration ────────────────────────

    /**
     * Handle to the shared [ReachabilityMetadataRepositoryService] that
     * provides the unpacked metadata repository.
     *
     * Annotated with [@ServiceReference][ServiceReference] so that Gradle
     * correctly bridges the build service across ClassLoader scopes when the
     * plugin is applied to multiple sibling projects via a convention plugin.
     * Without this annotation, Gradle loads the service type independently per
     * project scope, causing a type-mismatch error at task configuration time.
     */
    @get:ServiceReference
    abstract val repositoryService: Property<ReachabilityMetadataRepositoryService>

    /**
     * The resolved repository zip file (classifier=repository, ext=zip).
     *
     * Wired from the resolvable configuration in the plugin.  Kept on the task
     * (rather than in the build-service parameters) so that configuration
     * resolution happens via a proper Gradle provider chain, which is
     * configuration-cache safe.
     */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val repositoryZipFile: RegularFileProperty

    /**
     * Version of the GraalVM reachability metadata repository (e.g.
     * `"1.0.0"`).  Used as a cache input so that changing the repository
     * version correctly invalidates the build cache even when the set of
     * resolved coordinates stays the same.
     */
    @get:Input
    abstract val repositoryVersion: Property<String>

    /** Module coordinates (`"group:artifact"`) to skip during metadata collection. */
    @get:Input
    abstract val excludes: SetProperty<String>

    /**
     * Per-module metadata version overrides (`"group:artifact"` → config
     * version).  When present, the override version is forwarded to the
     * repository query via
     * [org.graalvm.reachability.Query.ArtifactQuery.forceConfigVersion]
     * instead of the version resolved from the dependency graph.
     */
    @get:Input
    abstract val moduleToConfigVersion: MapProperty<String, String>

    /**
     * When `true`, the repository query falls back to the latest available
     * configuration if no exact version match is found.
     */
    @get:Input
    abstract val useLatestConfigWhenVersionIsUntested: Property<Boolean>

    // ── Outputs ─────────────────────────────────────────────────────────

    /** Directory where the collected metadata files are written. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /**
     * Optional report file listing every resolved module, its status, and the
     * config directories that were copied.  When the property is not set, no
     * report is generated.
     */
    @get:OutputFile
    @get:Optional
    abstract val reportFile: RegularFileProperty

    // ── Task action ─────────────────────────────────────────────────────

    /**
     * Main task action: walks the resolved dependency graph, queries the
     * metadata repository for each external module, and copies all matching
     * metadata into [outputDir] in a single [DirectoryConfiguration.copy]
     * call.
     */
    @TaskAction
    fun run() {
        val output = outputDir.get().asFile.toPath()

        // Remove stale metadata from previous runs so removed dependencies
        // don't leave behind orphaned files.
        output.toFile().deleteRecursively()
        output.toFile().mkdirs()

        val repo = repositoryService.get().getRepository(repositoryZipFile.get().asFile)
        val excludeSet = excludes.getOrElse(emptySet())
        val versionOverrides = moduleToConfigVersion.getOrElse(emptyMap())
        val useLatest = useLatestConfigWhenVersionIsUntested.getOrElse(false)

        // Collect external module IDs, sorted for deterministic processing
        val modules =
            collectModuleComponents(rootComponent.get())
                .sortedWith(compareBy({ it.group }, { it.module }, { it.version }))

        val allConfigs = mutableListOf<DirectoryConfiguration>()
        val reportEntries = mutableListOf<ReportEntry>()

        for (id in modules) {
            val ga = "${id.group}:${id.module}"
            val gav = "${id.group}:${id.module}:${id.version}"

            // Skip explicitly excluded modules
            if (ga in excludeSet) {
                reportEntries.add(ReportEntry(gav, STATUS_EXCLUDED, emptyList()))

                logger.info("Skipping excluded module: {}", ga)

                continue
            }

            // Query the metadata repository using the rich Query API.
            // This correctly uses forceConfigVersion (rather than substituting
            // the version in the GAV string) so that the repository's fallback
            // logic (useLatestConfigWhenVersionIsUntested, default-for patterns)
            // works against the *original* resolved version.
            val configs =
                repo.findConfigurationsFor { query ->
                    if (useLatest) {
                        query.useLatestConfigWhenVersionIsUntested()
                    }
                    query.forArtifact { aq ->
                        aq.gav(gav)
                        versionOverrides[ga]?.let { aq.forceConfigVersion(it) }
                    }
                }

            if (configs.isEmpty()) {
                reportEntries.add(ReportEntry(gav, STATUS_NOT_FOUND, emptyList()))

                logger.info("No metadata found for {}", gav)
            } else {
                val dirs = configs.map { it.directory.fileName.toString() }

                reportEntries.add(ReportEntry(gav, STATUS_FOUND, dirs))
                allConfigs.addAll(configs)

                logger.info("Found {} metadata config(s) for {}", configs.size, gav)
            }
        }

        // Copy all collected metadata in one go
        DirectoryConfiguration.copy(allConfigs, output)

        // Write diagnostics
        writeReport(reportEntries)
        logSummary(reportEntries, modules.size)
    }

    // ── Diagnostics ─────────────────────────────────────────────────────

    private fun writeReport(entries: List<ReportEntry>) {
        if (!reportFile.isPresent) return

        val file = reportFile.get().asFile
        file.parentFile?.mkdirs()

        val found = entries.count { it.status == STATUS_FOUND }
        val excluded = entries.count { it.status == STATUS_EXCLUDED }
        val notFound = entries.count { it.status == STATUS_NOT_FOUND }

        file.bufferedWriter().use { w ->
            w.appendLine("# GraalVM Reachability Metadata Collection Report")
            w.appendLine("# Repository version: ${repositoryVersion.get()}")
            w.appendLine("# Total modules: ${entries.size}")
            w.appendLine("# Metadata found: $found")
            w.appendLine("# Excluded: $excluded")
            w.appendLine("# No metadata: $notFound")
            w.appendLine("#")
            w.appendLine("# GAV\tStatus\tConfig directories")
            for (entry in entries) {
                val dirs = entry.configDirectories.joinToString(", ")
                w.appendLine("${entry.gav}\t${entry.status}\t$dirs")
            }
        }
    }

    private fun logSummary(
        entries: List<ReportEntry>,
        totalModules: Int,
    ) {
        val found = entries.count { it.status == STATUS_FOUND }
        val excluded = entries.count { it.status == STATUS_EXCLUDED }
        val notFound = entries.count { it.status == STATUS_NOT_FOUND }

        logger.lifecycle(
            "Reachability metadata: {} modules analyzed, {} with metadata, {} excluded, {} without metadata",
            totalModules,
            found,
            excluded,
            notFound,
        )

        if (reportFile.isPresent) {
            logger.lifecycle("Report written to {}", reportFile.get().asFile)
        }
    }

    // ── Graph traversal ─────────────────────────────────────────────────

    /**
     * Performs an iterative breadth-first traversal of the resolved dependency
     * graph starting from [root], returning only external **module** component
     * identifiers ([ModuleComponentIdentifier]).
     *
     * Project components (the root project, included builds, etc.) are
     * silently skipped — they never contribute external reachability metadata.
     *
     * Returns a [Set] (preserving insertion order) of all unique module
     * component IDs encountered.  Iterative (queue-based) to avoid stack
     * overflow on deeply nested dependency trees.
     */
    internal fun collectModuleComponents(root: ResolvedComponentResult): Set<ModuleComponentIdentifier> {
        val visited = LinkedHashSet<ResolvedComponentResult>()
        val modules = LinkedHashSet<ModuleComponentIdentifier>()
        val queue = ArrayDeque<ResolvedComponentResult>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val current = queue.poll()
            if (!visited.add(current)) continue

            val id = current.id
            if (id is ModuleComponentIdentifier) {
                modules.add(id)
            }

            // Enqueue only successfully resolved transitive dependencies
            current.dependencies
                .filterIsInstance<ResolvedDependencyResult>()
                .forEach { queue.add(it.selected) }
        }

        return modules
    }

    // ── Internal types ──────────────────────────────────────────────────

    /**
     * A single entry in the diagnostics report.
     */
    internal data class ReportEntry(
        val gav: String,
        val status: String,
        val configDirectories: List<String>,
    )

    private companion object {
        const val STATUS_FOUND = "found"
        const val STATUS_NOT_FOUND = "not-found"
        const val STATUS_EXCLUDED = "excluded"
    }
}
