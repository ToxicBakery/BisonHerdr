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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.jsch)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.sshd.core)
    testRuntimeOnly(libs.slf4j.nop)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.testExt.junit)
    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.kotlin.testJunit)
}
