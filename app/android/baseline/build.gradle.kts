import com.toxicbakery.app.bison.composite.util.configureOptIns
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidBaseline)
    alias(libs.plugins.androidTest)
}

android {
    namespace = "com.toxicbakery.app.bison.baseline"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        jniLibs {
            // androidx.benchmark:benchmark-common and androidx.tracing:tracing-perfetto-binary ship
            // these libraries without a symbol table, so `llvm-strip --strip-unneeded` gives the
            // same bytes. The same block in app/android/build.gradle.kts tells why AGP cannot strip
            // them on CI. Do not add a library with symbols here.
            keepDebugSymbols += "**/libbenchmarkNative.so"
            keepDebugSymbols += "**/libtracing_perfetto.so"
        }
    }

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR,LOW-BATTERY"
    }

    testOptions.managedDevices.localDevices {
        create("pixelTabletApi35") {
            device = "Pixel Tablet"
            sdkVersion = 35
            systemImageSource = "aosp-atd"
        }
    }

    targetProjectPath = ":app:android"
}

kotlin {
    configureOptIns(serialization = false)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

baselineProfile {
    useConnectedDevices = true
    enableEmulatorDisplay = false
}

dependencies {
    implementation(libs.androidx.testExt.junit)
    implementation(libs.androidx.espresso.core)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark)
}
