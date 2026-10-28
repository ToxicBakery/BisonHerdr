import com.toxicbakery.app.bison.composite.util.configureOptIns
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    configureOptIns(serialization = false)
    android {
        namespace = "com.toxicbakery.app.bison.mosh"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    jvm()

    // The jvmShared group holds the actual factories that use JVM APIs (BouncyCastle and java.util.zip).
    // The jvm target and the Android target both compile it. The Android KMP library target is not a
    // KotlinAndroidTarget, so withAndroidTarget() does not find it. The platform type finds it.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withJvm()
                withCompilations { compilation -> compilation.platformType == KotlinPlatformType.androidJvm }
            }
        }
    }

    sourceSets {
        getByName("jvmSharedMain") {
            dependencies {
                implementation(libs.bouncycastle.bcprov)
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
        jvmTest {
            dependencies {
                implementation(libs.kotlin.testJunit)
            }
        }
    }
}

// The real mosh-server tests skip when mosh-server is absent, and fail when BISON_REQUIRE_MOSH is 1. The variable
// is an input, so that a run with the value 1 never uses a cached result of a run that skipped.
tasks.named<Test>("jvmTest") {
    inputs.property("bisonRequireMosh", providers.environmentVariable("BISON_REQUIRE_MOSH").orElse(""))
}
