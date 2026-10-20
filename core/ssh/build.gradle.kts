import com.toxicbakery.app.bison.composite.util.configureOptIns

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    configureOptIns()
    android {
        namespace = "com.toxicbakery.app.bison.ssh"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    jvm()
    sourceSets {
        commonMain {
            dependencies {
                api(projects.core.model)
                api(projects.core.protocol)
                implementation(projects.core.util)

                implementation(libs.arbor)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.serialization.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
