/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.allprivate.ide

import org.jetbrains.kotlin.annotation.plugin.ide.AnnotationBasedPluginModel
import org.jetbrains.kotlin.annotation.plugin.ide.AnnotationBasedPluginModelBuilderService
import org.jetbrains.kotlin.annotation.plugin.ide.DumpedPluginModel
import org.jetbrains.kotlin.annotation.plugin.ide.DumpedPluginModelImpl


interface AllPrivateModel : AnnotationBasedPluginModel {
    override fun dump(): DumpedPluginModel {
        return DumpedPluginModelImpl(AllPrivateModelImpl::class.java, annotations.toList(), presets.toList())
    }
}

class AllPrivateModelImpl(
    override val annotations: List<String>,
    override val presets: List<String>
) : AllPrivateModel

class AllOpenModelBuilderService : AnnotationBasedPluginModelBuilderService<AllPrivateModel>() {
    override val gradlePluginNames get() = listOf("org.jetbrains.kotlin.plugin.allprivate", "kotlin-allprivate")
    override val extensionName get() = "allPrivate"
    override val modelClass get() = AllPrivateModel::class.java

    override fun createModel(annotations: List<String>, presets: List<String>, extension: Any?): AllPrivateModelImpl {
        return AllPrivateModelImpl(annotations, presets)
    }
}
