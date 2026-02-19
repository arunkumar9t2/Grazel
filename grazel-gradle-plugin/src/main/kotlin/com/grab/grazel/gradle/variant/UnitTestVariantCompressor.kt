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
import com.grab.grazel.migrate.android.FORMAT_UNIT_TEST_NAME
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
     * @param projectName The project name, used for deriving fully compressed target names
     * @param variants Map of variant name to AndroidUnitTestData
     * @param buildTypeFn Function to extract build type name from variant name
     * @return UnitTestCompressionResult containing compressed test targets and mappings
     */
    fun compress(
        projectName: String,
        variants: Map<String, AndroidUnitTestData>,
        buildTypeFn: (String) -> String
    ): UnitTestCompressionResult
}

internal class DefaultUnitTestVariantCompressor @Inject constructor(
    private val equivalenceChecker: UnitTestEquivalenceChecker
) : UnitTestVariantCompressor {

    override fun compress(
        projectName: String,
        variants: Map<String, AndroidUnitTestData>,
        buildTypeFn: (String) -> String
    ): UnitTestCompressionResult {
        val engine = VariantCompressionEngine<AndroidUnitTestData, UnitTestCompressionResult>(
            nameOf = { it.name },
            copyWithName = { data, newName -> data.copy(name = newName) },
            areAllEquivalent = { equivalenceChecker.areAllEquivalent(it) },
            buildTypeFn = buildTypeFn,
            compressName = { _, _, toSuffix ->
                FORMAT_UNIT_TEST_NAME.format(projectName, toSuffix)
            },
            resultFactory = { targets, mapping, expanded ->
                UnitTestCompressionResult(targets, mapping, expanded)
            }
        )

        val crossBuildTypeConfig = VariantCompressionEngine.CrossBuildTypeConfig<AndroidUnitTestData>(
            enabled = true,
            equivalenceCheck = { equivalenceChecker.areAllEquivalent(it) },
            dependencyCheck = null
        )

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
     * Normalizes dependencies by project identity for comparison.
     *
     * For project dependencies, uses the project path (ignoring variant suffix)
     * so that tests depending on different variant suffixes of the same library
     * are treated as equivalent.
     */
    private fun normalizeDeps(deps: List<BazelDependency>): Set<String> {
        return deps.map { dep ->
            when (dep) {
                is BazelDependency.ProjectDependency -> dep.dependencyProject.path
                else -> dep.toString()
            }
        }.toSet()
    }
}
