package com.toxicbakery.app.bison.composite.util

import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Opts in to the experimental APIs that this project uses.
 *
 * @param serialization Set to false when kotlinx-serialization is not on the compile classpath
 *   of the module. The compiler then does not warn that the marker is unresolved.
 */
fun KotlinMultiplatformExtension.configureOptIns(serialization: Boolean = true) {
    compilerOptions { optIn.set(optInValues(serialization)) }
}

/** The Android form of [KotlinMultiplatformExtension.configureOptIns]. */
fun KotlinAndroidProjectExtension.configureOptIns(serialization: Boolean = true) {
    compilerOptions { optIn.set(optInValues(serialization)) }
}

internal fun optInValues(serialization: Boolean): List<String> =
    if (serialization) stdlibOptIns + SERIALIZATION_OPT_IN else stdlibOptIns

private val stdlibOptIns = listOf(
    "kotlin.ExperimentalUnsignedTypes",
    "kotlin.uuid.ExperimentalUuidApi",
)

private const val SERIALIZATION_OPT_IN = "kotlinx.serialization.ExperimentalSerializationApi"
