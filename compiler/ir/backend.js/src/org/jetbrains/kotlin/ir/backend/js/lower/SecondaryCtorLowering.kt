/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower

import org.jetbrains.kotlin.backend.common.DeclarationContainerLoweringPass
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.backend.js.ir.JsIrBuilder
import org.jetbrains.kotlin.ir.backend.js.utils.Namer
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueParameterSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.transformFlat
import org.jetbrains.kotlin.ir.visitors.IrElementTransformer
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

class SecondaryCtorLowering(val context: JsIrBackendContext) : IrElementTransformerVoid(), DeclarationContainerLoweringPass {

    private val oldCtorToNewMap = mutableMapOf<IrConstructorSymbol, JsIrBackendContext.SecondaryCtorPair>()

    override fun lower(irDeclarationContainer: IrDeclarationContainer) {
        irDeclarationContainer.declarations.transformFlat {
            if (it is IrClass) {
                listOf(it) + lowerClass(it)
            } else null
        }

        context.secondaryConstructorsMap.putAll(oldCtorToNewMap)
    }

    private fun lowerClass(irClass: IrClass): List<IrSimpleFunction> {
        val className = irClass.name.asString()
        val oldConstructors = mutableListOf<IrConstructor>()
        val newConstructors = mutableListOf<IrSimpleFunction>()

        for (declaration in irClass.declarations) {
            if (declaration is IrConstructor && !declaration.isPrimary) {
                // TODO delegate name generation
                val constructorName = "${className}_init"
                // We should split secondary constructor into two functions,
                //   *  Initializer which contains constructor's body and takes just created object as implicit param `$this`
                //   **   This function is also delegation constructor
                //   *  Creation function which has same signature with original constructor,
                //      creates new object via `Object.create` builtIn and passes it to corresponding `Init` function
                // In other words:
                // Foo::constructor(...) {
                //   body
                // }
                // =>
                // Foo_init_$Init$(..., $this) {
                //   body[ this = $this ]
                //   return $this
                // }
                // Foo_init_$Create$(...) {
                //   val t = Object.create(Foo.prototype);
                //   return Foo_init_$Init$(..., t)
                // }

                val newInitConstructor = createInitConstructor(declaration, constructorName, irClass.defaultType)
                val newCreateConstructor = createCreateConstructor(declaration, newInitConstructor, constructorName, irClass.defaultType)

                oldCtorToNewMap[declaration.symbol] =
                        JsIrBackendContext.SecondaryCtorPair(newInitConstructor.symbol, newCreateConstructor.symbol)

                oldConstructors += declaration
                newConstructors += newInitConstructor
                newConstructors += newCreateConstructor
            }
        }

        irClass.declarations.removeAll(oldConstructors)

        return newConstructors
    }

    private class ThisUsageReplaceTransformer(val function: IrFunctionSymbol, val thisSymbol: IrValueParameterSymbol) :
        IrElementTransformerVoid() {

        override fun visitReturn(expression: IrReturn): IrExpression = IrReturnImpl(
            expression.startOffset,
            expression.endOffset,
            expression.type,
            function,
            IrGetValueImpl(expression.startOffset, expression.endOffset, thisSymbol.owner.type, thisSymbol)
        )

        override fun visitGetValue(expression: IrGetValue): IrExpression =
            if (expression.symbol.owner.name.isSpecial && expression.symbol.owner.name.asString() == Namer.THIS_SPECIAL_NAME) IrGetValueImpl(
                expression.startOffset,
                expression.endOffset,
                expression.type,
                thisSymbol,
                expression.origin
            ) else {
                expression
            }
    }

    private fun createInitConstructor(
        declaration: IrConstructor,
        name: String,
        type: IrType
    ): IrSimpleFunction {

        val thisParam = JsIrBuilder.buildValueParameter("\$this", declaration.valueParameters.size, type)
        val functionName = "${name}_\$Init\$"

        return JsIrBuilder.buildFunction(
            functionName,
            declaration.visibility,
            Modality.FINAL,
            declaration.isInline,
            declaration.isExternal
        ).also {
            thisParam.run { parent = it }
            val retStmt = JsIrBuilder.buildReturn(it.symbol, JsIrBuilder.buildGetValue(thisParam.symbol), context.irBuiltIns.nothingType)
            val statements = (declaration.body!!.deepCopyWithSymbols(it) as IrStatementContainer).statements

            val newValueParameters = declaration.valueParameters.map { p ->
                val np = JsIrBuilder.buildValueParameter(p.name, p.index, p.type)
                np.parent = it
                np
            }

            it.valueParameters += (newValueParameters + thisParam)

            it.typeParameters += declaration.typeParameters.map { p ->
                val np = JsIrBuilder.buildTypeParameter(p.name, p.index, p.isReified, p.variance)
                np.parent = it
                np
            }

            it.returnType = type
            it.parent = declaration.parent

            it.body = JsIrBuilder.buildBlockBody(statements + retStmt).apply {
                transformChildrenVoid(ThisUsageReplaceTransformer(it.symbol, thisParam.symbol))
            }
        }
    }

    private fun createCreateConstructor(
        declaration: IrConstructor,
        ctorImpl: IrSimpleFunction,
        name: String,
        type: IrType
    ): IrSimpleFunction {

        val functionName = "${name}_\$Create\$"

        return JsIrBuilder.buildFunction(
            functionName,
            declaration.visibility,
            Modality.FINAL,
            declaration.isInline,
            declaration.isExternal
        ).also {
            it.valueParameters += declaration.valueParameters.map { p ->
                val np = JsIrBuilder.buildValueParameter(p.name, p.index, p.type)
                np.parent = it
                np
            }
            it.typeParameters += declaration.typeParameters.map { p ->
                val np = JsIrBuilder.buildTypeParameter(p.name, p.index, p.isReified, p.variance)
                np.parent = it
                np
            }
            it.parent = declaration.parent

            it.returnType = type

            val createFunctionIntrinsic = context.intrinsics.jsObjectCreate
            val irCreateCall = JsIrBuilder.buildCall(createFunctionIntrinsic.symbol, type, listOf(type))
            val irDelegateCall = JsIrBuilder.buildCall(ctorImpl.symbol, type).also { call ->
                for (i in 0 until it.valueParameters.size) {
                    call.putValueArgument(i, JsIrBuilder.buildGetValue(it.valueParameters[i].symbol))
                }
//                    valueParameters.forEachIndexed { i, p -> it.putValueArgument(i, JsIrBuilder.buildGetValue(p.symbol)) }
                call.putValueArgument(declaration.valueParameters.size, irCreateCall)

//                typeParameters.mapIndexed { i, t -> ctorImpl.typeParameters[i].descriptor ->  }
            }
            val irReturn = JsIrBuilder.buildReturn(it.symbol, irDelegateCall, context.irBuiltIns.nothingType)


            it.body = JsIrBuilder.buildBlockBody(listOf(irReturn))
        }
    }

    class CallsiteRedirectionTransformer(val context: JsIrBackendContext) : IrElementTransformer<IrFunction?> {

        override fun visitFunction(declaration: IrFunction, data: IrFunction?): IrStatement = super.visitFunction(declaration, declaration)

        override fun visitCall(expression: IrCall, ownerFunc: IrFunction?): IrElement {
            super.visitCall(expression, ownerFunc)

            // TODO: figure out the reason why symbol is not bound
            if (expression.symbol.isBound) {

                val target = expression.symbol.owner

                if (target is IrConstructor) {
                    if (!target.isPrimary) {
                        val ctor = context.secondaryConstructorsMap[target.symbol]
                        if (ctor != null) {

                            return redirectCall(expression, ctor.stub)
                        }
                    }
                }
            }

            return expression
        }

        override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall, ownerFunc: IrFunction?): IrElement {
            super.visitDelegatingConstructorCall(expression, ownerFunc)

            val target = expression.symbol
            if (target.owner.isPrimary) {
                // nothing to do here
                return expression
            }

            val fromPrimary = ownerFunc!! is IrConstructor
            // TODO: what is `deserialized` constructor?
            val ctor = context.secondaryConstructorsMap[target] ?: return expression
            val newCall = redirectCall(expression, ctor.delegate)

            val readThis = if (fromPrimary) {
                val thisKlass = expression.symbol.owner.parent as IrClass
                val thisSymbol = thisKlass.thisReceiver!!.symbol
                IrGetValueImpl(
                    expression.startOffset,
                    expression.endOffset,
                    expression.type,
                    thisSymbol
                )
            } else {
                IrGetValueImpl(expression.startOffset, expression.endOffset, expression.type, ownerFunc.valueParameters.last().symbol)
            }

            newCall.putValueArgument(expression.valueArgumentsCount, readThis)

            return newCall
        }

        private fun redirectCall(
            call: IrFunctionAccessExpression,
            newTarget: IrSimpleFunctionSymbol
        ) = IrCallImpl(call.startOffset, call.endOffset, call.type, newTarget).apply {

            copyTypeArgumentsFrom(call)

            for (i in 0 until call.valueArgumentsCount) {
                putValueArgument(i, call.getValueArgument(i))
            }
        }

    }
}
