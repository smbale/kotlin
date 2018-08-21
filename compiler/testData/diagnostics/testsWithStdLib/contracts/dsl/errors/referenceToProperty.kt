// !LANGUAGE: +AllowContractsForCustomFunctions +UseReturnsEffect
// !DIAGNOSTICS: -INVISIBLE_REFERENCE -INVISIBLE_MEMBER

import kotlin.internal.contracts.*

class Foo(val x: Int?) {
    fun isXNull(): Boolean {
        <!CONTRACT_NOT_ALLOWED!>contract {
            returns(false) implies (x != null)
        }<!>
        return x != null
    }
}