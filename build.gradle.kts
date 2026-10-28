import com.toxicbakery.app.bison.composite.util.resolveProjectVersion

plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidBaseline) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composite) apply false
}

// Single calendar-derived version for every module, resolved once from `git describe`
// (see composite util resolveProjectVersion()).
// Release builds (checked out on a tag) get a clean `26.29.1`; dev/PR/main builds
// get a `-dev` marker.
val projectVersion = resolveProjectVersion()
allprojects {
    version = projectVersion
}
