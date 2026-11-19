package dev.pocketbox.runtime.backup

import dev.pocketbox.runtime.install.RuntimePaths
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Backup/restore of /root (OpenCode config, auth, projects, dotfiles) as a
 * tar.gz archive (brief section 10). Archives may contain API keys — callers
 * must warn the user.
 */
object BackupManager {

    data class BackupResult(val success: Boolean, val message: String, val bytes: Long = 0)

    /** Export /root inside the rootfs to a tar.gz stream (SAF output). */
    fun exportRoot(paths: RuntimePaths, out: OutputStream): BackupResult {
        val rootHome = File(paths.rootfs, "root")
        if (!rootHome.exists()) return BackupResult(false, "Nothing to back up (/root missing)")
        val rootPath = rootHome.toPath()
        var count = 0L
        try {
            TarArchiveOutputStream(GZIPOutputStream(BufferedOutputStream(out))).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                // Walk without following symlinks so links pointing outside rootfs
                // (e.g. to /sdcard) are archived as symlinks, never dereferenced.
                Files.walkFileTree(
                    rootPath,
                    object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                            if (dir == rootPath) return FileVisitResult.CONTINUE
                            val rel = rootPath.relativize(dir).toString().replace(File.separatorChar, '/')
                            val entry = TarArchiveEntry(dir.toFile(), "$rel/")
                            tar.putArchiveEntry(entry)
                            tar.closeArchiveEntry()
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                            val rel = rootPath.relativize(file).toString().replace(File.separatorChar, '/')
                            if (attrs.isSymbolicLink) {
                                val target = Files.readSymbolicLink(file).toString()
                                val entry = TarArchiveEntry(rel, TarConstants.LF_SYMLINK)
                                entry.linkName = target
                                tar.putArchiveEntry(entry)
                                tar.closeArchiveEntry()
                            } else if (attrs.isRegularFile) {
                                val f = file.toFile()
                                val entry = TarArchiveEntry(f, rel)
                                entry.size = f.length()
                                tar.putArchiveEntry(entry)
                                FileInputStream(f).use { it.copyTo(tar) }
                                tar.closeArchiveEntry()
                                count += f.length()
                            }
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                            return FileVisitResult.CONTINUE
                        }
                    },
                )
                tar.finish()
            }
            return BackupResult(true, "Backup complete", count)
        } catch (e: Exception) {
            return BackupResult(false, "Backup failed: ${e.message}", count)
        }
    }

    /** Validate and import a /root tar.gz into the rootfs. */
    fun importRoot(paths: RuntimePaths, input: InputStream): BackupResult {
        val rootHome = File(paths.rootfs, "root")
        val staging = File(paths.rootfs, "root.restore-staging")
        RuntimePaths.deleteRecursivelyNoFollow(staging)
        staging.mkdirs()
        try {
            // Validate + extract to staging first.
            TarArchiveInputStream(GZIPInputStream(BufferedInputStream(input))).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    val name = sanitize(entry.name) ?: continue
                    val outFile = File(staging, name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else if (entry.isSymbolicLink) {
                        outFile.parentFile?.mkdirs()
                        try {
                            java.nio.file.Files.createSymbolicLink(outFile.toPath(), java.nio.file.Paths.get(entry.linkName))
                        } catch (_: Exception) {
                        }
                    } else if (!entry.isLink && !entry.isFIFO && !entry.isBlockDevice && !entry.isCharacterDevice) {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { tar.copyTo(it) }
                    }
                }
            }
            // Replace /root contents (keep staging as source of truth).
            rootHome.mkdirs()
            staging.listFiles()?.forEach { child ->
                val dest = File(rootHome, child.name)
                if (Files.exists(dest.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    RuntimePaths.deleteRecursivelyNoFollow(dest)
                }
                if (!child.renameTo(dest)) {
                    // cross-dir rename fallback
                    child.copyRecursively(dest, overwrite = true)
                }
            }
            RuntimePaths.deleteRecursivelyNoFollow(staging)
            return BackupResult(true, "Restore complete")
        } catch (e: Exception) {
            RuntimePaths.deleteRecursivelyNoFollow(staging)
            return BackupResult(false, "Restore failed: ${e.message}")
        }
    }

    private fun sanitize(name: String): String? {
        var n = name.trim()
        while (n.startsWith("./") || n.startsWith("/")) {
            n = if (n.startsWith("./")) n.removePrefix("./") else n.removePrefix("/")
        }
        if (n.isEmpty()) return null
        if (n.split('/').any { it == ".." }) return null
        return n
    }
}