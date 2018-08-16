/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.jps.incremental

import org.jetbrains.kotlin.incremental.CacheStatus
import org.jetbrains.kotlin.incremental.CacheVersion
import org.jetbrains.kotlin.incremental.dataContainerCacheVersion
import java.io.File
import java.io.IOException

class GlobalCacheVersion(rootPath: File, val expectedComponents: Set<String>) {
    private val version: CacheVersion = dataContainerCacheVersion(
        rootPath,
        expectedComponents.isNotEmpty()
    )

    val actualVersion get() = version.actualVersion
    val expectedVersion get() = version.expectedVersion

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

    val status: CacheStatus =
        if (expectedComponents.isNotEmpty()) {
            if (expectedComponents == actualComponents && expectedVersion == actualVersion) CacheStatus.VALID
            else CacheStatus.INVALID
        } else {
            // expectedComponents is empty => IC fully disabled
            if (actualComponents.isNotEmpty()) CacheStatus.SHOULD_BE_CLEARED
            else CacheStatus.CLEARED
        }

    fun saveIfNeeded() {
        version.saveIfNeeded()

        if (actualComponents.isEmpty()) actualComponentsFile.delete()
        else if (actualComponents != expectedComponents) {
            if (!actualComponentsFile.exists()) actualComponentsFile.mkdirs()
            actualComponentsFile.writeText(actualComponents.joinToString("\n"))
        }
    }

    fun clean() {
        version.clean()
        actualComponentsFile.delete()
    }
}