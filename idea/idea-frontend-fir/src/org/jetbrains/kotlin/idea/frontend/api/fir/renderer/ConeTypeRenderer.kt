/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.frontend.api.fir.renderer

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.resolve.inference.*
import org.jetbrains.kotlin.fir.types.*

internal class ConeTypeRenderer(
    private val session: FirSession,
    private val options: FirRendererOptions,
) {
    fun renderType(type: ConeTypeProjection, annotations: List<FirAnnotationCall>? = null): String = buildString {
        when (type) {
            is ConeKotlinErrorType -> {
                renderAnnotations(this, this@ConeTypeRenderer, options, annotations)
                //TODO error type
            }
            //is Dynamic??? -> append("dynamic")
            is ConeClassLikeType -> {
                val shouldRenderFunctionalType = shouldRenderAsPrettyFunctionType(type)
                if (shouldRenderFunctionalType) {
                    //TODO add             excludedTypeAnnotationClasses += listOf(StandardNames.FqNames.extensionFunctionType)
                    //with(options.functionTypeAnnotationsRenderer) {
                    renderAnnotations(this, this@ConeTypeRenderer, options, annotations)
                    //}
                    renderFunctionType(type)
                } else {
                    renderAnnotations(this, this@ConeTypeRenderer, options, annotations)
                    renderDefaultType(type)
                }
            }
            else -> append("???")
        }
    }

    fun shouldRenderAsPrettyFunctionType(type: ConeKotlinType): Boolean {
        return type.type.isBuiltinFunctionalType(session) && type.typeArguments.none { it.kind == ProjectionKind.STAR }
    }

    //TODO IMPLEMENT RENDER FLEXIBLES
//    fun renderFlexibleType(lowerRendered: String, upperRendered: String, builtIns: KotlinBuiltIns): String {
//        if (differsOnlyInNullability(lowerRendered, upperRendered)) {
//            if (upperRendered.startsWith("(")) {
//                // the case of complex type, e.g. (() -> Unit)?
//                return "($lowerRendered)!"
//            }
//            return "$lowerRendered!"
//        }
//
//        val kotlinCollectionsPrefix = classifierNamePolicy.renderClassifier(builtIns.collection, this).substringBefore("Collection")
//        val mutablePrefix = "Mutable"
//        // java.util.List<Foo> -> (Mutable)List<Foo!>!
//        val simpleCollection = replacePrefixes(
//            lowerRendered,
//            kotlinCollectionsPrefix + mutablePrefix,
//            upperRendered,
//            kotlinCollectionsPrefix,
//            "$kotlinCollectionsPrefix($mutablePrefix)"
//        )
//        if (simpleCollection != null) return simpleCollection
//        // java.util.Map.Entry<Foo, Bar> -> (Mutable)Map.(Mutable)Entry<Foo!, Bar!>!
//        val mutableEntry = replacePrefixes(
//            lowerRendered,
//            kotlinCollectionsPrefix + "MutableMap.MutableEntry",
//            upperRendered,
//            kotlinCollectionsPrefix + "Map.Entry",
//            "$kotlinCollectionsPrefix(Mutable)Map.(Mutable)Entry"
//        )
//        if (mutableEntry != null) return mutableEntry
//
//        val kotlinPrefix = classifierNamePolicy.renderClassifier(builtIns.array, this).substringBefore("Array")
//        // Foo[] -> Array<(out) Foo!>!
//        val array = replacePrefixes(
//            lowerRendered,
//            kotlinPrefix + escape("Array<"),
//            upperRendered,
//            kotlinPrefix + escape("Array<out "),
//            kotlinPrefix + escape("Array<(out) ")
//        )
//        if (array != null) return array
//
//        return "($lowerRendered..$upperRendered)"
//    }

    fun renderTypeArguments(typeArguments: Array<out ConeTypeProjection>): String = if (typeArguments.isEmpty()) ""
    else buildString {
        append("<")
        this.appendTypeProjections(typeArguments)
        append(">")
    }

    private fun StringBuilder.renderDefaultType(coneType: ConeClassLikeType) {
        if (coneType is ConeKotlinErrorType) {
            append("???") //TODO Make presentable type text and arguments
        } else {
            renderTypeConstructorAndArguments(coneType)
        }

        if (coneType.isMarkedNullable) {
            append("?")
        }

//        if (!coneType.canBeNull) {
//            append("!!")
//        }
    }

    private fun StringBuilder.renderTypeConstructorAndArguments(type: ConeClassLikeType) {
        append(type.lookupTag.classId.asString().replace("/", "."))
        appendTypeProjections(type.typeArguments)
    }

    private fun StringBuilder.appendTypeProjections(typeProjections: Array<out ConeTypeProjection>) {
        typeProjections.joinTo(this, ", ") {
            when (it.kind) {
                ProjectionKind.STAR -> "*"
                ProjectionKind.IN -> "in ${renderType(it)}"
                ProjectionKind.OUT -> "out ${renderType(it)}"
                ProjectionKind.INVARIANT -> renderType(it)
            }
        }
    }

    private fun StringBuilder.renderFunctionType(type: ConeClassLikeType) {
        val lengthBefore = length
        // we need special renderer to skip @ExtensionFunctionType
        val hasAnnotations = length != lengthBefore

        val isSuspend = type.isSuspendFunctionType(session)
        val isNullable = type.isMarkedNullable

        val receiverType = type.receiverType(session)

        val needParenthesis = isNullable || (hasAnnotations && receiverType != session)
        if (needParenthesis) {
            if (isSuspend) {
                insert(lengthBefore, '(')
            } else {
                if (hasAnnotations) {
                    assert(last() == ' ')
                    if (get(lastIndex - 1) != ')') {
                        // last annotation rendered without parenthesis - need to add them otherwise parsing will be incorrect
                        insert(lastIndex, "()")
                    }
                }

                append("(")
            }
        }

        if (isSuspend) {
            append("suspend")
            append(" ")
        }

        if (receiverType != null) {
            val surroundReceiver = shouldRenderAsPrettyFunctionType(receiverType) && !receiverType.isMarkedNullable ||
                    (receiverType.isSuspendFunctionType(session) /*TODO: || receiverType.annotations.isNotEmpty()*/)
            if (surroundReceiver) {
                append("(")
            }
            renderType(receiverType)
            if (surroundReceiver) {
                append(")")
            }
            append(".")
        }

        append("(")

        val parameterTypes = type.valueParameterTypesIncludingReceiver(session)
        var needComma = false
        for ((index, typeProjection) in parameterTypes.withIndex()) {

            if (typeProjection == null) continue
            if (index == 0 && receiverType != null) continue

            if (needComma) {
                append(", ")
            } else {
                needComma = true
            }

            //TODO support for parameterNamesInFunctionalTypes
            //val name = if (parameterNamesInFunctionalTypes) typeProjection.type.extractParameterNameFromFunctionTypeArgument() else null
            val name: String? = null
            if (name != null) {
                //append(renderName(name, false))
                append(name)
                append(": ")
            }

            appendTypeProjections(arrayOf(typeProjection))
        }

        append(") ").append("->").append(" ")

        val returnType = type.returnType(session)
        if (returnType != null) {
            renderType(returnType)
        } else {
        }


        if (needParenthesis) append(")")

        if (isNullable) append("?")
    }
}