package dev.pocketbox.runtime.install

import java.io.File
import java.nio.file.Files

/**
 * Manages guest-side mountpoint directories and OpenCode agent discovery hints
 * for Android shared internal storage mounted into the Alpine container.
 */
object AndroidStorageConfig {

    const val GUEST_SDCARD_PATH = "/sdcard"
    const val ENV_ANDROID_STORAGE = "ANDROID_STORAGE"
    const val AGENTS_BLOCK_START = "<!-- pocketbox:android-storage:start -->"
    const val AGENTS_BLOCK_END = "<!-- pocketbox:android-storage:end -->"

    /**
     * Pre-creates normal mountpoint directories inside [RuntimePaths.rootfs] for
     * each active [BindMount] so PRoot skips `build_glue()` (avoiding leftover
     * `0000` placeholder dirs on SIGKILL and ensuring `ls /` lists the mount).
     *
     * When `/sdcard` is not in [extraBinds], removes `rootfs/sdcard` ONLY if it is
     * a non-symlink, completely empty directory.
     */
    fun prepareMountPoints(paths: RuntimePaths, extraBinds: List<BindMount>) {
        if (!paths.rootfs.exists()) return

        for (bind in extraBinds) {
            val rel = bind.guestPath.trimStart('/')
            if (rel.isEmpty()) continue
            val mountDir = File(paths.rootfs, rel)
            val mountPath = mountDir.toPath()
            if (!Files.isSymbolicLink(mountPath) && !mountDir.exists()) {
                mountDir.mkdirs()
                mountDir.setReadable(true, false)
                mountDir.setExecutable(true, false)
                mountDir.setWritable(true, true)
            }
        }

        val hasSdcard = extraBinds.any { it.guestPath == GUEST_SDCARD_PATH }
        if (!hasSdcard) {
            cleanupEmptyMountPoint(paths, GUEST_SDCARD_PATH)
        }
    }

    private fun cleanupEmptyMountPoint(paths: RuntimePaths, guestPath: String) {
        val rel = guestPath.trimStart('/')
        if (rel.isEmpty()) return
        val dir = File(paths.rootfs, rel)
        val path = dir.toPath()
        if (Files.isSymbolicLink(path) || !dir.isDirectory) return
        val entries = dir.list()
        if (entries != null && entries.isEmpty()) {
            dir.delete()
        }
    }

    /**
     * Upserts or removes Pocketbox's managed Android-storage section in
     * `/root/.config/opencode/AGENTS.md` without disturbing user-authored rules.
     */
    fun syncAgentInstructions(paths: RuntimePaths, storageMountPath: String?) {
        if (!paths.rootfs.exists()) return
        val agentsFile = File(paths.rootfs, "root/.config/opencode/AGENTS.md")

        if (storageMountPath != null) {
            val block = buildAgentBlock(storageMountPath)
            val existing = if (agentsFile.exists() && !Files.isSymbolicLink(agentsFile.toPath())) {
                agentsFile.readText()
            } else {
                ""
            }
            val updated = upsertBlock(existing, block)
            agentsFile.parentFile?.mkdirs()
            agentsFile.writeText(updated)
        } else {
            if (!agentsFile.exists() || Files.isSymbolicLink(agentsFile.toPath())) return
            val existing = agentsFile.readText()
            val stripped = stripBlock(existing) ?: return
            if (stripped.isBlank()) {
                agentsFile.delete()
            } else {
                agentsFile.writeText(stripped)
            }
        }
    }

    private fun buildAgentBlock(mountPath: String): String = """
        $AGENTS_BLOCK_START
        ## Android Device Storage

        The host Android device's shared internal storage is mounted at `$mountPath` (also exposed via `${'$'}$ENV_ANDROID_STORAGE`).
        Common locations include:
        - `$mountPath/Documents` — documents and PDFs
        - `$mountPath/Download` — downloaded files
        - `$mountPath/DCIM` — camera photos and videos
        - `$mountPath/Pictures` — images and screenshots
        - `$mountPath/Music`, `$mountPath/Movies`, `$mountPath/Podcasts`

        This mount provides live read/write access to real files on the user's phone. Always confirm with the user before deleting, moving, or overwriting existing files in `$mountPath`.
        $AGENTS_BLOCK_END
    """.trimIndent()

    private fun upsertBlock(existing: String, block: String): String {
        val startIdx = existing.indexOf(AGENTS_BLOCK_START)
        val endIdx = existing.indexOf(AGENTS_BLOCK_END)
        if (startIdx >= 0 && endIdx >= startIdx) {
            val before = existing.substring(0, startIdx).trimEnd()
            val after = existing.substring(endIdx + AGENTS_BLOCK_END.length).trimStart()
            return buildString {
                if (before.isNotEmpty()) {
                    append(before)
                    append("\n\n")
                }
                append(block)
                if (after.isNotEmpty()) {
                    append("\n\n")
                    append(after)
                }
                append('\n')
            }
        }
        val trimmed = existing.trimEnd()
        return if (trimmed.isEmpty()) {
            "$block\n"
        } else {
            "$trimmed\n\n$block\n"
        }
    }

    /** Returns null if no managed block was present. */
    private fun stripBlock(existing: String): String? {
        val startIdx = existing.indexOf(AGENTS_BLOCK_START)
        val endIdx = existing.indexOf(AGENTS_BLOCK_END)
        if (startIdx < 0 || endIdx < startIdx) return null
        val before = existing.substring(0, startIdx).trimEnd()
        val after = existing.substring(endIdx + AGENTS_BLOCK_END.length).trimStart()
        return when {
            before.isEmpty() && after.isEmpty() -> ""
            before.isEmpty() -> "$after\n"
            after.isEmpty() -> "$before\n"
            else -> "$before\n\n$after\n"
        }
    }
}
