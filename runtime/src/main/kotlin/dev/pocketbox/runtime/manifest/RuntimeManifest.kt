package dev.pocketbox.runtime.manifest

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Pinned versions / URLs / checksums for everything the runtime downloads.
 * Shipped in assets/runtime-manifest.json; every value must be verified
 * upstream before being committed (see brief section 7.2).
 */
@Serializable
data class RuntimeManifest(
    val schema: Int = 1,
    val abis: Map<String, AbiEntry>,
    val apkBase: List<String>,
    val apkPacks: Map<String, List<String>> = emptyMap(),
) {
    @Serializable
    data class AbiEntry(
        val alpine: Artifact,
        val opencode: Artifact,
    )

    @Serializable
    data class Artifact(
        val version: String,
        val url: String,
        val sha256: String,
        val sizeBytes: Long = 0,
    )

    fun forAbi(abi: String): AbiEntry? = abis[abi]

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): RuntimeManifest = json.decodeFromString(text)
    }
}
