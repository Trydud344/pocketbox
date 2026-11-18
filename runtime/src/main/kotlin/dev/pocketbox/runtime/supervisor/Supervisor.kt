package dev.pocketbox.runtime.supervisor

import dev.pocketbox.runtime.install.NativeLibs
import dev.pocketbox.runtime.install.PRootOneShot
import dev.pocketbox.runtime.install.RuntimePaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File

/** Supervisor states from brief section 7.5. */
enum class ServerState { NOT_INSTALLED, INSTALLING, STOPPED, STARTING, RUNNING, STOPPING, BACKOFF, ERROR }

/** Immutable snapshot of what the UI and service need to know. */
data class RuntimeStatus(
    val state: ServerState = ServerState.NOT_INSTALLED,
    val port: Int = DEFAULT_PORT,
    val version: String? = null,
    val startedAt: Long? = null,
    val errorMessage: String? = null,
    val restartCount: Int = 0,
    val memoryUsageBytes: Long? = null,
) {
    companion object { const val DEFAULT_PORT = 4096 }
}

/**
 * Owns the PRoot/`opencode serve` process: spawn, health poll, liveness,
 * crash backoff, clean stop. All process management happens here; the
 * foreground service and the UI only talk to this class.
 */
class Supervisor(
    private val paths: RuntimePaths,
    private val scope: CoroutineScope,
    private val credentialsProvider: suspend () -> Pair<String, String>, // username, password
    private val portProvider: suspend () -> Int,
    private val bindHostProvider: suspend () -> String = { "127.0.0.1" },
    private val hostEnvProvider: suspend () -> Map<String, String> = { emptyMap() },
) {
    private val _status = MutableStateFlow(RuntimeStatus())
    val status: StateFlow<RuntimeStatus> = _status

    private var process: Process? = null
    private var watchdog: Job? = null
    private var backoffJob: Job? = null

    private val health = HealthClient("", "") // rebuilt per start with real creds
    private val logWriter = LogRotator(File(paths.logs, "server.log"))

    private var consecutiveFailures = 0
    private var failuresInWindow = 0
    private var windowStart = 0L
    @Volatile private var userStop = false

    fun markInstalled(version: String?) {
        if (_status.value.state == ServerState.NOT_INSTALLED || _status.value.state == ServerState.INSTALLING) {
            _status.value = _status.value.copy(state = ServerState.STOPPED, version = version)
        }
    }

    fun markNotInstalled() {
        _status.value = RuntimeStatus(state = ServerState.NOT_INSTALLED)
    }

    fun markInstalling() {
        _status.value = _status.value.copy(state = ServerState.INSTALLING, errorMessage = null)
    }

    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        val st = _status.value
        if (st.state == ServerState.RUNNING || st.state == ServerState.STARTING) return@withContext true
        if (!paths.installed()) {
            _status.value = st.copy(state = ServerState.NOT_INSTALLED)
            return@withContext false
        }
        userStop = false
        _status.value = st.copy(state = ServerState.STARTING, errorMessage = null)

        val (username, password) = credentialsProvider()
        val port = portProvider()
        val bindHost = bindHostProvider()

        val nativeDir = NativeLibs.dir()
        val proot = nativeDir?.let { File(it, "libproot.so") }
        val loader = nativeDir?.let { File(it, "libproot-loader.so") }
        if (proot == null || loader == null || !proot.exists() || !loader.exists()) {
            fail("Native PRoot libraries missing from $nativeDir")
            return@withContext false
        }

        // Port collision: try to bind before spawning.
        val chosenPort = freePortOr(port)
        _status.value = _status.value.copy(port = chosenPort)

        val argv = PRootOneShot.buildArgv(
            paths = paths,
            proot = proot,
            loader = loader,
            command = "/usr/local/bin/opencode serve --hostname $bindHost --port $chosenPort",
            // Credentials go into the guest environment only — never the command line.
            guestEnv = mapOf(
                "OPENCODE_SERVER_USERNAME" to username,
                "OPENCODE_SERVER_PASSWORD" to password,
            ),
        )
        val env = PRootOneShot.buildEnv(paths, loader, hostEnvProvider())

        try {
            val pb = ProcessBuilder(argv)
            pb.environment().clear()
            pb.environment().putAll(env)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            process = proc
            logWriter.append("--- starting opencode serve on $bindHost:$chosenPort ---\n")
            startLogCapture(proc)
        } catch (e: Exception) {
            fail("Failed to spawn PRoot: ${e.message}")
            return@withContext false
        }

        // Ready check: poll health up to 90 s (cold start under PRoot is slow).
        val hc = HealthClient(username, password)
        val healthHost = if (bindHost == "0.0.0.0" || bindHost == "::") "127.0.0.1" else bindHost
        val deadline = System.currentTimeMillis() + 90_000
        var ready = false
        var version: String? = null
        while (System.currentTimeMillis() < deadline) {
            if (process?.isAlive != true) break
            val h = hc.check(healthHost, chosenPort)
            if (h.healthy) {
                ready = true
                version = h.version
                break
            }
            delay(1500)
        }

        if (!ready) {
            val tail = logWriter.tail(50)
            stopProcess()
            fail("Server did not become healthy in 90 s.\n$tail")
            return@withContext false
        }

        consecutiveFailures = 0
        _status.value = _status.value.copy(
            state = ServerState.RUNNING,
            version = version,
            startedAt = System.currentTimeMillis(),
            errorMessage = null,
        )
        startWatchdog(hc, username, password)
        true
    }

    private fun startLogCapture(proc: Process) {
        Thread {
            proc.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { logWriter.append(it + "\n") }
            }
        }.apply { isDaemon = true; name = "pb-log-capture" }.start()
    }

    private fun startWatchdog(hc: HealthClient, username: String, password: String) {
        watchdog?.cancel()
        watchdog = scope.launch(Dispatchers.IO) {
            val healthHost = "127.0.0.1" // always reachable from the app even in LAN bind mode
            while (_status.value.state == ServerState.RUNNING) {
                delay(15_000)
                val port = _status.value.port
                val h = hc.check(healthHost, port)
                val alive = process?.isAlive == true
                if (!h.healthy) {
                    consecutiveFailures++
                    if (consecutiveFailures >= 3 || !alive) {
                        // Crash: restart with exponential backoff.
                        noteFailure()
                        if (shouldEnterError()) return@launch
                        _status.value = _status.value.copy(state = ServerState.BACKOFF, memoryUsageBytes = null)
                        stopProcess()
                        delay(backoffDelayMs())
                        if (!userStop) start()
                        return@launch
                    }
                } else {
                    consecutiveFailures = 0
                    val mem = readProcessMemory(process)
                    if (mem != null && _status.value.state == ServerState.RUNNING) {
                        _status.value = _status.value.copy(memoryUsageBytes = mem)
                    }
                }
            }
        }
    }

    private fun readProcessMemory(proc: Process?): Long? {
        if (proc == null) return null
        return try {
            val pid = extractPid(proc) ?: return null
            val statusFile = File("/proc/$pid/status")
            if (statusFile.exists()) {
                statusFile.useLines { lines ->
                    val line = lines.firstOrNull { it.startsWith("VmRSS:") }
                    line?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()?.times(1024)
                }
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractPid(proc: Process): Long? {
        return try {
            val f = proc.javaClass.getDeclaredField("pid")
            f.isAccessible = true
            f.getLong(proc)
        } catch (_: Throwable) {
            try {
                val m = proc.javaClass.getMethod("pid")
                (m.invoke(proc) as? Number)?.toLong()
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun noteFailure() {
        val now = System.currentTimeMillis()
        if (now - windowStart > 5 * 60_000) {
            windowStart = now
            failuresInWindow = 0
        }
        failuresInWindow++
    }

    private fun shouldEnterError(): Boolean {
        if (failuresInWindow >= 5) {
            _status.value = _status.value.copy(
                state = ServerState.ERROR,
                errorMessage = "Server restarted 5 times within 5 minutes.\n" +
                    logWriter.tail(50),
            )
            return true
        }
        return false
    }

    private fun backoffDelayMs(): Long {
        val n = minOf(failuresInWindow, 7)
        return (1L shl n) * 1000L  // 2, 4, 8 ... 128 s
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        userStop = true
        watchdog?.cancel()
        backoffJob?.cancel()
        if (_status.value.state != ServerState.STOPPED) {
            _status.value = _status.value.copy(state = ServerState.STOPPING)
            stopProcess()
            _status.value = _status.value.copy(state = ServerState.STOPPED, startedAt = null, memoryUsageBytes = null)
        }
    }

    private fun stopProcess() {
        val proc = process ?: return
        try {
            proc.destroy() // SIGTERM
            proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if (proc.isAlive) proc.destroyForcibly() // SIGKILL; --kill-on-exit reaps the tree
        } catch (_: Exception) {
        }
        process = null
    }

    suspend fun restart(): Boolean {
        stop()
        delay(1000)
        return start()
    }

    private fun fail(message: String) {
        _status.value = _status.value.copy(state = ServerState.ERROR, errorMessage = message)
    }

    /** Returns [port] if free, otherwise the next free port >= port. */
    private fun freePortOr(port: Int): Int {
        var p = port
        repeat(50) {
            try {
                java.net.ServerSocket().use { s ->
                    s.bind(java.net.InetSocketAddress("127.0.0.1", p))
                    return p
                }
            } catch (_: Exception) {
                p++
            }
        }
        return port
    }
}

/** Simple size-capped rotating log (5 x 2 MB). */
class LogRotator(private val file: File) {
    private val maxBytes = 2L * 1024 * 1024
    private val maxFiles = 5
    private val lock = Any()

    fun append(line: String) {
        synchronized(lock) {
            try {
                if (file.exists() && file.length() > maxBytes) rotate()
                file.appendText(line)
            } catch (_: Exception) {
            }
        }
    }

    private fun rotate() {
        for (i in maxFiles - 2 downTo 1) {
            val src = File(file.parentFile, "${file.name}.$i")
            val dst = File(file.parentFile, "${file.name}.${i + 1}")
            if (src.exists()) src.renameTo(dst)
        }
        val f1 = File(file.parentFile, "${file.name}.1")
        file.renameTo(f1)
    }

    fun tail(lines: Int): String = synchronized(lock) {
        try {
            val all = file.readText()
            val parts = all.lines().dropLastWhile { it.isEmpty() }
            parts.takeLast(lines.coerceAtMost(parts.size)).joinToString("\n")
        } catch (_: Exception) {
            ""
        }
    }
}
