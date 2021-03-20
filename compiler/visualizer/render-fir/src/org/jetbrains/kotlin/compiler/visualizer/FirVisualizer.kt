/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.compiler.visualizer

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.fir.*
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.expressions.impl.FirNoReceiverExpression
import org.jetbrains.kotlin.fir.references.*
import org.jetbrains.kotlin.fir.resolve.*
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.firUnsafe
import org.jetbrains.kotlin.fir.symbols.AbstractFirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.*
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.visitors.FirVisitor
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getChildrenOfType
import org.jetbrains.kotlin.types.AbstractStrictEqualityTypeChecker
import org.jetbrains.kotlin.types.ConstantValueKind
import org.jetbrains.kotlin.util.capitalizeDecapitalize.toLowerCaseAsciiOnly

private typealias Stack = MutableList<Pair<String, MutableList<String>>>

class FirVisualizer(private val firFile: FirFile) : BaseRenderer() {
    private val implicitReceivers = mutableListOf<FirTypeRef>()

    private fun FirElement.render(): String = buildString { this@render.accept(FirRendererForVisualizer(), this) }

    private val stack = mutableListOf("" to mutableListOf<String>())

    private fun Stack.push(
        levelName: String,
        defaultValues: MutableList<String> = mutableListOf()
    ) = this.add(levelName to defaultValues)

    private fun Stack.pop() = this.removeAt(this.size - 1)
    private fun Stack.addName(name: String) = this.last().second.add(name)
    private fun Stack.addName(name: Name) = this.addName(name.asString())
    private fun Stack.getPathByName(name: String): String {
        for ((reversedIndex, names) in this.asReversed().map { it.second }.withIndex()) {
            if (names.contains(name)) {
                return this.filterIndexed { index, _ -> index < this.size - reversedIndex && index > 0 }
                    .joinToString(separator = ".", postfix = ".") { it.first }
            }
        }
        if (name == "it") {
            return this.subList(1, this.size)
                .joinToString(separator = ".", postfix = ".") { it.first }
        }
        return "[NOT FOUND]."
    }

    override fun addAnnotation(annotationText: String, element: PsiElement?, deleteDuplicate: Boolean) {
        super.addAnnotation(annotationText, element, false)
    }

    override fun render(): String {
        val map = mutableMapOf<PsiElement, MutableList<FirElement>>().apply { Psi2FirMapper(this).visitFile(firFile) }
        map.keys.firstOrNull { it is KtFile }?.accept(PsiVisitor(map))
        return Annotator.annotate(firFile.psi!!.text, getAnnotations()).joinToString("\n")
    }

    inner class PsiVisitor(private val map: Map<PsiElement, MutableList<FirElement>>) : KtVisitorVoid() {
        private var lastCallWithLambda: String? = null

        private inline fun <reified T> KtElement.firstOfType(): T? {
            val firList = map[this]
            return firList?.filterIsInstance<T>()?.firstOrNull()
        }

        /**
         * @return rendered element or null if there is no such type
         */
        private inline fun <reified T : FirElement> KtElement.firstOfTypeWithRender(
            psi: PsiElement? = this,
            getRendererElement: T.() -> FirElement = { this }
        ): FirElement? {
            return firstOfType<T>()?.also { addAnnotation(it.getRendererElement().render(), psi) }
        }

        /**
         * @return rendered element or null if there is no such type
         */
        private inline fun <reified T : FirElement> KtElement.firstOfTypeWithLocalReplace(
            psi: PsiElement? = this,
            getName: T.() -> String
        ): FirElement? {
            return firstOfType<T>()?.also { addAnnotation(it.render().replace("<local>/", stack.getPathByName(it.getName())), psi) }
        }

        /**
         * @return first rendered element or null if there is no such type
         */
        private inline fun <reified T : FirElement> KtElement.allOfTypeWithLocalReplace(
            psi: PsiElement? = this,
            getName: T.() -> String
        ): FirElement? {
            val firList = map[this]
            val firElements = firList?.filterIsInstance<T>()
            if (firElements == null || firElements.isEmpty()) return null
            firElements.forEach { addAnnotation(it.render().replace("<local>/", stack.getPathByName(it.getName())), psi) }

            return firElements.first()
        }

        override fun visitElement(element: PsiElement) {
            element.acceptChildren(this)
        }

        override fun visitKtElement(element: KtElement) {
            when (element) {
                is KtClassInitializer, is KtSecondaryConstructor, is KtPrimaryConstructor, is KtSuperTypeCallEntry, is KtDelegatedSuperTypeEntry -> {
                    val valueParameters = element.getChildrenOfType<KtParameterList>()
                    valueParameters.flatMap { it.parameters }.forEach { stack.addName(it.nameAsSafeName) }

                    //add to init values from last block
                    //because when we are out of primary constructor information about properties will be removed
                    //is used in ClassInitializer block and in SuperTypeCallEntry
                    stack.push("<init>", stack.last().second)
                    element.acceptChildren(this)
                    stack.pop()
                }
                is KtClassOrObject -> {
                    if (element.isLocal) stack.addName((element.name ?: "<anonymous>"))
                    stack.push((element.name ?: "<anonymous>"))
                    element.acceptChildren(this)
                    stack.pop()
                }
                else -> element.acceptChildren(this)
            }
        }

        override fun visitPackageDirective(directive: KtPackageDirective) {
            //don't resolve package names
        }

        override fun visitSuperExpression(expression: KtSuperExpression) {
            //don't resolve super expression
        }

        override fun visitThisExpression(expression: KtThisExpression) {
            //don't resolve this expression
        }

        override fun visitNamedFunction(function: KtNamedFunction) {
            if (function.isLocal) stack.addName(function.name ?: "<no name provided>")
            stack.push((function.name ?: "<no name provided>"))
            if (function.equalsToken != null) {
                function.bodyExpression!!.firstOfTypeWithRender<FirReturnExpression>(function.equalsToken) { this.result.typeRef }
                    ?: function.firstOfTypeWithRender<FirTypedDeclaration>(function.equalsToken) { this.returnTypeRef }
            }
            super.visitNamedFunction(function)
            stack.pop()
        }

        private fun renderVariableType(variable: KtVariableDeclaration) {
            stack.addName(variable.nameAsSafeName)
            variable.firstOfTypeWithRender<FirVariable<*>>(variable.nameIdentifier)
            variable.acceptChildren(this)
        }

        override fun visitProperty(property: KtProperty) =
            renderVariableType(property)

        override fun visitDestructuringDeclarationEntry(multiDeclarationEntry: KtDestructuringDeclarationEntry) =
            renderVariableType(multiDeclarationEntry)

        override fun visitAnnotationEntry(annotationEntry: KtAnnotationEntry) {
            annotationEntry.firstOfTypeWithRender<FirAnnotationCall>(annotationEntry.children.first())
            super.visitAnnotationEntry(annotationEntry)
        }

        override fun visitConstructorCalleeExpression(constructorCalleeExpression: KtConstructorCalleeExpression) {
            constructorCalleeExpression.firstOfTypeWithRender<FirDelegatedConstructorCall>()
        }

        override fun visitParameter(parameter: KtParameter) {
            stack.addName(parameter.nameAsSafeName)
            if ((parameter.isLoopParameter && parameter.destructuringDeclaration == null) || parameter.ownerFunction is KtPropertyAccessor) {
                parameter.firstOfTypeWithRender<FirVariable<*>>(parameter.nameIdentifier)
            }
            super.visitParameter(parameter)
        }

        override fun visitTypeReference(typeReference: KtTypeReference) {
            typeReference.firstOfTypeWithRender<FirTypeRef>()
            super.visitTypeReference(typeReference)
        }

        override fun visitConstantExpression(expression: KtConstantExpression) {
            expression.firstOfTypeWithRender<FirConstExpression<*>>()
        }

        override fun visitReferenceExpression(expression: KtReferenceExpression) {
            if (expression is KtOperationReferenceExpression) return

            expression.firstOfTypeWithLocalReplace<FirResolvedNamedReference> { this.name.asString() }
                ?: expression.firstOfTypeWithLocalReplace<FirResolvedCallableReference> { this.name.asString() }
                ?: expression.firstOfTypeWithRender<FirResolvedQualifier>()
                ?: expression.firstOfTypeWithRender<FirElement>() //fallback for errors
            super.visitReferenceExpression(expression)
        }

        override fun visitUnaryExpression(expression: KtUnaryExpression) {
            if (expression.operationReference.getReferencedName() == "!!") {
                expression.baseExpression?.accept(this)
                return
            }
            expression.allOfTypeWithLocalReplace<FirFunctionCall>(expression.operationReference) { this.calleeReference.name.asString() }
            super.visitUnaryExpression(expression)
        }

        override fun visitBinaryExpression(expression: KtBinaryExpression) {
            val operation = expression.operationReference
            when {
                operation.getReferencedName() == "?:" -> {
                    expression.left?.accept(this)
                    expression.right?.accept(this)
                }
                operation.getReferencedName() in setOf("==", "!=") -> {
                    expression.left?.accept(this)
                    expression.firstOfTypeWithRender<FirEqualityOperatorCall>(operation)
                    expression.right?.accept(this)
                }
                else -> {
                    expression.allOfTypeWithLocalReplace<FirFunctionCall>(operation) { this.calleeReference.name.asString() }
                        ?: expression.firstOfTypeWithLocalReplace<FirVariableAssignment>(operation) { this.lValue.toString() }
                    super.visitBinaryExpression(expression)
                }
            }
        }

        override fun visitIfExpression(expression: KtIfExpression) {
            expression.firstOfTypeWithRender<FirWhenExpression> { this.typeRef }
            super.visitIfExpression(expression)
        }

        override fun visitWhenExpression(expression: KtWhenExpression) {
            expression.firstOfTypeWithRender<FirWhenExpression> { this.typeRef }
            super.visitWhenExpression(expression)
        }

        override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
            expression.firstOfTypeWithLocalReplace<FirFunctionCall>(expression.selectorExpression) { this.calleeReference.name.asString() }
            super.visitDotQualifiedExpression(expression)
        }

        override fun visitCallExpression(expression: KtCallExpression) {
            expression.firstOfTypeWithLocalReplace<FirFunctionCall> { this.calleeReference.name.asString() }
                ?: expression.firstOfTypeWithRender<FirArrayOfCall>()
            expression.children.filter { it.node.elementType != KtNodeTypes.REFERENCE_EXPRESSION }.forEach { psi ->
                when (psi) {
                    is KtLambdaArgument -> {
                        val firLambda = psi.firstOfType<FirLambdaArgumentExpression>()?.expression as? FirAnonymousFunction
                        firLambda?.receiverTypeRef?.let {
                            lastCallWithLambda = psi.getLambdaExpression()?.firstOfType<FirLabel>()?.name
                            implicitReceivers += it
                            psi.accept(this)
                            implicitReceivers -= it
                        } ?: psi.accept(this)
                    }
                    else -> psi.accept(this)
                }
            }
        }

        override fun visitLambdaExpression(lambdaExpression: KtLambdaExpression) {
            stack.push("<anonymous>")
            lastCallWithLambda?.let { addAnnotation("$it@${implicitReceivers.size - 1}", lambdaExpression) }
            super.visitLambdaExpression(lambdaExpression)
            stack.pop()
        }

        override fun visitArrayAccessExpression(expression: KtArrayAccessExpression) {
            //this method explicitly accept children and prevent default fallback to other fir element
            expression.acceptChildren(this)
        }

        override fun visitPropertyAccessor(accessor: KtPropertyAccessor) {
            if (accessor.isSetter) {
                stack.push("<set-${accessor.property.nameAsSafeName}>", mutableListOf("field"))
                super.visitPropertyAccessor(accessor)
                stack.pop()
            } else {
                super.visitPropertyAccessor(accessor)
            }
        }

        override fun visitWhenEntry(jetWhenEntry: KtWhenEntry) {
            jetWhenEntry.firstOfTypeWithRender<FirWhenBranch>(jetWhenEntry.expression) { this.result.typeRef }
            super.visitWhenEntry(jetWhenEntry)
        }

        override fun visitClassLiteralExpression(expression: KtClassLiteralExpression) {
            expression.firstOfTypeWithRender<FirGetClassCall>()
        }

        override fun visitPrefixExpression(expression: KtPrefixExpression) {
            expression.firstOfTypeWithRender<FirConstExpression<*>>(expression.baseExpression) ?: super.visitPrefixExpression(expression)
        }
    }

    inner class FirRendererForVisualizer : FirVisitor<Unit, StringBuilder>() {
        private val session = firFile.session
        private val filePackage = firFile.packageFqName.toString()
        private val filePackageWithSlash = filePackage.replace(".", "/")
        private val symbolProvider = firFile.session.symbolProvider

        private fun ConeTypeProjection.tryToRenderConeAsFunctionType(): String {
            if (this !is ConeKotlinType) return localTypeRenderer()
            val functionType = renderFunctionType(functionTypeKind, isExtensionFunctionType) { localTypeRenderer() }
            return functionType.removeCurrentFilePackage()
        }

        // TODO rewrite or extract in common utils
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

        private fun tryToSquashFlexibleType(lowerRendered: String, upperRendered: String): String? {
            val simpleCollection = replacePrefixes(
                lowerRendered,
                "kotlin/collections/Mutable",
                upperRendered,
                "kotlin/collections/",
                "kotlin/collections/(Mutable)"
            )
            if (simpleCollection != null) return simpleCollection

            val mutableEntry = replacePrefixes(
                lowerRendered,
                "kotlin/collections/MutableMap.MutableEntry",
                upperRendered,
                "kotlin/collections/Map.Entry",
                "kotlin/collections/(Mutable)Map.(Mutable)Entry"
            )
            if (mutableEntry != null) return mutableEntry

            val array = replacePrefixes(
                lowerRendered,
                "kotlin/Array<",
                upperRendered,
                "kotlin/Array<out ",
                "kotlin/Array<(out) "
            )
            if (array != null) return array

            return null
        }

        private fun ConeTypeProjection.localTypeRenderer(): String {
            val nullabilitySuffix = when {
                this is ConeKotlinType && this !is ConeKotlinErrorType && this !is ConeClassErrorType -> nullability.suffix
                else -> ""
            }

            return when (this) {
                is ConeKotlinTypeProjectionIn -> "in ${type.tryToRenderConeAsFunctionType()}"
                is ConeKotlinTypeProjectionOut -> "out ${type.tryToRenderConeAsFunctionType()}"
                is ConeClassLikeType -> {
                    buildString {
                        append(lookupTag.classId.asString())
                        if (typeArguments.isNotEmpty()) {
                            append(typeArguments.joinToString(prefix = "<", postfix = ">") { it.tryToRenderConeAsFunctionType() })
                        }
                        append(nullabilitySuffix)
                    }
                }
                is ConeLookupTagBasedType -> lookupTag.name.asString() + nullabilitySuffix
                is ConeFlexibleType -> {
                    val lowerRendered = lowerBound.tryToRenderConeAsFunctionType()
                    if (lowerBound.nullability == ConeNullability.NOT_NULL && upperBound.nullability == ConeNullability.NULLABLE &&
                        AbstractStrictEqualityTypeChecker
                            .strictEqualTypes(session.typeContext, lowerBound, upperBound.withNullability(ConeNullability.NOT_NULL))
                    ) {
                        "$lowerRendered!"
                    } else {
                        val upperRendered = upperBound.tryToRenderConeAsFunctionType()
                        tryToSquashFlexibleType(lowerRendered, upperRendered) ?: "$lowerRendered..$upperRendered"
                    }
                }
                else -> this.render()
            }
        }

        private fun String.removeCurrentFilePackage(): String {
            val withoutPackage = this.replaceFirst("$filePackage.", "").replaceFirst("$filePackageWithSlash/", "")

            return withoutPackage.let { if (it.startsWith("/")) it.substring(1) else it }
        }

        private fun ClassId.getWithoutCurrentPackage() = this.asString().removeCurrentFilePackage()

        private fun <T : FirElement> renderListInTriangles(list: List<T>, data: StringBuilder, withSpace: Boolean = false) {
            if (list.isNotEmpty()) {
                list.joinTo(data, separator = ", ", prefix = "<", postfix = ">") {
                    buildString { it.accept(this@FirRendererForVisualizer, this) }
                }
                if (withSpace) data.append(" ")
            }
        }

        private fun visitArguments(arguments: List<FirExpression>, data: StringBuilder) {
            arguments.joinTo(data, ", ", "(", ")") {
                if (it is FirResolvedQualifier) {
                    val lookupTag = (it.typeRef as FirResolvedTypeRef).coneTypeSafe<ConeClassLikeType>()?.lookupTag
                    val type = lookupTag?.let { tag ->
                        (symbolProvider.getSymbolByLookupTag(tag)?.fir as? FirClass)?.superTypeRefs?.first()?.render()
                    }
                    if (type != null) return@joinTo type
                }
                it.typeRef.render()
            }
        }

        private fun renderImplicitReceiver(symbol: AbstractFirBasedSymbol<*>, psi: PsiElement?) {
            val implicitReceiverIndex = (symbol.fir as? FirCallableMemberDeclaration<*>)?.dispatchReceiverType?.let {
                implicitReceivers.map { (it as? FirResolvedTypeRef)?.coneType }.indexOf(it)
            } ?: return
            if (implicitReceiverIndex != -1) {
                addAnnotation("this@$implicitReceiverIndex", psi)
            }
        }

        private fun renderConstructorSymbol(symbol: FirConstructorSymbol, data: StringBuilder) {
            data.append("constructor ")
            data.append(getSymbolId(symbol))
            renderListInTriangles(symbol.firUnsafe<FirConstructor>().typeParameters, data)
        }

        private fun renderVariable(variable: FirVariable<*>, data: StringBuilder) {
            when (variable) {
                is FirEnumEntry -> {
                    data.append("enum entry ")
                    variable.returnTypeRef.accept(this, data)
                    data.append(".${variable.name}")
                    return
                }
                !is FirValueParameter -> when {
                    variable.isVar -> data.append("var ")
                    variable.isVal -> data.append("val ")
                }
            }
            data.append(getSymbolId(variable.symbol)).append(": ").append(variable.returnTypeRef.render())
        }

        private fun renderPropertySymbol(symbol: FirPropertySymbol, data: StringBuilder) {
            data.append(if (symbol.fir.isVar) "var" else "val").append(" ")
            renderListInTriangles(symbol.fir.typeParameters, data, withSpace = true)

            val id = getSymbolId(symbol)
            val receiver = symbol.fir.receiverTypeRef?.render()
            if (receiver != null) {
                data.append(receiver).append(".")
            } else if (id != symbol.callableId.callableName.asString()) {
                data.append("($id)").append(".")
            }

            data.append(symbol.callableId.callableName).append(": ")
            data.append(symbol.fir.returnTypeRef.render())
        }

        private fun renderFunctionSymbol(symbol: FirNamedFunctionSymbol, data: StringBuilder, call: FirFunctionCall? = null) {
            data.append("fun ")
            renderListInTriangles(symbol.fir.typeParameters, data, true)

            val id = getSymbolId(symbol)
            val callableName = symbol.callableId.callableName
            val receiverType = symbol.fir.receiverTypeRef

            if (call == null) {
                // call is null for callable reference
                if (receiverType == null) {
                    symbol.callableId.className?.let { data.append("($id).$callableName") } ?: data.append(id)
                } else {
                    data.append("${receiverType.render()}.$callableName")
                }
                return
            }

            var withExtensionFunctionType = false
            when {
                call.extensionReceiver !is FirNoReceiverExpression -> {
                    // render type from symbol because this way it will be consistent with psi render
                    symbol.fir.receiverTypeRef?.accept(this, data)
                    data.append(".").append(callableName)
                }
                call.dispatchReceiver.typeRef.annotations.any { it.isExtensionFunctionAnnotationCall } -> {
                    withExtensionFunctionType = true
                    symbol.fir.valueParameters.first().returnTypeRef.accept(this, data)
                    data.append(".").append(callableName)
                }
                call.dispatchReceiver !is FirNoReceiverExpression -> {
                    data.append("(")
                    val dispatch = buildString { call.dispatchReceiver.typeRef.accept(this@FirRendererForVisualizer, this) }
                    val localPath = if (symbol.isLocalDeclaration()) stack.getPathByName(dispatch) else ""
                    data.append(localPath).append(dispatch).append(").").append(callableName)
                }
                else -> data.append(id)
            }

            renderListInTriangles(call.typeArguments, data)
            val valueParameters = symbol.fir.valueParameters.let { if (withExtensionFunctionType) it.drop(1) else it }
            visitValueParameters(valueParameters, data)
            data.append(": ")
            symbol.fir.returnTypeRef.accept(this, data)
        }

        override fun visitElement(element: FirElement, data: StringBuilder) {
            element.acceptChildren(this, data)
        }

        override fun visitErrorNamedReference(errorNamedReference: FirErrorNamedReference, data: StringBuilder) {
            data.append(errorNamedReference.name)
        }

        private fun visitConstructor(call: FirResolvable, data: StringBuilder) {
            val calleeReference = call.calleeReference
            if (calleeReference !is FirResolvedNamedReference) {
                data.append("[ERROR: Unresolved]")
            } else {
                when (call) {
                    is FirDelegatedConstructorCall -> {
                        val actualReturnType = calleeReference.resolvedSymbol.firUnsafe<FirConstructor>().returnTypeRef
                        if (call.constructedTypeRef.coneType != actualReturnType.coneType) {
                            // is typealias
                            val coneType = call.constructedTypeRef.coneType.localTypeRenderer()
                            val typeWithActual = buildString { call.constructedTypeRef.accept(this@FirRendererForVisualizer, this) }
                            data.append("fun ").append(coneType).append(".<init>(): ").append(typeWithActual)
                            return
                        }
                    }
                }
                visitConstructor(calleeReference.resolvedSymbol.fir as FirConstructor, data)
            }
        }

        override fun visitConstructor(constructor: FirConstructor, data: StringBuilder) {
            renderConstructorSymbol(constructor.symbol, data)
            visitValueParameters(constructor.valueParameters, data)
        }

        override fun visitTypeParameterRef(typeParameterRef: FirTypeParameterRef, data: StringBuilder) {
            visitTypeParameter(typeParameterRef.symbol.fir, data)
        }

        override fun visitTypeParameter(typeParameter: FirTypeParameter, data: StringBuilder) {
            data.append(typeParameter.name)
            val bounds = typeParameter.bounds.filterNot { it.render() == "kotlin/Any?" }
            if (bounds.isNotEmpty()) {
                data.append(" : ")
                bounds.joinTo(data, separator = ", ") {
                    buildString { it.accept(this@FirRendererForVisualizer, this) }
                }
            }
        }

        override fun visitBackingFieldReference(backingFieldReference: FirBackingFieldReference, data: StringBuilder) {
            val firProperty = backingFieldReference.resolvedSymbol.fir
            data.append(if (firProperty.isVar) "var " else "val ")
                .append(stack.getPathByName("field"))
                .append("field: ")
                .append(firProperty.returnTypeRef.render())
        }

        override fun visitProperty(property: FirProperty, data: StringBuilder) {
            if (property.isLocal) {
                visitVariable(property, data)
                return
            }
            data.append(property.returnTypeRef.render())
        }

        private fun visitValueParameters(valueParameters: List<FirValueParameter>, data: StringBuilder) {
            valueParameters.joinTo(data, separator = ", ", prefix = "(", postfix = ")") {
                buildString { it.accept(this@FirRendererForVisualizer, this) }
            }
        }

        override fun visitValueParameter(valueParameter: FirValueParameter, data: StringBuilder) {
            if (valueParameter.isVararg) {
                data.append("vararg ")
                valueParameter.returnTypeRef.coneTypeSafe<ConeClassLikeType>()?.arrayElementType()?.let { data.append(it.localTypeRenderer()) }
            } else {
                valueParameter.returnTypeRef.accept(this, data)
            }
            valueParameter.defaultValue?.let { data.append(" = ...") }
        }

        override fun <F : FirVariable<F>> visitVariable(variable: FirVariable<F>, data: StringBuilder) {
            data.append(variable.returnTypeRef.render())
        }

        override fun visitNamedReference(namedReference: FirNamedReference, data: StringBuilder) {
            if (namedReference is FirErrorNamedReference) {
                data.append("[ERROR : ${namedReference.diagnostic.reason}]")
                return
            }
            visitElement(namedReference, data)
        }

        override fun visitResolvedNamedReference(resolvedNamedReference: FirResolvedNamedReference, data: StringBuilder) {
            val symbol = resolvedNamedReference.resolvedSymbol
            renderImplicitReceiver(symbol, resolvedNamedReference.source.psi)
            when {
                symbol is FirPropertySymbol && !symbol.fir.isLocal -> renderPropertySymbol(symbol, data)
                symbol is FirNamedFunctionSymbol -> {
                    val fir = symbol.fir
                    data.append(stack.getPathByName(resolvedNamedReference.name.asString()))
                        .append(resolvedNamedReference.name)
                        .append(": ")
                        .append(fir.dispatchReceiverType?.tryToRenderConeAsFunctionType())
                }
                else -> (symbol.fir as? FirVariable<*>)?.let { renderVariable(it, data) }
            }
        }

        override fun visitResolvedCallableReference(resolvedCallableReference: FirResolvedCallableReference, data: StringBuilder) {
            when (val symbol = resolvedCallableReference.resolvedSymbol) {
                is FirPropertySymbol -> renderPropertySymbol(symbol, data)
                is FirNamedFunctionSymbol -> {
                    renderFunctionSymbol(symbol, data)

                    val fir = symbol.firUnsafe<FirFunction<*>>()
                    visitValueParameters(fir.valueParameters, data)
                    data.append(": ")
                    fir.returnTypeRef.accept(this, data)
                }
            }
        }

        override fun visitAnnotationCall(annotationCall: FirAnnotationCall, data: StringBuilder) {
            visitConstructor(annotationCall, data)
        }

        override fun visitDelegatedConstructorCall(delegatedConstructorCall: FirDelegatedConstructorCall, data: StringBuilder) {
            val coneClassType = delegatedConstructorCall.constructedTypeRef.coneTypeSafe<ConeClassLikeType>()
            if (coneClassType != null) {
                visitConstructor(delegatedConstructorCall, data)
            } else {
                data.append("[ERROR : ${delegatedConstructorCall.constructedTypeRef.render()}]")
            }
        }

        override fun visitComparisonExpression(comparisonExpression: FirComparisonExpression, data: StringBuilder) {
            data.append("CMP(${comparisonExpression.operation.operator}, ")
            comparisonExpression.compareToCall.accept(this, data)
            data.append(")")
        }

        override fun visitTypeOperatorCall(typeOperatorCall: FirTypeOperatorCall, data: StringBuilder) {
            //skip rendering for as/as?/is/!is
        }

        override fun visitAssignmentOperatorStatement(assignmentOperatorStatement: FirAssignmentOperatorStatement, data: StringBuilder) {
            data.append("assignment operator statement ${assignmentOperatorStatement.operation}")
        }

        override fun visitEqualityOperatorCall(equalityOperatorCall: FirEqualityOperatorCall, data: StringBuilder) {
            data.append("EQ operator call")
        }

        override fun visitSafeCallExpression(safeCallExpression: FirSafeCallExpression, data: StringBuilder) {
            safeCallExpression.receiver.accept(this, data)
            data.append("?.{ ")
            safeCallExpression.regularQualifiedAccess.accept(this, data)
            data.append(" }")
        }

        override fun visitCheckedSafeCallSubject(checkedSafeCallSubject: FirCheckedSafeCallSubject, data: StringBuilder) {
            data.append("\$subj\$")
        }

        override fun visitImplicitInvokeCall(implicitInvokeCall: FirImplicitInvokeCall, data: StringBuilder) {
            visitFunctionCall(implicitInvokeCall, data)
        }

        override fun visitFunctionCall(functionCall: FirFunctionCall, data: StringBuilder) {
            when (val callee = functionCall.calleeReference) {
                is FirResolvedNamedReference -> {
                    if (functionCall.explicitReceiver == null) {
                        renderImplicitReceiver(callee.resolvedSymbol, functionCall.source.psi)
                    }
                    when (callee.resolvedSymbol) {
                        is FirConstructorSymbol -> {
                            renderConstructorSymbol(callee.resolvedSymbol as FirConstructorSymbol, data)
                            visitValueParameters((callee.resolvedSymbol.fir as FirConstructor).valueParameters, data)
                        }
                        else -> renderFunctionSymbol(callee.resolvedSymbol as FirNamedFunctionSymbol, data, functionCall)
                    }
                }
                is FirErrorNamedReference -> data.append("[ERROR : ${callee.diagnostic.reason}]")
            }
        }

        override fun <T> visitConstExpression(constExpression: FirConstExpression<T>, data: StringBuilder) {
            when (constExpression.kind) {
                ConstantValueKind.String -> return
                ConstantValueKind.Null -> constExpression.typeRef.accept(this, data)
                else -> data.append(constExpression.kind)
            }
        }

        override fun visitResolvedQualifier(resolvedQualifier: FirResolvedQualifier, data: StringBuilder) {
            resolvedQualifier.symbol?.fir?.let { fir ->
                if (fir is FirClass) {
                    data.append(fir.classKind.name.toLowerCaseAsciiOnly().replace("_", " ")).append(" ")
                    data.append(fir.symbol.classId.asString().removeCurrentFilePackage())
                    renderListInTriangles(fir.typeParameters, data)
                    if (fir.superTypeRefs.any { it.render() != "kotlin/Any" }) {
                        data.append(": ")
                        fir.superTypeRefs.joinTo(data, separator = ", ") { typeRef -> typeRef.render() }
                    }
                }
            }
        }

        override fun visitVariableAssignment(variableAssignment: FirVariableAssignment, data: StringBuilder) {
            //data.append("variable assignment")
        }

        override fun visitStarProjection(starProjection: FirStarProjection, data: StringBuilder) {
            data.append("*")
        }

        override fun visitTypeProjectionWithVariance(typeProjectionWithVariance: FirTypeProjectionWithVariance, data: StringBuilder) {
            val variance = typeProjectionWithVariance.variance.label
            if (variance.isNotEmpty()) data.append("$variance ")
            typeProjectionWithVariance.typeRef.accept(this, data)
        }

        override fun visitResolvedTypeRef(resolvedTypeRef: FirResolvedTypeRef, data: StringBuilder) {
            val coneType = resolvedTypeRef.type
            data.append(coneType.tryToRenderConeAsFunctionType())

            if (coneType is ConeClassLikeType) {
                val original = coneType.directExpansionType(session)
                original?.let { data.append(" /* = ${it.localTypeRenderer()} */") }
            }
        }

        override fun visitErrorTypeRef(errorTypeRef: FirErrorTypeRef, data: StringBuilder) {
            data.append("[ERROR : ${errorTypeRef.diagnostic.reason}]")
        }

        override fun visitTypeRefWithNullability(typeRefWithNullability: FirTypeRefWithNullability, data: StringBuilder) {
            if (typeRefWithNullability.isMarkedNullable) {
                data.append("?")
            }
        }

        override fun visitGetClassCall(getClassCall: FirGetClassCall, data: StringBuilder) {
            getClassCall.argument.accept(this, data)
        }

        override fun visitArrayOfCall(arrayOfCall: FirArrayOfCall, data: StringBuilder) {
            val name = arrayOfCall.typeRef.coneType.classId?.shortClassName
            val typeArguments = arrayOfCall.typeRef.coneType.typeArguments
            val typeParameters = if (typeArguments.isEmpty()) "" else " <T>"
            data.append("fun$typeParameters ${name?.asString()?.decapitalize()}Of")
            typeArguments.firstOrNull()?.let {
                data.append("<").append(it.tryToRenderConeAsFunctionType()).append(">")
            }
            data.append("(vararg T): $name${typeParameters.trim()}") // TODO change "T" to concrete type is array is primitive
        }

        private fun AbstractFirBasedSymbol<*>.isLocalDeclaration(): Boolean {
            return when (val fir = this.fir) {
                is FirConstructor -> {
                    val firClass = fir.returnTypeRef.coneTypeUnsafe<ConeClassLikeType>().lookupTag.toFirRegularClass(session)
                    firClass?.isLocal ?: false
                }
                is FirCallableDeclaration<*> -> {
                    fir.dispatchReceiverClassOrNull()?.toFirRegularClass(session)?.isLocal ?: false
                }
                else -> false
            }
        }

        private fun getSymbolId(symbol: AbstractFirBasedSymbol<*>?): String {
            return when (symbol) {
                is FirCallableSymbol<*> -> {
                    val callableId = symbol.callableId
                    val isLocal = symbol.isLocalDeclaration()
                    val trimmedCallableId = callableId.toString()
                        .replaceFirst(".${callableId.callableName}", "")
                        .removeCurrentFilePackage()
                    val localPath = if (isLocal) stack.getPathByName(trimmedCallableId) else ""
                    localPath + trimmedCallableId
                }
                is FirClassLikeSymbol<*> -> symbol.classId.getWithoutCurrentPackage()
                else -> ""
            }
        }
    }
}
