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

package org.jetbrains.kotlin.allprivate

import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.extensions.AnnotationBasedExtension
import org.jetbrains.kotlin.extensions.DeclarationAttributeAltererExtension
import org.jetbrains.kotlin.psi.KtModifierListOwner

class CliAllPrivateDeclarationAttributeAltererExtension(
    private val allPrivateAnnotationFqNames: List<String>
) : AbstractAllPrivateDeclarationAttributeAltererExtension() {
    override fun getAnnotationFqNames(modifierListOwner: KtModifierListOwner?) = allPrivateAnnotationFqNames
}

abstract class AbstractAllPrivateDeclarationAttributeAltererExtension : DeclarationAttributeAltererExtension, AnnotationBasedExtension {
    companion object {
        val ANNOTATIONS_FOR_TESTS = listOf("AllPrivate", "AllPrivate2", "test.AllPrivate")
    }

   override fun refineDeclarationVisibility(
       modifierListOwner: KtModifierListOwner,
       declaration: DeclarationDescriptor?,
       containingDeclaration: DeclarationDescriptor?,
       currentVisibility: Visibility,
       isImplicitVisibility: Boolean
    ): Visibility? {
       val descriptor = declaration as? ClassDescriptor ?: containingDeclaration ?: return null
       if (descriptor.hasSpecialAnnotation(modifierListOwner)) {
           // If it has the annotation, make it public
           // Else make it private
           return Visibilities.Public
       }
       return null
    }
}