package dev.pocketbox.runtime.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AndroidStorageConfigTest {

    @Test
    fun testBindMountValidationAndFormatting() {
        val m = BindMount("/storage/emulated/0", "/sdcard")
        assertEquals("/storage/emulated/0:/sdcard", m.toProotArg())
    }

    @Test(expected = IllegalArgumentException::class)
    fun testBindMountRejectsRelativeHostPath() {
        BindMount("storage/emulated/0", "/sdcard")
    }

    @Test(expected = IllegalArgumentException::class)
    fun testBindMountRejectsRelativeGuestPath() {
        BindMount("/storage/emulated/0", "sdcard")
    }

    @Test(expected = IllegalArgumentException::class)
    fun testBindMountRejectsColonInHostPath() {
        BindMount("/storage/emulated/0:bad", "/sdcard")
    }

    @Test(expected = IllegalArgumentException::class)
    fun testBindMountRejectsColonInGuestPath() {
        BindMount("/storage/emulated/0", "/sdcard:bad")
    }

    @Test
    fun testPrepareMountPointsCreatesAndCleansEmptySdcard() {
        val tempDir = Files.createTempDirectory("storage-mp-test").toFile()
        try {
            val paths = RuntimePaths(File(tempDir, "files"), File(tempDir, "cache"))
            paths.rootfs.mkdirs()

            val bind = BindMount("/storage/emulated/0", AndroidStorageConfig.GUEST_SDCARD_PATH)
            AndroidStorageConfig.prepareMountPoints(paths, listOf(bind))
            val sdcardDir = File(paths.rootfs, "sdcard")
            assertTrue("Expected rootfs/sdcard to be created", sdcardDir.isDirectory)

            // Disabling removes the empty mountpoint directory
            AndroidStorageConfig.prepareMountPoints(paths, emptyList())
            assertFalse("Expected empty rootfs/sdcard to be cleaned up", sdcardDir.exists())

            // Non-empty local rootfs/sdcard must NOT be deleted when disabled
            sdcardDir.mkdirs()
            val localFile = File(sdcardDir, "keep.txt").apply { writeText("keep") }
            AndroidStorageConfig.prepareMountPoints(paths, emptyList())
            assertTrue("Non-empty rootfs/sdcard must be preserved", localFile.exists())
        } finally {
            RuntimePaths.deleteRecursivelyNoFollow(tempDir)
        }
    }

    @Test
    fun testSyncAgentInstructionsCreateIdempotentAndRemove() {
        val tempDir = Files.createTempDirectory("storage-agents-test").toFile()
        try {
            val paths = RuntimePaths(File(tempDir, "files"), File(tempDir, "cache"))
            paths.rootfs.mkdirs()

            val agentsFile = File(paths.rootfs, "root/.config/opencode/AGENTS.md")
            assertFalse(agentsFile.exists())

            // 1. Create when enabled
            AndroidStorageConfig.syncAgentInstructions(paths, "/sdcard")
            assertTrue(agentsFile.exists())
            val firstContent = agentsFile.readText()
            assertTrue(firstContent.contains(AndroidStorageConfig.AGENTS_BLOCK_START))
            assertTrue(firstContent.contains("/sdcard/Documents"))
            assertTrue(firstContent.contains(AndroidStorageConfig.AGENTS_BLOCK_END))

            // 2. Idempotent on second call
            AndroidStorageConfig.syncAgentInstructions(paths, "/sdcard")
            assertEquals(firstContent, agentsFile.readText())

            // 3. Remove deletes file if only managed block was present
            AndroidStorageConfig.syncAgentInstructions(paths, null)
            assertFalse("AGENTS.md should be deleted when empty after stripping block", agentsFile.exists())
        } finally {
            RuntimePaths.deleteRecursivelyNoFollow(tempDir)
        }
    }

    @Test
    fun testSyncAgentInstructionsPreservesUserContent() {
        val tempDir = Files.createTempDirectory("storage-agents-user-test").toFile()
        try {
            val paths = RuntimePaths(File(tempDir, "files"), File(tempDir, "cache"))
            val agentsFile = File(paths.rootfs, "root/.config/opencode/AGENTS.md")
            agentsFile.parentFile?.mkdirs()
            agentsFile.writeText("# My Custom Rules\nAlways write tests.\n")

            // Append managed block without clobbering user text
            AndroidStorageConfig.syncAgentInstructions(paths, "/sdcard")
            val withBlock = agentsFile.readText()
            assertTrue(withBlock.startsWith("# My Custom Rules\nAlways write tests."))
            assertTrue(withBlock.contains(AndroidStorageConfig.AGENTS_BLOCK_START))

            // Add user text after the block too, then re-sync and strip
            agentsFile.writeText(withBlock + "\n# Footer Rule\nBe concise.\n")
            AndroidStorageConfig.syncAgentInstructions(paths, "/sdcard")
            val reSynced = agentsFile.readText()
            assertTrue(reSynced.contains("# My Custom Rules"))
            assertTrue(reSynced.contains("# Footer Rule"))

            AndroidStorageConfig.syncAgentInstructions(paths, null)
            assertTrue(agentsFile.exists())
            val stripped = agentsFile.readText()
            assertFalse(stripped.contains(AndroidStorageConfig.AGENTS_BLOCK_START))
            assertTrue(stripped.contains("# My Custom Rules\nAlways write tests."))
            assertTrue(stripped.contains("# Footer Rule\nBe concise."))
        } finally {
            RuntimePaths.deleteRecursivelyNoFollow(tempDir)
        }
    }
}
