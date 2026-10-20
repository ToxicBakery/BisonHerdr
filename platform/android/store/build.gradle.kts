import com.toxicbakery.app.bison.composite.util.configureOptIns

plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "com.toxicbakery.app.bison.platform.store"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}

kotlin {
    configureOptIns(serialization = false)
}

dependencies {
    implementation(projects.core.store)
    implementation(libs.arbor)
    implementation(libs.kotlinx.io)
}
