package io.github.bjoernmayer.graalvm

import org.graalvm.reachability.GraalVMReachabilityMetadataRepository
import org.graalvm.reachability.internal.FileSystemRepository
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.io.File
import javax.inject.Inject

/**
 * Gradle [BuildService] that manages the GraalVM reachability metadata repository.
 *
 * Registered as a shared service so that:
 * - The repository zip is unpacked **at most once** per build, even if multiple
 *   tasks consume metadata.
 * - Gradle can coordinate task scheduling via [org.gradle.api.Task.usesService].
 * - The unpacked repository is cached across builds under [Params.unpackDir],
 *   keyed by repository version.
 *
 * The [repository] property is lazily initialized: the zip is only extracted on
 * first access, and a marker file guards against reuse of partially unpacked
 * directories (e.g. from a killed build).
 */
abstract class ReachabilityMetadataRepositoryService : BuildService<ReachabilityMetadataRepositoryService.Params> {
    interface Params : BuildServiceParameters {
        /** The resolved repository zip (classifier=repository, ext=zip). */
        val repositoryZip: RegularFileProperty

        /** Directory to unpack into (cache). */
        val unpackDir: DirectoryProperty

        /** Re-unpack even if present (mostly for debugging). */
        val forceUnpack: Property<Boolean>
    }

    /** Gradle-injected service for working with zip/tar archives. */
    @get:Inject
    abstract val archiveOps: ArchiveOperations

    /** Gradle-injected service for file copy/delete operations. */
    @get:Inject
    abstract val fsOps: FileSystemOperations

    private companion object {
        /**
         * Sentinel file written after a successful unpack.
         * If missing, the directory is considered corrupt or incomplete and will be re-extracted.
         */
        const val UNPACK_COMPLETE_MARKER = ".unpack-complete"
    }

    /**
     * Lazily initialized [GraalVMReachabilityMetadataRepository] backed by the
     * unpacked file-system repository.
     *
     * On first access:
     * 1. Checks whether [UNPACK_COMPLETE_MARKER] exists in [Params.unpackDir].
     * 2. If missing (or [Params.forceUnpack] is true), deletes the directory,
     *    extracts the zip, and writes the marker.
     * 3. Creates a [FileSystemRepository] pointing at the unpacked directory.
     *
     * Thread-safe via Kotlin's [lazy] (uses `LazyThreadSafetyMode.SYNCHRONIZED`
     * by default).
     */
    val repository: GraalVMReachabilityMetadataRepository by lazy {
        val zipFile: File = parameters.repositoryZip.get().asFile
        val unpackDirFile: File = parameters.unpackDir.get().asFile
        val marker = File(unpackDirFile, UNPACK_COMPLETE_MARKER)

        if (parameters.forceUnpack.getOrElse(false) || !marker.exists()) {
            // Wipe any partial extraction and re-extract from scratch
            fsOps.delete { it.delete(unpackDirFile) }
            fsOps.copy {
                it.from(archiveOps.zipTree(zipFile))
                it.into(unpackDirFile)
            }
            // Mark the extraction as complete so subsequent builds can skip it
            marker.createNewFile()
        }

        FileSystemRepository(unpackDirFile.toPath())
    }
}
