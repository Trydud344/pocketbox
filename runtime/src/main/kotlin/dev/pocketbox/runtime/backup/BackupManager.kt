package dev.pocketbox.runtime.backup

import dev.pocketbox.runtime.install.RuntimePaths
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
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
        var count = 0L
        try {
            TarArchiveOutputStream(GZIPOutputStream(BufferedOutputStream(out))).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                rootHome.walkTopDown().forEach { f ->
                    val rel = f.toRelativeString(rootHome)
                    if (rel.isEmpty()) return@forEach
                    val entry = TarArchiveEntry(f, rel)
                    if (f.isFile) {
                        entry.size = f.length()
                        tar.putArchiveEntry(entry)
                        FileInputStream(f).use { it.copyTo(tar) }
                        tar.closeArchiveEntry()
                        count += f.length()
                    } else if (f.isDirectory) {
                        tar.putArchiveEntry(entry)
                        tar.closeArchiveEntry()
                    }
                }
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
        staging.deleteRecursively()
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
                if (dest.exists()) {
                    if (dest.isDirectory) dest.deleteRecursively() else dest.delete()
                }
                if (!child.renameTo(dest)) {
                    // cross-dir rename fallback
                    child.copyRecursively(dest, overwrite = true)
                }
            }
            staging.deleteRecursively()
            return BackupResult(true, "Restore complete")
        } catch (e: Exception) {
            staging.deleteRecursively()
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