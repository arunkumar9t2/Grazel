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

/**
 * Generic compression engine that extracts shared variant compression logic.
 *
 * This engine handles the core compression algorithm (~90% of the logic) while allowing
 * type-specific behavior to be injected via lambda adapters. This eliminates code duplication
 * between library and test compression while maintaining type safety.
 *
 * The engine operates in two phases:
 * 1. **Phase 1: Flavor compression** - Compress variants within each build type
 * 2. **Phase 2: Cross-build-type compression** - Optionally compress across build types
 *
 * Phase 1 groups variants by build type, then attempts to merge equivalent variants into
 * single targets per build type.
 *
 * Phase 2 checks if all build-type targets are equivalent and can be merged into a single
 * target with no suffix. This phase is optional and configured via CrossBuildTypeConfig.
 *
 * @param T The variant data type (AndroidLibraryData or AndroidUnitTestData)
 * @param R The result type to produce (VariantCompressionResult or UnitTestCompressionResult)
 * @param nameOf Lambda to extract the name field from variant data
 * @param copyWithName Lambda to create a copy of variant data with a new name
 * @param areAllEquivalent Lambda to check if all variants in a list are equivalent
 * @param buildTypeFn Function to extract build type name from variant name
 * @param normalizeSuffix Function to normalize variant names into target suffixes
 * @param resultFactory Lambda to construct the result object from compression data
 */
internal class VariantCompressionEngine<T, R>(
    private val nameOf: (T) -> String,
    private val copyWithName: (T, String) -> T,
    private val areAllEquivalent: (List<T>) -> Boolean,
    private val buildTypeFn: (String) -> String,
    private val normalizeSuffix: (String) -> String = ::normalizeVariantSuffix,
    private val compressName: (data: T, fromSuffix: String, toSuffix: String) -> String =
        { data, from, to -> nameOf(data).removeSuffix(from) + to },
    private val resultFactory: (Map<String, T>, Map<String, String>, Set<String>) -> R
) {
    /**
     * Configuration for cross-build-type compression (Phase 2).
     *
     * @param T The variant data type (must match engine's type parameter)
     * @property enabled Whether to attempt Phase 2 compression
     * @property equivalenceCheck Lambda to check if all build-type targets are equivalent
     * @property dependencyCheck Optional lambda returning blocker reason, or null if unblocked
     */
    data class CrossBuildTypeConfig<T>(
        val enabled: Boolean,
        val equivalenceCheck: (List<T>) -> Boolean,
        val dependencyCheck: (() -> String?)? = null
    )
    /**
     * Compress variants WITH dependency blocking checks (for libraries).
     *
     * This method requires a blocking check function that can prevent compression when
     * dependencies are expanded. Use this for library targets where dependency compression
     * state must propagate up the dependency chain.
     *
     * @param variants Map of variant name to variant data
     * @param blockingReason Function returning a reason string if compression is blocked, null otherwise
     * @param crossBuildType Optional Phase 2 configuration. If null, only Phase 1 is performed.
     * @return FlavorCompression containing compressed targets and metadata
     */
    fun compressWithBlocking(
        variants: Map<String, T>,
        blockingReason: (buildType: String, variants: Map<String, T>) -> String?,
        crossBuildType: CrossBuildTypeConfig<T>? = null
    ): FlavorCompression<T, R> {
        return compressInternal(variants, blockingReason, crossBuildType)
    }

    /**
     * Compress variants WITHOUT dependency blocking checks (for tests).
     *
     * This method bypasses dependency blocking checks, allowing tests to compress independently
     * of their library dependencies. Use this for test targets which are never transitive
     * dependencies and thus safe to compress freely.
     *
     * @param variants Map of variant name to variant data
     * @param crossBuildType Optional Phase 2 configuration. If null, only Phase 1 is performed.
     * @return FlavorCompression containing compressed targets and metadata
     */
    fun compressWithoutBlocking(
        variants: Map<String, T>,
        crossBuildType: CrossBuildTypeConfig<T>? = null
    ): FlavorCompression<T, R> {
        return compressInternal(variants, null, crossBuildType)
    }

    /**
     * Internal compression implementation shared by both blocking and non-blocking paths.
     *
     * @param variants Map of variant name to variant data
     * @param blockingReason Optional function to check if compression is blocked; null to skip checks
     * @param crossBuildType Optional Phase 2 configuration; null to skip Phase 2
     * @return FlavorCompression containing compressed targets and metadata
     */
    private fun compressInternal(
        variants: Map<String, T>,
        blockingReason: ((String, Map<String, T>) -> String?)?,
        crossBuildType: CrossBuildTypeConfig<T>?
    ): FlavorCompression<T, R> {
        if (variants.isEmpty()) {
            return emptyCompression()
        }

        // Phase 1: Flavor compression (within build types)
        val variantsByBuildType = variants.entries.groupBy { (name, _) -> buildTypeFn(name) }
        val results = variantsByBuildType.map { (buildType, group) ->
            val map = group.associate { it.key to it.value }
            compressBuildType(buildType, map, blockingReason)
        }
        val flavorCompressed = merge(results)

        // Phase 2: Cross-build-type compression (if configured)
        return if (crossBuildType?.enabled == true) {
            tryFullCompression(flavorCompressed, crossBuildType)
        } else {
            flavorCompressed
        }
    }

    /**
     * Compresses variants within a single build type.
     *
     * Determines whether variants can be compressed into a single target or must remain
     * expanded as separate targets. Handles three cases:
     * 1. Single variant - nothing to compress
     * 2. Blocked or non-equivalent - expand to separate targets
     * 3. Equivalent and unblocked - compress to single target
     *
     * @param buildType The build type name (e.g., "debug", "release")
     * @param variants Map of variant name to data for this build type
     * @param blockingReason Optional function to check if compression is blocked
     * @return EngineResult containing targets, mappings, and expanded status
     */
    private fun compressBuildType(
        buildType: String,
        variants: Map<String, T>,
        blockingReason: ((String, Map<String, T>) -> String?)?
    ): EngineResult<T> {
        // Case 1: Single variant - nothing to compress
        if (variants.size == 1) {
            val (name, data) = variants.entries.first()
            val suffix = normalizeSuffix(name)
            return EngineResult(
                targetsBySuffix = mapOf(suffix to data),
                variantToSuffix = mapOf(name to suffix),
                isExpanded = false
            )
        }

        // Case 2: Check if compression is blocked by dependencies
        val blockReason = blockingReason?.invoke(buildType, variants)
        if (blockReason != null) {
            return expand(buildType, blockReason, variants)
        }

        // Case 3: Check if all variants are equivalent
        if (!areAllEquivalent(variants.values.toList())) {
            return expand(buildType, "variants differ in configuration", variants)
        }

        // All checks passed - compress to single target
        return compressToSingleTarget(buildType, variants)
    }

    /**
     * Expands variants as separate targets (compression failed or blocked).
     *
     * @param buildType The build type name
     * @param reason Why expansion was necessary
     * @param variants Map of variant name to data
     * @return EngineResult with each variant as a separate target
     */
    private fun expand(
        buildType: String,
        reason: String,
        variants: Map<String, T>
    ): EngineResult<T> {
        val targetsBySuffix = mutableMapOf<String, T>()
        val variantToSuffix = mutableMapOf<String, String>()

        variants.forEach { (variantName, data) ->
            val suffix = normalizeSuffix(variantName)
            targetsBySuffix[suffix] = data
            variantToSuffix[variantName] = suffix
        }

        return EngineResult(
            targetsBySuffix = targetsBySuffix,
            variantToSuffix = variantToSuffix,
            isExpanded = true
        )
    }

    /**
     * Compresses equivalent variants into a single target.
     *
     * Picks the first variant alphabetically as the representative, renames it to use
     * the build type suffix, and maps all variants to this compressed target.
     *
     * @param buildType The build type name
     * @param variants Map of variant name to data (all equivalent)
     * @return EngineResult with single compressed target
     */
    private fun compressToSingleTarget(
        buildType: String,
        variants: Map<String, T>
    ): EngineResult<T> {
        val suffix = normalizeSuffix(buildType)

        // Pick representative (first alphabetically)
        val sortedVariants = variants.entries.sortedBy { it.key }
        val representative = sortedVariants.first()

        // Derive compressed name from representative
        val representativeSuffix = normalizeSuffix(representative.key)
        val compressedName = compressName(representative.value, representativeSuffix, suffix)
        val compressedData = copyWithName(representative.value, compressedName)

        // All variants map to the compressed suffix
        val variantMappings = variants.keys.associateWith { suffix }

        return EngineResult(
            targetsBySuffix = mapOf(suffix to compressedData),
            variantToSuffix = variantMappings,
            isExpanded = false
        )
    }

    /**
     * Merges multiple build type results into a single compression outcome.
     *
     * @param results List of EngineResult from each build type
     * @return FlavorCompression aggregating all results
     */
    private fun merge(results: List<EngineResult<T>>): FlavorCompression<T, R> {
        val targetsBySuffix = mutableMapOf<String, T>()
        val variantToSuffix = mutableMapOf<String, String>()
        val expandedBuildTypes = mutableSetOf<String>()

        for (result in results) {
            targetsBySuffix.putAll(result.targetsBySuffix)
            variantToSuffix.putAll(result.variantToSuffix)

            if (result.isExpanded) {
                // Extract build type from one of the variant names
                val sampleVariantName = result.variantToSuffix.keys.firstOrNull() ?: continue
                val buildType = buildTypeFn(sampleVariantName)
                expandedBuildTypes.add(buildType)
            }
        }

        return FlavorCompression(
            targetsBySuffix = targetsBySuffix,
            variantToSuffix = variantToSuffix,
            expandedBuildTypes = expandedBuildTypes,
            resultFactory = resultFactory
        )
    }

    /**
     * Attempts Phase 2: cross-build-type compression.
     *
     * Checks if all build-type targets can be merged into a single target with no suffix.
     * This requires:
     * 1. Flavor compression succeeded (no expanded build types)
     * 2. More than one build-type target exists
     * 3. Dependencies are unblocked (if dependency check provided)
     * 4. All build-type targets are equivalent
     *
     * @param flavorCompressed Result from Phase 1 (flavor compression)
     * @param config Phase 2 configuration
     * @return Updated FlavorCompression with full compression applied, or original if blocked
     */
    private fun tryFullCompression(
        flavorCompressed: FlavorCompression<T, R>,
        config: CrossBuildTypeConfig<T>
    ): FlavorCompression<T, R> {
        // 1. Flavor compression must have succeeded (no expanded build types)
        if (flavorCompressed.expandedBuildTypes.isNotEmpty()) return flavorCompressed

        // 2. Must have more than one build-type target to compress
        if (flavorCompressed.targetsBySuffix.size <= 1) return flavorCompressed

        // 3. Check dependencies if needed
        config.dependencyCheck?.invoke()?.let { reason ->
            // Blocked by dependencies
            return flavorCompressed
        }

        // 4. All build-type targets must be equivalent
        val targets = flavorCompressed.targetsBySuffix.values.toList()
        if (!config.equivalenceCheck(targets)) return flavorCompressed

        // All checks passed - apply full compression
        return applyFullCompression(flavorCompressed)
    }

    /**
     * Applies full compression by merging all build-type targets into single target.
     *
     * Picks the first target alphabetically by suffix as representative, removes its suffix,
     * and maps all variants to the empty suffix.
     *
     * @param flavorCompressed Result from Phase 1
     * @return FlavorCompression with single target having empty suffix
     */
    private fun applyFullCompression(
        flavorCompressed: FlavorCompression<T, R>
    ): FlavorCompression<T, R> {
        // Pick representative (first alphabetically by suffix)
        val representativeSuffix = flavorCompressed.targetsBySuffix.keys.minOf { it }
        val representativeData = flavorCompressed.targetsBySuffix.getValue(representativeSuffix)

        // Remove suffix from name
        val compressedName = compressName(representativeData, representativeSuffix, "")
        val fullyCompressedData = copyWithName(representativeData, compressedName)

        // All variants map to empty suffix
        val newVariantToSuffix = flavorCompressed.variantToSuffix.mapValues { "" }

        return FlavorCompression(
            targetsBySuffix = mapOf("" to fullyCompressedData),
            variantToSuffix = newVariantToSuffix,
            expandedBuildTypes = emptySet(),
            resultFactory = flavorCompressed.resultFactory
        )
    }

    /**
     * Creates an empty compression result for when no variants are provided.
     */
    private fun emptyCompression(): FlavorCompression<T, R> {
        return FlavorCompression(
            targetsBySuffix = emptyMap(),
            variantToSuffix = emptyMap(),
            expandedBuildTypes = emptySet(),
            resultFactory = resultFactory
        )
    }
}

/**
 * Intermediate result from a single build type's compression.
 *
 * @param T The variant data type
 */
private data class EngineResult<T>(
    val targetsBySuffix: Map<String, T>,
    val variantToSuffix: Map<String, String>,
    val isExpanded: Boolean
)

/**
 * Aggregated result from flavor compression (compressing variants within each build type).
 *
 * Contains all the data accumulated from processing each build type. Can be converted
 * to the final result type R using the resultFactory.
 *
 * @param T The variant data type
 * @param R The result type
 */
internal data class FlavorCompression<T, R>(
    val targetsBySuffix: Map<String, T>,
    val variantToSuffix: Map<String, String>,
    val expandedBuildTypes: Set<String>,
    val resultFactory: (Map<String, T>, Map<String, String>, Set<String>) -> R
) {
    /**
     * Converts this compression outcome to the final result type.
     */
    fun toResult(): R = resultFactory(targetsBySuffix, variantToSuffix, expandedBuildTypes)
}
