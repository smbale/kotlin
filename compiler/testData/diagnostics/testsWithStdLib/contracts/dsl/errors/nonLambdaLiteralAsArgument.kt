// !LANGUAGE: +AllowContractsForCustomFunctions +UseReturnsEffect
// !DIAGNOSTICS: -INVISIBLE_REFERENCE -INVISIBLE_MEMBER -EXPOSED_PARAMETER_TYPE

import kotlin.internal.contracts.*

fun passLambdaValue(l: ContractBuilder.() -> Unit) {
    contract(<!ERROR_IN_CONTRACT_DESCRIPTION(First argument of 'contract'-call should be a lambda expression)!>l<!>)
}

fun passAnonymousFunction(x: Boolean) {
    contract(<!ERROR_IN_CONTRACT_DESCRIPTION(First argument of 'contract'-call should be a lambda expression)!>fun ContractBuilder.() {
        returns() implies x
    }<!>)
}