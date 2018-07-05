// IGNORE_BACKEND: JS_IR
// IGNORE_BACKEND: JVM_IR
// WITH_RUNTIME
// WITH_COROUTINES
// COMMON_COROUTINES_TEST
import helpers.*
import COROUTINES_PACKAGE.*
import COROUTINES_PACKAGE.intrinsics.*

class Controller {
    suspend fun suspendHere(v: Int): Int = suspendCoroutineUninterceptedOrReturn { x ->
        x.resume(v * 2)
        COROUTINE_SUSPENDED
    }
}

fun builder(c: suspend Controller.() -> Unit) {
    c.startCoroutine(Controller(), EmptyContinuation)
}

inline fun foo(x: (Int) -> Unit) {
    var _i = 1
    while (_i <= 2) {
        val i = _i++
        x(i)
    }
}

fun box(): String {
    var result = ""

    builder {
        result += "-"
        foo {
            result += suspendHere(it).toString()
        }
        result += "+"
    }

    if (result != "-24+") return "fail: $result"

    return "OK"
}
