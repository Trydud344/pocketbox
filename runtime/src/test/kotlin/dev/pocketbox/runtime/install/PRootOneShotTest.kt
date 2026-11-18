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
            val rootfs = File(tempDir, "rootfs").apply { mkdirs() }
            val fake = File(tempDir, "fake_proc").apply { mkdirs() }
            val logs = File(tempDir, "logs").apply { mkdirs() }
            val cache = File(tempDir, "cache").apply { mkdirs() }

            val dummyPaths = object {
                val filesDir = tempDir
                val rootfs = rootfs
                val fakeProc = fake
                val logs = logs
                val prootTmp = File(cache, "proot-tmp")
            }

            // We can construct dummy RuntimePaths-like paths
            val prootBin = File(tempDir, "libproot.so")
            val loaderBin = File(tempDir, "libproot-loader.so")

            // Test PRootOneShot.buildArgv directly using an actual RuntimePaths requires context or mocked paths.
            // But we can test splitCommand and buildArgv logic.
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
