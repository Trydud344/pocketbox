package dev.pocketbox.runtime.supervisor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

class SupervisorLogicTest {

    @Test
    fun testRuntimeStatusDefaults() {
        val status = RuntimeStatus()
        assertEquals(ServerState.NOT_INSTALLED, status.state)
        assertEquals(4096, status.port)
        assertEquals(null, status.version)
        assertEquals(null, status.startedAt)
        assertEquals(null, status.errorMessage)
        assertEquals(null, status.memoryUsageBytes)
    }

    @Test
    fun testBackoffProgression() {
        // Exponential backoff logic from brief: (1L shl minOf(n, 7)) * 1000L
        fun calcBackoff(failures: Int): Long {
            val n = minOf(failures, 7)
            return (1L shl n) * 1000L
        }

        assertEquals(2000L, calcBackoff(1))   // 2s
        assertEquals(4000L, calcBackoff(2))   // 4s
        assertEquals(8000L, calcBackoff(3))   // 8s
        assertEquals(16000L, calcBackoff(4))  // 16s
        assertEquals(32000L, calcBackoff(5))  // 32s
        assertEquals(64000L, calcBackoff(6))  // 64s
        assertEquals(128000L, calcBackoff(7)) // 128s cap
        assertEquals(128000L, calcBackoff(10)) // capped at 128s
    }

    @Test
    fun testLogRotatorTail() {
        val tempDir = Files.createTempDirectory("log-test").toFile()
        try {
            val logFile = File(tempDir, "server.log")
            val rotator = LogRotator(logFile)

            for (i in 1..100) {
                rotator.append("line $i\n")
            }

            val tail10 = rotator.tail(10)
            val lines = tail10.trim().split("\n")
            assertEquals(10, lines.size)
            assertEquals("line 91", lines.first())
            assertEquals("line 100", lines.last())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testPortDetection() {
        // Find a free port by binding a temporary server socket
        val socket = ServerSocket(0)
        val boundPort = socket.localPort

        fun findFreePort(startPort: Int): Int {
            var p = startPort
            repeat(50) {
                try {
                    ServerSocket().use { s ->
                        s.bind(java.net.InetSocketAddress("127.0.0.1", p))
                        return p
                    }
                } catch (_: Exception) {
                    p++
                }
            }
            return startPort
        }

        try {
            val chosen = findFreePort(boundPort)
            // Since boundPort is busy, chosen must be > boundPort
            assertTrue("Expected port to advance past busy port: $chosen > $boundPort", chosen > boundPort)
        } finally {
            socket.close()
        }
    }
}
