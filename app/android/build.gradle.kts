import com.android.build.api.artifact.SingleArtifact
import com.toxicbakery.app.bison.composite.util.configureOptIns
import com.toxicbakery.app.bison.composite.util.projectVersionCode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.androidBaseline)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.toxicbakery.app.bison"
    compileSdk {
        version = release(libs.versions.android.compileSdk.get().toInt()) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.toxicbakery.app.bison"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = projectVersionCode(project.version.toString())
        versionName = project.version.toString()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    val releaseKeystore: String? = System.getenv("ANDROID_KEYSTORE_FILE")
    signingConfigs {
        if (!releaseKeystore.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }

        create("benchmark") {
            isDebuggable = false
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        checkReleaseBuilds = false
    }

    sourceSets {
        getByName("benchmark").kotlin.directories.add("src/release/kotlin")
    }
}

kotlin {
    configureOptIns()
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

baselineProfile {
    mergeIntoMain = true
    saveInSrc = true
    automaticGenerationDuringBuild = false
    dexLayoutOptimization = true
}

dependencies {
    implementation(projects.core.appVersion)
    implementation(projects.core.common)
    implementation(projects.core.debugServer)
    implementation(projects.core.di)
    implementation(projects.core.model)
    implementation(projects.core.protocol)
    implementation(projects.core.resources)
    implementation(projects.core.ssh)
    implementation(projects.core.store)
    implementation(projects.core.util)
    implementation(projects.platform.android.di)
    implementation(projects.platform.android.ssh)
    implementation(projects.platform.android.store)

    // The simulated Herdr server reaches the debug APK only; release builds have no compile-time
    // access to FakeHerdrServer and bind NoDebugServer instead.
    debugImplementation(projects.core.fakeServer)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splash)
    implementation(libs.androidx.lifecycle.runtimeCompose)
    implementation(libs.androidx.lifecycle.viewmodelCompose)
    implementation(libs.androidx.material)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.arbor)
    implementation(libs.arbor.android)
    implementation(libs.compose.components.resources)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.kodein)
    implementation(libs.kodein.compose)
    implementation(libs.kodein.compose.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.io)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.testExt.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)

    baselineProfile(projects.app.android.baseline)
}

/**
 * Checks the classes of a release build in its R8 mapping file. The mapping names each class of the dex files, and
 * each class that R8 merged into a different class or inlined.
 *
 * The task fails when the mapping names a class of [debugOnlyClasses] or [debugOnlySources], or when it does not name
 * each class of [requiredClasses]. AGP runs only the debug unit tests, so a unit test cannot do this check.
 */
abstract class CheckReleaseClassesTask : DefaultTask() {

    /** The R8 mapping file of the release variant. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mappingFile: RegularFileProperty

    /**
     * The classes and packages that only debug builds can have. This list also stops a class that moves from a
     * debug source set to a main source set, because [debugOnlySources] then does not have the class.
     */
    @get:Input
    abstract val debugOnlyClasses: SetProperty<String>

    /**
     * The directories of the Kotlin sources that only debug builds compile. Each file gives the names
     * `<package>.<file name>`, `<package>.<file name>Kt`, and `<package>.<class>` for each top-level class.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val debugOnlySources: ConfigurableFileCollection

    /** The classes that the release build must have. */
    @get:Input
    abstract val requiredClasses: SetProperty<String>

    /** The result of the check. Gradle skips the task when its inputs did not change. */
    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun checkClasses() {
        val sourceNames = debugOnlySources.asFileTree.matching { include("**/*.kt") }.files.flatMap(::classNamesOf)
        check(sourceNames.isNotEmpty()) { "The debug-only source directories have no Kotlin file" }
        val debugOnlyNames = (debugOnlyClasses.get() + sourceNames).toSortedSet()
        // A name matches only when no letter, digit, or underscore follows it. A `$` or a `.` can follow it.
        val debugOnlyPattern = Regex(debugOnlyNames.joinToString("|", "(?:", ")(?!\\w)") { Regex.escape(it) })

        val found = sortedSetOf<String>()
        val missing = requiredClasses.get().toSortedSet()
        mappingFile.get().asFile.useLines { lines ->
            for (line in lines) {
                debugOnlyPattern.findAll(line).mapTo(found) { it.value }
                // A line with no indent and no `#` starts a class: `<original name> -> <new name>:`.
                if (!line.startsWith(" ") && !line.startsWith("#")) missing.remove(line.substringBefore(" -> "))
            }
        }

        val errors = buildList {
            if (found.isNotEmpty()) {
                add("The release build has classes that only a debug build can have: ${found.joinToString()}")
            }
            if (missing.isNotEmpty()) add("The release build does not have the classes ${missing.joinToString()}")
        }
        val report = reportFile.get().asFile
        report.writeText(
            if (errors.isEmpty()) {
                "No debug-only class (${debugOnlyNames.size} names). Found: ${requiredClasses.get().sorted()}\n"
            } else {
                errors.joinToString("\n", postfix = "\n")
            }
        )
        if (errors.isNotEmpty()) throw GradleException(errors.joinToString("\n"))
    }

    /** Returns the names of [file]: the file name, the file class with the `Kt` suffix, and the top-level classes. */
    private fun classNamesOf(file: File): List<String> {
        val lines = file.readLines()
        val packageName = lines.firstOrNull { it.startsWith("package ") }?.removePrefix("package ")?.trim()
            ?: throw GradleException("$file has no package line")
        val classes = lines.mapNotNull { TOP_LEVEL_CLASS.find(it)?.groupValues?.get(1) }
        return (listOf(file.nameWithoutExtension, "${file.nameWithoutExtension}Kt") + classes)
            .map { "$packageName.$it" }
    }

    private companion object {
        /** A class, object, or interface with no indent, after its modifiers. */
        val TOP_LEVEL_CLASS = Regex("""^(?:[a-z]+\s+)*(?:class|object|interface)\s+(\w+)""")
    }
}

// Release builds must bind RealSshBackend and NoDebugServer, and must not have the simulated SSH backend, the fake
// server, or the demo activity. The android CI target runs this task after assembleRelease.
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        tasks.register<CheckReleaseClassesTask>("check${variantName}Classes") {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Checks that the $variantName build has RealSshBackend and no simulated server code."
            mappingFile = variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE)
            debugOnlyClasses = setOf(
                "com.toxicbakery.app.bison.activity.DemoActivity",
                "com.toxicbakery.app.bison.fakeserver",
                "com.toxicbakery.app.bison.ssh.SelectingSshBackend",
                "com.toxicbakery.app.bison.ssh.SimulatedSshBackend",
            )
            debugOnlySources.from(
                rootDir.resolve("platform/android/ssh/src/debug/kotlin"),
                rootDir.resolve("core/fake-server/src/commonMain/kotlin"),
                // The debug DebugModule.kt has the same class name as the release DebugModule.kt.
                fileTree("src/debug/kotlin") { exclude("**/DebugModule.kt") },
            )
            requiredClasses = setOf(
                "com.toxicbakery.app.bison.debug.NoDebugServer",
                "com.toxicbakery.app.bison.ssh.RealSshBackend",
            )
            reportFile = layout.buildDirectory.file("reports/check${variantName}Classes/result.txt")
        }
    }
}
