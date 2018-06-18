/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js

import org.jetbrains.kotlin.backend.common.CommonBackendContext
import org.jetbrains.kotlin.backend.common.ReflectionTypes
import org.jetbrains.kotlin.backend.common.descriptors.KnownPackageFragmentDescriptor
import org.jetbrains.kotlin.backend.common.ir.Ir
import org.jetbrains.kotlin.backend.common.ir.Symbols
import org.jetbrains.kotlin.backend.js.JsDescriptorsFactory
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.backend.js.lower.inline.ModuleIndex
import org.jetbrains.kotlin.ir.backend.js.utils.OperatorNames
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrEnumEntrySymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.util.SymbolTable
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.types.KotlinType

class JsIrBackendContext(
    val module: ModuleDescriptor,
    override val irBuiltIns: IrBuiltIns,
    val symbolTable: SymbolTable,
    irModuleFragment: IrModuleFragment
) : CommonBackendContext {

    override val builtIns = module.builtIns

    override val sharedVariablesManager =
        JsSharedVariablesManager(builtIns, KnownPackageFragmentDescriptor(builtIns.builtInsModule, FqName("kotlin.js.internal")))
    override val descriptorsFactory = JsDescriptorsFactory()
    override val reflectionTypes: ReflectionTypes by lazy(LazyThreadSafetyMode.PUBLICATION) {
        // TODO
        ReflectionTypes(module, FqName("kotlin.reflect"))
    }

    private val internalPackageName = FqName("kotlin.js")
    private val internalPackage = module.getPackage(internalPackageName)


    private val coroutinePackageNameSrting = "kotlin.coroutines.experimental"
    private val intrinsicsPackageName = Name.identifier("intrinsics")
    private val COROUTINE_SUSPENDED_NAME = Name.identifier("COROUTINE_SUSPENDED")
    private val COROUTINE_CONTEXT_NAME = Name.identifier("coroutineContext")
    private val COROUTINE_IMPL_NAME = Name.identifier("CoroutineImpl")

    private val coroutinePackageName = FqName(coroutinePackageNameSrting)
    private val coroutineIntrinsicsPackageName = coroutinePackageName.child(intrinsicsPackageName)

    private val coroutinePackage = module.getPackage(coroutinePackageName)
    private val coroutineIntrinsicsPackage = module.getPackage(coroutineIntrinsicsPackageName)

    val enumEntryToGetInstanceFunction = mutableMapOf<IrEnumEntrySymbol, IrSimpleFunctionSymbol>()

    val coroutineContextProperty: PropertyDescriptor
        get() {
            val vars = coroutinePackage.memberScope.getContributedVariables(
                COROUTINE_CONTEXT_NAME,
                NoLookupLocation.FROM_BACKEND
            )
            return vars.single()
        }

    val intrinsics = JsIntrinsics(module, irBuiltIns, this)

    private val operatorMap = referenceOperators()

//    val functions = (0..22).map { symbolTable.referenceClass(getClass(FqName("kotlin.Function$it"))) }
    val functions = (0..22).map { symbolTable.referenceClass(builtIns.getFunction(it)) }

    val kFunctions by lazy {
        (0..22).map { symbolTable.referenceClass(reflectionTypes.getKFunction(it)) }
    }

//    val suspendFunctions by lazy {
//        (0..22).map { symbolTable.referenceClass(getClass(FqName("kotlin.SuspendFunction$it"))) }
//    }
    val suspendFunctions = (0..22).map { symbolTable.referenceClass(builtIns.getSuspendFunction(it)) }


    fun getOperatorByName(name: Name, type: KotlinType) = operatorMap[name]?.get(type)

    val originalModuleIndex = ModuleIndex(irModuleFragment)

    override val ir = object : Ir<CommonBackendContext>(this, irModuleFragment) {
        override val symbols = object : Symbols<CommonBackendContext>(this@JsIrBackendContext, symbolTable.lazyWrapper) {

            override fun calc(initializer: () -> IrClassSymbol): IrClassSymbol {
                val v = lazy { initializer() }
                return object : IrClassSymbol {
                    override val owner: IrClass get() = v.value.owner
                    override val isBound: Boolean get() = v.value.isBound
                    override fun bind(owner: IrClass) = v.value.bind(owner)
                    override val descriptor: ClassDescriptor get() = v.value.descriptor
                }
            }

            override val areEqual
                get () = TODO("not implemented")

            override val ThrowNullPointerException
                get () = irBuiltIns.throwNpeSymbol

            override val ThrowNoWhenBranchMatchedException
                get () = irBuiltIns.noWhenBranchMatchedExceptionSymbol

            override val ThrowTypeCastException
                get () = irBuiltIns.throwCceSymbol

            override val ThrowUninitializedPropertyAccessException = symbolTable.referenceSimpleFunction(
                irBuiltIns.defineOperator(
                    "throwUninitializedPropertyAccessException",
                    builtIns.nothingType,
                    listOf(builtIns.stringType)
                ).descriptor
            )

            override val stringBuilder
                get() = TODO("not implemented")
            override val copyRangeTo: Map<ClassDescriptor, IrSimpleFunctionSymbol>
                get() = TODO("not implemented")
            override val coroutineImpl = symbolTable.referenceClass(
                getClass(
                    coroutinePackageName.child(COROUTINE_IMPL_NAME)
                )
            )
            override val coroutineSuspendedGetter = symbolTable.referenceSimpleFunction(
                coroutineIntrinsicsPackage.memberScope.getContributedVariables(COROUTINE_SUSPENDED_NAME, NoLookupLocation.FROM_BACKEND).single().getter!!
            )
        }

        override fun shouldGenerateHandlerParameterForDefaultBodyFun() = true
    }

    val throwable = symbolTable.referenceClass(builtIns.throwable)

    private fun referenceOperators() = OperatorNames.ALL.map { name ->
        // TODO to replace KotlinType with IrType we need right equals on IrType
        name to irBuiltIns.primitiveTypes.fold(mutableMapOf<KotlinType, IrFunctionSymbol>()) { m, t ->
            val function = t.memberScope.getContributedFunctions(name, NoLookupLocation.FROM_BACKEND).singleOrNull()
            function?.let { m.put(t, symbolTable.referenceSimpleFunction(it)) }
            m
        }
    }.toMap()

    private fun findClass(memberScope: MemberScope, className: String) = findClass(memberScope, Name.identifier(className))

    private fun findClass(memberScope: MemberScope, name: Name) =
        memberScope.getContributedClassifier(name, NoLookupLocation.FROM_BACKEND) as ClassDescriptor

    private fun findFunctions(memberScope: MemberScope, className: String) =
        findFunctions(memberScope, Name.identifier(className))

    private fun findFunctions(memberScope: MemberScope, name: Name) =
        memberScope.getContributedFunctions(name, NoLookupLocation.FROM_BACKEND).toList()

    override fun getInternalClass(name: String) = findClass(internalPackage.memberScope, name)

    override fun getClass(fqName: FqName) = findClass(module.getPackage(fqName.parent()).memberScope, fqName.shortName())

    override fun getInternalFunctions(name: String) = findFunctions(internalPackage.memberScope, name)

    fun getFunctions(fqName: FqName) = findFunctions(module.getPackage(fqName.parent()).memberScope, fqName.shortName())

    override fun log(message: () -> String) {
        /*TODO*/
        print(message())
    }

    override fun report(element: IrElement?, irFile: IrFile?, message: String, isError: Boolean) {
        /*TODO*/
        print(message)
    }
}