/*
 * Copyright 2022 Grabtaxi Holdings PTE LTD (GRAB)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.grab.grazel.gradle.variant

import com.grab.grazel.GrazelPluginTest
import com.grab.grazel.bazel.TestSize
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.buildProject
import com.grab.grazel.migrate.android.AndroidUnitTestData
import org.gradle.api.Project
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnitTestVariantCompressorTest : GrazelPluginTest() {

    private lateinit var compressor: UnitTestVariantCompressor
    private lateinit var checker: UnitTestEquivalenceChecker
    private lateinit var project: Project

    @Before
    fun setup() {
        checker = DefaultUnitTestEquivalenceChecker()
        compressor = DefaultUnitTestVariantCompressor(checker)
        project = buildProject("root")
    }

    private fun createTestData(
        name: String,
        deps: List<BazelDependency> = emptyList(),
        srcs: List<String> = listOf("src/test/kotlin/**/*.kt"),
        compose: Boolean = false,
        testSize: TestSize = TestSize.MEDIUM
    ): AndroidUnitTestData {
        return AndroidUnitTestData(
            name = name,
            srcs = srcs,
            additionalSrcSets = emptyList(),
            deps = deps,
            tags = emptyList(),
            customPackage = "com.example",
            associates = emptyList(),
            resources = emptyList(),
            compose = compose,
            testSize = testSize
        )
    }

    private fun buildTypeFn(variantName: String): String {
        return when {
            variantName.endsWith("Debug", ignoreCase = true) ||
                variantName.endsWith("DebugUnitTest", ignoreCase = true) -> "debug"
            variantName.endsWith("Release", ignoreCase = true) ||
                variantName.endsWith("ReleaseUnitTest", ignoreCase = true) -> "release"
            else -> "debug"
        }
    }

    @Test
    fun `compress empty variants returns empty result`() {
        val result = compressor.compress(
            projectName = "my-lib",
            variants = emptyMap(),
            buildTypeFn = ::buildTypeFn
        )

        assertTrue(result.suffixes.isEmpty())
        assertTrue(result.targets.isEmpty())
        assertTrue(result.expandedBuildTypes.isEmpty())
    }

    @Test
    fun `compress single variant keeps it as-is with normalized suffix`() {
        val variants = mapOf(
            "freeDebugUnitTest" to createTestData("my-lib-test")
        )

        val result = compressor.compress(
            projectName = "my-lib",
            variants = variants,
            buildTypeFn = ::buildTypeFn
        )

        assertEquals(1, result.suffixes.size)
        assertEquals(1, result.targets.size)
        assertEquals(
            "-free-debug-unit-test",
            result.suffixForVariant("freeDebugUnitTest")
        )
        assertEquals("my-lib-test", result.dataForSuffix("-free-debug-unit-test").name)
    }

    @Test
    fun `compress equivalent variants within build type deduplicates to one target`() {
        // When library is fully compressed, all test variants get the same name
        val variants = mapOf(
            "freeDebugUnitTest" to createTestData("my-lib-test"),
            "paidDebugUnitTest" to createTestData("my-lib-test")
        )

        val result = compressor.compress(
            projectName = "my-lib",
            variants = variants,
            buildTypeFn = ::buildTypeFn
        )

        // Should compress to single "-debug" target with build-type-derived name
        assertEquals(1, result.suffixes.size)
        assertTrue(result.suffixes.contains("-debug"))
        assertEquals("my-lib-debug-test", result.dataForSuffix("-debug").name)

        // Both variants map to compressed suffix
        assertEquals("-debug", result.suffixForVariant("freeDebugUnitTest"))
        assertEquals("-debug", result.suffixForVariant("paidDebugUnitTest"))
    }

    @Test
    fun `compress non-equivalent variants within build type expands them`() {
        val variants = mapOf(
            "freeDebugUnitTest" to createTestData("my-lib-test", compose = true),
            "paidDebugUnitTest" to createTestData("my-lib-test", compose = false)
        )

        val result = compressor.compress(
            projectName = "my-lib",
            variants = variants,
            buildTypeFn = ::buildTypeFn
        )

        // Should keep expanded
        assertEquals(2, result.suffixes.size)
        assertTrue(result.expandedBuildTypes.contains("debug"))
        assertEquals(
            "-free-debug-unit-test",
            result.suffixForVariant("freeDebugUnitTest")
        )
        assertEquals(
            "-paid-debug-unit-test",
            result.suffixForVariant("paidDebugUnitTest")
        )
    }

    @Test
    fun `compress equivalent across build types fully compresses`() {
        val variants = mapOf(
            "freeDebugUnitTest" to createTestData("my-lib-debug-test"),
            "paidDebugUnitTest" to createTestData("my-lib-debug-test"),
            "freeReleaseUnitTest" to createTestData("my-lib-release-test"),
            "paidReleaseUnitTest" to createTestData("my-lib-release-test")
        )

        val result = compressor.compress(
            projectName = "my-lib",
            variants = variants,
            buildTypeFn = ::buildTypeFn
        )

        // Should fully compress to single target with empty suffix
        assertEquals(1, result.suffixes.size)
        assertTrue(result.suffixes.contains(""))
        assertTrue(result.isFullyCompressed)
        assertEquals("my-lib-test", result.dataForSuffix("").name)

        // All variants map to empty suffix
        assertEquals("", result.suffixForVariant("freeDebugUnitTest"))
        assertEquals("", result.suffixForVariant("paidDebugUnitTest"))
        assertEquals("", result.suffixForVariant("freeReleaseUnitTest"))
        assertEquals("", result.suffixForVariant("paidReleaseUnitTest"))
    }

    @Test
    fun `compress non-equivalent across build types preserves build-type targets`() {
        val variants = mapOf(
            "freeDebugUnitTest" to createTestData("my-lib-debug-test", testSize = TestSize.SMALL),
            "paidDebugUnitTest" to createTestData("my-lib-debug-test", testSize = TestSize.SMALL),
            "freeReleaseUnitTest" to createTestData("my-lib-release-test", testSize = TestSize.LARGE),
            "paidReleaseUnitTest" to createTestData("my-lib-release-test", testSize = TestSize.LARGE)
        )

        val result = compressor.compress(
            projectName = "my-lib",
            variants = variants,
            buildTypeFn = ::buildTypeFn
        )

        // Phase 1 compresses within build types, Phase 2 blocked because targets differ
        assertEquals(2, result.suffixes.size)
        assertFalse(result.isFullyCompressed)
        assertTrue(result.suffixes.containsAll(listOf("-debug", "-release")))
        assertTrue(result.expandedBuildTypes.isEmpty())

        assertEquals("-debug", result.suffixForVariant("freeDebugUnitTest"))
        assertEquals("-debug", result.suffixForVariant("paidDebugUnitTest"))
        assertEquals("-release", result.suffixForVariant("freeReleaseUnitTest"))
        assertEquals("-release", result.suffixForVariant("paidReleaseUnitTest"))
    }

    @Test
    fun `normalizeDeps treats project deps with different suffixes as equivalent`() {
        val depProject = buildProject("dependency", parent = project)

        val variants = mapOf(
            "freeDebugUnitTest" to createTestData(
                "my-lib-test",
                deps = listOf(
                    BazelDependency.ProjectDependency(depProject, suffix = "-free-debug")
                )
            ),
            "paidDebugUnitTest" to createTestData(
                "my-lib-test",
                deps = listOf(
                    BazelDependency.ProjectDependency(depProject, suffix = "-paid-debug")
                )
            )
        )

        val result = compressor.compress(
            projectName = "my-lib",
            variants = variants,
            buildTypeFn = ::buildTypeFn
        )

        // Should compress because normalizeDeps treats same project with different suffixes as equivalent
        assertEquals(1, result.suffixes.size)
        assertTrue(result.suffixes.contains("-debug"))
    }

    @Test
    fun `variantToSuffix validation passes for all compression scenarios`() {
        // This test verifies the init check in UnitTestCompressionResult doesn't throw
        val variants = mapOf(
            "freeDebugUnitTest" to createTestData("my-lib-debug-test"),
            "paidDebugUnitTest" to createTestData("my-lib-debug-test"),
            "freeReleaseUnitTest" to createTestData("my-lib-release-test"),
            "paidReleaseUnitTest" to createTestData("my-lib-release-test")
        )

        // Should not throw - all suffix refs must exist as keys in targetsBySuffix
        val result = compressor.compress(
            projectName = "my-lib",
            variants = variants,
            buildTypeFn = ::buildTypeFn
        )

        // Verify the result is valid (init check passed)
        val allSuffixValues = result.variantToSuffix.values.toSet()
        assertTrue(allSuffixValues.all { it in result.targetsBySuffix })
    }
}
