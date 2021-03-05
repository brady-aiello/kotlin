/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.frontend.api.fir.renderer

import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.visitors.FirVisitor
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.renderer.render
import org.jetbrains.kotlin.util.capitalizeDecapitalize.toLowerCaseAsciiOnly


internal class FirRenderer private constructor(
    private var containingDeclaration: FirDeclaration?,
    private val options: FirRendererOptions,
    private val typeRenderer: ConeTypeRenderer
) : FirVisitor<Unit, StringBuilder>() {

//    private val functionTypeAnnotationsRenderer: FirRenderer by lazy {
//        withOptions {
//            ExcludedTypeAnnotations.internalAnnotationsForResolve += listOf(StandardNames.FqNames.extensionFunctionType)
//        } as FirRenderer
//    }

    private fun StringBuilder.renderAnnotations(annotated: FirAnnotatedDeclaration, target: AnnotationUseSiteTarget? = null) =
        renderAnnotations(this@renderAnnotations, typeRenderer, options, annotated.annotations, target)

    private fun renderType(type: ConeTypeProjection, annotations: List<FirAnnotationCall>? = null): String =
        typeRenderer.renderType(type, annotations)

    private fun renderType(firRef: FirTypeRef): String {
        assert(firRef is FirResolvedTypeRef)
        return renderType(firRef.coneType, firRef.annotations)
    }

    private fun renderName(descriptor: FirDeclaration, builder: StringBuilder, rootRenderedElement: Boolean) {
        val name = when (descriptor) {
            is FirRegularClass -> descriptor.name
            is FirSimpleFunction -> descriptor.name
            is FirProperty -> descriptor.name
            is FirValueParameter -> descriptor.name
            is FirTypeParameter -> descriptor.name
            else -> Name.special("INVALID NAME") //TODO
        }
        builder.append(name.render())
    }

    private fun renderCompanionObjectName(descriptor: FirRegularClass, builder: StringBuilder) {
        if (descriptor.name != SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT) {
            renderSpaceIfNeeded(builder)
            builder.append(descriptor.name.render())
        }
    }

    private fun renderVisibility(visibility: Visibility, builder: StringBuilder): Boolean {
        @Suppress("NAME_SHADOWING")
        var visibility = visibility
        if (RendererModifier.VISIBILITY !in options.modifiers) return false
        if (options.normalizedVisibilities) {
            visibility = visibility.normalize()
        }
        builder.append(visibility.internalDisplayName).append(" ")
        return true
    }

    private fun renderModality(modality: Modality, builder: StringBuilder, defaultModality: Modality) {
        if (modality == defaultModality) return
        renderModifier(builder, RendererModifier.MODALITY in options.modifiers, modality.name.toLowerCaseAsciiOnly())
    }

    private fun FirMemberDeclaration.implicitModalityWithoutExtensions(containingDeclaration: FirDeclaration?): Modality {
        if (this is FirRegularClass) {
            return if (classKind == ClassKind.INTERFACE) Modality.ABSTRACT else Modality.FINAL
        }
        val containingClassDescriptor = containingDeclaration as? FirRegularClass ?: return Modality.FINAL
        if (this !is FirCallableMemberDeclaration<*>) return Modality.FINAL
        if (overridesSomething(this)) {
            if (containingClassDescriptor.modality != Modality.FINAL) return Modality.OPEN
        }
        return if (containingClassDescriptor.classKind == ClassKind.INTERFACE && this.visibility != DescriptorVisibilities.PRIVATE) {
            if (this.modality == Modality.ABSTRACT) Modality.ABSTRACT else Modality.OPEN
        } else
            Modality.FINAL
    }

    private fun renderModalityForCallable(
        callable: FirCallableMemberDeclaration<*>,
        containingDeclaration: FirDeclaration?,
        builder: StringBuilder
    ) {
        val modality = callable.modality ?: return
        val isTopLevel = containingDeclaration == null
        if (!isTopLevel || modality != Modality.FINAL) {
            if (modality == Modality.OPEN && overridesSomething(callable)) {
                return
            }
            renderModality(modality, builder, callable.implicitModalityWithoutExtensions(containingDeclaration))
        }
    }

    private fun renderOverride(callableMember: FirCallableMemberDeclaration<*>, builder: StringBuilder) {
        if (RendererModifier.OVERRIDE !in options.modifiers) return
        if (overridesSomething(callableMember)) {
            renderModifier(builder, true, "override")
        }
    }

    private fun renderModifier(builder: StringBuilder, value: Boolean, modifier: String) {
        if (value) {
            builder.append(modifier)
            builder.append(" ")
        }
    }

    private fun renderMemberModifiers(descriptor: FirMemberDeclaration, builder: StringBuilder) {
        renderModifier(builder, descriptor.isExternal, "external")
        renderModifier(builder, RendererModifier.EXPECT in options.modifiers && descriptor.isExpect, "expect")
        renderModifier(builder, RendererModifier.ACTUAL in options.modifiers && descriptor.isActual, "actual")
    }

    private fun renderAdditionalModifiers(functionDescriptor: FirMemberDeclaration, builder: StringBuilder) {
        val isOperator =
            functionDescriptor.isOperator// && functionDescriptor.overriddenDescriptors.none { it.isOperator } //TODO
        val isInfix =
            functionDescriptor.isInfix// && functionDescriptor.overriddenDescriptors.none { it.isInfix } //TODO

        renderModifier(builder, functionDescriptor.isTailRec, "tailrec")
        renderSuspendModifier(functionDescriptor, builder)
        renderModifier(builder, functionDescriptor.isInline, "inline")
        renderModifier(builder, isInfix, "infix")
        renderModifier(builder, isOperator, "operator")
    }

    private fun renderSuspendModifier(functionDescriptor: FirMemberDeclaration, builder: StringBuilder) {
        renderModifier(builder, functionDescriptor.isSuspend, "suspend")
    }

    override fun visitValueParameter(valueParameter: FirValueParameter, data: StringBuilder) {
        renderValueParameter(valueParameter, containingDeclaration is FirConstructor, true, data)
    }

    override fun visitProperty(property: FirProperty, data: StringBuilder) {
        data.appendLine()
        data.appendTabs()
        renderPropertyAnnotations(property, data)
        renderVisibility(property.visibility, data)
        renderModifier(data, RendererModifier.CONST in options.modifiers && property.isConst, "const")
        renderMemberModifiers(property, data)
        renderModalityForCallable(property, containingDeclaration, data)
        renderOverride(property, data)
        renderModifier(data, RendererModifier.LATEINIT in options.modifiers && property.isLateInit, "lateinit")
        renderValVarPrefix(property, data)
        renderTypeParameters(property.typeParameters, data, true)
        renderReceiver(property, data)

        renderName(property, data, true)
        data.append(": ").append(renderType(property.returnTypeRef))

        renderWhereSuffix(property.typeParameters, data)
    }

    override fun visitPropertyAccessor(propertyAccessor: FirPropertyAccessor, data: StringBuilder) {
        require(containingDeclaration is FirProperty?) { "Invalid containing declaration" }
        data.renderAnnotations(propertyAccessor)
        renderVisibility(propertyAccessor.visibility, data)

        renderModalityForCallable(propertyAccessor, containingDeclaration, data)

        renderMemberModifiers(propertyAccessor, data)

        renderAdditionalModifiers(propertyAccessor, data)

        val keyword = if (propertyAccessor.isGetter) "get" else "set"

        data.append(keyword).append(" ")

//        renderValueParameters(function.valueParameters, function.hasSynthesizedParameterNames(), builder)

        if (options.renderContainingDeclarations) {
            propertyAccessor.body?.accept(this, data)
        }
    }

    override fun visitSimpleFunction(simpleFunction: FirSimpleFunction, data: StringBuilder) {
        data.appendLine()
        data.appendTabs()
        data.renderAnnotations(simpleFunction)
        renderVisibility(simpleFunction.visibility, data)

        renderModalityForCallable(simpleFunction, containingDeclaration, data)

        renderMemberModifiers(simpleFunction, data)

        renderOverride(simpleFunction, data)

        renderAdditionalModifiers(simpleFunction, data)

        data.append("fun").append(" ")
        renderTypeParameters(simpleFunction.typeParameters, data, true)
        renderReceiver(simpleFunction, data)

        renderName(simpleFunction, data, true)

        renderValueParameters(simpleFunction.valueParameters, false, data)

        val returnType = simpleFunction.returnTypeRef
        if (options.unitReturnType || (!returnType.isUnit)) {
            data.append(": ").append(renderType(returnType))
        }

        renderWhereSuffix(simpleFunction.typeParameters, data)

        if (options.renderContainingDeclarations) {
            simpleFunction.body?.let {
                underBlockDeclaration(simpleFunction, it, data)
            }
        }
    }

    override fun visitThisReceiverExpression(thisReceiverExpression: FirThisReceiverExpression, data: StringBuilder) {
        data.append("this") // renders <this>
        //TODO was builder.append(descriptor.name) // renders <this>
    }

    override fun visitConstructor(constructor: FirConstructor, data: StringBuilder) {
        data.appendLine()
        data.appendTabs()
        val containingClass = containingDeclaration
        require(containingClass is FirRegularClass) { "Invalid renderer containing declaration for constructor" }

        data.renderAnnotations(constructor)
//        val visibilityRendered = (options.renderDefaultVisibility || constructor.constructedClass.modality != Modality.SEALED)
//                && renderVisibility(constructor.visibility, builder)

        data.append("constructor")
        data.append(" ")
        renderName(containingClass, data, true)
        renderTypeParameterRefs(constructor.typeParameters, data, false)

        renderValueParameters(constructor.valueParameters, true, data)

        renderWhereSuffix(constructor.typeParameters, data)

        if (options.renderContainingDeclarations) {
            constructor.body?.let {
                underBlockDeclaration(constructor, it, data)
            }
        }
    }

    override fun visitTypeParameter(typeParameter: FirTypeParameter, data: StringBuilder) {
        renderTypeParameter(typeParameter, data, true)
    }

    override fun visitRegularClass(regularClass: FirRegularClass, data: StringBuilder) {
        data.appendLine()
        data.appendTabs()
        val isEnumEntry = regularClass.classKind == ClassKind.ENUM_ENTRY

        data.renderAnnotations(regularClass)
        if (!isEnumEntry) {
            renderVisibility(regularClass.visibility, data)
        }
        if (!(regularClass.classKind == ClassKind.INTERFACE && regularClass.modality == Modality.ABSTRACT ||
                    regularClass.classKind.isSingleton && regularClass.modality == Modality.FINAL)
        ) {
            val modality = regularClass.modality
            if (modality != null) {
                renderModality(modality, data, regularClass.implicitModalityWithoutExtensions(containingDeclaration))
            }

        }
        renderMemberModifiers(regularClass, data)
        renderModifier(data, RendererModifier.INNER in options.modifiers && regularClass.isInner, "inner")
        renderModifier(data, RendererModifier.DATA in options.modifiers && regularClass.isData, "data")
        renderModifier(data, RendererModifier.INLINE in options.modifiers && regularClass.isInline, "inline")
        //TODO renderModifier(data, RendererModifier.VALUE in modifiers && regularClass.isValue, "value")
        renderModifier(data, RendererModifier.FUN in options.modifiers && regularClass.isFun, "fun")
        renderClassKindPrefix(regularClass, data)

        if (!regularClass.isCompanion) {
            renderSpaceIfNeeded(data)
            renderName(regularClass, data, true)
        } else {
            renderCompanionObjectName(regularClass, data)
        }

        if (isEnumEntry) return

        val typeParameters = regularClass.typeParameters
        renderTypeParameterRefs(typeParameters, data, false)

        renderSuperTypes(regularClass, data)
        renderWhereSuffix(typeParameters, data)

        if (options.renderContainingDeclarations) {
            underBlockDeclaration(regularClass, data) {
                regularClass.declarations.forEach {
                    it.accept(this, data)
                }
            }
        }
    }

    private var tabbedString = ""

    private inline fun underBlockDeclaration(firDeclaration: FirDeclaration, data: StringBuilder, body: () -> Unit) {
        val oldLength = data.length
        data.append(" {")
        val oldTabbedString = tabbedString
        tabbedString = " ".repeat(tabbedString.length + 4)
        val unchangedLength = data.length

        val oldContainingDeclaration = containingDeclaration
        containingDeclaration = firDeclaration
        body()
        containingDeclaration = oldContainingDeclaration

        tabbedString = oldTabbedString
        if (unchangedLength != data.length) {
            data.appendLine()
            data.appendTabs()
            data.append("}")
        } else {
            data.delete(oldLength, data.length)
        }
    }

    private fun StringBuilder.appendTabs() = append(tabbedString)

    private fun underBlockDeclaration(firDeclaration: FirDeclaration, firBlock: FirBlock, data: StringBuilder) {
        underBlockDeclaration(firDeclaration, data) {
            firBlock.accept(this, data)
        }
    }

    override fun visitTypeAlias(typeAlias: FirTypeAlias, data: StringBuilder) {
        data.renderAnnotations(typeAlias)
        renderVisibility(typeAlias.visibility, data)
        renderMemberModifiers(typeAlias, data)
        data.append("typealias").append(" ")
        renderName(typeAlias, data, true)

        renderTypeParameters(typeAlias.typeParameters, data, false)

        data.append(" = ").append(renderType(typeAlias.expandedTypeRef))
    }

    override fun visitElement(element: FirElement, data: StringBuilder) {
        element.acceptChildren(this, data)
    }

    companion object {
        fun render(
            declarationDescriptor: FirDeclaration,
            containingDeclaration: FirDeclaration?,
            options: FirRendererOptions,
            coneTypeRenderer: ConeTypeRenderer,
        ): String {
            val renderer = org.jetbrains.kotlin.idea.frontend.api.fir.renderer.FirRenderer(
                containingDeclaration,
                options,
                coneTypeRenderer
            )
            return buildString {
                declarationDescriptor.accept(renderer, this)
                deleteCharAt(0) //Remove new line
            }
        }
    }


    /* TYPE PARAMETERS */
    private fun renderTypeParameter(typeParameter: FirTypeParameter, builder: StringBuilder, topLevel: Boolean) {
        if (topLevel) {
            builder.append("<")
        }

        renderModifier(builder, typeParameter.isReified, "reified")
        val variance = typeParameter.variance.label
        renderModifier(builder, variance.isNotEmpty(), variance)

        builder.renderAnnotations(typeParameter)

        renderName(typeParameter, builder, topLevel)
        val upperBoundsCount = typeParameter.bounds.size
        if ((upperBoundsCount > 1 && !topLevel) || upperBoundsCount == 1) {
            val upperBound = typeParameter.bounds.iterator().next()
            if (!upperBound.isNullableAny) {
                builder.append(" : ").append(renderType(upperBound))
            }
        } else if (topLevel) {
            var first = true
            for (upperBound in typeParameter.bounds) {
                if (upperBound.isNullableAny) {
                    continue
                }
                if (first) {
                    builder.append(" : ")
                } else {
                    builder.append(" & ")
                }
                builder.append(renderType(upperBound))
                first = false
            }
        } else {
            // rendered with "where"
        }

        if (topLevel) {
            builder.append(">")
        }
    }

    private fun renderTypeParameterRefs(typeParameters: List<FirTypeParameterRef>, builder: StringBuilder, withSpace: Boolean) =
        renderTypeParameters(typeParameters.map { it.symbol.fir }, builder, withSpace)

    private fun renderTypeParameters(typeParameters: List<FirTypeParameter>, builder: StringBuilder, withSpace: Boolean) {
        if (typeParameters.isNotEmpty()) {
            builder.append("<")
            renderTypeParameterList(builder, typeParameters)
            builder.append(">")
            if (withSpace) {
                builder.append(" ")
            }
        }
    }

    private fun renderTypeParameterList(builder: StringBuilder, typeParameters: List<FirTypeParameter>) {
        val iterator = typeParameters.iterator()
        while (iterator.hasNext()) {
            val typeParameterDescriptor = iterator.next()
            renderTypeParameter(typeParameterDescriptor, builder, false)
            if (iterator.hasNext()) {
                builder.append(", ")
            }
        }
    }

    private fun renderReceiver(callableDescriptor: FirCallableDeclaration<*>, builder: StringBuilder) {
        val receiverType = callableDescriptor.receiverTypeRef
        if (receiverType != null) {
            builder.renderAnnotations(callableDescriptor, AnnotationUseSiteTarget.RECEIVER)

            var result = renderType(receiverType)

            if (typeRenderer.shouldRenderAsPrettyFunctionType(receiverType.coneType) && receiverType.isMarkedNullable == true) {
                result = "($result)"
            }
            builder.append(result).append(".")
        }
    }

    private fun renderWhereSuffix(typeParameters: List<FirTypeParameterRef>, builder: StringBuilder) {

        val upperBoundStrings = ArrayList<String>(0)

        for (typeParameter in typeParameters) {
            val typeParameterFir = typeParameter.symbol.fir
            typeParameterFir.bounds
                .drop(1) // first parameter is rendered by renderTypeParameter
                .mapTo(upperBoundStrings) { typeParameterFir.name.render() + " : " + renderType(it) }
        }

        if (upperBoundStrings.isNotEmpty()) {
            builder.append(" ").append("where").append(" ")
            upperBoundStrings.joinTo(builder, ", ")
        }
    }

    private fun renderValueParameters(
        parameters: List<FirValueParameter>,
        isInPrimaryConstructor: Boolean,
        builder: StringBuilder
    ) {
        val parameterCount = parameters.size

        builder.append("(")
        for ((index, parameter) in parameters.withIndex()) {
            renderValueParameter(parameter, isInPrimaryConstructor, false, builder)
            if (index != parameterCount - 1) {
                builder.append(", ")
            }
        }
        builder.append(")")
    }

    /* VARIABLES */
    private fun renderValueParameter(
        valueParameter: FirValueParameter,
        isInPrimaryConstructor: Boolean,
        topLevel: Boolean,
        builder: StringBuilder
    ) {
        if (topLevel) {
            builder.append("value-parameter").append(" ")
        }

        builder.renderAnnotations(valueParameter)
        renderModifier(builder, valueParameter.isCrossinline, "crossinline")
        renderModifier(builder, valueParameter.isNoinline, "noinline")

        renderVariable(valueParameter, builder, topLevel, isInPrimaryConstructor)

        val withDefaultValue = valueParameter.defaultValue != null //TODO check if default value is inherited
        if (withDefaultValue) {
            builder.append(" = ...")
        }
    }

    private fun renderValVarPrefix(variable: FirVariable<*>, builder: StringBuilder, isInPrimaryConstructor: Boolean = false) {
        if (isInPrimaryConstructor || variable !is ValueParameterDescriptor) {
            builder.append(if (variable.isVar) "var" else "val").append(" ")
        }
    }

    private fun renderVariable(
        variable: FirVariable<*>,
        builder: StringBuilder,
        topLevel: Boolean,
        isInPrimaryConstructor: Boolean
    ) {
        val typeToRender = variable.returnTypeRef

        val isVarArg = (variable as? FirValueParameter)?.isVararg ?: false
        renderModifier(builder, isVarArg, "vararg")

        if (isInPrimaryConstructor || topLevel) {
            renderValVarPrefix(variable, builder, isInPrimaryConstructor)
        }

        renderName(variable, builder, topLevel)
        builder.append(": ")

        builder.append(renderType(typeToRender))
    }

    private fun renderPropertyAnnotations(property: FirProperty, builder: StringBuilder) {
        if (RendererModifier.ANNOTATIONS !in options.modifiers) return

        builder.renderAnnotations(property)


//        property.backingField?.let { builder.renderAnnotations(it, AnnotationUseSiteTarget.FIELD) }
//        property.delegateField?.let { builder.renderAnnotations(it, AnnotationUseSiteTarget.PROPERTY_DELEGATE_FIELD) }

//        if (PropertyAccessorRenderingPolicy.DEBUG == PropertyAccessorRenderingPolicy.NONE) {
//            property.getter?.let {
//                builder.renderAnnotations(it, AnnotationUseSiteTarget.PROPERTY_GETTER)
//            }
//            property.setter?.let { setter ->
//                setter.let {
//                    builder.renderAnnotations(it, AnnotationUseSiteTarget.PROPERTY_SETTER)
//                }
//                setter.valueParameters.single().let {
//                    builder.renderAnnotations(it, AnnotationUseSiteTarget.SETTER_PARAMETER)
//                }
//            }
//        }
    }

    private fun renderSuperTypes(klass: FirRegularClass, builder: StringBuilder) {

        if (klass.defaultType().isNothing) return

        val supertypes = klass.superTypeRefs
        if (supertypes.isEmpty() || klass.superTypeRefs.singleOrNull()?.let { it.isAny || it.isNullableAny } == true) return

        renderSpaceIfNeeded(builder)
        builder.append(": ")
        supertypes.joinTo(builder, ", ") { renderType(it) }
    }


    private fun getClassifierKindPrefix(classifier: FirDeclaration): String = when (classifier) {
        is TypeAliasDescriptor -> "typealias"
        is FirRegularClass ->
            if (classifier.isCompanion) {
                "companion object"
            } else {
                when (classifier.classKind) {
                    ClassKind.CLASS -> "class"
                    ClassKind.INTERFACE -> "interface"
                    ClassKind.ENUM_CLASS -> "enum class"
                    ClassKind.OBJECT -> "object"
                    ClassKind.ANNOTATION_CLASS -> "annotation class"
                    ClassKind.ENUM_ENTRY -> "enum entry"
                }
            }
        else ->
            throw AssertionError("Unexpected classifier: $classifier")
    }

    private fun renderClassKindPrefix(klass: FirRegularClass, builder: StringBuilder) {
        builder.append(getClassifierKindPrefix(klass))
    }

    private fun renderSpaceIfNeeded(builder: StringBuilder) {
        val length = builder.length
        if (length == 0 || builder[length - 1] != ' ') {
            builder.append(' ')
        }
    }

    private fun replacePrefixes(
        lowerRendered: String,
        lowerPrefix: String,
        upperRendered: String,
        upperPrefix: String,
        foldedPrefix: String
    ): String? {
        if (lowerRendered.startsWith(lowerPrefix) && upperRendered.startsWith(upperPrefix)) {
            val lowerWithoutPrefix = lowerRendered.substring(lowerPrefix.length)
            val upperWithoutPrefix = upperRendered.substring(upperPrefix.length)
            val flexibleCollectionName = foldedPrefix + lowerWithoutPrefix

            if (lowerWithoutPrefix == upperWithoutPrefix) return flexibleCollectionName

            if (differsOnlyInNullability(lowerWithoutPrefix, upperWithoutPrefix)) {
                return "$flexibleCollectionName!"
            }
        }
        return null
    }

    private fun differsOnlyInNullability(lower: String, upper: String) =
        lower == upper.replace("?", "") || upper.endsWith("?") && ("$lower?") == upper || "($lower)?" == upper

    private fun overridesSomething(callable: FirCallableMemberDeclaration<*>) = false //TODO callable.overriddenDescriptors.isNotEmpty()

}
