/*
 * Copyright 2010-2015 JetBrains s.r.o.
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

package org.jetbrains.kotlin.idea.debugger

import com.intellij.debugger.engine.JavaStackFrame
import com.intellij.debugger.engine.JavaValue
import com.intellij.debugger.engine.evaluation.EvaluationContextImpl
import com.intellij.debugger.jdi.LocalVariableProxyImpl
import com.intellij.debugger.jdi.StackFrameProxyImpl
import com.intellij.debugger.ui.impl.watch.MethodsTracker
import com.intellij.debugger.ui.impl.watch.StackFrameDescriptorImpl
import com.intellij.xdebugger.frame.XValueChildrenList
import com.sun.jdi.Value
import org.jetbrains.kotlin.codegen.inline.INLINE_FUN_VAR_SUFFIX
import org.jetbrains.kotlin.codegen.inline.isFakeLocalVariableForInline
import org.jetbrains.kotlin.idea.debugger.evaluate.THIS_NAME

class KotlinStackFrame(frame: StackFrameProxyImpl) : JavaStackFrame(StackFrameDescriptorImpl(frame, MethodsTracker()), true) {
    private val kotlinVariableViewService = ToggleKotlinVariablesState.getService()

    override fun superBuildVariables(evaluationContext: EvaluationContextImpl, children: XValueChildrenList) {
        if (!kotlinVariableViewService.kotlinVariableView) {
            return super.superBuildVariables(evaluationContext, children)
        }

        val nodeManager = evaluationContext.debugProcess.xdebugProcess!!.nodeManager

        fun addItem(variable: LocalVariableProxyImpl) {
            val variableDescriptor = nodeManager.getLocalVariableDescriptor(null, variable)
            children.add(JavaValue.create(null, variableDescriptor, evaluationContext, nodeManager, false))
        }

        val (thisReferences, otherVariables) = visibleVariables
            .partition { it.name() == THIS_NAME || it.name().startsWith("$THIS_NAME ") }

        thisReferences.forEach(::addItem)
        otherVariables.forEach(::addItem)
    }

    override fun getVisibleVariables(): List<LocalVariableProxyImpl> {
        if (!kotlinVariableViewService.kotlinVariableView) {
            return super.getVisibleVariables().map { variable ->
                if (isFakeLocalVariableForInline(variable.name())) variable.wrapSyntheticInlineVariable() else variable
            }
        }

        val nonSyntheticVariables = super.getVisibleVariables().filter { !isFakeLocalVariableForInline(it.name()) }

        val inlineDepth = calculateInlineDepth(nonSyntheticVariables)
        val effectivelyVisibleVariables = nonSyntheticVariables
            .filter { it.name().endsWith(INLINE_FUN_VAR_SUFFIX.repeat(inlineDepth)) }

        return effectivelyVisibleVariables
            .groupBy { getVariableNameBase(it) }
            .flatMap { remapVariableGroup(it.key, it.value) }
            .sortedBy { it.variable }
            .distinctBy { it.name() }
    }

    private fun remapVariableGroup(
        baseName: String,
        variables: List<LocalVariableProxyImpl>
    ): List<LocalVariableProxyImpl> {
        val mappedVariables = mutableListOf<LocalVariableProxyImpl>()

        val mainVariable = variables.maxBy { it.variable } ?: return emptyList()

        mappedVariables += if (mainVariable.isCapturedThisReference()) {
            // Explicitly leave this_ variable with suffix even if it's the only one
            remapVariableName(mainVariable)
        } else {
            mainVariable.clone(withName = baseName)
        }

        if (!mainVariable.isLabeledThisReference() && mainVariable.isCaptured()) {
            return mappedVariables
        }

        for (variable in variables) {
            if (variable === mainVariable) {
                continue
            }

            mappedVariables += remapVariableName(variable)
        }

        return mappedVariables
    }

    private fun getVariableNameBase(variable: LocalVariableProxyImpl): String {
        if (variable.isThisReference()) {
            return THIS_NAME
        }

        return dropCapturedSuffix(variable.name())
    }

    private fun remapVariableName(variable: LocalVariableProxyImpl) = with(variable) {
        when {
            isLabeledThisReference() -> {
                clone(withName = THIS_NAME + " (" + dropCapturedSuffix(variable.name().drop(THIS_NAME.length)) + ")")
            }
            isCapturedThisReference() -> clone(withName = "$THIS_NAME (inline context)")
            isCaptured() -> clone(withName = name().dropLast(INLINE_FUN_VAR_SUFFIX.length))
            else -> variable
        }
    }

    private fun calculateInlineDepth(variables: List<LocalVariableProxyImpl>): Int {
        return variables.fold(0) { currentMax, v ->
            maxOf(currentMax, calculateInlineDepth(v.name()))
        }
    }

    private fun calculateInlineDepth(name: String): Int {
        var lastIndex = name.lastIndex
        var depth = 0

        while (name.lastIndexOf(INLINE_FUN_VAR_SUFFIX, startIndex = lastIndex) >= 0) {
            lastIndex -= INLINE_FUN_VAR_SUFFIX.length
            depth++
        }
        return depth
    }

    private fun dropCapturedSuffix(name: String): String {
        val depth = calculateInlineDepth(name)
        return name.dropLast(INLINE_FUN_VAR_SUFFIX.length * depth)
    }

    private fun LocalVariableProxyImpl.isCaptured(): Boolean {
        return name().endsWith(INLINE_FUN_VAR_SUFFIX)
    }

    private fun LocalVariableProxyImpl.isThisReference(): Boolean {
        return isCapturedThisReference() || isLabeledThisReference()
    }

    private fun LocalVariableProxyImpl.isCapturedThisReference(): Boolean {
        val name = name()
        return name.startsWith(THIS_NAME + "_") && name.endsWith(INLINE_FUN_VAR_SUFFIX)
    }

    private fun LocalVariableProxyImpl.isLabeledThisReference(): Boolean {
        return name().startsWith(THIS_NAME + "@")
    }
}

private fun LocalVariableProxyImpl.clone(withName: String): LocalVariableProxyImpl {
    return object : LocalVariableProxyImpl(frame, variable) {
        override fun name() = withName
    }
}

private fun LocalVariableProxyImpl.wrapSyntheticInlineVariable(): LocalVariableProxyImpl {
    val proxyWrapper = object : StackFrameProxyImpl(frame.threadProxy(), frame.stackFrame, frame.indexFromBottom) {
        override fun getValue(localVariable: LocalVariableProxyImpl): Value {
            return frame.virtualMachine.mirrorOfVoid()
        }
    }
    return LocalVariableProxyImpl(proxyWrapper, variable)
}