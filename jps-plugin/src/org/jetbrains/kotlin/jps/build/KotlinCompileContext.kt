/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.jps.build

import com.intellij.openapi.util.Key
import org.jetbrains.jps.ModuleChunk
import org.jetbrains.jps.incremental.CompileContext
import org.jetbrains.jps.incremental.FSOperations
import org.jetbrains.jps.incremental.ModuleBuildTarget
import org.jetbrains.jps.incremental.fs.CompilationRound
import org.jetbrains.kotlin.incremental.storage.version.CacheAttributesDiff
import org.jetbrains.kotlin.incremental.storage.version.CacheStatus
import org.jetbrains.kotlin.incremental.storage.version.loadDiff
import org.jetbrains.kotlin.jps.incremental.CompositeLookupsCacheAttributesManager
import org.jetbrains.kotlin.jps.incremental.KotlinDataContainerTarget
import org.jetbrains.kotlin.jps.incremental.cleanLookupStorage
import org.jetbrains.kotlin.jps.incremental.getKotlinCache
import java.io.File

internal val kotlinCompileContextKey = Key<KotlinCompileContext>("kotlin")

val CompileContext.kotlin: KotlinCompileContext
    get() = getUserData(kotlinCompileContextKey)
        ?: error("KotlinCompilation available only at build phase (between KotlinBuilder.buildStarted and KotlinBuilder.buildFinished)")

class KotlinCompileContext(val context: CompileContext) {
    init {
        context.testingContext?.kotlinCompileContext = this
    }

    // TODO(1.2.80): As all targets now loaded at build start, no ConcurrentHasMap in KotlinBuildTargets needed anymore
    val targetsBinding = KotlinBuildTargets(context)

    val dataManager = context.projectDescriptor.dataManager
    val dataPaths = dataManager.dataPaths
    val testingLogger: TestingBuildLogger?
        get() = context.testingContext?.buildLogger

    lateinit var chunks: List<KotlinChunk>
    private lateinit var chunksBindingByRepresentativeTarget: Map<ModuleBuildTarget, KotlinChunk>
    lateinit var lookupsCacheAttributesManager: CompositeLookupsCacheAttributesManager
    lateinit var initialLookupsCacheStateDiff: CacheAttributesDiff<*>

    val shouldCheckCacheVersions = System.getProperty(KotlinBuilder.SKIP_CACHE_VERSION_CHECK_PROPERTY) == null

    val rebuildAfterCacheVersionChanged = RebuildAfterCacheVersionChangeMarker(dataManager)
    val hasKotlinMarker = HasKotlinMarker(dataManager)

    fun loadTargets() {
        val globalCacheRootPath = dataPaths.getTargetDataRoot(KotlinDataContainerTarget)

        val chunks = mutableListOf<KotlinChunk>()
        val expectedLookupsCacheComponents = mutableSetOf<String>()

        // visit all kotlin build targets, and collect globalLookupCacheIds (jvm, js)
        context.projectDescriptor.buildTargetIndex.getSortedTargetChunks(context).forEach { chunk ->
            val moduleBuildTargets = chunk.targets.mapNotNull {
                if (it is ModuleBuildTarget) context.kotlinBuildTargets[it]!!
                else null
            }

            if (moduleBuildTargets.isNotEmpty()) {
                chunks.add(KotlinChunk(this, moduleBuildTargets))
                moduleBuildTargets.forEach {
                    expectedLookupsCacheComponents.add(it.globalLookupCacheId)
                }
            }
        }

        this.chunks = chunks.toList()
        this.chunksBindingByRepresentativeTarget = chunks.associateBy { it.representativeTarget.jpsModuleBuildTarget }
        this.lookupsCacheAttributesManager = CompositeLookupsCacheAttributesManager(globalCacheRootPath, expectedLookupsCacheComponents)
        this.initialLookupsCacheStateDiff = lookupsCacheAttributesManager.loadDiff()
    }

    fun checkCacheVersions() {
        when (initialLookupsCacheStateDiff.status) {
            CacheStatus.INVALID -> {
                // global cache needs to be rebuilt
                testingLogger?.invalidOrUnusedCache(initialLookupsCacheStateDiff)

                KotlinBuilder.LOG.info("Global lookup map are INVALID: $initialLookupsCacheStateDiff")

                clearLookupCache()
                markAllKotlinForRebuild("Kotlin incremental cache setting or format was changed")
            }
            CacheStatus.VALID -> {
                // global cache is enabled and valid
                // let check local module caches
                chunks.forEach { chunk ->
                    if (chunk.shouldRebuild()) markChunkForRebuildBeforeBuild(chunk)
                }
            }
            CacheStatus.SHOULD_BE_CLEARED -> {
                context.testingContext?.buildLogger?.invalidOrUnusedCache(initialLookupsCacheStateDiff)
                KotlinBuilder.LOG.info("Removing global cache as it is not required anymore: $initialLookupsCacheStateDiff")

                clearAllCaches()
            }
            CacheStatus.CLEARED -> Unit
        }
    }

    private fun logMarkDirtyForTestingBeforeRound(file: File, shouldProcess: Boolean): Boolean {
        if (shouldProcess) {
            testingLogger?.markedAsDirtyBeforeRound(listOf(file))
            testingLogger?.markedAsDirtyAfterRound(listOf(file))
        }
        return shouldProcess
    }

    fun markAllKotlinForRebuild(reason: String) {
        KotlinBuilder.LOG.info("Rebuilding all Kotlin: $reason")

        val dataManager = context.projectDescriptor.dataManager

        chunks.forEach {
            markChunkForRebuildBeforeBuild(it)
        }

        dataManager.cleanLookupStorage(KotlinBuilder.LOG)
    }

    private fun markChunkForRebuildBeforeBuild(chunk: KotlinChunk) {
        chunk.targets.forEach {
            FSOperations.markDirty(context, CompilationRound.NEXT, it.jpsModuleBuildTarget) { file ->
                logMarkDirtyForTestingBeforeRound(file, file.isKotlinSourceFile)
            }

            dataManager.getKotlinCache(it)?.clean()
            hasKotlinMarker.clean(it.jpsModuleBuildTarget)
            rebuildAfterCacheVersionChanged[it.jpsModuleBuildTarget] = true
        }
    }

    private fun clearAllCaches() {
        clearLookupCache()

        KotlinBuilder.LOG.info("Clearing caches for all targets")
        chunks.forEach { chunk ->
            chunk.targets.forEach {
                dataManager.getKotlinCache(it)?.clean()
            }
        }
    }

    private fun clearLookupCache() {
        KotlinBuilder.LOG.info("Clearing lookup cache")
        dataManager.cleanLookupStorage(KotlinBuilder.LOG)
        initialLookupsCacheStateDiff.saveExpectedIfNeeded()
    }

    fun cleanupCaches() {
        chunks.forEach { chunk ->
            chunk.targets.forEach { target ->
                if (target.initialLocalCacheAttributesDiff.status == CacheStatus.SHOULD_BE_CLEARED) {
                    KotlinBuilder.LOG.debug(
                        "$target caches is cleared as not required anymore: ",
                        target.initialLocalCacheAttributesDiff
                    )
                    testingLogger?.invalidOrUnusedCache(target.initialLocalCacheAttributesDiff)
                    dataManager.getKotlinCache(target)?.clean()
                }
            }
        }
    }

    fun dispose() {
        initialLookupsCacheStateDiff.saveExpectedIfNeeded()
    }

    fun getChunk(rawChunk: ModuleChunk): KotlinChunk? {
        val rawRepresentativeTarget = rawChunk.representativeTarget()
        if (targetsBinding[rawRepresentativeTarget] == null) return null

        return chunksBindingByRepresentativeTarget[rawRepresentativeTarget]
            ?: error("Kotlin binding for chunk $this is not loaded at build start")
    }
}

