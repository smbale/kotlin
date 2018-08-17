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
import org.jetbrains.kotlin.incremental.CacheStatus
import org.jetbrains.kotlin.jps.incremental.CompositeLookupsCacheAttributesDiff
import org.jetbrains.kotlin.jps.incremental.KotlinDataContainerTarget
import org.jetbrains.kotlin.jps.incremental.cleanLookupStorage
import org.jetbrains.kotlin.jps.incremental.getKotlinCache

internal val kotlinCompileContextKey = Key<KotlinCompilation>("kotlin")

val CompileContext.kotlinCompilation: KotlinCompilation
    get() = getUserData(kotlinCompileContextKey)
        ?: error("KotlinCompilation available only at build phase (between KotlinBuilder.buildStarted and KotlinBuilder.buildFinished)")

class KotlinCompilation(val context: CompileContext) {
    // TODO(1.2.80): As all targets now loaded at build start, no ConcurrentHasMap in KotlinBuildTargets needed anymore
    val targetsBinding = KotlinBuildTargets(context)

    val dataManager = context.projectDescriptor.dataManager
    val dataPaths = dataManager.dataPaths
    val testingLogger: TestingBuildLogger?
        get() = context.testingContext?.buildLogger

    lateinit var chunks: List<KotlinChunk>
    private lateinit var chunksBindingByRepresentativeTarget: Map<ModuleBuildTarget, KotlinChunk>
    lateinit var initialLookupsCacheStateDiff: CompositeLookupsCacheAttributesDiff

    val shouldCheckCacheVersions = System.getProperty(KotlinBuilder.SKIP_CACHE_VERSION_CHECK_PROPERTY) == null

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
        this.initialLookupsCacheStateDiff = CompositeLookupsCacheAttributesDiff(globalCacheRootPath, expectedLookupsCacheComponents)
    }

    fun checkCacheVersions() {
        when (initialLookupsCacheStateDiff.status) {
            CacheStatus.INVALID -> {
                // global cache needs to be rebuilt
                testingLogger?.invalidOrUnusedCache(initialLookupsCacheStateDiff)
                KotlinBuilder.LOG.debug("Global lookup map invalidated, reason: ", initialLookupsCacheStateDiff)

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
                KotlinBuilder.LOG.debug(
                    "Removing global cache as it is not required anymore, reason: ",
                    initialLookupsCacheStateDiff
                )

                clearAllCaches()
            }
            CacheStatus.CLEARED -> Unit
        }
    }

    fun markAllKotlinForRebuild(reason: String) {
        KotlinBuilder.LOG.info("Rebuilding all Kotlin: $reason")

        val dataManager = context.projectDescriptor.dataManager
        val rebuildAfterCacheVersionChanged = RebuildAfterCacheVersionChangeMarker(dataManager)
        val hasKotlinMarker = HasKotlinMarker(dataManager)

        context.projectDescriptor.buildTargetIndex.allTargets.forEach { target ->
            if (target is ModuleBuildTarget) {
                val kotlinTarget = context.kotlinBuildTargets[target]!!

                FSOperations.markDirty(context, CompilationRound.NEXT, target) { file ->
                    file.isKotlinSourceFile
                }

                hasKotlinMarker.clean(target)
                dataManager.getKotlinCache(kotlinTarget)?.clean()
                rebuildAfterCacheVersionChanged[target] = true
            }
        }

        dataManager.cleanLookupStorage(KotlinBuilder.LOG)
    }

    private fun markChunkForRebuildBeforeBuild(chunk: KotlinChunk) {
        val dataManager = context.projectDescriptor.dataManager
        val hasKotlinMarker = HasKotlinMarker(dataManager)
        val rebuildAfterCacheVersionChanged = RebuildAfterCacheVersionChangeMarker(dataManager)

        chunk.targets.forEach {
            FSOperations.markDirty(context, CompilationRound.NEXT, it.jpsModuleBuildTarget) { file ->
                file.isKotlinSourceFile
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
        initialLookupsCacheStateDiff.clean()
    }

    fun cleanupCaches() {
        chunks.forEach { chunk ->
            chunk.targets.forEach { target ->
                if (target.initialLocalCacheAttributesDiff.status == CacheStatus.SHOULD_BE_CLEARED) {
                    KotlinBuilder.LOG.debug(
                        "$target caches is cleared as not required anymore: ",
                        target.initialLocalCacheAttributesDiff
                    )
                    dataManager.getKotlinCache(target)?.clean()
                }
            }
        }
    }

    fun dispose() {
        initialLookupsCacheStateDiff.saveExpectedAttributesIfNeeded()
    }

    fun getChunk(rawChunk: ModuleChunk): KotlinChunk? {
        val rawRepresentativeTarget = rawChunk.representativeTarget()
        if (targetsBinding[rawRepresentativeTarget] == null) return null

        return chunksBindingByRepresentativeTarget[rawRepresentativeTarget]
            ?: error("Kotlin binding for chunk $this is not loaded at build start")
    }
}

