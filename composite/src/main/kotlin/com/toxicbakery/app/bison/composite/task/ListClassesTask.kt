package com.toxicbakery.app.bison.composite.task

import com.toxicbakery.app.bison.composite.util.ReleaseClasses
import org.gradle.api.DefaultTask
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Writes the names of the classes in [jars] and [directories] to [classList], one name in each line, in sort order.
 *
 * An Android build gives the two lists with `ScopedArtifacts.toGet(ScopedArtifact.CLASSES, ...)` for one variant.
 */
@CacheableTask
abstract class ListClassesTask : DefaultTask() {

    /** The class jars of the variant. */
    @get:Classpath
    abstract val jars: ListProperty<RegularFile>

    /** The class directories of the variant. */
    @get:Classpath
    abstract val directories: ListProperty<Directory>

    @get:OutputFile
    abstract val classList: RegularFileProperty

    @TaskAction
    fun list() {
        val names = sortedSetOf<String>()
        jars.get().map { jar -> jar.asFile }.filter { jar -> jar.isFile }.forEach { jar ->
            names += ReleaseClasses.classesInJar(jar)
        }
        directories.get().map { directory -> directory.asFile }.filter { directory -> directory.isDirectory }
            .forEach { directory -> names += ReleaseClasses.classesInDirectory(directory) }
        val output = classList.get().asFile
        output.parentFile.mkdirs()
        output.writeText(names.joinToString(separator = "\n", postfix = "\n"))
    }
}
