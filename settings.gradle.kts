rootProject.name = "BisonHerdr"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    // Keep buildLogicVersion() identical to the copy in composite/build.gradle.kts. Only the
    // indentation and the `java.security.` prefix are different. BuildLogicVersionParityTest in composite compares the copies.
    fun buildLogicVersion(root: File): String {
        val sources = listOf("build.gradle.kts", "gradle.properties", "settings.gradle.kts")
            .map { name -> root.resolve(name) } +
            root.resolve("src/main").walkTopDown().filter { file -> file.isFile }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        sources.filter { file -> file.exists() }
            .sortedBy { file -> file.toRelativeString(root).replace(File.separatorChar, '/') }
            .forEach { file ->
                digest.update(
                    file.toRelativeString(root).replace(File.separatorChar, '/').toByteArray()
                )
                digest.update(0)
                digest.update(file.readBytes())
            }
        return "1.0-" + digest.digest().joinToString("") { value: Byte -> "%02x".format(value) }.take(12)
    }

    val compositeVersion = buildLogicVersion(File(rootDir, "composite"))

    repositories {
        maven { url = uri("composite/m2") }
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }

    // The version in gradle/libs.versions.toml is a placeholder; the digest decides.
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "composite") useVersion(compositeVersion)
        }
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(
    ":app:android",
    ":app:android:baseline",
    ":core:app-version",
    ":core:common",
    ":core:debug-server",
    ":core:di",
    ":core:fake-server",
    ":core:model",
    ":core:mosh",
    ":core:protocol",
    ":core:resources",
    ":core:ssh",
    ":core:store",
    ":core:util",
    ":platform:android:di",
    ":platform:android:ssh",
    ":platform:android:store",
)
