/*
 * Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.declarations.impl

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirPropertyAccessor
import org.jetbrains.kotlin.fir.expressions.FirBody
import org.jetbrains.kotlin.ir.declarations.IrDeclarationKind

class FirPropertyAccessorImpl(
    session: FirSession,
    psi: PsiElement?,
    override val isGetter: Boolean,
    override val visibility: Visibility,
    body: FirBody?
) : FirAbstractFunction(session, psi, IrDeclarationKind.PROPERTY_ACCESSOR, body), FirPropertyAccessor