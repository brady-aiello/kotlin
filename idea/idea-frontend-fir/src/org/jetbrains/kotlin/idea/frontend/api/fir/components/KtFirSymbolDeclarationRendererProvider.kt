/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.frontend.api.fir.components

import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.types.ConeTypeProjection
import org.jetbrains.kotlin.idea.frontend.api.ValidityToken
import org.jetbrains.kotlin.idea.frontend.api.components.KtSymbolDeclarationRendererProvider
import org.jetbrains.kotlin.idea.frontend.api.fir.KtFirAnalysisSession
import org.jetbrains.kotlin.idea.frontend.api.fir.renderer.ConeTypeRenderer
import org.jetbrains.kotlin.idea.frontend.api.fir.renderer.FirRenderer
import org.jetbrains.kotlin.idea.frontend.api.fir.renderer.FirRendererOptions
import org.jetbrains.kotlin.idea.frontend.api.fir.symbols.KtFirSymbol
import org.jetbrains.kotlin.idea.frontend.api.symbols.KtSymbol
import org.jetbrains.kotlin.idea.frontend.api.symbols.markers.KtSymbolWithKind

internal class KtFirSymbolDeclarationRendererProvider(
    override val analysisSession: KtFirAnalysisSession,
    override val token: ValidityToken
): KtSymbolDeclarationRendererProvider() {

    fun renderType(type: ConeTypeProjection) {
        val options = FirRendererOptions().also { it.renderContainingDeclarations = true }
        ConeTypeRenderer(analysisSession.firResolveState.rootModuleSession, options).renderType(type)
    }

    override fun render(symbol: KtSymbol): String? {
        require(symbol is KtFirSymbol<*>)

        val containingSymbol = with(analysisSession) {
            (symbol as? KtSymbolWithKind)?.getContainingSymbol()
        }
        require(containingSymbol is KtFirSymbol<*>?)

        val options = FirRendererOptions().also { it.renderContainingDeclarations = true }

        return symbol.firRef.withFir(FirResolvePhase.IMPLICIT_TYPES_BODY_RESOLVE) { fir ->
            val typeRenderer = ConeTypeRenderer(fir.session, options)
            containingSymbol?.firRef?.withFir { containingFir ->
                FirRenderer.render(fir, containingFir, options, typeRenderer)
            } ?: FirRenderer.render(fir, null, options, typeRenderer)
        }
    }
}