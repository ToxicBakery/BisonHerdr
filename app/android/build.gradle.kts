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

    androidTestImplementation(libs.androidx.testExt.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)

    baselineProfile(projects.app.android.baseline)
}
