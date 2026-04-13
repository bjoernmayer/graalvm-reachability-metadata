package io.github.bjoernmayer.graalvm

import org.gradle.api.artifacts.ModuleIdentifier
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.DependencyResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.ResolvedVariantResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [CollectReachabilityMetadataTask.collectModuleComponents].
 *
 * Uses stub implementations of [ResolvedComponentResult] to exercise the BFS
 * traversal without requiring a real Gradle build.
 */
class CollectModuleComponentsTest {
    private val task =
        org.gradle.testfixtures.ProjectBuilder
            .builder()
            .build()
            .tasks
            .create("test", CollectReachabilityMetadataTask::class.java)

    // ── Stub types ───────────────────────────────────────────────────────

    /** Minimal [ModuleIdentifier] stub. */
    private data class StubModuleIdentifier(
        private val grp: String,
        private val name: String,
    ) : ModuleIdentifier {
        override fun getGroup() = grp

        override fun getName() = name
    }

    /** Minimal [ModuleComponentIdentifier] stub. */
    private data class StubModuleId(
        private val grp: String,
        private val mod: String,
        private val ver: String,
    ) : ModuleComponentIdentifier {
        override fun getGroup() = grp

        override fun getModule() = mod

        override fun getVersion() = ver

        override fun getModuleIdentifier() = StubModuleIdentifier(grp, mod)

        override fun getDisplayName() = "$grp:$mod:$ver"

        override fun toString() = displayName
    }

    /** Minimal [ComponentIdentifier] representing a project. */
    private data class StubProjectId(
        private val path: String,
    ) : ComponentIdentifier {
        override fun getDisplayName() = "project $path"

        override fun toString() = displayName
    }

    /** Minimal [ResolvedDependencyResult] stub that wraps a selected component. */
    private class StubDependency(
        private val child: ResolvedComponentResult,
    ) : ResolvedDependencyResult {
        override fun getSelected() = child

        override fun getResolvedVariant(): ResolvedVariantResult = throw UnsupportedOperationException()

        override fun getRequested() = throw UnsupportedOperationException()

        override fun isConstraint() = false

        override fun getFrom(): ResolvedComponentResult = throw UnsupportedOperationException()
    }

    /**
     * Minimal [ResolvedComponentResult] stub that returns the given [componentId]
     * and wraps each child as a [StubDependency].
     */
    private class StubComponent(
        private val componentId: ComponentIdentifier,
        private val children: List<ResolvedComponentResult> = emptyList(),
    ) : ResolvedComponentResult {
        override fun getId() = componentId

        override fun getDependencies(): Set<DependencyResult> = children.map { StubDependency(it) }.toSet()

        override fun getDependenciesForVariant(variant: ResolvedVariantResult): List<DependencyResult> =
            throw UnsupportedOperationException()

        override fun getSelectionReason() = throw UnsupportedOperationException()

        override fun getVariants() = throw UnsupportedOperationException()

        override fun getDependents() = throw UnsupportedOperationException()

        override fun getModuleVersion() = null
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun component(
        id: ComponentIdentifier,
        vararg deps: ResolvedComponentResult,
    ) = StubComponent(id, deps.toList())

    private fun moduleId(
        group: String,
        module: String,
        version: String,
    ) = StubModuleId(group, module, version)

    private fun projectId(path: String) = StubProjectId(path)

    // ── Tests ────────────────────────────────────────────────────────────

    @Test
    fun `root project with no dependencies yields empty set`() {
        val root = component(projectId(":"))

        val result = task.collectModuleComponents(root)

        assertTrue(result.isEmpty(), "Expected empty set for root-only graph")
    }

    @Test
    fun `single module dependency is collected`() {
        val jacksonId = moduleId("com.fasterxml.jackson.core", "jackson-databind", "2.18.3")
        val jackson = component(jacksonId)
        val root = component(projectId(":"), jackson)

        val result = task.collectModuleComponents(root)

        assertEquals(setOf(jacksonId), result)
    }

    @Test
    fun `transitive dependencies are collected`() {
        val annotationsId = moduleId("com.fasterxml.jackson.core", "jackson-annotations", "2.18.3")
        val coreId = moduleId("com.fasterxml.jackson.core", "jackson-core", "2.18.3")
        val databindId = moduleId("com.fasterxml.jackson.core", "jackson-databind", "2.18.3")

        val annotations = component(annotationsId)
        val core = component(coreId)
        val databind = component(databindId, annotations, core)
        val root = component(projectId(":"), databind)

        val result = task.collectModuleComponents(root)

        assertEquals(setOf(databindId, annotationsId, coreId), result)
    }

    @Test
    fun `diamond dependency graph does not produce duplicates`() {
        val commonId = moduleId("org.example", "common", "1.0")
        val common = component(commonId)

        val libAId = moduleId("org.example", "lib-a", "1.0")
        val libBId = moduleId("org.example", "lib-b", "1.0")
        val libA = component(libAId, common)
        val libB = component(libBId, common)

        val root = component(projectId(":"), libA, libB)

        val result = task.collectModuleComponents(root)

        assertEquals(setOf(libAId, libBId, commonId), result)
    }

    @Test
    fun `project components are skipped`() {
        val modId = moduleId("org.example", "external-lib", "2.0")
        val moduleComp = component(modId)

        val subprojectComp = component(projectId(":subproject"), moduleComp)
        val root = component(projectId(":"), subprojectComp)

        val result = task.collectModuleComponents(root)

        assertEquals(setOf(modId), result, "Only the external module should be collected")
    }

    @Test
    fun `root module component is itself collected`() {
        val rootId = moduleId("org.example", "root-module", "1.0")
        val root = component(rootId)

        val result = task.collectModuleComponents(root)

        assertEquals(setOf(rootId), result)
    }

    @Test
    fun `cyclic graph is handled without infinite loop`() {
        val aId = moduleId("org.example", "a", "1.0")
        val bId = moduleId("org.example", "b", "1.0")

        val a = StubComponent(aId)
        val b = StubComponent(bId)

        val aWithCycle =
            object : ResolvedComponentResult by a {
                override fun getDependencies(): Set<DependencyResult> = setOf(StubDependency(b))
            }
        val bWithCycle =
            object : ResolvedComponentResult by b {
                override fun getDependencies(): Set<DependencyResult> = setOf(StubDependency(aWithCycle))
            }

        val root = component(projectId(":"), aWithCycle, bWithCycle)

        val result = task.collectModuleComponents(root)

        assertEquals(setOf(aId, bId), result)
    }
}
