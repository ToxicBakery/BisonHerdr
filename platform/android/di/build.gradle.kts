import com.toxicbakery.app.bison.composite.util.configureOptIns

plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "com.toxicbakery.app.bison.platform.di"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}

kotlin {
    configureOptIns(serialization = false)
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.protocol)
    implementation(projects.core.ssh)
    implementation(projects.core.store)
    implementation(projects.platform.android.ssh)
    implementation(projects.platform.android.store)

    implementation(libs.kodein)
}
