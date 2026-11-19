package dev.pocketbox.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.pocketbox.app.server.ServerService
import dev.pocketbox.app.settings.SettingsStore
import dev.pocketbox.app.storage.AndroidStorageAccess
import dev.pocketbox.runtime.install.AndroidStorageConfig
import dev.pocketbox.runtime.install.BindMount
import dev.pocketbox.runtime.install.Installer
import dev.pocketbox.runtime.install.NativeLibs
import dev.pocketbox.runtime.install.RuntimePaths
import dev.pocketbox.runtime.manifest.RuntimeManifest
import dev.pocketbox.runtime.supervisor.Supervisor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom

class PocketboxApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        NativeLibs.init(File(applicationInfo.nativeLibraryDir))
        createChannels()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                ServerService.CHANNEL_STATUS,
                getString(R.string.notif_channel_status),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.notif_channel_status_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                ServerService.CHANNEL_ERRORS,
                getString(R.string.notif_channel_errors),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = getString(R.string.notif_channel_errors_desc) }
        )
    }
}

/** Poor man's DI: one instance of everything, owned by the Application. */
class AppContainer(private val app: Application) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val paths = RuntimePaths(app)
    val settings = SettingsStore(app)

    val manifest: RuntimeManifest by lazy {
        val text = app.assets.open("runtime-manifest.json").bufferedReader().use { it.readText() }
        RuntimeManifest.parse(text)
    }

    val credentials = CredentialsStore(app)

    val installer: Installer by lazy {
        Installer(
            paths = paths,
            manifest = manifest,
            wifiOnly = { settings.wifiOnly.value },
            onMetered = {
                try {
                    val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
                    cm?.isActiveNetworkMetered == true
                } catch (_: SecurityException) {
                    false
                }
            },
        )
    }

    val supervisor = Supervisor(
        paths = paths,
        scope = appScope,
        credentialsProvider = { credentials.getUsername() to credentials.getPassword() },
        portProvider = { settings.port.value },
        bindHostProvider = { if (settings.bindLan.value) "0.0.0.0" else "127.0.0.1" },
        hostEnvProvider = { activeHostEnv() },
        extraBindsProvider = {
            val enabled = settings.androidStorageEnabledFlow.first()
            AndroidStorageAccess.resolveActiveBindMounts(app, enabled)
        },
    )

    fun activeBindMounts(enabled: Boolean = settings.androidStorageEnabled.value): List<BindMount> =
        AndroidStorageAccess.resolveActiveBindMounts(app, enabled)

    fun activeGuestEnv(binds: List<BindMount>): Map<String, String> =
        AndroidStorageAccess.guestEnvFor(binds)

    fun activeHostEnv(): Map<String, String> =
        if (settings.noSeccomp.value) mapOf("PROOT_NO_SECCOMP" to "1") else emptyMap()

    suspend fun syncAndroidStorageConfig(enabled: Boolean = settings.androidStorageEnabled.value) =
        withContext(Dispatchers.IO) {
            val binds = AndroidStorageAccess.resolveActiveBindMounts(app, enabled)
            AndroidStorageConfig.prepareMountPoints(paths, binds)
            val sdcard = binds.firstOrNull { it.guestPath == AndroidStorageConfig.GUEST_SDCARD_PATH }
            AndroidStorageConfig.syncAgentInstructions(paths, sdcard?.guestPath)
        }
}

/**
 * Server credentials: random >= 24 chars password generated at first install,
 * stored in Keystore-backed encrypted prefs (brief sections 5.4 and 9.2).
 */
class CredentialsStore(context: Context) {

    private val prefs by lazy {
        fun createEncrypted(): android.content.SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                "pb_credentials",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
        try {
            createEncrypted()
        } catch (_: Exception) {
            try {
                context.deleteSharedPreferences("pb_credentials")
                createEncrypted()
            } catch (_: Exception) {
                context.getSharedPreferences("pb_credentials_fallback", Context.MODE_PRIVATE)
            }
        }
    }

    companion object {
        private const val KEY_USER = "username"
        private const val KEY_PASS = "password"
        const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#%^*-_=+"
        const val USERNAME_DEFAULT = "opencode"
        const val PASSWORD_LENGTH = 32

        fun generatePassword(random: SecureRandom = SecureRandom()): String {
            return buildString {
                repeat(PASSWORD_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
            }
        }
    }

    private val random = SecureRandom()

    fun getUsername(): String = prefs.getString(KEY_USER, null) ?: USERNAME_DEFAULT

    fun ensureCredentials(): String {
        val existing = prefs.getString(KEY_PASS, null)
        if (existing != null) return existing
        val generated = generatePassword(random)
        prefs.edit().putString(KEY_USER, USERNAME_DEFAULT).putString(KEY_PASS, generated).apply()
        return generated
    }

    fun getPassword(): String = prefs.getString(KEY_PASS, null) ?: ensureCredentials()

    /** "Rotate password" (brief 9.2): replace and restart. */
    fun rotate(): String {
        val generated = generatePassword(random)
        prefs.edit().putString(KEY_PASS, generated).apply()
        return generated
    }
}
