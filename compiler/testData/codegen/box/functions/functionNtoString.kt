// IGNORE_BACKEND: JVM_IR
// IGNORE_BACKEND: JS_IR
// TODO: muted automatically, investigate should it be ran for JS or not
// IGNORE_BACKEND: JS, NATIVE

// WITH_REFLECT

fun check(expected: String, obj: Any?) {
    val actual = obj.toString()
    if (actual != expected)
        throw AssertionError("Expected: $expected, actual: $actual")
}

fun box(): String {
    check("kotlin.Int.() -> kotlin.Unit",
          fun Int.() {})
    return "OK"
}
