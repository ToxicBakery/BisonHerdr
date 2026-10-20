import com.toxicbakery.app.bison.composite.util.configureOptIns

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    configureOptIns()
    android {
        namespace = "com.toxicbakery.app.bison.common"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    jvm()
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(projects.core.appVersion)
                implementation(projects.core.debugServer)
                implementation(projects.core.model)
                implementation(projects.core.protocol)
                implementation(projects.core.resources)
                implementation(projects.core.ssh)
                implementation(projects.core.store)
                implementation(projects.core.util)

                implementation(libs.androidx.lifecycle.viewmodelCompose)
                implementation(libs.androidx.lifecycle.runtimeCompose)
                implementation(libs.androidx.navigation3.ui)
                implementation(libs.androidx.navigation3.viewmodel)
                implementation(libs.arbor)
                implementation(libs.coil)
                implementation(libs.compose.components.resources)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material.icons.core)
                implementation(libs.compose.material.icons.extended)
                implementation(libs.compose.material3)
                implementation(libs.compose.runtime)
                implementation(libs.compose.ui)
                implementation(libs.compose.ui.tooling.preview)
                implementation(libs.kodein)
                implementation(libs.kodein.compose)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.serialization.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.kotlinx.io)
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}
