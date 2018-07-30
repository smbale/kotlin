/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package kotlin.script.experimental.util

import kotlin.reflect.KProperty

data class PropertyKey<T>(val name: String, val defaultValue: T? = null)

//open class PropertyBag internal constructor(private val parent: PropertyBag?, internal val data: Map<PropertyKey<*>, Any?>) {
//    constructor(parent: PropertyBag? = null, pairs: Iterable<Pair<PropertyKey<*>, Any?>>) :
//            this(parent, HashMap<PropertyKey<*>, Any?>().also { it.putAll(pairs) })
//
//    constructor(pairs: Iterable<Pair<PropertyKey<*>, Any?>>) : this(null, pairs)
//    constructor(parent: PropertyBag, vararg pairs: Pair<PropertyKey<*>, Any?>) : this(parent, pairs.asIterable())
//    constructor(vararg pairs: Pair<PropertyKey<*>, Any?>) : this(null, pairs.asIterable())
//
//    fun cloneWithNewParent(newParent: PropertyBag?): PropertyBag = when {
//        this == newParent -> this
//        newParent == null -> this
//        parent == null -> createOptimized(newParent, data)
//        else -> createOptimized(parent.cloneWithNewParent(newParent), data)
//    }
//
//    inline operator fun <reified T> get(key: PropertyKey<T>): T = getRaw(key) as T
//
//    fun <T> getRaw(key: PropertyKey<T>): Any? = getOrNullRaw(key) ?: throw IllegalArgumentException("Unknown key $key")
//
//    inline fun <reified T> getOrNull(key: PropertyKey<T>): T? = getOrNullRaw(key)?.let { it as T }
//
//    fun <T> getOrNullRaw(key: PropertyKey<T>): Any? = data[key] ?: parent?.getOrNullRaw(key) ?: key.defaultValue
//
//    companion object {
//        fun createOptimized(parent: PropertyBag?, data: Map<PropertyKey<*>, Any?>): PropertyBag = when {
//            parent != null && data.isEmpty() -> parent
//            parent != null && parent.data.isEmpty() -> createOptimized(parent.parent, data)
//            else -> PropertyBag(parent, data)
//        }
//    }
//}
//
//fun chainPropertyBags(propertyBags: Iterable<PropertyBag?>): PropertyBag =
//    propertyBags.fold(PropertyBag()) { res, next -> if (next == null) res else res.cloneWithNewParent(next) }
//
//fun chainPropertyBags(vararg propertyBags: PropertyBag?): PropertyBag = chainPropertyBags(propertyBags.asIterable())
