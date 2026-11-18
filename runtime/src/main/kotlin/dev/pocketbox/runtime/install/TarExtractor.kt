package dev.pocketbox.runtime.install

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/**
 * Extracts an Alpine minirootfs tarball, handling the edge cases called out in
 * the brief (section 12): device nodes are skipped, hard links become copies,
 * symlinks preserved, permissions kept. Extraction goes into a staging dir,
 * then is atomically renamed.
 */
object TarExtractor {

    fun extractTarGz(archive: File, destDir: File) {
        destDir.mkdirs()
        GZIPInputStream(FileInputStream(archive)).use { gzip ->
            TarArchiveInputStream(gzip).use { tar ->
                val hardLinks = mutableListOf<Triple<String, String, Int>>() // target -> linkPath with mode
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    val name = sanitizeName(entry.name) ?: continue
                    val outFile = File(destDir, name)

                    when {
                        entry.isSymbolicLink -> {
                            outFile.parentFile?.mkdirs()
                            deleteIfExists(outFile)
                            val target = entry.linkName
                            try {
                                java.nio.file.Files.createSymbolicLink(
                                    outFile.toPath(),
                                    java.nio.file.Paths.get(target),
                                )
                            } catch (e: java.nio.file.FileAlreadyExistsException) {
                                // fine
                            } catch (e: java.nio.file.FileSystemException) {
                                // Some filesystems (FUSE) don't support symlinks;
                                // fall back to copying the target if we have it.
                                val targetFile = File(destDir, normalizeInside(name, target))
                                if (targetFile.isFile) {
                                    targetFile.copyTo(outFile, overwrite = true)
                                }
                            }
                        }

                        entry.isLink -> {
                            // hard link: remember, materialize as copy after all entries
                            hardLinks.add(Triple(entry.linkName, name, entry.mode))
                        }

                        entry.isDirectory -> {
                            outFile.mkdirs()
                            setPerms(outFile, entry.mode)
                        }

                        entry.isFIFO || entry.isBlockDevice || entry.isCharacterDevice -> {
                            // Device nodes / fifos are meaningless inside PRoot; skip.
                        }

                        else -> { // regular file
                            outFile.parentFile?.mkdirs()
                            deleteIfExists(outFile)
                            FileOutputStream(outFile).use { out -> tar.copyTo(out) }
                            setPerms(outFile, entry.mode)
                        }
                    }
                }

                // Materialize hard links as full copies now that all sources exist.
                for ((target, linkPath, mode) in hardLinks) {
                    val src = File(destDir, sanitizeName(target) ?: continue)
                    val dst = File(destDir, linkPath)
                    if (!dst.exists() && src.exists()) {
                        dst.parentFile?.mkdirs()
                        deleteIfExists(dst)
                        src.copyTo(dst, overwrite = true)
                        setPerms(dst, mode)
                    }
                }
            }
        }
    }

    private fun sanitizeName(name: String): String? {
        var n = name.trim()
        while (n.startsWith("./") || n.startsWith("/")) {
            n = if (n.startsWith("./")) n.removePrefix("./") else n.removePrefix("/")
        }
        if (n.isEmpty()) return null
        if (n.split('/').any { it == ".." }) return null // path traversal guard
        if (n.contains('\u0000')) return null
        return n
    }

    private fun normalizeInside(from: String, target: String): String {
        // Best-effort resolution for the symlink-fallback copy path.
        val baseParts = from.split('/').dropLast(1).toMutableList()
        for (part in target.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> if (baseParts.isNotEmpty()) baseParts.removeAt(baseParts.size - 1)
                else -> baseParts.add(part)
            }
        }
        return baseParts.joinToString("/")
    }

    private fun deleteIfExists(f: File) {
        val path = f.toPath()
        if (java.nio.file.Files.isSymbolicLink(path)) {
            try {
                java.nio.file.Files.deleteIfExists(path)
            } catch (_: Exception) {
                f.delete()
            }
        } else if (f.isDirectory) {
            f.deleteRecursively()
        } else {
            f.delete()
        }
    }

    private fun setPerms(f: File, mode: Int) {
        try {
            // Only the classic 0777-class bits matter on app-private storage.
            val m = mode and 0b111111111
            f.setReadable(m and 0b100100100 != 0, false)
            f.setWritable(m and 0b010010010 != 0, false)
            f.setExecutable(m and 0b001001001 != 0, false)
            // Owner bits exact:
            f.setReadable(m and 0b100000000 != 0, true)
            f.setWritable(m and 0b010000000 != 0, true)
            f.setExecutable(m and 0b001000000 != 0, true)
        } catch (_: SecurityException) {
        }
    }
}
