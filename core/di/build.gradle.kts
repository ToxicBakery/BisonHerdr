import com.toxicbakery.app.bison.composite.util.configureOptIns

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    configureOptIns()
    android {
        namespace = "com.toxicbakery.app.bison.di"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    jvm()
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(projects.core.common)
                implementation(projects.core.debugServer)
                implementation(projects.core.model)
                implementation(projects.core.protocol)
                implementation(projects.core.ssh)
                implementation(projects.core.store)
                implementation(projects.core.util)

                implementation(libs.androidx.lifecycle.viewmodelCompose)
                implementation(libs.kodein)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(projects.core.fakeServer)
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
