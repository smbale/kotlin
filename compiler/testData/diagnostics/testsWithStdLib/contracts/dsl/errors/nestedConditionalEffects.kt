// !LANGUAGE: +AllowContractsForCustomFunctions +UseReturnsEffect
// !DIAGNOSTICS: -INVISIBLE_REFERENCE -INVISIBLE_MEMBER

import kotlin.internal.contracts.*

fun foo(boolean: Boolean) {
    <!ERROR_IN_CONTRACT_DESCRIPTION(Error in contract description)!>contract {
        (returns() implies (boolean)) <!UNRESOLVED_REFERENCE!>implies<!> (!boolean)
    }<!>
}