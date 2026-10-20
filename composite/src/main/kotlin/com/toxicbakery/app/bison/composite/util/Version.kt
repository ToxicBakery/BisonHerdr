package com.toxicbakery.app.bison.composite.util

import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations

/**
 * Single source of truth for the app's calendar-derived version. There is no committed
 * `VERSION` file; the version is resolved from `git describe` so every module in
 * [`allprojects`] agrees.
 *
 * The scheme is `<2-digit ISO week-year>.<ISO week>.<count>`:
 *
 * - Release build (CI, checked out on a tag): `git describe` yields exactly
 *   `v26.29.1` -> `26.29.1` (no marker).
 * - Dev build (local / PR / main): `v26.29.1-7-gabc123` ->
 *   `26.29.1-dev+7-gabc123`; a bare SHA before the first tag -> `0.0.0-dev+g<sha>`.
 *
 * The `-dev` marker never appears on a clean release, ensuring dev and release builds
 * are unambiguously distinguishable.
 *
 * The year is 2-digit because jpackage's MSI validator caps the *major* component at
 * 255, so a 4-digit `2026.x.y` cannot be packaged for Windows at all; see
 * [packagingVersion].
 */
fun Project.resolveProjectVersion(): String =
    providers.of(GitVersionValueSource::class.java) {
        // Anchor git to the repo root so the same value resolves regardless of which
        // subproject triggers the ValueSource.
        parameters.workingDir.set(rootDir.absolutePath)
    }.get()

/**
 * The monotonically increasing integer the stores require (Play `versionCode`, App
 * Store `CURRENT_PROJECT_VERSION`), derived from the leading `year.week.count`:
 *
 *   code = (year % 100) * 100000 + week * 1000 + count
 *
 * `26.29.1` -> `2629001`. Any `-dev+...` metadata is ignored. Dev builds with no tag
 * yet (`0.0.0-dev`) floor to `1`, since Android requires `versionCode >= 1`.
 *
 * `% 100` (rather than `- 2000`) keeps the arithmetic correct for the 2-digit year the
 * scheme uses, and is value-identical for the 4-digit form — `2026 - 2000` and
 * `2026 % 100` are both `26`.
 *
 * This mirrors `version_code()` in `scripts/cut_version.py`; keep the two in sync.
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
 * The version as desktop native packagers will accept it: the plain `major.minor.patch`
 * core with any `-dev+<metadata>` suffix stripped.
 */
fun packagingVersion(version: String): String = version.substringBefore('-')

/**
 * Turns raw `git describe --tags --always --dirty` output into the version string.
 * Pure and side-effect free so the mapping is easy to reason about; kept `internal`
 * for the [GitVersionValueSource] and unit tests.
 */
internal fun parseGitDescribe(raw: String?): String {
    val describe = raw?.trim().orEmpty()
    if (describe.isEmpty()) {
        // No git, or an unexpected failure: still produce a valid, obviously-dev version.
        return "0.0.0-dev"
    }
    val d = describe.removePrefix("v")

    // Exactly on a tag with a clean tree -> the release version, verbatim.
    val releaseRegex = Regex("""^\d+\.\d+\.\d+$""")
    if (releaseRegex.matches(d)) return d

    // `<year.week.count>-<extra>` (commits-since / dirty) -> a dev build off that tag.
    val taggedRegex = Regex("""^(\d+\.\d+\.\d+)(?:-(.+))?$""")
    val match = taggedRegex.matchEntire(d)
    if (match != null) {
        val baseVersion = match.groupValues[1]
        val extra = match.groupValues[2]
        // SemVer build metadata is dot-separated `[0-9A-Za-z-]`; `+` is not allowed
        // inside it, so normalise any stray `+` to `.`.
        return if (extra.isEmpty()) "$baseVersion-dev" else "$baseVersion-dev+${extra.replace('+', '.')}"
    }

    // No tag reachable: `git describe --always` returned a bare short SHA (maybe -dirty).
    return "0.0.0-dev+g$d"
}

/**
 * Runs `git describe` off the Gradle configuration path in a configuration-cache-safe
 * way. A [ValueSource] is re-evaluated at the start of each build to detect a changed
 * result, so the version stays fresh *without* the cache-invalidating problem that a
 * raw `project.exec {}`/`Runtime.exec` at configuration time would cause.
 */
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
