import java.security.MessageDigest

plugins {
    `java-gradle-plugin`
    `kotlin-dsl`
    `kotlin-dsl-precompiled-script-plugins`
    `maven-publish`
}

group = "com.toxicbakery.app.bison.composite"

// The published version carries a digest of this build logic, so the coordinate changes
// whenever the source does. The root settings.gradle.kts computes the same digest and asks
// for exactly that version, which turns a forgotten republish into an immediate
// "plugin not found" instead of a jar that is silently out of date.
//
// Keep buildLogicVersion() identical to the copy in the root settings.gradle.kts. Only the
// indentation and the `java.security.` prefix are different: in a project script, `java`
// is the Java plugin extension, so this copy imports MessageDigest. The two cannot share a
// file, because this build produces the plugin that the root build consumes.
// BuildLogicVersionParityTest compares the two copies.
version = buildLogicVersion(layout.projectDirectory.asFile)

fun buildLogicVersion(root: File): String {
    val sources = listOf("build.gradle.kts", "gradle.properties", "settings.gradle.kts")
        .map { name -> root.resolve(name) } +
        root.resolve("src/main").walkTopDown().filter { file -> file.isFile }
    val digest = MessageDigest.getInstance("SHA-256")
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

gradlePlugin {
    website = "https://github.com/herdrdev/herdr"
    vcsUrl = "https://github.com/herdrdev/herdr.git"
    plugins {
        register("composite") {
            id = "composite"
            displayName = "Composite Plugin"
            implementationClass = "com.toxicbakery.app.bison.composite.CompositePlugin"
        }
    }
}

dependencies {
    compileOnly(libs.kotlin.gradle)
    testImplementation(libs.kotlin.test)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

publishing {
    repositories {
        maven(uri("m2")) {
            name = "m2"
        }
    }
}

val clearM2 = tasks.register<Delete>("clearM2") {
    delete(layout.projectDirectory.dir("m2"))
}

tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(clearM2)
}
