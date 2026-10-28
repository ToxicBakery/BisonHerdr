import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.HostTestBuilder
import com.android.build.api.variant.ScopedArtifacts
import com.toxicbakery.app.bison.composite.task.CheckReleaseClassesTask
import com.toxicbakery.app.bison.composite.task.ListClassesTask
import com.toxicbakery.app.bison.composite.util.configureOptIns
import com.toxicbakery.app.bison.composite.util.projectVersionCode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.androidBaseline)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.licensee)
}

// The feature flag of FIDO2 hardware security keys. See FeatureFlags.hardwareSecurityKeys.
val hardwareSecurityKeys: Boolean = providers.gradleProperty("bison.hardwareSecurityKeys")
    .map(String::toBoolean)
    .getOrElse(false)

// Stop a release build with FIDO2 security keys on, because nobody did the manual test yet (issue #23). The debug and
// benchmark build types are test builds, so they can have the flag. The pull request that turns on the flag removes
// this check.
val checkReleaseFeatureFlags = tasks.register("checkReleaseFeatureFlags") {
    val enabled = hardwareSecurityKeys
    doLast {
        if (enabled) {
            throw GradleException(
                "bison.hardwareSecurityKeys=true is only for a debug or benchmark test build (issue #23). " +
                    "Remove the property to build a release.",
            )
        }
    }
}
tasks.named { it == "preReleaseBuild" }.configureEach { dependsOn(checkReleaseFeatureFlags) }

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

        // FIDO2 hardware security keys (issue #23). Off in each build type until the manual test in
        // docs/testing/fido2-security-keys.md passes. Add -Pbison.hardwareSecurityKeys=true to a debug or benchmark
        // build to test it. A release build with the property stops (checkReleaseFeatureFlags).
        buildConfigField("boolean", "HARDWARE_SECURITY_KEYS", hardwareSecurityKeys.toString())
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
            // The resource shrink removes each resource that no kept code and no manifest refers
            // to. Give a resource that the app loads by name (getIdentifier) a tools:keep rule.
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }

        create("benchmark") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
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

    packaging {
        jniLibs {
            // androidx.graphics:graphics-path (from Compose UI) ships this library without a
            // symbol table, so `llvm-strip --strip-unneeded` gives the same bytes. Without this
            // line, AGP strips it with its default NDK 28.2.13676358. On 2026-10-28, the CI runner
            // image has NDK 27.3, 29.0 and 30.0, but not 28.2. Thus AGP writes "Unable to strip"
            // for each APK. Do not add a library with symbols here.
            keepDebugSymbols += "**/libandroidx.graphics.path.so"
        }
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

// The license check. The licensee tasks fail when a dependency in the runtime classpath of an app
// variant has no license in this allow list. The library modules get into the APK only through this
// classpath, so the check also covers their dependencies. Test and androidTest dependencies do not
// get into an APK, so the check does not read them. Do not allow a GPL, AGPL, or LGPL-only license.
// Licensee writes a warning for an entry that no dependency uses, so add only the entries in use.
licensee {
    // AndroidX, Compose, Kotlin, kotlinx, Material, Coil, Moshi, Okio, Guava, JSpecify, YubiKit, Arbor.
    allow("Apache-2.0")
    // Kodein DI and Kaverit.
    allow("MIT")
    allowUrl("https://opensource.org/license/mit") {
        because("it is the MIT license. The POM of org.slf4j:slf4j-api gives only this URL.")
    }
    allowUrl("https://www.bouncycastle.org/licence.html") {
        because("the Bouncy Castle Licence is the MIT license (META-INF/LICENSE.md of bcprov)")
    }
    // The POM of JSch lists three licenses: JSch and the JZlib copy in JSch are BSD-3-Clause, and
    // the jBCrypt copy is ISC. Licensee accepts an artifact at its first allowed license, so an
    // entry for the two other URLs is not used. A review found that the two other licenses are
    // also permissive.
    allowUrl("https://github.com/mwiede/jsch/blob/master/LICENSE.txt") {
        because("it is the BSD-3-Clause license (Revised BSD) of JSch")
    }
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
    implementation(libs.yubikit.android)
    implementation(libs.yubikit.core)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.testExt.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)

    baselineProfile(projects.app.android.baseline)
}

// The release class check. CheckReleaseClassesTask and ReleaseClasses in the composite build give the rules. The
// android CI target runs checkReleaseClasses after assembleRelease.
val listDebugClasses = tasks.register<ListClassesTask>("listDebugClasses") {
    classList = layout.buildDirectory.file("intermediates/release_class_check/debug-classes.txt")
}
val listReleaseClasses = tasks.register<ListClassesTask>("listReleaseClasses") {
    classList = layout.buildDirectory.file("intermediates/release_class_check/release-classes.txt")
}
val checkReleaseClasses = tasks.register<CheckReleaseClassesTask>("checkReleaseClasses") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Checks that the release build has no debug-only class and has RealSshBackend and NoDebugServer."
    debugClassList = listDebugClasses.flatMap { task -> task.classList }
    releaseClassList = listReleaseClasses.flatMap { task -> task.classList }
    // Each class that only the debug build has must match one of these names. Move a class to a main source set,
    // and the check fails when R8 keeps the class.
    debugOnlyClasses = setOf(
        "com.toxicbakery.app.bison.activity.DemoActivity",
        "com.toxicbakery.app.bison.debug.FakeServerModuleAliasKt",
        "com.toxicbakery.app.bison.fakeserver",
        "com.toxicbakery.app.bison.ssh.SelectingSshBackend",
        "com.toxicbakery.app.bison.ssh.SimulatedSshBackend",
    )
    checkedPackage = "com.toxicbakery.app.bison"
    // ReleaseDebugModuleTest checks the release bindings. This check only finds the classes in the release build.
    requiredClasses = setOf(
        "com.toxicbakery.app.bison.debug.NoDebugServer",
        "com.toxicbakery.app.bison.ssh.RealSshBackend",
    )
    reportFile = layout.buildDirectory.file("reports/checkReleaseClasses/result.txt")
}

androidComponents {
    // AGP makes unit tests only for the debug build type. ReleaseDebugModuleTest in src/testRelease checks the
    // bindings of the release DebugModule.
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
    // The class lists include the classes of all modules and libraries of the variant, before R8.
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.artifacts.forScope(ScopedArtifacts.Scope.ALL)
            .use(listDebugClasses)
            .toGet(ScopedArtifact.CLASSES, ListClassesTask::jars, ListClassesTask::directories)
    }
    onVariants(selector().withBuildType("release")) { variant ->
        variant.artifacts.forScope(ScopedArtifacts.Scope.ALL)
            .use(listReleaseClasses)
            .toGet(ScopedArtifact.CLASSES, ListClassesTask::jars, ListClassesTask::directories)
        val mapping = variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE)
        checkReleaseClasses.configure { mappingFile = mapping }
    }
}
