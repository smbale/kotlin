// !LANGUAGE: +AllowContractsForCustomFunctions +UseReturnsEffect
// !DIAGNOSTICS: -INVISIBLE_REFERENCE -INVISIBLE_MEMBER

import kotlin.internal.contracts.*

inline fun <reified T> referToReifiedGeneric(x: Any?) {
    contract {
        returns() implies (x is <!ERROR_IN_CONTRACT_DESCRIPTION(References to type parameters are forbidden in contracts)!>T<!>)
    }
}

class Generic<T> {
    fun referToCaptured(x: Any?) {
        <!CONTRACT_NOT_ALLOWED(Contracts are allowed only for top-level functions)!>contract {
            returns() implies (x is <!CANNOT_CHECK_FOR_ERASED!>T<!>)
        }<!>
    }
}

fun referToSubstituted(x: Any?) {
    <!ERROR_IN_CONTRACT_DESCRIPTION(Error in contract description)!>contract {
        returns() implies (x is <!CANNOT_CHECK_FOR_ERASED!>Generic<String><!>)
    }<!>
}

fun referToSubstitutedWithStar(x: Any?) {
    contract {
        returns() implies (x is Generic<*>)
    }
}

typealias GenericString = Generic<String>
typealias FunctionalType = () -> Unit
typealias SimpleType = Int

fun referToAliasedGeneric(x: Any?) {
    <!ERROR_IN_CONTRACT_DESCRIPTION(Error in contract description)!>contract {
        returns() implies (x is <!CANNOT_CHECK_FOR_ERASED!>GenericString<!>)
    }<!>
}

fun referToAliasedFunctionType(x: Any?) {
    <!ERROR_IN_CONTRACT_DESCRIPTION(Error in contract description)!>contract {
        returns() implies (x is <!CANNOT_CHECK_FOR_ERASED!>FunctionalType<!>)
    }<!>
}

fun referToAliasedSimpleType(x: Any?) {
    contract {
        returns() implies (x is SimpleType)
    }
}