package com.nitroboost.app.core

import java.io.File
import java.io.IOException

/**
 * Same-directory replacement that works on the project's API 24 floor.
 * `java.nio.file.Files` is only available from Android API 26; on Android's
 * Linux-backed filesystems, File.renameTo delegates to rename(2), which
 * atomically replaces a destination on the same filesystem.
 */
object AtomicFileOps {

    @Throws(IOException::class)
    fun replace(source: File, destination: File) {
        val sourceParent = source.absoluteFile.parentFile?.canonicalFile
        val destinationParent = destination.absoluteFile.parentFile?.canonicalFile
        if (sourceParent == null || sourceParent != destinationParent) {
            throw IOException("atomic replacement requires files in the same directory")
        }
        if (!source.renameTo(destination)) {
            throw IOException("could not atomically replace ${destination.name}")
        }
    }
}
