/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package kotlin.coroutines.experimental.intrinsics

import kotlin.coroutines.experimental.Continuation
import kotlin.coroutines.experimental.CoroutineImpl

@SinceKotlin("1.1")
@kotlin.internal.InlineOnly
@Suppress("UNUSED_PARAMETER")
public suspend inline fun <T> suspendCoroutineOrReturn(crossinline block: (Continuation<T>) -> Any?): T =
    suspendCoroutineUninterceptedOrReturn { cont -> block(cont.intercepted()) }

/**
 * Obtains the current continuation instance inside suspend functions and either suspends
 * currently running coroutine or returns result immediately without suspension.
 *
 * Unlike [suspendCoroutineOrReturn] it does not intercept continuation.
 */
@SinceKotlin("1.2")
public suspend fun <T> suspendCoroutineUninterceptedOrReturn(block: (Continuation<T>) -> Any?): T =
    js("block(arguments[arguments.length - 1])").unsafeCast<T>()

/**
 * Intercept continuation with [ContinuationInterceptor].
 */
@SinceKotlin("1.2")
@kotlin.internal.InlineOnly
public inline fun <T> Continuation<T>.intercepted(): Continuation<T> {
    val self = this
    return js("self_0.get_facade()").unsafeCast<Continuation<T>>()
}

/**
 * This value is used as a return value of [suspendCoroutineOrReturn] `block` argument to state that
 * the execution was suspended and will not return any result immediately.
 */
@SinceKotlin("1.1")
public val COROUTINE_SUSPENDED: Any = Any()


@SinceKotlin("1.1")
@Suppress("UNCHECKED_CAST")
@kotlin.internal.InlineOnly
public inline fun <T> (suspend () -> T).startCoroutineUninterceptedOrReturn(
    completion: Continuation<T>
): Any? {
    val self_0 = this
    val cmpt_0 = completion
    return js("self_0.invoke(cmpt_0)").unsafeCast<Any?>()
//    return (this as Function1<Continuation<T>, Any?>).invoke(completion)
}

@SinceKotlin("1.1")
@Suppress("UNCHECKED_CAST")
@kotlin.internal.InlineOnly
public inline fun <R, T> (suspend R.() -> T).startCoroutineUninterceptedOrReturn(
    receiver: R,
    completion: Continuation<T>
): Any? {
    return (this as Function2<R, Continuation<T>, Any?>).invoke(receiver, completion)
}

@SinceKotlin("1.1")
public fun <R, T> (suspend R.() -> T).createCoroutineUnchecked(
    receiver: R,
    completion: Continuation<T>
): Continuation<Unit> {
    return ((this as CoroutineImpl).create(receiver, completion) as CoroutineImpl).facade
}

@SinceKotlin("1.1")
public fun <T> (suspend () -> T).createCoroutineUnchecked(
    completion: Continuation<T>
): Continuation<Unit> {
    return ((this as CoroutineImpl).create(completion) as CoroutineImpl).facade
}