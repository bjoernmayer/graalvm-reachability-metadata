package io.github.bjoernmayer.graalvm

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Gradle TestKit functional tests that exercise the plugin end-to-end against
 * a real (temporary) Gradle project.
 */
class CollectReachabilityMetadataFunctionalTest {
    @TempDir
    lateinit var projectDir: File

    private val buildFile by lazy { File(projectDir, "build.gradle.kts") }
    private val settingsFile by lazy { File(projectDir, "settings.gradle.kts") }
    private val outputDir by lazy { File(projectDir, "build/generated/native-reachability-metadata") }
    private val reportFile by lazy { File(projectDir, "build/reports/graalvm-reachability-metadata/report.txt") }

    @BeforeEach
    fun setup() {
        settingsFile.writeText(
            """
            rootProject.name = "functional-test"
            """.trimIndent(),
        )
    }

    private fun runner(vararg args: String) =
        GradleRunner
            .create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*args, "--stacktrace", "--no-configuration-cache")
            .forwardOutput()

    @Nested
    inner class BasicWiring {
        @Test
        fun `plugin applies and task is registered`() {
            buildFile.writeText(
                """
                plugins {
                    java
                    id("io.github.bjoernmayer.graalvm-reachability-metadata")
                }

                repositories { mavenCentral() }
                """.trimIndent(),
            )

            val result = runner("tasks", "--group=build").build()

            assertTrue(result.output.contains("collectReachabilityMetadata"))
        }
    }

    @Nested
    inner class MetadataCollection {
        @Test
        fun `collects metadata for dependency with known metadata`() {
            buildFile.writeText(
                """
                plugins {
                    java
                    id("io.github.bjoernmayer.graalvm-reachability-metadata")
                }

                repositories { mavenCentral() }

                dependencies {
                    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
                }
                """.trimIndent(),
            )

            val result = runner("collectReachabilityMetadata").build()

            assertEquals(TaskOutcome.SUCCESS, result.task(":collectReachabilityMetadata")!!.outcome)
            assertTrue(outputDir.isDirectory, "Output directory should exist")
            assertTrue(
                outputDir.walk().any { it.name == "reachability-metadata.json" },
                "Expected reachability-metadata.json in output",
            )
            assertTrue(reportFile.exists(), "Report file should exist")
            val report = reportFile.readText()
            assertTrue(report.contains("jackson-databind"), "Report should mention jackson-databind")
            assertTrue(report.contains("found"), "Report should contain 'found' status")
        }

        @Test
        fun `no metadata for dependency without repository entry`() {
            buildFile.writeText(
                """
                plugins {
                    java
                    id("io.github.bjoernmayer.graalvm-reachability-metadata")
                }

                repositories { mavenCentral() }

                dependencies {
                    implementation("org.slf4j:slf4j-api:2.0.9")
                }
                """.trimIndent(),
            )

            val result = runner("collectReachabilityMetadata").build()

            assertEquals(TaskOutcome.SUCCESS, result.task(":collectReachabilityMetadata")!!.outcome)
            assertTrue(reportFile.exists(), "Report file should exist")
            assertTrue(reportFile.readText().contains("not-found"), "Report should contain 'not-found' status")
        }
    }

    @Nested
    inner class Excludes {
        @Test
        fun `excluded module is skipped`() {
            buildFile.writeText(
                """
                plugins {
                    java
                    id("io.github.bjoernmayer.graalvm-reachability-metadata")
                }

                repositories { mavenCentral() }

                dependencies {
                    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
                }

                reachabilityMetadata {
                    excludes.add("com.fasterxml.jackson.core:jackson-databind")
                }
                """.trimIndent(),
            )

            val result = runner("collectReachabilityMetadata").build()

            assertEquals(TaskOutcome.SUCCESS, result.task(":collectReachabilityMetadata")!!.outcome)
            val report = reportFile.readText()
            assertTrue(report.contains("excluded"), "Report should contain 'excluded' status")
            assertFalse(
                outputDir.walk().any { it.path.contains("jackson-databind") },
                "jackson-databind metadata should not be in output",
            )
        }
    }

    @Nested
    inner class StaleMetadataRemoval {
        @Test
        fun `stale metadata is removed when dependency is dropped`() {
            // First run: with jackson-databind
            buildFile.writeText(
                """
                plugins {
                    java
                    id("io.github.bjoernmayer.graalvm-reachability-metadata")
                }

                repositories { mavenCentral() }

                dependencies {
                    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
                }
                """.trimIndent(),
            )

            runner("collectReachabilityMetadata").build()
            assertTrue(
                outputDir.walk().any { it.path.contains("jackson-databind") },
                "jackson-databind metadata should be present after first run",
            )

            // Second run: dependency removed
            buildFile.writeText(
                """
                plugins {
                    java
                    id("io.github.bjoernmayer.graalvm-reachability-metadata")
                }

                repositories { mavenCentral() }
                """.trimIndent(),
            )

            runner("collectReachabilityMetadata").build()
            assertFalse(
                outputDir.walk().any { it.path.contains("jackson-databind") },
                "jackson-databind metadata should be gone after dependency removal",
            )
        }
    }

    @Nested
    inner class Caching {
        @Test
        fun `second run is up-to-date`() {
            buildFile.writeText(
                """
                plugins {
                    java
                    id("io.github.bjoernmayer.graalvm-reachability-metadata")
                }

                repositories { mavenCentral() }

                dependencies {
                    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
                }
                """.trimIndent(),
            )

            runner("collectReachabilityMetadata").build()
            val result = runner("collectReachabilityMetadata").build()

            assertEquals(TaskOutcome.UP_TO_DATE, result.task(":collectReachabilityMetadata")!!.outcome)
        }
    }
}
