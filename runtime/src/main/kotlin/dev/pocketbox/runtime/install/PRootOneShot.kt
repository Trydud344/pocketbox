package dev.pocketbox.runtime.install

import java.io.File

/** Result of a one-shot command executed inside the PRoot container. */
data class ExecResult(val exitCode: Int, val output: String)

/**
 * Runs a one-shot command inside the Alpine rootfs through PRoot. Used by the
 * installer (apk add, smoke tests) and the bounded "Run command" screen.
 */
object PRootOneShot {

    fun exec(
        paths: RuntimePaths,
        abi: String,
        command: String,
        env: Map<String, String> = emptyMap(),
        guestEnv: Map<String, String> = emptyMap(),
        extraBinds: List<BindMount> = emptyList(),
        timeoutMs: Long = 10 * 60 * 1000,
    ): ExecResult {
        val nativeDir = NativeLibs.dir()
            ?: throw InstallException("Native libraries not extracted yet")
        val proot = File(nativeDir, "libproot.so")
        val loader = File(nativeDir, "libproot-loader.so")

        val argv = buildArgv(paths, proot, loader, command, guestEnv, extraBinds)
        val envp = buildEnv(paths, loader, env)

        val pb = ProcessBuilder(argv)
        pb.environment().clear()
        pb.environment().putAll(envp)
        pb.redirectErrorStream(true)

        val proc = pb.start()
        val output = StringBuilder()
        Thread {
            proc.inputStream.bufferedReader().useLines { lines ->
                lines.forEach {
                    synchronized(output) {
                        output.append(it).append('\n')
                        if (output.length > 400_000) output.delete(0, 200_000)
                    }
                }
            }
        }.apply { isDaemon = true }.start()

        val finished = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroy()
            proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            proc.destroyForcibly()
            return ExecResult(-1, "timeout after ${timeoutMs}ms\n${output.takeLast(2000)}")
        }
        Thread.sleep(200) // let the reader drain
        return ExecResult(proc.exitValue(), output.toString())
    }

    /** The proot command line from brief section 7.4. */
    fun buildArgv(
        paths: RuntimePaths,
        proot: File,
        loader: File,
        command: String,
        guestEnv: Map<String, String> = emptyMap(),
        extraBinds: List<BindMount> = emptyList(),
    ): List<String> {
        val rootfs = paths.rootfs.absolutePath
        val fake = paths.fakeProc.absolutePath
        val argv = mutableListOf(
            proot.absolutePath,
            "--kill-on-exit",
            "--link2symlink",
            "-0",
            "-r", rootfs,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${paths.rootfs.absolutePath}/tmp:/dev/shm",
            "-b", "$fake/stat:/proc/stat",
            "-b", "$fake/loadavg:/proc/loadavg",
            "-b", "$fake/uptime:/proc/uptime",
            "-b", "$fake/version:/proc/version",
            "-b", "$fake/vmstat:/proc/vmstat",
        )
        for (bind in extraBinds) {
            argv.add("-b")
            argv.add(bind.toProotArg())
        }
        argv.addAll(
            listOf(
                "-w", "/root",
                "/usr/bin/env", "-i",
                "HOME=/root", "USER=root", "LANG=C.UTF-8", "TMPDIR=/tmp",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            )
        )
        // Guest-side env (credentials etc.) appended to `env -i`, never the argv tail.
        for ((k, v) in guestEnv) {
            if (k.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) argv.add("$k=$v")
        }
        argv.addAll(splitCommand(command))
        return argv
    }

    /**
     * Minimal shell-like splitting for our own command strings. Supports
     * single/double quotes and backslash escapes. Not a full shell parser.
     */
    fun splitCommand(command: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var i = 0
        var quote: Char? = null
        while (i < command.length) {
            val c = command[i]
            when {
                quote != null -> {
                    if (c == '\\' && i + 1 < command.length && (command[i + 1] == quote || command[i + 1] == '\\')) {
                        cur.append(command[i + 1]); i += 2
                    } else if (c == quote) {
                        quote = null; i++
                    } else {
                        cur.append(c); i++
                    }
                }
                c == '\'' || c == '"' -> { quote = c; i++ }
                c == '\\' && i + 1 < command.length -> { cur.append(command[i + 1]); i += 2 }
                c.isWhitespace() -> {
                    if (cur.isNotEmpty()) { out.add(cur.toString()); cur.setLength(0) }
                    i++
                }
                else -> { cur.append(c); i++ }
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    /** PROOT_* env for the proot process itself; guest env via `env -i` above. */
    fun buildEnv(
        paths: RuntimePaths,
        loader: File,
        extra: Map<String, String>,
    ): Map<String, String> {
        val env = mutableMapOf(
            "PROOT_TMP_DIR" to paths.prootTmp.absolutePath,
            "PROOT_LOADER" to loader.absolutePath,
            "TMPDIR" to paths.prootTmp.absolutePath,
            "HOME" to paths.filesDir.absolutePath,
            "PATH" to (System.getenv("PATH") ?: "/system/bin"),
            "LD_LIBRARY_PATH" to (NativeLibs.dir()?.absolutePath ?: ""),
        )
        env.putAll(extra)
        return env
    }
}

/** Access to the app's extracted nativeLibraryDir from the runtime module. */
object NativeLibs {
    @Volatile
    private var nativeDir: File? = null

    fun init(dir: File) {
        nativeDir = dir
    }

    fun dir(): File? = nativeDir
}
