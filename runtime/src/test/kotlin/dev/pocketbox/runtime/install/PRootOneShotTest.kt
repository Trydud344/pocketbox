package dev.pocketbox.runtime.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PRootOneShotTest {

    @Test
    fun testSplitCommandSimple() {
        val args = PRootOneShot.splitCommand("echo hello world")
        assertEquals(listOf("echo", "hello", "world"), args)
    }

    @Test
    fun testSplitCommandQuotes() {
        val args = PRootOneShot.splitCommand("opencode serve --hostname '127.0.0.1' --port 4096")
        assertEquals(listOf("opencode", "serve", "--hostname", "127.0.0.1", "--port", "4096"), args)

        val args2 = PRootOneShot.splitCommand("""/bin/sh -c "echo 'nested quotes' && ls -la"""")
        assertEquals(listOf("/bin/sh", "-c", "echo 'nested quotes' && ls -la"), args2)
    }

    @Test
    fun testSplitCommandEscapes() {
        val args = PRootOneShot.splitCommand("""foo\ bar baz\ test""")
        assertEquals(listOf("foo bar", "baz test"), args)
    }

    @Test
    fun testSplitCommandMultipleSpaces() {
        val args = PRootOneShot.splitCommand("   cmd    arg1    arg2   ")
        assertEquals(listOf("cmd", "arg1", "arg2"), args)
    }

    @Test
    fun testBuildArgvStructureAndSecurity() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "pb-test-${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            val filesDir = File(tempDir, "files").apply { mkdirs() }
            val cacheDir = File(tempDir, "cache").apply { mkdirs() }
            val paths = RuntimePaths(filesDir, cacheDir)
            paths.ensureDirs()
            paths.rootfs.mkdirs()

            val prootBin = File(tempDir, "libproot.so")
            val loaderBin = File(tempDir, "libproot-loader.so")

            val argv = PRootOneShot.buildArgv(
                paths = paths,
                proot = prootBin,
                loader = loaderBin,
                command = "/usr/local/bin/opencode serve --hostname 127.0.0.1 --port 4096",
                guestEnv = mapOf(
                    "OPENCODE_SERVER_USERNAME" to "opencode",
                    "OPENCODE_SERVER_PASSWORD" to "secret123",
                    "ANDROID_STORAGE" to "/sdcard",
                ),
                extraBinds = listOf(BindMount("/storage/emulated/0", "/sdcard")),
            )

            // Extra bind mount must appear as `-b /storage/emulated/0:/sdcard` before `-w /root`
            val bindIdx = argv.indexOf("/storage/emulated/0:/sdcard")
            val workdirIdx = argv.indexOf("-w")
            assertTrue("Expected bind mount arg", bindIdx > 0)
            assertEquals("-b", argv[bindIdx - 1])
            assertTrue("Bind mount must precede -w", bindIdx < workdirIdx)

            // Credentials and ANDROID_STORAGE must be in `env -i` section, never after command binary
            val envIdx = argv.indexOf("/usr/bin/env")
            val cmdIdx = argv.indexOf("/usr/local/bin/opencode")
            val passIdx = argv.indexOf("OPENCODE_SERVER_PASSWORD=secret123")
            val storageEnvIdx = argv.indexOf("ANDROID_STORAGE=/sdcard")
            assertTrue("Password must be in guest env", passIdx in (envIdx + 1) until cmdIdx)
            assertTrue("ANDROID_STORAGE must be in guest env", storageEnvIdx in (envIdx + 1) until cmdIdx)
            assertFalse("Password must not appear in command argv", argv.subList(cmdIdx, argv.size).any { it.contains("secret123") })
        } finally {
            RuntimePaths.deleteRecursivelyNoFollow(tempDir)
        }
    }
}
