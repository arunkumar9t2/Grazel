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
import com.grab.grazel.migrate.android.AndroidLibraryData
import org.gradle.api.Project
import javax.inject.Inject

/** Describes why compression succeeded or failed for a build type group */
internal sealed class VariantCompressionDecision {
    data class Compressed(
        val buildType: String,
        val variants: List<String>,
        val compressedSuffix: String
    ) : VariantCompressionDecision()

    data class Expanded(
        val buildType: String,
        val variants: List<String>,
        val reason: String
    ) : VariantCompressionDecision()

    data class SingleVariant(
        val buildType: String,
        val variant: String,
        val suffix: String
    ) : VariantCompressionDecision()

    /**
     * Represents full compression across all build types into a single target. This occurs when all
     * build-type targets are equivalent and all dependencies are also fully compressed.
     */
    data class FullyCompressed(
        val buildTypes: List<String>,
        val variants: List<String>
    ) : VariantCompressionDecision()
}

internal data class CompressionResultWithDecisions(
    val result: VariantCompressionResult,
    val decisions: List<VariantCompressionDecision>
)

/**
 * Compresses Android variant targets by grouping equivalent variants together.
 *
 * Compression reduces the number of Bazel targets by identifying variants that have identical build
 * configuration and combining them into a single target per build type.
 */
internal interface VariantCompressor {
    /**
     * Compresses variants based on equivalence analysis.
     *
     * @param variants Map of variant name to AndroidLibraryData
     * @param buildTypeFn Function to extract build type name from variant name
     * @param dependencyVariantCompressionResults Map of dependency project to their
     *    CompressionResult
     * @param checkDependencyBlocking If true, compression is blocked when dependencies are expanded.
     *    If false, compression proceeds regardless of dependency state (for test targets).
     * @return CompressionResultWithDecisions containing compressed targets, mappings, and decision
     *    info
     */
    fun compress(
        variants: Map<String, AndroidLibraryData>,
        buildTypeFn: (String) -> String,
        dependencyVariantCompressionResults: Map<Project, VariantCompressionResult>,
        checkDependencyBlocking: Boolean = true
    ): CompressionResultWithDecisions
}

internal class DefaultVariantCompressor @Inject constructor(
    private val equivalenceChecker: VariantEquivalenceChecker
) : VariantCompressor {

    // =========================================================================
    // Public API
    // =========================================================================

    override fun compress(
        variants: Map<String, AndroidLibraryData>,
        buildTypeFn: (String) -> String,
        dependencyVariantCompressionResults: Map<Project, VariantCompressionResult>,
        checkDependencyBlocking: Boolean
    ): CompressionResultWithDecisions {
        if (variants.isEmpty()) {
            return CompressionResultWithDecisions(
                result = VariantCompressionResult.empty(),
                decisions = emptyList()
            )
        }

        // Create engine with library-specific adapters
        val engine = VariantCompressionEngine<AndroidLibraryData, VariantCompressionResult>(
            nameOf = { it.name },
            copyWithName = { data, newName -> data.copy(name = newName) },
            areAllEquivalent = { areAllVariantsEquivalent(it) },
            buildTypeFn = buildTypeFn,
            resultFactory = { targets, mapping, expanded ->
                VariantCompressionResult(targets, mapping, expanded)
            }
        )

        // Configure Phase 2: cross-build-type compression with dependency checks
        val crossBuildTypeConfig = VariantCompressionEngine.CrossBuildTypeConfig<AndroidLibraryData>(
            enabled = true,
            equivalenceCheck = { areAllVariantsEquivalent(it) },
            dependencyCheck = {
                // Check if all dependencies are fully compressed
                val projectDeps = variants.values
                    .flatMap { it.deps }
                    .filterIsInstance<BazelDependency.ProjectDependency>()
                    .map { it.dependencyProject }
                    .toSet()

                val blocked = projectDeps.any { dep ->
                    dependencyVariantCompressionResults[dep]?.isFullyCompressed == false
                }

                if (blocked) {
                    val blockingDeps = projectDeps.filter {
                        dependencyVariantCompressionResults[it]?.isFullyCompressed == false
                    }
                    "blocked by non-fully-compressed dependencies: ${blockingDeps.joinToString(", ") { it.path }}"
                } else null
            }
        )

        // Run both Phase 1 and Phase 2 compression in engine
        val result = if (checkDependencyBlocking) {
            engine.compressWithBlocking(
                variants = variants,
                blockingReason = { buildType, vars ->
                    if (isCompressionBlocked(vars, buildType, dependencyVariantCompressionResults)) {
                        val deps = findBlockingDependencies(vars, buildType, dependencyVariantCompressionResults)
                        "blocked by dependencies: ${deps.joinToString(", ")}"
                    } else null
                },
                crossBuildType = crossBuildTypeConfig
            )
        } else {
            engine.compressWithoutBlocking(variants, crossBuildType = crossBuildTypeConfig)
        }

        // Build decision list if full compression occurred
        val decisions = mutableListOf<VariantCompressionDecision>()
        val finalResult = result.toResult()
        if (finalResult.isFullyCompressed) {
            decisions.add(
                VariantCompressionDecision.FullyCompressed(
                    buildTypes = variants.values.map { buildTypeFn(it.name) }.distinct(),
                    variants = variants.keys.toList()
                )
            )
        }

        return CompressionResultWithDecisions(
            result = finalResult,
            decisions = decisions
        )
    }

    // =========================================================================
    // Shared Helpers
    // =========================================================================

    /**
     * Checks if compression is blocked for a build type because any direct dependency is expanded
     * for that build type.
     */
    private fun isCompressionBlocked(
        variants: Map<String, AndroidLibraryData>,
        buildType: String,
        dependencyResults: Map<Project, VariantCompressionResult>
    ): Boolean {
        val projectDependencies = extractProjectDependencies(variants)
        return projectDependencies.any { depProject ->
            dependencyResults[depProject]?.isExpanded(buildType) ?: false
        }
    }

    /**
     * Finds the project dependencies that are blocking compression for a given build type.
     */
    private fun findBlockingDependencies(
        variants: Map<String, AndroidLibraryData>,
        buildType: String,
        dependencyResults: Map<Project, VariantCompressionResult>
    ): List<String> {
        val projectDependencies = extractProjectDependencies(variants)
        return projectDependencies
            .filter { dependencyResults[it]?.isExpanded(buildType) == true }
            .map { it.path }
    }

    /**
     * Extracts all unique project dependencies from a set of variants.
     */
    private fun extractProjectDependencies(
        variants: Map<String, AndroidLibraryData>
    ): Set<Project> = variants.values
        .flatMap { it.deps }
        .filterIsInstance<BazelDependency.ProjectDependency>()
        .map { it.dependencyProject }
        .toSet()

    /**
     * Checks if all variants in a list are equivalent to each other.
     */
    private fun areAllVariantsEquivalent(variants: List<AndroidLibraryData>): Boolean {
        if (variants.size <= 1) return true
        val first = variants.first()
        return variants.drop(1).all { equivalenceChecker.areEquivalent(first, it) }
    }
}
