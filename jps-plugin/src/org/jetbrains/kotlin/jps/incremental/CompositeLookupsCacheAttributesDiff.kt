/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.jps.incremental

import org.jetbrains.kotlin.incremental.CacheStatus
import org.jetbrains.kotlin.incremental.CacheAttributesDiff
import org.jetbrains.kotlin.incremental.readLookupsCacheStatus
import java.io.File
import java.io.IOException

/**
 * Global lookups cache that may contain lookups for several compilers (jvm, js).
 *
 * TODO(1.2.80): got rid of shared lookup cache, replace with individual lookup cache for each compiler
 */
class CompositeLookupsCacheAttributesDiff(
    rootPath: File,
    val expectedComponents: Set<String>
) : CacheAttributesDiff {
    private val cacheAttributesDiff: CacheAttributesDiff = readLookupsCacheStatus(
        rootPath,
        expectedComponents.isNotEmpty()
    )

    override val actualVersion get() = cacheAttributesDiff.actualVersion
    override val expectedVersion get() = cacheAttributesDiff.expectedVersion

    private val actualComponentsFile = File(rootPath, "components.txt")
    val actualComponents: Set<String>

    init {
        actualComponents = if (!actualComponentsFile.exists()) setOf()
        else try {
            actualComponentsFile.readLines().toSet()
        } catch (e: IOException) {
            setOf<String>()
        }
    }

    override val status: CacheStatus =
        if (expectedComponents.isNotEmpty()) {
            if (expectedComponents == actualComponents && expectedVersion == actualVersion) CacheStatus.VALID
            else CacheStatus.INVALID
        } else {
            // expectedComponents is empty => IC fully disabled
            if (actualComponents.isNotEmpty()) CacheStatus.SHOULD_BE_CLEARED
            else CacheStatus.CLEARED
        }

    override fun saveExpectedAttributesIfNeeded() {
        cacheAttributesDiff.saveExpectedAttributesIfNeeded()

        if (actualComponents.isEmpty()) actualComponentsFile.delete()
        else if (actualComponents != expectedComponents) {
            if (!actualComponentsFile.exists()) actualComponentsFile.mkdirs()
            actualComponentsFile.writeText(actualComponents.joinToString("\n"))
        }
    }

    override fun clean() {
        cacheAttributesDiff.clean()
        actualComponentsFile.delete()
    }

    override fun toString(): String {
        return "CompositeLookupsCacheAttributesDiff(" +
                "actual { version=$actualVersion, components=$actualComponents } -> " +
                "expected { version=$expectedVersion, components=$expectedComponents }" +
                ")"
    }
}