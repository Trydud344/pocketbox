package dev.pocketbox.runtime.install

import dev.pocketbox.runtime.manifest.RuntimeManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The full install pipeline (brief section 7.3): preflight, checksum-verified
 * resumable downloads, tar extraction, config seeding, apk packages, OpenCode
 * install, credentials/defaults, state persistence.
 *
 * Idempotent: each step checks what is already done before doing work.
 */
class Installer(
    private val paths: RuntimePaths,
    private val manifest: RuntimeManifest,
    private val abi: String = RuntimePaths.deviceAbi(),
    private val wifiOnly: () -> Boolean = { true },
    private val onMetered: () -> Boolean = { false },
) {
    constructor(
        paths: RuntimePaths,
        manifest: RuntimeManifest,
        abi: String = RuntimePaths.deviceAbi(),
        wifiOnly: Boolean,
        onMetered: () -> Boolean = { false },
    ) : this(paths, manifest, abi, { wifiOnly }, onMetered)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val steps = InstallStep.entries.toList()

    fun install(): Flow<InstallEvent> = flow {
        val entry = manifest.forAbi(abi)
            ?: throw InstallException("Unsupported ABI: $abi")

        paths.ensureDirs()
        emit(InstallEvent.StepChanged(InstallStep.PREFLIGHT, 0, steps.size))

        // ---- 1. Preflight ----
        if (paths.installed()) {
            emit(InstallEvent.Log("Runtime already installed"))
            emit(InstallEvent.Completed)
            return@flow
        }
        val freeBytes = paths.filesDir.usableSpace
        if (freeBytes < 1L shl 30) {
            throw InstallException(
                "Not enough free space: ${freeBytes / (1 shl 20)} MB available, need at least 1 GB"
            )
        }
        if (freeBytes < 2L shl 30) {
            emit(InstallEvent.Log("Warning: low storage (${freeBytes / (1 shl 20)} MB free, recommend >= 2 GB)"))
        }
        if (wifiOnly() && onMetered()) {
            throw InstallException("Wi-Fi only is enabled but the network is metered")
        }
        emit(InstallEvent.Log("Preflight OK: ${freeBytes / (1 shl 20)} MB free, ABI $abi"))

        val alpine = entry.alpine
        val opencode = entry.opencode

        // ---- 2. Downloads (resumable, checksum-verified) ----
        emit(InstallEvent.StepChanged(InstallStep.DOWNLOAD_ALPINE, 1, steps.size))
        val alpineFile = downloadWithResume(alpine, "alpine-minirootfs-${alpine.version}.tar.gz")

        emit(InstallEvent.StepChanged(InstallStep.DOWNLOAD_OPENCODE, 2, steps.size))
        val opencodeFile = downloadWithResume(opencode, "opencode-${opencode.version}.tar.gz")

        // ---- 3. Extract rootfs to staging, atomic rename ----
        emit(InstallEvent.StepChanged(InstallStep.EXTRACT_ROOTFS, 3, steps.size))
        if (!paths.rootfs.exists()) {
            paths.rootfsStaging.deleteRecursively()
            paths.rootfsStaging.mkdirs()
            emit(InstallEvent.Log("Extracting ${alpineFile.name} ..."))
            TarExtractor.extractTarGz(alpineFile, paths.rootfsStaging)
            paths.rootfs.deleteRecursively()
            if (!paths.rootfsStaging.renameTo(paths.rootfs)) {
                throw InstallException("Could not move staging rootfs into place")
            }
        }
        emit(InstallEvent.Log("Rootfs ready (${alpine.version})"))

        // ---- 4. Seed config ----
        emit(InstallEvent.StepChanged(InstallStep.SEED_CONFIG, 4, steps.size))
        seedConfig()

        // ---- 5. apk packages ----
        emit(InstallEvent.StepChanged(InstallStep.APK_PACKAGES, 5, steps.size))
        installApkPackages(entry)

        // ---- 6. OpenCode binary ----
        emit(InstallEvent.StepChanged(InstallStep.INSTALL_OPENCODE, 6, steps.size))
        installOpenCode(opencodeFile)

        // ---- 7. Defaults (credentials are written by the app layer) ----
        emit(InstallEvent.StepChanged(InstallStep.WRITE_DEFAULTS, 7, steps.size))
        writeDefaults(entry)

        // ---- 8. Persist state ----
        emit(InstallEvent.StepChanged(InstallStep.FINALIZE, 8, steps.size))
        val state = buildJsonObject {
            put("schema", 1)
            put("abi", abi)
            put("alpineVersion", alpine.version)
            put("opencodeVersion", opencode.version)
            put("installedAt", System.currentTimeMillis())
        }
        paths.stateFile.writeText(Json.encodeToString(JsonObject.serializer(), state))

        alpineFile.delete()
        opencodeFile.delete()
        emit(InstallEvent.Completed)
    }.flowOn(Dispatchers.IO)

    /** Resumable download with SHA-256 verification; one retry on mismatch. */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<InstallEvent>.downloadWithResume(
        artifact: RuntimeManifest.Artifact,
        name: String,
    ): File {
        val file = File(paths.downloads, name)
        val part = File(paths.downloads, "$name.part")
        for (attempt in 1..2) {
            try {
                download(artifact, file, part)
                emit(InstallEvent.Log("Verified ${artifact.url.substringAfterLast('/')}"))
                return file
            } catch (e: ChecksumMismatch) {
                if (attempt == 2) throw InstallException("Checksum mismatch for $name", e)
                emit(InstallEvent.Log("Checksum mismatch, retrying download"))
                part.delete()
                file.delete()
            }
        }
        throw InstallException("unreachable")
    }

    private class ChecksumMismatch(message: String) : IOException(message)

    private suspend fun kotlinx.coroutines.flow.FlowCollector<InstallEvent>.download(
        artifact: RuntimeManifest.Artifact,
        file: File,
        part: File,
    ) {
        var downloaded = if (part.exists()) part.length() else 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            var retryFromStart = false
            val rb = okhttp3.Request.Builder()
                .url(artifact.url)
                .header("Accept-Encoding", "identity")
            if (downloaded > 0) rb.header("Range", "bytes=$downloaded-")
            val response = client.newCall(rb.build()).execute()
            response.use { resp ->
                when {
                    resp.code == 200 -> {
                        downloaded = 0
                        part.parentFile?.mkdirs()
                        resp.body!!.byteStream().use { input ->
                            val out = java.io.FileOutputStream(part, false)
                            copyWithProgress(input, out, artifact.sizeBytes, downloaded, artifact)
                            out.close()
                        }
                    }
                    resp.code == 206 && downloaded > 0 -> {
                        resp.body!!.byteStream().use { input ->
                            val out = java.io.FileOutputStream(part, true)
                            copyWithProgress(input, out, artifact.sizeBytes, downloaded, artifact)
                            out.close()
                        }
                    }
                    resp.code == 416 -> {
                        // Range not satisfiable: server may not support resume.
                        downloaded = 0
                        part.delete()
                        retryFromStart = true
                    }
                    else -> throw InstallException("HTTP ${resp.code} for ${artifact.url}")
                }
            }
            if (retryFromStart) continue
            // verify
            currentCoroutineContext().ensureActive()
            val digest = MessageDigest.getInstance("SHA-256")
            java.io.FileInputStream(part).use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (!hex.equals(artifact.sha256, ignoreCase = true)) {
                throw ChecksumMismatch("expected ${artifact.sha256.take(12)}… got ${hex.take(12)}…")
            }
                if (!part.renameTo(file)) {
                    file.delete()
                    if (!part.renameTo(file)) throw InstallException("Could not finalize download ${file.name}")
                }
            return
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<InstallEvent>.copyWithProgress(
        input: java.io.InputStream,
        out: java.io.OutputStream,
        totalSize: Long,
        alreadyDownloaded: Long,
        artifact: RuntimeManifest.Artifact,
    ) {
        val buf = ByteArray(1 shl 16)
        var written = alreadyDownloaded
        val total = if (totalSize > 0) totalSize else -1L
        var lastEmit = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
            written += n
            if (written - lastEmit > (1 shl 20)) {
                lastEmit = written
                emit(
                    InstallEvent.DownloadProgress(
                        artifact.url.substringAfterLast('/'),
                        written,
                        total,
                    )
                )
            }
        }
        out.flush()
    }

    /** Seed resolv.conf, hosts, passwd check, /root, /tmp 1777, fake /proc files. */
    private fun seedConfig() {
        val rootfs = paths.rootfs
        File(rootfs, "root").mkdirs()
        val tmp = File(rootfs, "tmp")
        tmp.mkdirs()
        tmp.setReadable(true, false)
        tmp.setWritable(true, false)
        tmp.setExecutable(true, false)

        val resolv = File(rootfs, "etc/resolv.conf")
        if (!resolv.exists() || resolv.length() == 0L) {
            resolv.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        }
        val hosts = File(rootfs, "etc/hosts")
        if (hosts.length() == 0L) {
            hosts.writeText("127.0.0.1 localhost\n::1 localhost\n")
        }
        val passwd = File(rootfs, "etc/passwd")
        val content = passwd.readText()
        if (!content.contains("^root:".toRegex())) {
            passwd.writeText("root:x:0:0:root:/root:/bin/ash\n$content")
        }

        // Fake /proc files that Android restricts but Node/Bun-based tools read.
        val fake = paths.fakeProc
        fake.listFiles()?.forEach { it.delete() }
        File(fake, "stat").writeText(fakeStat())
        File(fake, "loadavg").writeText("0.10 0.15 0.20 1/100 1000\n")
        File(fake, "uptime").writeText("1000.00 2000.00\n")
        File(fake, "version").writeText("Linux version 5.15.0-pocketbox (proot)\n")
        File(fake, "vmstat").writeText("nr_free_pages 100000\n")
    }

    private fun fakeStat(): String = buildString {
        append("cpu  100 0 100 10000 0 0 0 0 0 0\n")
        for (i in 0..7) append("cpu$i 12 0 12 1000 0 0 0 0 0 0\n")
        append("intr 1000\n")
        append("ctxt 2000\n")
        append("btime 1700000000\n")
        append("processes 100\n")
        append("procs_running 1\n")
        append("procs_blocked 0\n")
    }

    /** Run apk add for the base package list inside PRoot. */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<InstallEvent>.installApkPackages(
        entry: RuntimeManifest.AbiEntry,
    ) {
        val marker = File(paths.rootfs, ".pb-apk-done")
        if (marker.exists()) return
        val packages = (listOf("alpine-baselayout") + manifest.apkBase).joinToString(" ")
        emit(InstallEvent.Log("Installing packages: $packages"))
        val result = PRootOneShot.exec(
            paths = paths,
            abi = abi,
            command = "/bin/sh -c 'apk update && apk add --no-cache $packages'",
            env = mapOf("HOME" to "/root"),
            timeoutMs = 15 * 60 * 1000,
        )
        if (result.exitCode != 0) {
            throw InstallException("apk add failed (${result.exitCode}):\n${result.output.takeLast(2000)}")
        }
        marker.writeText(manifest.apkBase.joinToString(" "))
        emit(InstallEvent.Log("Packages installed"))
    }

    /** Install the OpenCode binary to /usr/local/bin/opencode and smoke-test it. */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<InstallEvent>.installOpenCode(
        tarball: File,
    ) {
        val binDir = File(paths.rootfs, "usr/local/bin")
        binDir.mkdirs()
        val binary = File(binDir, "opencode")
        if (binary.exists() && binary.length() > 10_000_000) return

        // The release tarball contains a single `opencode` binary.
        TarExtractor.extractTarGz(tarball, binDir)
        if (!binary.exists()) throw InstallException("opencode binary missing after extraction")
        binary.setExecutable(true, false)

        emit(InstallEvent.Log("Smoke test: opencode --version"))
        val result = PRootOneShot.exec(
            paths = paths,
            abi = abi,
            command = "/usr/local/bin/opencode --version",
            env = mapOf("HOME" to "/root"),
        )
        if (result.exitCode != 0) {
            throw InstallException("opencode --version failed (${result.exitCode}):\n${result.output.takeLast(1500)}")
        }
        emit(InstallEvent.Log("opencode: ${result.output.trim().lineSequence().first()}"))
    }

    private fun writeDefaults(entry: RuntimeManifest.AbiEntry) {
        val binDir = File(paths.rootfs, "usr/local/bin")
        val doctor = File(binDir, "pb-doctor")
        doctor.writeText(DOCTOR_SCRIPT)
        doctor.setExecutable(true, false)

        // Seed OpenCode config with self-update disabled (best-effort, re-verify
        // config key against the shipped binary's behavior — see DECISIONS.md D9).
        val ocConfigDir = File(paths.rootfs, "root/.config/opencode")
        ocConfigDir.mkdirs()
        val ocConfig = File(ocConfigDir, "opencode.json")
        if (!ocConfig.exists()) {
            ocConfig.writeText("""{"autoupdate": false}""")
        }

        // Ensure the projects directory exists (brief 7.6).
        File(paths.rootfs, "root/projects").mkdirs()

        // Keep a copy of the manifest used to install (for Settings > About).
        File(paths.filesDir, "runtime-manifest-used.json").writeText(
            Json.encodeToString(RuntimeManifest.serializer(), manifest)
        )
    }

    /** Apply a custom resolv.conf (nameserver lines) into the guest. */
    fun applyDns(resolvContent: String) {
        val resolv = File(paths.rootfs, "etc/resolv.conf")
        resolv.parentFile?.mkdirs()
        resolv.writeText(resolvContent.trimEnd() + "\n")
    }

    /** Install an extra apk package pack (node/python/build) on demand. */
    suspend fun installPack(packName: String): ExecResult {
        val packages = manifest.apkPacks[packName] ?: return ExecResult(1, "Unknown pack: $packName")
        return PRootOneShot.exec(
            paths = paths,
            abi = abi,
            command = "/bin/sh -c 'apk update && apk add --no-cache ${packages.joinToString(" ")}'",
            env = emptyMap(),
            timeoutMs = 20 * 60 * 1000,
        )
    }

    /** Import a file (e.g. auth/config) into the container under targetPath (relative to rootfs or absolute inside guest). */
    fun importFile(source: File, guestPath: String): File {
        val rel = guestPath.removePrefix("/").trimStart('/')
        val target = File(paths.rootfs, rel)
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
        // Best-effort perms for config/auth files.
        target.setReadable(true, true)
        target.setWritable(true, true)
        return target
    }

    companion object {
        val DOCTOR_SCRIPT = """
            #!/bin/sh
            echo "== pb-doctor =="
            echo "opencode: $(/usr/local/bin/opencode --version 2>&1 | head -1)"
            echo "git:      $(git --version 2>&1 || echo missing)"
            echo "rg:       $(rg --version 2>&1 | head -1 || echo missing)"
            echo "curl:     $(curl --version 2>&1 | head -1 || echo missing)"
            echo "disk:     $(df -h /root 2>/dev/null | tail -1)"
            echo "dns:      $(nslookup opencode.ai 2>&1 | tail -2 | head -1 || echo failed)"
            echo "date:     $(date 2>&1)"
        """.trimIndent()
    }
}
