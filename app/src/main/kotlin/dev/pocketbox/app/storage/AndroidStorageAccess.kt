package dev.pocketbox.app.storage

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.pocketbox.runtime.install.AndroidStorageConfig
import dev.pocketbox.runtime.install.BindMount
import java.io.File

/**
 * OS-version-aware helper for checking/requesting Android shared internal
 * storage access and resolving active PRoot bind mounts (`/sdcard`).
 */
object AndroidStorageAccess {

    fun hasPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val read = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
            val write = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
            read && write
        }
    }

    /**
     * Primary shared external storage root (typically `/storage/emulated/0`,
     * or `/storage/emulated/<userId>` for secondary Android profiles).
     */
    @Suppress("DEPRECATION")
    fun externalStorageRoot(): File = Environment.getExternalStorageDirectory()

    /**
     * Opens Android's "All files access" settings screen on API 30+.
     * Returns true if an activity was successfully launched.
     */
    fun launchAllFilesAccessSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val appIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(appIntent)
            true
        } catch (_: ActivityNotFoundException) {
            val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(fallback)
                true
            } catch (_: Exception) {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Resolves the active PRoot bind mounts when Android storage access is both
     * enabled by the user and currently granted by Android.
     */
    fun resolveActiveBindMounts(context: Context, enabled: Boolean): List<BindMount> {
        if (!enabled || !hasPermission(context)) return emptyList()
        val root = externalStorageRoot()
        val hostPath = root.absolutePath
        if (!root.exists() || !root.isDirectory || ':' in hostPath) return emptyList()
        return listOf(
            BindMount(
                hostPath = hostPath,
                guestPath = AndroidStorageConfig.GUEST_SDCARD_PATH,
            ),
        )
    }

    fun guestEnvFor(binds: List<BindMount>): Map<String, String> {
        val sdcard = binds.firstOrNull { it.guestPath == AndroidStorageConfig.GUEST_SDCARD_PATH }
            ?: return emptyMap()
        return mapOf(AndroidStorageConfig.ENV_ANDROID_STORAGE to sdcard.guestPath)
    }
}
