package dev.pocketbox.runtime.backup

import dev.pocketbox.runtime.install.RuntimePaths
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream

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
            RuntimePaths.deleteRecursivelyNoFollow(tempDir)
        }
    }

    @Test
    fun testSymlinkToExternalStorageIsNeverFollowedByBackupOrWipe() {
        val tempDir = Files.createTempDirectory("symlink-safety-test").toFile()
        try {
            val filesDir = File(tempDir, "files").apply { mkdirs() }
            val cacheDir = File(tempDir, "cache").apply { mkdirs() }
            val fakeExternalStorage = File(tempDir, "fake-sdcard").apply { mkdirs() }
            val preciousPhoto = File(fakeExternalStorage, "precious.jpg").apply { writeText("photo-bytes") }

            val paths = RuntimePaths(filesDir, cacheDir)
            val rootDir = File(paths.rootfs, "root").apply { mkdirs() }
            File(rootDir, "note.txt").writeText("inside-root")

            // Create a symlink inside /root pointing to external storage
            val phoneLink = File(rootDir, "phone").toPath()
            Files.createSymbolicLink(phoneLink, fakeExternalStorage.toPath())

            // 1. Export must NOT dereference the symlink or archive precious.jpg
            val baos = ByteArrayOutputStream()
            val exportResult = BackupManager.exportRoot(paths, baos)
            assertTrue(exportResult.success)

            val archivedNames = mutableListOf<String>()
            TarArchiveInputStream(GZIPInputStream(ByteArrayInputStream(baos.toByteArray()))).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    archivedNames.add(entry.name)
                }
            }
            assertTrue("Expected note.txt in archive", archivedNames.contains("note.txt"))
            assertTrue("Expected symlink entry 'phone' in archive", archivedNames.contains("phone"))
            assertFalse("Must NOT archive files behind symlink", archivedNames.any { it.contains("precious.jpg") })

            // 2. Import replacing /root must NOT delete precious.jpg behind existing symlink
            val importResult = BackupManager.importRoot(paths, ByteArrayInputStream(baos.toByteArray()))
            assertTrue(importResult.success)
            assertTrue("External file must survive restore", preciousPhoto.exists())
            assertEquals("photo-bytes", preciousPhoto.readText())

            // 3. RuntimePaths.wipe() must delete rootfs without touching external storage
            paths.wipe()
            assertFalse("rootfs should be wiped", paths.rootfs.exists())
            assertTrue("External file must survive wipe()", preciousPhoto.exists())
            assertEquals("photo-bytes", preciousPhoto.readText())
        } finally {
            RuntimePaths.deleteRecursivelyNoFollow(tempDir)
        }
    }
}
