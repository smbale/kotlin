/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.incremental

import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmBytecodeBinaryVersion
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmMetadataVersion
import java.io.File
import java.io.IOException

private val NORMAL_VERSION = 9
private val DATA_CONTAINER_VERSION = 3

private val NORMAL_VERSION_FILE_NAME = "format-version.txt"

fun localCacheVersionManager(dataRoot: File) = CacheVersionManager(File(dataRoot, NORMAL_VERSION_FILE_NAME))

fun readLocalCacheStatus(dataRoot: File, enabled: Boolean): CacheAttributesDiff =
    localCacheVersionManager(dataRoot).diff(NORMAL_VERSION, enabled)

private val DATA_CONTAINER_VERSION_FILE_NAME = "data-container-format-version.txt"

fun lookupsCacheVersionManager(dataRoot: File) = CacheVersionManager(File(dataRoot, DATA_CONTAINER_VERSION_FILE_NAME))

fun readLookupsCacheStatus(dataRoot: File, enabled: Boolean): CacheAttributesDiff =
    lookupsCacheVersionManager(dataRoot).diff(DATA_CONTAINER_VERSION, enabled)

/**
 * Manages files with cache version.
 */
class CacheVersionManager(private val versionFile: File) {
    internal fun diff(expectedOwnVersion: Int, isEnabled: Boolean) =
        CacheAttributesDiffImpl(expectedOwnVersion, versionFile, isEnabled)

    fun clean() {
        versionFile.delete()
    }
}

/**
 * Diff between actual and expected cache attributes.
 * [status] are calculated based on this diff (see [CacheStatus]).
 * Based on that [status] system may perform required actions (i.e. rebuild something, clearing caches, etc...).
 */
interface CacheAttributesDiff {
    val status: CacheStatus

    val actualVersion: Int?
    val expectedVersion: Int?

    fun saveExpectedAttributesIfNeeded()
    fun clean()
    override fun toString(): String
}

enum class CacheStatus {
    /**
     * Cache is valid and ready to use.
     */
    VALID,

    /**
     * Cache is not exists or have outdated versions and/or other attributes.
     */
    INVALID,

    /**
     * Cache is exists, but not required anymore.
     */
    SHOULD_BE_CLEARED,

    /**
     * Cache is not exists and not required.
     */
    CLEARED
}

class CacheAttributesDiffImpl(
    private val expectedOwnVersion: Int,
    private val versionFile: File,
    val isEnabled: Boolean
) : CacheAttributesDiff {
    val wasEnabled: Boolean
        get() = versionFile.exists()

    override val actualVersion: Int? = if (wasEnabled) {
        try {
            versionFile.readText().toInt()
        } catch (e: NumberFormatException) {
            null
        } catch (e: IOException) {
            null
        }
    } else null

    override val expectedVersion: Int?
        get() = if (isEnabled) {
            val metadata = JvmMetadataVersion.INSTANCE
            val bytecode = JvmBytecodeBinaryVersion.INSTANCE
            expectedOwnVersion * 1000000 +
                    bytecode.major * 10000 + bytecode.minor * 100 +
                    metadata.major * 1000 + metadata.minor
        } else null

    override val status: CacheStatus = if (isEnabled) {
        if (wasEnabled && expectedVersion == actualVersion) CacheStatus.VALID
        else CacheStatus.INVALID
    } else {
        if (wasEnabled) CacheStatus.SHOULD_BE_CLEARED
        else CacheStatus.VALID
    }

    override fun saveExpectedAttributesIfNeeded() {
        if (!isEnabled) return

        if (!versionFile.parentFile.exists()) {
            versionFile.parentFile.mkdirs()
        }

        versionFile.writeText(expectedVersion.toString())
    }

    override fun clean() {
        versionFile.delete()
    }

    override fun toString(): String {
        return "CacheState($status: actualVersion=$expectedOwnVersion -> expectedVersion=$expectedVersion)"
    }
}