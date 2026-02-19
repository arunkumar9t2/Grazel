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

import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.migrate.android.AndroidUnitTestData
import javax.inject.Inject

/**
 * Compresses Android unit test variant targets by grouping equivalent test variants together.
 *
 * Test compression is simpler than library compression because:
 * - Tests are never transitive dependencies (no dependency blocking needed)
 * - Tests can compress independently of their library dependencies' compression state
 * - Test targets reference libraries using the library's compressed suffix
 */
internal interface UnitTestVariantCompressor {
    /**
     * Compresses test variants based on equivalence analysis.
     *
     * Unlike library compression, this does NOT check dependency blocking since test targets
     * are never used as dependencies by other modules.
     *
     * @param variants Map of variant name to AndroidUnitTestData
     * @param buildTypeFn Function to extract build type name from variant name
     * @return UnitTestCompressionResult containing compressed test targets and mappings
     */
    fun compress(
        variants: Map<String, AndroidUnitTestData>,
        buildTypeFn: (String) -> String
    ): UnitTestCompressionResult
}

internal class DefaultUnitTestVariantCompressor @Inject constructor(
    private val equivalenceChecker: UnitTestEquivalenceChecker
) : UnitTestVariantCompressor {

    override fun compress(
        variants: Map<String, AndroidUnitTestData>,
        buildTypeFn: (String) -> String
    ): UnitTestCompressionResult {
        // Create engine with test-specific adapters
        val engine = VariantCompressionEngine<AndroidUnitTestData, UnitTestCompressionResult>(
            nameOf = { it.name },
            copyWithName = { data, newName -> data.copy(name = newName) },
            areAllEquivalent = { equivalenceChecker.areAllEquivalent(it) },
            buildTypeFn = buildTypeFn,
            resultFactory = { targets, mapping, expanded ->
                UnitTestCompressionResult(targets, mapping, expanded)
            }
        )

        // Configure Phase 2: cross-build-type compression for tests
        // Tests don't need dependency checks since they're leaf nodes
        val crossBuildTypeConfig = VariantCompressionEngine.CrossBuildTypeConfig<AndroidUnitTestData>(
            enabled = true,
            equivalenceCheck = { equivalenceChecker.areAllEquivalent(it) },
            dependencyCheck = null  // Tests are leaf nodes, no dependency blocking needed
        )

        // Run both Phase 1 and Phase 2 compression
        return engine.compressWithoutBlocking(
            variants = variants,
            crossBuildType = crossBuildTypeConfig
        ).toResult()
    }
}

/**
 * Checks if unit test variants are equivalent and can be compressed.
 */
internal interface UnitTestEquivalenceChecker {
    /**
     * Returns true if all test variants have identical configuration.
     *
     * Tests are equivalent if they have the same:
     * - Source files
     * - Additional source sets
     * - Resources
     * - Dependencies (normalized to ignore variant suffixes)
     * - Compose configuration
     * - Test size
     * - Custom package
     * - Tags
     *
     * Note: Associates are excluded from comparison since they reference the library target.
     */
    fun areAllEquivalent(variants: List<AndroidUnitTestData>): Boolean
}

internal class DefaultUnitTestEquivalenceChecker @Inject constructor() : UnitTestEquivalenceChecker {
    override fun areAllEquivalent(variants: List<AndroidUnitTestData>): Boolean {
        if (variants.size <= 1) return true

        val first = variants.first()
        return variants.drop(1).all { other ->
            first.srcs == other.srcs &&
                first.additionalSrcSets == other.additionalSrcSets &&
                first.resources == other.resources &&
                first.compose == other.compose &&
                first.testSize == other.testSize &&
                first.customPackage == other.customPackage &&
                first.tags == other.tags &&
                normalizeDeps(first.deps) == normalizeDeps(other.deps)
            // Note: associates excluded - they reference the library
        }
    }

    /**
     * Normalizes dependencies by removing variant suffixes for comparison.
     *
     * This allows tests to be equivalent even if they reference different variant
     * suffixes of the same library (e.g., "-free-debug" vs "-paid-debug").
     */
    private fun normalizeDeps(deps: List<BazelDependency>): Set<String> {
        return deps.map { dep ->
            when (dep) {
                is BazelDependency.ProjectDependency -> {
                    val basePath = dep.dependencyProject.path
                    // Remove suffix pattern like "-debug", "-free-debug"
                    basePath.replace(Regex("-[a-z]+(-[a-z]+)?$"), "")
                }
                else -> dep.toString()
            }
        }.toSet()
    }
}
