/*
 * Copyright 2024 Grabtaxi Holdings PTE LTD (GRAB)
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

package com.grab.grazel.migrate.android

import com.android.builder.model.LintOptions
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.bazel.starlark.BazelDependency.FileDependency
import com.grab.grazel.gradle.LINT_PLUGIN_ID
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.kotlin.dsl.the

internal fun Project.customLintRulesTargets(): List<BazelDependency>? {
    return configurations
        .asSequence()
        .filter { it.name.contains("lintChecks") }
        .flatMap { lintChecksConfig ->
            lintChecksConfig
                .dependencies
                .asSequence()
                .filterIsInstance<ProjectDependency>()
                .map { BazelDependency.ProjectDependency(it.dependencyProject) }
        }.let { it.toList().ifEmpty { null } }
}

internal fun lintConfigs(project: Project): LintConfigData {
    return if (project.plugins.hasPlugin(LINT_PLUGIN_ID)) {
        val lint = project.the<LintOptions>()
        LintConfigData(
            enabled = true,
            lintConfig = lint.lintConfig?.let {
                FileDependency(
                    file = it,
                    rootProject = project.rootProject
                )
            },
            baselinePath = lint.baselineFile?.let { project.relativePath(it) },
            lintChecks = project.customLintRulesTargets()
        )
    } else {
        LintConfigData(
            enabled = true, // enable Lint by default even when its not enabled in gradle
            lintConfig = null,
            baselinePath = null,
            lintChecks = project.customLintRulesTargets()
        )
    }
}

internal fun lintConfigs(
    lintOptions: com.android.build.gradle.internal.dsl.LintOptions,
    project: Project
) = LintConfigData(
    enabled = true, // enable lint for all targets by default
    lintConfig = lintOptions.lintConfig?.let {
        FileDependency(
            file = it,
            rootProject = project.rootProject
        )
    },
    baselinePath = lintOptions.baselineFile?.let {
        project.relativePath(it)
    },
    lintChecks = project.customLintRulesTargets()
)