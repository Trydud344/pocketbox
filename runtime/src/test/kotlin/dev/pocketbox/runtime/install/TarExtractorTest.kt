package dev.pocketbox.runtime.install

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class TarExtractorTest {

    @Test
    fun testExtractTarGzAndPathTraversalProtection() {
        val tempDir = Files.createTempDirectory("tar-test").toFile()
        try {
            val archiveFile = File(tempDir, "test.tar.gz")
            val destDir = File(tempDir, "extracted")

            // Create a test tar.gz
            TarArchiveOutputStream(GZIPOutputStream(BufferedOutputStream(FileOutputStream(archiveFile)))).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)

                // 1. Directory: etc/
                val dirEntry = TarArchiveEntry("etc/")
                tar.putArchiveEntry(dirEntry)
                tar.closeArchiveEntry()

                // 2. Regular file: etc/motd
                val fileEntry = TarArchiveEntry("etc/motd")
                val motdBytes = "Welcome to Pocketbox Alpine\n".toByteArray()
                fileEntry.size = motdBytes.size.toLong()
                tar.putArchiveEntry(fileEntry)
                tar.write(motdBytes)
                tar.closeArchiveEntry()

                // 3. Regular file: bin/busybox
                val binEntry = TarArchiveEntry("bin/busybox")
                val binBytes = "binary-content-1234".toByteArray()
                binEntry.size = binBytes.size.toLong()
                binEntry.mode = 0b111101101 // 755
                tar.putArchiveEntry(binEntry)
                tar.write(binBytes)
                tar.closeArchiveEntry()

                // 4. Hard link: bin/ash -> bin/busybox
                val hardLinkEntry = TarArchiveEntry("bin/ash", TarArchiveEntry.LF_LINK)
                hardLinkEntry.linkName = "bin/busybox"
                tar.putArchiveEntry(hardLinkEntry)
                tar.closeArchiveEntry()

                // 5. Malicious traversal entry: ../../evil.txt
                val evilEntry = TarArchiveEntry("../../evil.txt")
                val evilBytes = "hacked".toByteArray()
                evilEntry.size = evilBytes.size.toLong()
                tar.putArchiveEntry(evilEntry)
                tar.write(evilBytes)
                tar.closeArchiveEntry()

                tar.finish()
            }

            // Extract using TarExtractor
            TarExtractor.extractTarGz(archiveFile, destDir)

            // Assertions
            val motd = File(destDir, "etc/motd")
            assertTrue("etc/motd should exist", motd.exists())
            assertEquals("Welcome to Pocketbox Alpine\n", motd.readText())

            val busybox = File(destDir, "bin/busybox")
            assertTrue("bin/busybox should exist", busybox.exists())
            assertEquals("binary-content-1234", busybox.readText())

            val ash = File(destDir, "bin/ash")
            assertTrue("bin/ash hard link should be materialized", ash.exists())
            assertEquals("binary-content-1234", ash.readText())

            // Security assertion: evil.txt must NOT exist outside destDir!
            val escapedFile = File(tempDir, "evil.txt")
            assertFalse("Path traversal attack must be blocked", escapedFile.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testInstalledCheckHandlesAbsoluteGuestSymlinkForBinSh() {
        val tempDir = Files.createTempDirectory("paths-test").toFile()
        try {
            val paths = RuntimePaths(File(tempDir, "files"), File(tempDir, "cache"))
            paths.ensureDirs()
            assertFalse(paths.installed())

            // Simulate Alpine rootfs where bin/sh is an absolute guest symlink -> /bin/busybox
            // (which does not exist at /bin/busybox on the Android host root)
            val binDir = File(paths.rootfs, "bin")
            binDir.mkdirs()
            Files.createSymbolicLink(
                File(binDir, "sh").toPath(),
                java.nio.file.Paths.get("/nonexistent_android_host_path/bin/busybox"),
            )
            assertTrue("rootfsExtracted should detect symlinked bin/sh", paths.rootfsExtracted())
            assertFalse("installed() must be false before stateFile is written", paths.installed())

            paths.stateFile.writeText("""{"schema":1,"opencodeVersion":"1.19.6"}""")
            assertTrue("installed() must be true once stateFile and rootfs exist", paths.installed())
            assertEquals("1.19.6", paths.installedOpencodeVersion())
            assertTrue("Should detect upgrade needed from 1.19.6 to 2.3.2", paths.needsOpencodeUpgrade("2.3.2"))
            assertFalse("Should not need upgrade when versions match", paths.needsOpencodeUpgrade("1.19.6"))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
