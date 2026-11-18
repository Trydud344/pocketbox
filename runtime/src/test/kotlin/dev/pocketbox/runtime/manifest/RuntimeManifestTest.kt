package dev.pocketbox.runtime.manifest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeManifestTest {

    private val sampleJson = """
    {
      "schema": 1,
      "abis": {
        "arm64-v8a": {
          "alpine": {
            "version": "3.24.4",
            "url": "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.4-aarch64.tar.gz",
            "sha256": "4b14c18f7e90a063b61462ea1462a5732c154bf6adad594974fe6487cd98a8de",
            "sizeBytes": 4029843
          },
          "opencode": {
            "version": "1.19.6",
            "url": "https://github.com/anomalyco/opencode/releases/download/v1.19.6/opencode-linux-arm64-musl.tar.gz",
            "sha256": "45407388777dfc1894591c9727c169f202ad92c75757038950630a0fbb12ca97",
            "sizeBytes": 78461019
          }
        },
        "x86_64": {
          "alpine": {
            "version": "3.24.4",
            "url": "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/x86_64/alpine-minirootfs-3.24.4-x86_64.tar.gz",
            "sha256": "cdaa38cb8de3537967ee5b6c9b6cd00cc962da2b018e6eea48696bb274923012",
            "sizeBytes": 3704088
          },
          "opencode": {
            "version": "1.19.6",
            "url": "https://github.com/anomalyco/opencode/releases/download/v1.19.6/opencode-linux-x64-musl.tar.gz",
            "sha256": "2a22da39d0b69681272f4daeb51316244eda5c3d606d9e507d61bbf5c1b868a2",
            "sizeBytes": 79891356
          }
        }
      },
      "apkBase": ["bash", "git", "curl", "ca-certificates"],
      "apkPacks": {
        "node": ["nodejs", "npm"],
        "python": ["python3", "py3-pip"],
        "build": ["build-base"]
      },
      "extraUnknownField": "should-be-safely-ignored"
    }
    """.trimIndent()

    @Test
    fun testParseManifest() {
        val manifest = RuntimeManifest.parse(sampleJson)

        assertEquals(1, manifest.schema)
        assertEquals(2, manifest.abis.size)

        val arm64 = manifest.forAbi("arm64-v8a")
        assertNotNull(arm64)
        assertEquals("3.24.4", arm64!!.alpine.version)
        assertEquals(
            "4b14c18f7e90a063b61462ea1462a5732c154bf6adad594974fe6487cd98a8de",
            arm64.alpine.sha256,
        )
        assertEquals(4029843L, arm64.alpine.sizeBytes)

        assertEquals("1.19.6", arm64.opencode.version)
        assertEquals(
            "45407388777dfc1894591c9727c169f202ad92c75757038950630a0fbb12ca97",
            arm64.opencode.sha256,
        )

        val x86 = manifest.forAbi("x86_64")
        assertNotNull(x86)
        assertEquals("3.24.4", x86!!.alpine.version)

        assertNull(manifest.forAbi("armeabi-v7a"))

        assertTrue(manifest.apkBase.contains("git"))
        assertTrue(manifest.apkBase.contains("curl"))

        assertEquals(listOf("nodejs", "npm"), manifest.apkPacks["node"])
        assertEquals(listOf("python3", "py3-pip"), manifest.apkPacks["python"])
        assertEquals(listOf("build-base"), manifest.apkPacks["build"])
    }
}
