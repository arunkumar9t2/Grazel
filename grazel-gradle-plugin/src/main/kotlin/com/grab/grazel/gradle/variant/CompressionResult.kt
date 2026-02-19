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
 * Common interface for variant compression results.
 *
 * Provides unified access to compression outcomes for both library and test variants,
 * enabling generic compression algorithms while preserving type-specific data.
 *
 * @param T The data type stored per target (AndroidLibraryData or AndroidUnitTestData)
 */
internal interface CompressionResult<T> {
    /**
     * Map from target suffix to target data (1:1 relationship).
     * Each suffix represents a unique Bazel target to be generated.
     */
    val targetsBySuffix: Map<String, T>

    /**
     * Map from variant name to target suffix (many:1 relationship).
     * Multiple variants may map to the same suffix when compressed.
     */
    val variantToSuffix: Map<String, String>

    /**
     * Set of build type names that should remain expanded (not compressed).
     * Build types in this set had their variants kept as separate targets.
     */
    val expandedBuildTypes: Set<String>
}
