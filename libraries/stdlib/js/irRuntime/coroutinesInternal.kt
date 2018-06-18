/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package kotlin.js

import kotlin.coroutines.experimental.*

internal external fun <T> getContinuation(): Continuation<T>
internal external suspend fun <T> returnIfSuspended(@Suppress("UNUSED_PARAMETER") argument: Any?): T

//fun <T> normalizeContinuation(continuation: Continuation<T>): Continuation<T> =
//    (continuation as? CoroutineImpl)?.facade ?: continuation
//
//internal fun <T> interceptContinuationIfNeeded(
//    context: CoroutineContext,
//    continuation: Continuation<T>
//) = context[ContinuationInterceptor]?.interceptContinuation(continuation) ?: continuation
