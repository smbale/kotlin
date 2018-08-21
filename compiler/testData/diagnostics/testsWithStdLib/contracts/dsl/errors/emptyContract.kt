// !LANGUAGE: +AllowContractsForCustomFunctions +UseReturnsEffect
// !DIAGNOSTICS: -INVISIBLE_REFERENCE -INVISIBLE_MEMBER

import kotlin.internal.contracts.*

fun emptyContract() {
    <!ERROR_IN_CONTRACT_DESCRIPTION(Error in contract description)!>contract { }<!>
}