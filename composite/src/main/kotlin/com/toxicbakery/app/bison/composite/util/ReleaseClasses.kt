package com.toxicbakery.app.bison.composite.util

import java.io.File
import java.util.zip.ZipFile

/**
 * Reads the classes of a build and the R8 mapping file of a release build. Then it finds the classes that only a
 * debug build can have.
 *
 * A class name has the Java form, for example `com.example.Outer$Inner`. A debug-only name is a class or a package.
 * It matches the class, its nested classes, and the classes of the package.
 */
object ReleaseClasses {

    /** The class names of an R8 mapping file. */
    class Mapping(
        /** The original names of the classes in the dex files. */
        val classes: Set<String>,
        /** The original names of the classes that R8 inlined into a different class, or moved members from. */
        val inlinedFrom: Set<String>,
    )

    /** The problems that [check] found. The release build is correct when [isEmpty] is true. */
    data class Result(
        /** The classes of the release mapping that match a debug-only name. */
        val shipped: List<String>,
        /** The top-level classes that only the debug build has, and that no debug-only name matches. */
        val undeclared: List<String>,
        /** The debug-only names that match no class of the debug build. */
        val unused: List<String>,
        /** The required classes that are not classes of the release mapping. */
        val missing: List<String>,
    ) {
        val isEmpty: Boolean
            get() = shipped.isEmpty() && undeclared.isEmpty() && unused.isEmpty() && missing.isEmpty()
    }

    private val NON_CLASS_FILES = setOf("module-info", "package-info")

    /**
     * Returns the class name of the class file at [path] in a jar or in a class directory. Returns null when [path]
     * is not a class file, or when it is in `META-INF` or is a module or package descriptor.
     */
    fun classNameOf(path: String): String? {
        val normalized = path.replace('\\', '/').removePrefix("/")
        if (!normalized.endsWith(".class") || normalized.startsWith("META-INF/")) return null
        val name = normalized.removeSuffix(".class")
        if (name.substringAfterLast('/') in NON_CLASS_FILES) return null
        return name.replace('/', '.')
    }

    /** Returns the class names of the class files in [jar]. */
    fun classesInJar(jar: File): List<String> =
        ZipFile(jar).use { zip ->
            zip.entries().asSequence().mapNotNull { entry -> classNameOf(entry.name) }.toList()
        }

    /** Returns the class names of the class files under [directory]. */
    fun classesInDirectory(directory: File): List<String> =
        directory.walkTopDown()
            .filter { file -> file.isFile }
            .mapNotNull { file -> classNameOf(file.relativeTo(directory).invariantSeparatorsPath) }
            .toList()

    /**
     * Reads the [lines] of an R8 mapping file.
     *
     * A line with no indent starts a class: `<original name> -> <new name>:`. An indented member line names the
     * original class of a member that R8 inlined or moved, for example `1:4:void com.example.Debug.run():8:11 -> a`.
     * The reader ignores the comment lines, the fields, and the types of the parameters.
     */
    fun readMapping(lines: Sequence<String>): Mapping {
        val classes = HashSet<String>()
        val inlinedFrom = HashSet<String>()
        for (line in lines) {
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            val original = line.substringBefore(" -> ")
            if (!line.startsWith(" ") && !line.startsWith("\t")) {
                classes += original.trim()
                continue
            }
            if ('(' !in original) continue
            // `1:4:void com.example.Debug.run` -> `com.example.Debug.run`. A member of the same class has no dot.
            val member = original.substringBefore('(').trim().substringAfterLast(' ')
            if ('.' in member) inlinedFrom += member.substringBeforeLast('.')
        }
        return Mapping(classes = classes, inlinedFrom = inlinedFrom)
    }

    /** Returns the top-level class of [className]: the part before the first `$`. */
    fun topLevelOf(className: String): String = className.substringBefore('$')

    /** Returns true when [className] is [name], a nested class of [name], or a class in the package [name]. */
    fun matches(className: String, name: String): Boolean =
        className == name || className.startsWith("$name$") || className.startsWith("$name.")

    /**
     * Checks the release mapping [mapping] against the debug-only names [debugOnlyNames].
     *
     * - A class of the release mapping that matches a debug-only name is in [Result.shipped].
     * - A top-level class in [checkedPackage] that [debugClasses] has and [releaseClasses] does not have is debug-only
     *   code. When no debug-only name matches it, it is in [Result.undeclared]. Thus each new debug-only class must
     *   get a name. A later move of the class to a main source set then fails the check when R8 keeps the class. A
     *   class that has a twin with the same name in the release build, such as `DebugModuleKt`, is not debug-only.
     * - A debug-only name that matches no class of [debugClasses] is in [Result.unused].
     * - A class of [requiredClasses] that is not a class of the release mapping is in [Result.missing].
     */
    fun check(
        mapping: Mapping,
        debugClasses: Set<String>,
        releaseClasses: Set<String>,
        debugOnlyNames: Set<String>,
        checkedPackage: String,
        requiredClasses: Set<String>,
    ): Result {
        fun isDebugOnly(className: String) = debugOnlyNames.any { name -> matches(className, name) }

        val shipped = (mapping.classes + mapping.inlinedFrom).filter(::isDebugOnly).sorted()
        val releaseTopLevel = releaseClasses.mapTo(HashSet(), ::topLevelOf)
        val undeclared = debugClasses.asSequence()
            .map(::topLevelOf)
            .filter { className -> className.startsWith("$checkedPackage.") }
            .filter { className -> className !in releaseTopLevel && !isDebugOnly(className) }
            .toSortedSet()
            .toList()
        val unused = debugOnlyNames.filter { name -> debugClasses.none { className -> matches(className, name) } }
            .sorted()
        val missing = requiredClasses.filter { className -> className !in mapping.classes }.sorted()
        return Result(shipped = shipped, undeclared = undeclared, unused = unused, missing = missing)
    }
}
