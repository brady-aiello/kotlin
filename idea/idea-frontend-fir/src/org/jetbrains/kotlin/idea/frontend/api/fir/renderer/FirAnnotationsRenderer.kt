/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.frontend.api.fir.renderer

import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirConstExpression
import org.jetbrains.kotlin.fir.expressions.argumentMapping
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.name.FqName

internal fun renderAnnotations(
    builder: StringBuilder,
    coneTypeRenderer: ConeTypeRenderer,
    options: FirRendererOptions,
    annotations: List<FirAnnotationCall>?,
    target: AnnotationUseSiteTarget? = null
) {
    if (annotations == null) return
    if (RendererModifier.ANNOTATIONS !in options.modifiers) return

    //TODO
    //val excluded = if (annotated is KotlinType) ExcludedTypeAnnotations.internalAnnotationsForResolve else emptySet<FqName>()
    val excluded = emptySet<FqName>()

    val filter = options.annotationFilter
    for (annotation in annotations) {
        if (annotation.toAnnotationClassId().asSingleFqName() !in excluded
            && !annotation.isParameterName()
            && (filter == null || filter(annotation))
        ) {
            builder.append(renderAnnotation(annotation, target, coneTypeRenderer))
            builder.append(" ")
        }
    }
}


private fun FirAnnotationCall.isParameterName(): Boolean {
    return toAnnotationClassId().asSingleFqName() == StandardNames.FqNames.parameterName
}

private fun renderAnnotation(annotation: FirAnnotationCall, target: AnnotationUseSiteTarget?, coneTypeRenderer: ConeTypeRenderer): String {
    return buildString {
        append('@')
        if (target != null) {
            append(target.renderName + ":")
        }
        val resolvedTypeRef = annotation.typeRef as? FirResolvedTypeRef
        require(resolvedTypeRef != null)
        append(coneTypeRenderer.renderType(resolvedTypeRef.type))

        val arguments = renderAndSortAnnotationArguments(annotation)
        if (arguments.isNotEmpty()) {
            arguments.joinTo(this, ", ", "(", ")")
        }
    }
}

private fun renderAndSortAnnotationArguments(descriptor: FirAnnotationCall): List<String> {
    val argumentList = descriptor.argumentMapping?.entries?.map { (name, value) ->
        "$name = ${renderConstant(value)}"
    }
    return argumentList?.sorted() ?: emptyList()
}

private fun renderConstant(value: FirValueParameter): String {
    return when (value) {
        is FirConstExpression<*> -> value.toString()
        else -> "RENDER ERROR"
    }
}