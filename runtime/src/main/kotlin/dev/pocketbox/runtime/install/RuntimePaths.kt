package dev.pocketbox.runtime.install

import android.content.Context
import android.os.Build
import dev.pocketbox.runtime.manifest.RuntimeManifest
import java.io.File
import java.io.IOException

/**
 * Idempotent install pipeline state. Each completed step is recorded so an
 * interrupted install can resume where it left off (brief section 7.3).
 */
enum class InstallStep {
    PREFLIGHT,
    DOWNLOAD_ALPINE,
    DOWNLOAD_OPENCODE,
    EXTRACT_ROOTFS,
    SEED_CONFIG,
    APK_PACKAGES,
    INSTALL_OPENCODE,
    WRITE_DEFAULTS,
    FINALIZE,
}

enum class InstallPhase { IDLE, RUNNING, DONE, FAILED }

/**
 * Where runtime state lives on the device (brief section 10):
 *  filesDir/rootfs          guest /
 *  filesDir/fake_proc       fake /proc files bind-mounted by proot
 *  filesDir/logs            server.log
 *  cacheDir/downloads       in-flight downloads (safe to delete)
 *  cacheDir/proot-tmp       PROOT_TMP_DIR
 */
class RuntimePaths(
    val filesDir: File,
    val cacheDir: File,
) {
    constructor(context: Context) : this(context.filesDir, context.cacheDir)

    val rootfs: File = File(filesDir, "rootfs")
    val rootfsStaging: File = File(filesDir, "rootfs.staging")
    val fakeProc: File = File(filesDir, "fake_proc")
    val logs: File = File(filesDir, "logs")
    val serverLog: File = File(logs, "server.log")
    val downloads: File = File(cacheDir, "downloads")
    val prootTmp: File = File(cacheDir, "proot-tmp")
    val stateFile: File = File(filesDir, "runtime-state.json")
    val projectsDir: File get() = File(rootfs, "root/projects")

    fun ensureDirs() {
        logs.mkdirs()
        downloads.mkdirs()
        prootTmp.mkdirs()
        fakeProc.mkdirs()
    }

    fun rootfsExtracted(): Boolean {
        val sh = File(rootfs, "bin/sh")
        val busybox = File(rootfs, "bin/busybox")
        return busybox.exists() ||
            sh.exists() ||
            java.nio.file.Files.isSymbolicLink(sh.toPath())
    }

    fun installed(): Boolean = stateFile.exists() && rootfsExtracted()

    /** Reads the installed OpenCode version recorded in runtime-state.json, if present. */
    fun installedOpencodeVersion(): String? {
        if (!stateFile.exists()) return null
        return try {
            val elem = kotlinx.serialization.json.Json.parseToJsonElement(stateFile.readText())
            val obj = elem as? kotlinx.serialization.json.JsonObject ?: return null
            (obj["opencodeVersion"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        } catch (_: Exception) {
            null
        }
    }

    /** True if the runtime is installed but its OpenCode version differs from [targetVersion]. */
    fun needsOpencodeUpgrade(targetVersion: String?): Boolean {
        if (targetVersion == null || !installed()) return false
        return installedOpencodeVersion() != targetVersion
    }

    fun wipe() {
        deleteRecursivelyNoFollow(rootfsStaging)
        deleteRecursivelyNoFollow(rootfs)
        deleteRecursivelyNoFollow(fakeProc)
        stateFile.delete()
        deleteRecursivelyNoFollow(logs)
        deleteRecursivelyNoFollow(prootTmp)
        deleteRecursivelyNoFollow(downloads)
    }

    companion object {
        /**
         * Deletes [root] recursively without following symbolic links.
         * Critical once the app holds external storage permissions so symlinks
         * inside `rootfs` pointing to `/sdcard` or `/storage/emulated/0` can
         * never delete user files on the host device.
         */
        fun deleteRecursivelyNoFollow(root: File) {
            val rootPath = root.toPath()
            if (!java.nio.file.Files.exists(rootPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
            if (java.nio.file.Files.isSymbolicLink(rootPath) ||
                !java.nio.file.Files.isDirectory(rootPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            ) {
                try {
                    java.nio.file.Files.deleteIfExists(rootPath)
                } catch (_: IOException) {
                }
                return
            }
            try {
                java.nio.file.Files.walkFileTree(
                    rootPath,
                    object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                        override fun preVisitDirectory(
                            dir: java.nio.file.Path,
                            attrs: java.nio.file.attribute.BasicFileAttributes,
                        ): java.nio.file.FileVisitResult {
                            if (!attrs.isSymbolicLink) {
                                val f = dir.toFile()
                                f.setReadable(true, true)
                                f.setWritable(true, true)
                                f.setExecutable(true, true)
                            }
                            return java.nio.file.FileVisitResult.CONTINUE
                        }

                        override fun visitFile(
                            file: java.nio.file.Path,
                            attrs: java.nio.file.attribute.BasicFileAttributes,
                        ): java.nio.file.FileVisitResult {
                            try {
                                java.nio.file.Files.deleteIfExists(file)
                            } catch (_: IOException) {
                            }
                            return java.nio.file.FileVisitResult.CONTINUE
                        }

                        override fun visitFileFailed(
                            file: java.nio.file.Path,
                            exc: IOException,
                        ): java.nio.file.FileVisitResult {
                            try {
                                if (!java.nio.file.Files.isSymbolicLink(file)) {
                                    val f = file.toFile()
                                    f.setReadable(true, true)
                                    f.setWritable(true, true)
                                    f.setExecutable(true, true)
                                }
                                java.nio.file.Files.deleteIfExists(file)
                            } catch (_: IOException) {
                            }
                            return java.nio.file.FileVisitResult.CONTINUE
                        }

                        override fun postVisitDirectory(
                            dir: java.nio.file.Path,
                            exc: IOException?,
                        ): java.nio.file.FileVisitResult {
                            try {
                                java.nio.file.Files.deleteIfExists(dir)
                            } catch (_: IOException) {
                            }
                            return java.nio.file.FileVisitResult.CONTINUE
                        }
                    },
                )
            } catch (_: IOException) {
            }
        }

        fun deviceAbi(): String = when (Build.SUPPORTED_ABIS?.firstOrNull()) {
            "arm64-v8a" -> "arm64-v8a"
            "x86_64" -> "x86_64"
            else -> Build.SUPPORTED_ABIS?.firstOrNull() ?: "unknown"
        }
    }
}

/** Thrown with a user-presentable message when the pipeline must stop. */
class InstallException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Progress events emitted during install. */
sealed class InstallEvent {
    data class StepChanged(val step: InstallStep, val index: Int, val total: Int) : InstallEvent()
    data class DownloadProgress(val what: String, val downloadedBytes: Long, val totalBytes: Long) : InstallEvent()
    data class Log(val message: String) : InstallEvent()
    data class Failed(val step: InstallStep, val message: String) : InstallEvent()
    data object Completed : InstallEvent()
}
