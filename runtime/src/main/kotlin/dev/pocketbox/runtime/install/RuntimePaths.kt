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
        rootfsStaging.deleteRecursively()
        rootfs.deleteRecursively()
        fakeProc.deleteRecursively()
        stateFile.delete()
        logs.deleteRecursively()
        prootTmp.deleteRecursively()
        downloads.deleteRecursively()
    }

    companion object {
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
