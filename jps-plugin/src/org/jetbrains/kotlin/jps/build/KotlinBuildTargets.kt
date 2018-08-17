/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.jps.build

import org.jetbrains.jps.ModuleChunk
import org.jetbrains.jps.builders.ModuleBasedBuildTargetType
import org.jetbrains.jps.builders.java.JavaModuleBuildTargetType
import org.jetbrains.jps.incremental.CompileContext
import org.jetbrains.jps.incremental.ModuleBuildTarget
import org.jetbrains.jps.model.library.JpsOrderRootType
import org.jetbrains.jps.model.module.JpsModule
import org.jetbrains.jps.util.JpsPathUtil
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.TargetPlatformKind
import org.jetbrains.kotlin.incremental.CacheStatus
import org.jetbrains.kotlin.jps.model.kotlinCompilerArguments
import org.jetbrains.kotlin.jps.model.targetPlatform
import org.jetbrains.kotlin.jps.platforms.KotlinCommonModuleBuildTarget
import org.jetbrains.kotlin.jps.platforms.KotlinJsModuleBuildTarget
import org.jetbrains.kotlin.jps.platforms.KotlinJvmModuleBuildTarget
import org.jetbrains.kotlin.jps.platforms.KotlinModuleBuildTarget
import org.jetbrains.kotlin.utils.LibraryUtils
import java.io.File
import java.util.concurrent.ConcurrentHashMap

val CompileContext.kotlinBuildTargets
    get() = kotlinCompilation.targetsBinding

class KotlinBuildTargets internal constructor(val compileContext: CompileContext) {
    private val byJpsModuleBuildTarget = ConcurrentHashMap<ModuleBuildTarget, KotlinModuleBuildTarget<*>>()
    private val isKotlinJsStdlibJar = ConcurrentHashMap<String, Boolean>()

    @JvmName("getNullable")
    operator fun get(target: ModuleBuildTarget?): KotlinModuleBuildTarget<*>? {
        if (target == null) return null
        return get(target)
    }

    operator fun get(target: ModuleBuildTarget): KotlinModuleBuildTarget<*>? {
        if (target.targetType !is ModuleBasedBuildTargetType) return null

        return byJpsModuleBuildTarget.computeIfAbsent(target) {
            when (target.module.targetPlatform ?: detectTargetPlatform(target)) {
                is TargetPlatformKind.Common -> KotlinCommonModuleBuildTarget(compileContext, target)
                is TargetPlatformKind.JavaScript -> KotlinJsModuleBuildTarget(compileContext, target)
                is TargetPlatformKind.Jvm -> KotlinJvmModuleBuildTarget(compileContext, target)
            }
        }
    }

    /**
     * Compatibility for KT-14082
     * todo: remove when all projects migrated to facets
     */
    private fun detectTargetPlatform(target: ModuleBuildTarget): TargetPlatformKind<*> {
        if (hasJsStdLib(target)) return TargetPlatformKind.JavaScript

        return TargetPlatformKind.DEFAULT_PLATFORM
    }

    private fun hasJsStdLib(target: ModuleBuildTarget): Boolean {
        KotlinJvmModuleBuildTarget(compileContext, target).allDependencies.libraries.forEach { library ->
            for (root in library.getRoots(JpsOrderRootType.COMPILED)) {
                val url = root.url

                val isKotlinJsLib = isKotlinJsStdlibJar.computeIfAbsent(url) {
                    LibraryUtils.isKotlinJavascriptStdLibrary(JpsPathUtil.urlToFile(url))
                }

                if (isKotlinJsLib) return true
            }
        }

        return false
    }
}

fun ModuleChunk.toKotlinChunk(context: CompileContext): KotlinChunk? =
    context.kotlinCompilation.getChunk(this)

class KotlinChunk(val compilation: KotlinCompilation, val targets: List<KotlinModuleBuildTarget<*>>) {
    val containsTests = targets.any { it.isTests }

    val representativeTarget
        get() = targets.first()

    private val presentableModulesToCompilersList: String
        get() = targets.joinToString { "${it.module.name} (${it.globalLookupCacheId})" }

    init {
        check(targets.all { it.javaClass == representativeTarget.javaClass }) {
            "Cyclically dependent modules $presentableModulesToCompilersList should have same compiler."
        }
    }

    val compilerArguments = representativeTarget.jpsModuleBuildTarget.module.kotlinCompilerArguments

    val langVersion =
        compilerArguments.languageVersion?.let { LanguageVersion.fromVersionString(it) } ?: LanguageVersion.LATEST_STABLE

    val apiVersion =
        compilerArguments.apiVersion?.let { ApiVersion.parse(it) } ?: ApiVersion.createByLanguageVersion(langVersion)

    fun shouldRebuild(): Boolean {
        if (isVersionChanged()) return true

        targets.forEach {
            if (it.initialLocalCacheAttributesDiff.status == CacheStatus.INVALID) {
                KotlinBuilder.LOG.debug("$it cache is invalid ${it.initialLocalCacheAttributesDiff}, rebuilding $this")
                return true
            }
        }

        return false
    }

    private fun isVersionChanged(): Boolean {
        val buildMetaInfo = representativeTarget.buildMetaInfoFactory.create(compilerArguments)

        for (target in targets) {
            if (target.isVersionChanged(this, buildMetaInfo)) return true
        }

        return false
    }

    fun buildMetaInfoFile(target: ModuleBuildTarget): File =
        File(
            compilation.dataPaths.getTargetDataRoot(target),
            representativeTarget.buildMetaInfoFileName
        )

    fun saveVersions() {
        targets.forEach {
            it.initialLocalCacheAttributesDiff.saveExpectedAttributesIfNeeded()
        }

        val serializedMetaInfo = representativeTarget.buildMetaInfoFactory.serializeToString(compilerArguments)

        targets.forEach {
            buildMetaInfoFile(it.jpsModuleBuildTarget).writeText(serializedMetaInfo)
        }
    }

    /**
     * The same as [org.jetbrains.jps.ModuleChunk.getPresentableShortName]
     */
    val presentableShortName: String
        get() = buildString {
            if (containsTests) append("tests of ")
            append(targets.first().module.name)
            if (targets.size > 1) {
                val andXMore = "and ${targets.size - 1} more"
                val other = ", " + targets.asSequence().drop(1).joinToString()
                append(if (other.length < andXMore.length) other else andXMore)
            }
        }

    override fun toString(): String {
        return "KotlinChunk<${representativeTarget.javaClass.simpleName}>" +
                "(${targets.joinToString { it.jpsModuleBuildTarget.presentableName }})"
    }
}

fun ModuleBuildTarget(module: JpsModule, isTests: Boolean) =
    ModuleBuildTarget(module, if (isTests) JavaModuleBuildTargetType.TEST else JavaModuleBuildTargetType.PRODUCTION)

val JpsModule.productionBuildTarget
    get() = ModuleBuildTarget(this, false)

val JpsModule.testBuildTarget
    get() = ModuleBuildTarget(this, true)