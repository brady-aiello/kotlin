/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.allprivate.ide

import org.jetbrains.kotlin.allprivate.AllPrivateCommandLineProcessor
import org.jetbrains.kotlin.annotation.plugin.ide.AbstractMavenImportHandler
import org.jetbrains.kotlin.annotation.plugin.ide.AnnotationBasedCompilerPluginSetup.PluginOption
import org.jetbrains.kotlin.utils.PathUtil

class AllPrivateMavenProjectImportHandler : AbstractMavenImportHandler() {
    private companion object {
        val ANNOTATION_PARAMETER_PREFIX = "all-private:${AllPrivateCommandLineProcessor.ANNOTATION_OPTION.optionName}="
    }

    override val compilerPluginId = AllPrivateCommandLineProcessor.PLUGIN_ID
    override val pluginName = "allprivate"
    override val mavenPluginArtifactName = "kotlin-maven-allprivate"
    override val pluginJarFileFromIdea = PathUtil.kotlinPathsForIdeaPlugin.allPrivatePluginJarPath

    override fun getOptions(enabledCompilerPlugins: List<String>, compilerPluginOptions: List<String>): List<PluginOption>? {
        if ("all-private" !in enabledCompilerPlugins && "ios" !in enabledCompilerPlugins) {
            return null
        }

        val annotations = mutableListOf<String>()

        for ((presetName, presetAnnotations) in AllPrivateCommandLineProcessor.SUPPORTED_PRESETS) {
            if (presetName in enabledCompilerPlugins) {
                annotations.addAll(presetAnnotations)
            }
        }

        annotations.addAll(compilerPluginOptions.mapNotNull { text ->
            if (!text.startsWith(ANNOTATION_PARAMETER_PREFIX)) return@mapNotNull null
            text.substring(ANNOTATION_PARAMETER_PREFIX.length)
        })

        return annotations.map { PluginOption(AllPrivateCommandLineProcessor.ANNOTATION_OPTION.optionName, it) }
    }
}
