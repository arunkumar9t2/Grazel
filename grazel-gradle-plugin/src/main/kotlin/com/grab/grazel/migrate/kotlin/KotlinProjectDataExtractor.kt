/*
 * Copyright 2021 Grabtaxi Holdings PTE LTD (GRAB)
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

package com.grab.grazel.migrate.kotlin

import com.grab.grazel.bazel.rules.KOTLIN_PARCELIZE_TARGET
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.gradle.ConfigurationScope
import com.grab.grazel.gradle.dependencies.DependenciesDataSource
import com.grab.grazel.gradle.dependencies.DependencyGraphs
import com.grab.grazel.gradle.dependencies.directProjectDependencies
import com.grab.grazel.gradle.hasKotlinAndroidExtensions
import com.grab.grazel.migrate.android.SourceSetType
import com.grab.grazel.migrate.android.collectMavenDeps
import com.grab.grazel.migrate.android.filterSourceSetPaths
import dagger.Lazy
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.kotlin.dsl.the
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

internal interface KotlinProjectDataExtractor {
    fun extract(project: Project): KotlinProjectData
}

@Singleton
internal class DefaultKotlinProjectDataExtractor @Inject constructor(
    private val dependenciesDataSource: DependenciesDataSource,
    private val dependencyGraphsProvider: Lazy<DependencyGraphs>
) : KotlinProjectDataExtractor {

    private val projectDependencyGraphs get() = dependencyGraphsProvider.get()

    override fun extract(project: Project): KotlinProjectData {
        val sourceSets = project.the<KotlinJvmProjectExtension>().sourceSets
        val srcs = project.kotlinSources(sourceSets, SourceSetType.JAVA_KOTLIN).toList()
        val resources = project.kotlinSources(sourceSets, SourceSetType.RESOURCES).toList()

        val deps =
            projectDependencyGraphs.directProjectDependencies(project, ConfigurationScope.BUILD) +
                    dependenciesDataSource.collectMavenDeps(project) +
                    project.androidJarDeps() +
                    project.kotlinParcelizeDeps()

        return KotlinProjectData(
            name = project.name,
            srcs = srcs,
            res = resources,
            deps = deps
        )
    }

    private fun Project.kotlinSources(
        sourceSets: NamedDomainObjectContainer<KotlinSourceSet>,
        sourceSetType: SourceSetType
    ): Sequence<String> {
        val sourceSetChoosers: KotlinSourceSet.() -> Sequence<File> = when (sourceSetType) {
            SourceSetType.JAVA, SourceSetType.JAVA_KOTLIN, SourceSetType.KOTLIN -> {
                { kotlin.srcDirs.asSequence() }
            }
            SourceSetType.RESOURCES, SourceSetType.RESOURCES_CUSTOM -> {
                { resources.srcDirs.asSequence() }
            }
            SourceSetType.ASSETS -> {
                { emptySequence() }
            }
        }
        val dirs = sourceSets
            .asSequence()
            .filter { !it.name.toLowerCase().contains("test") } // TODO Consider enabling later.
            .flatMap(sourceSetChoosers)
        return filterSourceSetPaths(dirs, sourceSetType.patterns)
    }
}

internal fun Project.kotlinParcelizeDeps(): List<BazelDependency.StringDependency> {
    return when {
        hasKotlinAndroidExtensions -> listOf(KOTLIN_PARCELIZE_TARGET)
        else -> emptyList()
    }
}