import com.toxicbakery.app.bison.composite.task.LocalizationCheckTask
import com.toxicbakery.app.bison.composite.util.configureOptIns

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    configureOptIns()
    android {
        namespace = "com.toxicbakery.app.bison.resources"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    jvm()
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.androidx.lifecycle.runtimeCompose)
                api(libs.compose.components.resources)
                implementation(libs.compose.ui)
            }
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.toxicbakery.app.bison.resources"
    generateResClass = auto
}

val composeResourcesDir = layout.projectDirectory.dir("src/commonMain/composeResources")

val localizationCheck = tasks.register<LocalizationCheckTask>("localizationCheck") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Compares each translated strings.xml with the base locale."
    baseFile = composeResourcesDir.file("values/strings.xml")
    translationFiles.from(
        fileTree(composeResourcesDir) {
            include("values-*/strings.xml")
        }
    )
    expectedLocales = emptySet()
    untranslatedAllowList = setOf(
        "app_name",
        "app_demo_name",
        "app_version_label",
        "transport_badge_mosh_udp",
        "transport_badge_mosh_roamed",
        "transport_badge_ssh_tcp",
    )
    reportFile = layout.buildDirectory.file("reports/localization/strings.txt")
}

tasks.named("check") {
    dependsOn(localizationCheck)
}
