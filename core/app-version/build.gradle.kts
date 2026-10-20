import com.toxicbakery.app.bison.composite.util.configureOptIns
import com.toxicbakery.app.bison.composite.util.projectVersionCode

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

// The resolved version reaches shared code only through generated source: `project.version` is a
// Gradle-side value, and commonMain must stay platform-agnostic (so no BuildConfig, which is
// Android-only).
//
// WHAT IS STAMPED, AND WHY IT IS NOT THE WHOLE STRING
//
// `git describe` resolves a new value on every commit, but only the part after `+` is volatile:
// parseGitDescribe() puts commits-since / sha / dirty exclusively in SemVer build metadata. Baking
// that into compiled code made this file — and so core:common and everything downstream of it —
// change on every commit, which on CI (where every run carries a fresh sha) is a guaranteed cache
// miss on the largest module in the build.
//
// Dropping the metadata leaves a release exact (`26.30.1`, no `+` to strip) and a dev build stable
// between commits (`26.30.1-dev`). That still keeps dev and release builds unambiguously
// distinguishable because the `-dev` marker is what carries that distinction. The full string
// including the sha is unaffected everywhere it is already recorded: Android's versionName and
// every published release artifact. Pass -Pbison.stampBuildMetadata=true to force it into this
// in-app surface too.
val stampBuildMetadata = providers.gradleProperty("bison.stampBuildMetadata")
    .map(String::toBoolean)
    .getOrElse(false)

val generateAppVersion = tasks.register("generateAppVersion") {
    val outputDir = layout.buildDirectory.dir("generated/appVersion")
    val fullVersion = project.version.toString()
    val versionName = if (stampBuildMetadata) fullVersion else fullVersion.substringBefore('+')
    val versionCode = projectVersionCode(fullVersion)
    outputs.dir(outputDir)
    inputs.property("versionName", versionName)
    inputs.property("versionCode", versionCode)
    doLast {
        // `git describe` output is a tag or a hex sha in practice, but this string is pasted into
        // Kotlin source, so escape the three characters that would otherwise produce a file that
        // does not compile: a backslash, a quote, and `$` (which would start a string template).
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
             *
             * [NAME] is clean on a release (`26.30.1`) and carries the `-dev` marker otherwise;
             * [CODE] is the store integer. The `+<commits>-g<sha>` build metadata is omitted so
             * that this value — and therefore every module compiled against it — stays stable
             * between commits; build with `-Pbison.stampBuildMetadata=true` to include it.
             *
             * These are deliberately NOT `const`. A `const val` is inlined into every call site and
             * so forms part of this module's ABI, which means a dependent's compiled output is
             * invalidated whenever the value changes. A plain `val` is read through a getter and
             * leaves the ABI byte-identical, so Gradle can skip recompiling JVM/Android dependents
             * when a new tag moves this string. Do not add `const`.
             */
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
        commonMain {
            kotlin.srcDir(generateAppVersion)
        }
        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}
