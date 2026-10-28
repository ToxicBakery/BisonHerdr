package com.toxicbakery.app.bison.composite.task

import com.toxicbakery.app.bison.composite.util.ReleaseClasses
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Checks the classes of a release build in its R8 mapping file. [ReleaseClasses.check] gives the rules.
 *
 * The mapping names each class of the dex files, and each class that R8 inlined or moved members from. The task
 * fails when the mapping has a debug-only class, when the debug build has a debug-only class that
 * [debugOnlyClasses] does not name, when a name of [debugOnlyClasses] matches no class, or when the mapping does
 * not have each class of [requiredClasses].
 */
@CacheableTask
abstract class CheckReleaseClassesTask : DefaultTask() {

    /** The R8 mapping file of the release variant. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mappingFile: RegularFileProperty

    /** The class list of the debug variant from [ListClassesTask]. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val debugClassList: RegularFileProperty

    /** The class list of the release variant from [ListClassesTask], before R8. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val releaseClassList: RegularFileProperty

    /** The classes and the packages that only a debug build can have. */
    @get:Input
    abstract val debugOnlyClasses: SetProperty<String>

    /** The package of the debug-only classes that must have a name in [debugOnlyClasses]. */
    @get:Input
    abstract val checkedPackage: Property<String>

    /** The classes that the release build must have. */
    @get:Input
    abstract val requiredClasses: SetProperty<String>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun check() {
        val mapping = mappingFile.get().asFile.useLines(block = ReleaseClasses::readMapping)
        val result = ReleaseClasses.check(
            mapping = mapping,
            debugClasses = readClassList(debugClassList),
            releaseClasses = readClassList(releaseClassList),
            debugOnlyNames = debugOnlyClasses.get(),
            checkedPackage = checkedPackage.get(),
            requiredClasses = requiredClasses.get(),
        )
        val errors = buildList {
            if (result.shipped.isNotEmpty()) {
                add("The release build has classes that only a debug build can have: ${result.shipped.joinToString()}")
            }
            if (result.undeclared.isNotEmpty()) {
                add(
                    "Only the debug build has these classes, but debugOnlyClasses does not name them: " +
                        "${result.undeclared.joinToString()}. Add each one to debugOnlyClasses."
                )
            }
            if (result.unused.isNotEmpty()) {
                add("debugOnlyClasses names classes that the debug build does not have: ${result.unused.joinToString()}")
            }
            if (result.missing.isNotEmpty()) {
                add("The release build does not have the classes ${result.missing.joinToString()}")
            }
        }

        val report = reportFile.get().asFile
        report.parentFile.mkdirs()
        report.writeText(
            if (errors.isEmpty()) {
                "No debug-only class in the release build. Required classes: ${requiredClasses.get().sorted()}\n"
            } else {
                errors.joinToString(separator = "\n", postfix = "\n")
            }
        )
        if (errors.isNotEmpty()) throw GradleException(errors.joinToString(separator = "\n"))
    }

    private fun readClassList(classList: RegularFileProperty): Set<String> =
        classList.get().asFile.readLines().filterTo(HashSet()) { line -> line.isNotBlank() }
}
