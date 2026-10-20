import com.toxicbakery.app.bison.composite.util.configureOptIns
import com.toxicbakery.app.bison.composite.util.projectVersionCode

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

val stampBuildMetadata = providers.gradleProperty("bison.stampBuildMetadata")
    .map(String::toBoolean)
    .getOrElse(false)

val generateAppVersion by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/appVersion")
    val fullVersion = project.version.toString()
    val versionName = if (stampBuildMetadata) fullVersion else fullVersion.substringBefore('+')
    val versionCode = projectVersionCode(fullVersion)
    outputs.dir(outputDir)
    inputs.property("versionName", versionName)
    inputs.property("versionCode", versionCode)
    doLast {
        val literal = versionName
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\\$")
        val file = outputDir.get()
            .file("com/toxicbakery/app/bison/version/AppVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package com.toxicbakery.app.bison.version

            /**
             * Build-time version stamp — GENERATED, do not edit.
             */
            const val APP_VERSION: String = "$literal"

            object AppVersion {
                val NAME: String = "$literal"
                val CODE: Int = $versionCode
            }

            """.trimIndent()
        )
    }
}

kotlin {
    configureOptIns(serialization = false)
    android {
        namespace = "com.toxicbakery.app.bison.version"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    jvm()
    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(generateAppVersion)
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}
