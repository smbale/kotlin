/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.utils

import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.js.backend.ast.*

open class JsGenerationContext {
    fun newDeclaration(scope: JsScope, func: IrFunction? = null): JsGenerationContext {
        return JsGenerationContext(this, if (func != null) JsBlock() else JsGlobalBlock(), scope, func)
    }

    fun newSuspendableContext(resumePoints: MutableList<Pair<Int, JsStatement>>) =
        JsSuspendableGenerationContext(currentScope.declareFreshName("\$coroutine\$"), resumePoints, this)

    val currentBlock: JsBlock
    val currentScope: JsScope
    val currentFunction: IrFunction?
    val parent: JsGenerationContext?
    val staticContext: JsStaticContext

    open val coroutineLabel: JsName get() = parent?.run { coroutineLabel } ?: error("Should be inside coroutine scope")

    fun nextState() = staticContext.stateCounter++

    private val program: JsProgram

    constructor(rootScope: JsRootScope, backendContext: JsIrBackendContext) {

        this.parent = null
        this.program = rootScope.program
        this.staticContext = JsStaticContext(rootScope, program.globalBlock, SimpleNameGenerator(), backendContext)
        this.currentScope = rootScope
        this.currentBlock = program.globalBlock
        this.currentFunction = null
    }

    constructor(parent: JsGenerationContext, block: JsBlock, scope: JsScope, func: IrFunction?) {
        this.parent = parent
        this.program = parent.program
        this.staticContext = parent.staticContext
        this.currentBlock = block
        this.currentScope = scope
        this.currentFunction = func
    }

    fun getNameForSymbol(symbol: IrSymbol): JsName = staticContext.getNameForSymbol(symbol, this)
    fun getNameForLoop(loop: IrLoop): JsName? = staticContext.getNameForLoop(loop, this)

    open fun addResumePoint(id: Int, point: JsStatement) {
        parent?.run { addResumePoint(id, point) } ?: error("Should be inside coroutine scope")
    }


}

class JsSuspendableGenerationContext(
    override val coroutineLabel: JsName,
    private val resumePoints: MutableList<Pair<Int, JsStatement>>,
    parent: JsGenerationContext
) : JsGenerationContext(parent, parent.currentBlock, parent.currentScope, parent.currentFunction) {

    init {
        staticContext.stateCounter = 0
    }

    override fun addResumePoint(id: Int, point: JsStatement) {
        resumePoints += Pair(id, point)
    }
}