/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.frontend.api.fir.renderer

import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.types.ConeKotlinType

class FirRendererOptions {
    var modifiers: Set<RendererModifier> = RendererModifier.ALL
    var typeNormalizer: (ConeKotlinType) -> ConeKotlinType = { it }
    var annotationFilter: ((FirAnnotationCall) -> Boolean)? = null
    var parameterNamesInFunctionalTypes: Boolean = true
    var presentableUnresolvedTypes: Boolean = false
    var informativeErrorType: Boolean = true
    var unitReturnType: Boolean = false
    var normalizedVisibilities: Boolean = false
    var renderContainingDeclarations: Boolean = false
    //var functionTypeAnnotationsRenderer
}

enum class RendererModifier(val includeByDefault: Boolean) {
    VISIBILITY(true),
    MODALITY(true),
    OVERRIDE(true),
    ANNOTATIONS(false),
    INNER(true),
    MEMBER_KIND(true),
    DATA(true),
    INLINE(true),
    EXPECT(true),
    ACTUAL(true),
    CONST(true),
    LATEINIT(true),
    FUN(true),
    VALUE(true)
    ;

    companion object {
        @JvmField
        val ALL_EXCEPT_ANNOTATIONS = values().filter { it.includeByDefault }.toSet()

        @JvmField
        val ALL = values().toSet()
    }
}