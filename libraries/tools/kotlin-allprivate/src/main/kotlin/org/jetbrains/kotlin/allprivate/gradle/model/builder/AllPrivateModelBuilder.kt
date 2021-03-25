/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.allprivate.gradle.model.builder

import org.gradle.api.Project
import org.gradle.tooling.provider.model.ToolingModelBuilder
import org.jetbrains.kotlin.allprivate.gradle.AllPrivateExtension
import org.jetbrains.kotlin.allprivate.gradle.model.impl.AllPrivateImpl
import org.jetbrains.kotlin.gradle.model.AllPrivate

/**
 * [ToolingModelBuilder] for [AllPrivate] models.
 * This model builder is registered for Kotlin All Private sub-plugin.
 */
class AllPrivateModelBuilder : ToolingModelBuilder {

    override fun canBuild(modelName: String): Boolean {
        return modelName == AllPrivate::class.java.name
    }

    override fun buildAll(modelName: String, project: Project): Any? {
        if (modelName == AllPrivate::class.java.name) {
            val extension = project.extensions.getByType(AllPrivateExtension::class.java)
            return AllPrivateImpl(project.name, extension.myAnnotations, extension.myPresets)
        }
        return null
    }
}