import com.toxicbakery.app.bison.composite.util.configureOptIns

plugins {
    alias(libs.plugins.androidBaseline)
    alias(libs.plugins.androidTest)
}

android {
    namespace = "com.toxicbakery.app.bison.baseline"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        create("benchmark") {
            matchingFallbacks += listOf("release")
        }
    }

    targetProjectPath = ":app:android"
}

kotlin {
    configureOptIns(serialization = false)
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.testExt.junit)
    implementation(libs.androidx.espresso.core)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark)
}
