package com.dongholab.pagetuner.storage

import java.io.File
import java.io.IOException
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.InvocationTargetException

/**
 * Commits a closed, fully written staging file beside its destination. The caller owns
 * flushing the staged bytes and cleaning it up on failure. Never deletes the destination
 * first or falls back to copying over it when atomic replacement is unavailable.
 */
internal fun replaceFileAtomically(source: File, target: File) {
    val sourcePath = source.canonicalFile
    val targetPath = target.canonicalFile
    if (sourcePath == targetPath || sourcePath.parentFile != targetPath.parentFile) {
        throw IOException("Atomic replacement requires distinct files in the same directory.")
    }
    // Android's same-directory rename uses the native atomic rename, including on API 23.
    if (source.renameTo(target)) return

    // Windows File.renameTo may refuse an existing destination. NIO's ATOMIC_MOVE
    // uses MoveFileEx(REPLACE_EXISTING) there. Reflective access avoids any linkage to
    // java.nio.file (introduced on Android API 26) on older Android devices.
    try {
        val filesClass = Class.forName("java.nio.file.Files")
        val pathClass = Class.forName("java.nio.file.Path")
        val optionClass = Class.forName("java.nio.file.CopyOption")
        val standardOptions = Class.forName("java.nio.file.StandardCopyOption")
        val options = ReflectArray.newInstance(optionClass, 2)
        ReflectArray.set(options, 0, standardOptions.getField("ATOMIC_MOVE").get(null))
        ReflectArray.set(options, 1, standardOptions.getField("REPLACE_EXISTING").get(null))
        val toPath = File::class.java.getMethod("toPath")
        filesClass.getMethod("move", pathClass, pathClass, options.javaClass)
            .invoke(null, toPath.invoke(source), toPath.invoke(target), options)
    } catch (error: InvocationTargetException) {
        val cause = error.cause
        if (cause is IOException) throw cause
        throw IOException("Atomic file replacement failed.", cause ?: error)
    } catch (error: ReflectiveOperationException) {
        throw IOException("Atomic file replacement is unavailable on this platform.", error)
    }
}
