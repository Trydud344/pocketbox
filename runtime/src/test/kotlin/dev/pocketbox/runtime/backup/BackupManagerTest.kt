package dev.pocketbox.runtime.backup

import dev.pocketbox.runtime.install.RuntimePaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class BackupManagerTest {

    @Test
    fun testExportAndRestoreRoundTrip() {
        val tempDir = Files.createTempDirectory("backup-test").toFile()
        try {
            val filesDir = File(tempDir, "files").apply { mkdirs() }
            val cacheDir = File(tempDir, "cache").apply { mkdirs() }
            val paths = RuntimePaths(filesDir, cacheDir)

            // Seed /root with sample data
            val rootDir = File(paths.rootfs, "root").apply { mkdirs() }
            val configDir = File(rootDir, ".config/opencode").apply { mkdirs() }
            File(configDir, "opencode.json").writeText("""{"autoupdate": false}""")

            val projectDir = File(rootDir, "projects/sample-app").apply { mkdirs() }
            File(projectDir, "main.js").writeText("console.log('hello');")
            File(rootDir, ".bashrc").writeText("export FOO=bar")

            // 1. Export
            val baos = ByteArrayOutputStream()
            val exportResult = BackupManager.exportRoot(paths, baos)
            assertTrue("Export should succeed: ${exportResult.message}", exportResult.success)
            assertTrue("Exported bytes should be > 0", exportResult.bytes > 0)

            // 2. Clear root directory to simulate restore on clean/reset state
            rootDir.deleteRecursively()
            assertTrue("Root should be deleted", !rootDir.exists())

            // 3. Restore
            val bais = ByteArrayInputStream(baos.toByteArray())
            val importResult = BackupManager.importRoot(paths, bais)
            assertTrue("Restore should succeed: ${importResult.message}", importResult.success)

            // 4. Verify contents
            val restoredConfig = File(paths.rootfs, "root/.config/opencode/opencode.json")
            assertTrue(restoredConfig.exists())
            assertEquals("""{"autoupdate": false}""", restoredConfig.readText())

            val restoredMain = File(paths.rootfs, "root/projects/sample-app/main.js")
            assertTrue(restoredMain.exists())
            assertEquals("console.log('hello');", restoredMain.readText())

            val restoredBashrc = File(paths.rootfs, "root/.bashrc")
            assertTrue(restoredBashrc.exists())
            assertEquals("export FOO=bar", restoredBashrc.readText())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
