import com.toxicbakery.app.bison.composite.util.configureOptIns

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
}

android {
    namespace = "com.toxicbakery.app.bison.platform.ssh"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}

kotlin {
    configureOptIns()
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.protocol)
    implementation(projects.core.ssh)
    implementation(projects.core.store)

    implementation(libs.arbor)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.kotlinx.coroutines.test)
}
