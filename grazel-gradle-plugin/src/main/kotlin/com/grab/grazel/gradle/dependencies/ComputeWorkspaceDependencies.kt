package com.grab.grazel.gradle.dependencies

import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.gradle.dependencies.model.OverrideTarget
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.COMPILE
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.gradle.dependencies.model.allDependencies
import com.grab.grazel.gradle.dependencies.model.merge
import com.grab.grazel.gradle.dependencies.model.versionInfo
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.util.fromJson
import org.gradle.api.file.RegularFile

internal class ComputeWorkspaceDependencies {

    fun compute(compileDependenciesJsons: List<RegularFile>): WorkspaceDependencies {
        // Parse all jsons and compute the classPaths among all variants.
        // Maximum compatible version is picked using [maxVersionByShortId] since jsons produced by
        // [ResolveVariantDependenciesTask] is module specific and we can have two version of the
        // same dependency.
        val classPaths = compileDependenciesJsons
            .map<RegularFile, ResolveDependenciesResult>(::fromJson)
            .groupBy(
                keySelector = ResolveDependenciesResult::variantName,
                valueTransform = { resolvedDependency ->
                    resolvedDependency.dependencies.getValue(COMPILE.name)
                }
            ).mapValues { (_, dependencyLists) ->
                dependencyLists
                    .flatten()
                    .groupBy(ResolvedDependency::shortId)
                    .mapValues { (_, dependencies) -> maxVersionByShortId(dependencies) }
            }.toMutableMap()

        // Even though [ResolveVariantDependenciesTask] does classpath reduction per module, the
        // final classpath here will not be accurate. For example, a dependency may appear twice in
        // both `release` and `default`. In order to correct this, we remove duplicates in non default
        // classPaths by comparing entries against occurrence in default classPath.
        val defaultClasspath = classPaths.getValue(DEFAULT_VARIANT)

        // Reduce non default classpath entries to contain only artifacts unique to them
        val reducedClasspath = classPaths
            .filter { it.key != DEFAULT_VARIANT } // Skip default classpath
            .filter { it.value.isNotEmpty() }
            .mapValues { (_, dependencies) -> dependencies.filterKeys { it !in defaultClasspath } }
            .toMutableMap()
            .apply { put(DEFAULT_VARIANT, defaultClasspath) } // Add default classpath back

        // After reduction, flatten the dependency graph such that all transitive dependencies
        // appear as direct. Run the [maxVersionByShortId] one more time to pick max version correctly
        val flattenClasspath = reducedClasspath.mapValues { (_, dependencyMap) ->
            dependencyMap.values
                .flatMap(ResolvedDependency::allDependencies)
                .groupBy(ResolvedDependency::shortId)
                .mapValues { (_, dependencies) -> maxVersionByShortId(dependencies) }
        }

        // While the above map contains accurate version information in each classpath, there is
        // still possibility of duplicate versions among all classpath, in order to fix this
        // we iterate non default classpath once again but check if any of them appear already
        // in default classpath.
        // If they do establish a [OverrideTarget] to default classpath
        val defaultFlatClasspath = flattenClasspath.getValue(DEFAULT_VARIANT)

        val reducedFinalClasspath: MutableMap<String, List<ResolvedDependency>> = flattenClasspath
            .filter { it.key != DEFAULT_VARIANT }
            .filter { it.value.isNotEmpty() }
            .mapValues { (_, dependencies) ->
                dependencies.entries.map { (shortId, dependency) ->
                    // If a transitive dependency is already in default classpath,
                    // then we override it to point to default classpath instead
                    if (shortId in defaultFlatClasspath && !dependency.direct) {
                        val (group, name, _, _) = dependency.id.split(":")
                        dependency.copy(
                            overrideTarget = OverrideTarget(
                                artifactShortId = shortId,
                                label = BazelDependency.MavenDependency(
                                    group = group,
                                    name = name
                                )
                            )
                        )
                    } else dependency
                }
            }
            .toMutableMap()

        // Add default classpath to the final result
        reducedFinalClasspath[DEFAULT_VARIANT] = defaultFlatClasspath.values.toList()

        // Sort dependencies by ID
        val sortedFinalClasspath = reducedFinalClasspath.mapValues {
            it.value.sortedBy(ResolvedDependency::id)
        }
        return WorkspaceDependencies(result = sortedFinalClasspath)
    }

    /**
     * Selects the [ResolvedDependency] with the highest version from a list of dependencies with
     * the same shortId. Also merges metadata like exclude rules and override targets.
     *
     * @param dependencies List of dependencies with the same shortId
     * @return The dependency with the highest version, with merged metadata
     */
    private fun maxVersionByShortId(dependencies: List<ResolvedDependency>): ResolvedDependency {
        if (dependencies.isEmpty()) {
            throw IllegalArgumentException("Cannot find max version in an empty list of dependencies")
        }
        return dependencies.reduce { acc, dependency ->
            // Pick the max version and merge metadata
            if (acc.versionInfo > dependency.versionInfo) {
                acc.merge(dependency)
            } else dependency.merge(acc)
        }
    }
}