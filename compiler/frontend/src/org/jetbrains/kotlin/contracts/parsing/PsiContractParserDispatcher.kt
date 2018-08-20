/*
 * Copyright 2010-2017 JetBrains s.r.o.
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

package org.jetbrains.kotlin.contracts.parsing

import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.contracts.description.BooleanExpression
import org.jetbrains.kotlin.contracts.description.ContractDescription
import org.jetbrains.kotlin.contracts.description.EffectDeclaration
import org.jetbrains.kotlin.contracts.description.expressions.BooleanVariableReference
import org.jetbrains.kotlin.contracts.description.expressions.ConstantReference
import org.jetbrains.kotlin.contracts.description.expressions.ContractDescriptionValue
import org.jetbrains.kotlin.contracts.description.expressions.VariableReference
import org.jetbrains.kotlin.contracts.parsing.ContractsDslNames.CALLS_IN_PLACE_EFFECT
import org.jetbrains.kotlin.contracts.parsing.ContractsDslNames.CONDITIONAL_EFFECT
import org.jetbrains.kotlin.contracts.parsing.ContractsDslNames.RETURNS_EFFECT
import org.jetbrains.kotlin.contracts.parsing.ContractsDslNames.RETURNS_NOT_NULL_EFFECT
import org.jetbrains.kotlin.contracts.parsing.effects.PsiCallsEffectParser
import org.jetbrains.kotlin.contracts.parsing.effects.PsiConditionalEffectParser
import org.jetbrains.kotlin.contracts.parsing.effects.PsiReturnsEffectParser
import org.jetbrains.kotlin.descriptors.ParameterDescriptor
import org.jetbrains.kotlin.descriptors.ReceiverParameterDescriptor
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall
import org.jetbrains.kotlin.resolve.calls.callUtil.getType

internal class PsiContractParserDispatcher(
    private val collector: ContractParsingDiagnosticsCollector,
    private val callContext: ContractCallContext
) {
    private val conditionParser = PsiConditionParser(collector, callContext, this)
    private val constantParser = PsiConstantParser(callContext)
    private val effectsParsers: Map<Name, PsiEffectParser> = mapOf(
        RETURNS_EFFECT to PsiReturnsEffectParser(collector, callContext, this),
        RETURNS_NOT_NULL_EFFECT to PsiReturnsEffectParser(collector, callContext, this),
        CALLS_IN_PLACE_EFFECT to PsiCallsEffectParser(collector, callContext, this),
        CONDITIONAL_EFFECT to PsiConditionalEffectParser(collector, callContext, this)
    )

    fun parseContract(): ContractDescription? {
        // Must be non-null because of checks in 'checkContractAndRecordIfPresent', but actually is not, see EA-124365
        val resolvedCall = callContext.contractCallExpression.getResolvedCall(callContext.bindingContext) ?: return null

        val lambda = resolvedCall.firstArgumentAsExpressionOrNull() as? KtLambdaExpression ?: return null

        val effects = lambda.bodyExpression?.statements?.mapNotNull { parseEffect(it) } ?: return null

        if (effects.isEmpty()) return null

        return ContractDescription(effects, callContext.functionDescriptor)
    }

    fun parseCondition(expression: KtExpression?): BooleanExpression? = expression?.accept(conditionParser, Unit)

    fun parseEffect(expression: KtExpression?): EffectDeclaration? {
        if (expression == null) return null
        val returnType = expression.getType(callContext.bindingContext) ?: return null
        val parser = effectsParsers[returnType.constructor.declarationDescriptor?.name]
        if (parser == null) {
            collector.badDescription("Unrecognized effect", expression)
            return null
        }
        return parser.tryParseEffect(expression)
    }

    fun parseConstant(expression: KtExpression?): ConstantReference? {
        if (expression == null) return null
        return expression.accept(constantParser, Unit)
    }

    fun parseVariable(expression: KtExpression?): VariableReference? {
        if (expression == null) return null
        val descriptor = expression.getResolvedCall(callContext.bindingContext)?.resultingDescriptor ?: return null
        if (descriptor !is ParameterDescriptor) {
            collector.badDescription("only references to parameters are allowed in contract description", expression)
            return null
        }

        if (descriptor is ReceiverParameterDescriptor && descriptor.type.constructor.declarationDescriptor?.isFromContractDsl() == true) {
            collector.badDescription("only references to parameters are allowed. Did you miss label on <this>?", expression)
            return null
        }

        return if (KotlinBuiltIns.isBoolean(descriptor.type))
            BooleanVariableReference(descriptor)
        else
            VariableReference(descriptor)
    }

    fun parseValue(expression: KtExpression?): ContractDescriptionValue? {
        val variable = parseVariable(expression)
        if (variable != null) return variable

        return parseConstant(expression)
    }
}