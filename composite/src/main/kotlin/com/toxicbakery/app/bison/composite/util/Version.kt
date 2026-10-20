package com.toxicbakery.app.bison.composite.util

import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations

/**
 * Single source of truth for the app's calendar-derived version, resolved from `git describe`
 * so every module in `allprojects` agrees.
 */
fun Project.resolveProjectVersion(): String =
    providers.of(GitVersionValueSource::class.java) {
        parameters.workingDir.set(rootDir.absolutePath)
    }.get()

/**
 * Monotonically increasing integer derived from `year.week.count`:
 * `code = (year % 100) * 100000 + week * 1000 + count`, floored to 1.
 */
fun projectVersionCode(version: String): Int {
    val base = version.substringBefore('-')
    val parts = base.split('.')
    val year = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val week = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val count = parts.getOrNull(2)?.toIntOrNull() ?: 0
    val code = (year % 100) * 100000 + week * 1000 + count
    return code.coerceAtLeast(1)
}

/**
 * The plain `major.minor.patch` core with any `-dev+<metadata>` suffix stripped.
 */
fun packagingVersion(version: String): String = version.substringBefore('-')

internal fun parseGitDescribe(raw: String?): String {
    val describe = raw?.trim().orEmpty()
    if (describe.isEmpty()) {
        return "0.0.0-dev"
    }
    val d = describe.removePrefix("v")

    val releaseRegex = Regex("""^\d+\.\d+\.\d+$""")
    if (releaseRegex.matches(d)) return d

    val taggedRegex = Regex("""^(\d+\.\d+\.\d+)(?:-(.+))?$""")
    val match = taggedRegex.matchEntire(d)
    if (match != null) {
        val baseVersion = match.groupValues[1]
        val extra = match.groupValues[2]
        return if (extra.isEmpty()) "$baseVersion-dev" else "$baseVersion-dev+${extra.replace('+', '.')}"
    }

    return "0.0.0-dev+g$d"
}

abstract class GitVersionValueSource : ValueSource<String, GitVersionValueSource.Params> {

    interface Params : ValueSourceParameters {
        val workingDir: Property<String>
    }

    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): String {
        val stdout = ByteArrayOutputStream()
        val raw = runCatching {
            execOperations.exec {
                workingDir = java.io.File(parameters.workingDir.get())
                commandLine("git", "describe", "--tags", "--always", "--dirty")
                standardOutput = stdout
                errorOutput = ByteArrayOutputStream()
                isIgnoreExitValue = true
            }
            stdout.toString().trim()
        }.getOrNull()
        return parseGitDescribe(raw)
    }
}
