package dev.pocketbox.app.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.dataStore by preferencesDataStore(name = "pb_settings")

/** User settings (brief section 8.2.5). */
class SettingsStore(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private object Keys {
        val PORT = intPreferencesKey("port")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        val KEEP_CPU_AWAKE = booleanPreferencesKey("keep_cpu_awake")
        val DNS = stringPreferencesKey("dns")
        val BIND_LAN = booleanPreferencesKey("bind_lan")
        val NO_SECCOMP = booleanPreferencesKey("no_seccomp")
        val ANDROID_STORAGE_ENABLED = booleanPreferencesKey("android_storage_enabled")
    }

    // Snapshot values for synchronous access from non-suspend code.
    private val _port = MutableStateFlow(4096)
    private val _wifiOnly = MutableStateFlow(true)
    private val _startOnBoot = MutableStateFlow(false)
    private val _keepCpuAwake = MutableStateFlow(false)
    private val _dns = MutableStateFlow(DEFAULT_DNS)
    private val _bindLan = MutableStateFlow(false)
    private val _noSeccomp = MutableStateFlow(false)
    private val _androidStorageEnabled = MutableStateFlow(false)

    init {
        scope.launch {
            val p = context.dataStore.data.first()
            _port.value = p[Keys.PORT] ?: 4096
            _wifiOnly.value = p[Keys.WIFI_ONLY] ?: true
            _startOnBoot.value = p[Keys.START_ON_BOOT] ?: false
            _keepCpuAwake.value = p[Keys.KEEP_CPU_AWAKE] ?: false
            _dns.value = p[Keys.DNS] ?: DEFAULT_DNS
            _bindLan.value = p[Keys.BIND_LAN] ?: false
            _noSeccomp.value = p[Keys.NO_SECCOMP] ?: false
            _androidStorageEnabled.value = p[Keys.ANDROID_STORAGE_ENABLED] ?: false
        }
    }

    val port get() = _port
    val wifiOnly get() = _wifiOnly
    val startOnBoot get() = _startOnBoot
    val keepCpuAwake get() = _keepCpuAwake
    val dns get() = _dns
    val bindLan get() = _bindLan
    val noSeccomp get() = _noSeccomp
    val androidStorageEnabled get() = _androidStorageEnabled

    val portFlow: Flow<Int> = context.dataStore.data.map { it[Keys.PORT] ?: 4096 }
    val wifiOnlyFlow: Flow<Boolean> = context.dataStore.data.map { it[Keys.WIFI_ONLY] ?: true }
    val dnsFlow: Flow<String> = context.dataStore.data.map { it[Keys.DNS] ?: DEFAULT_DNS }
    val bindLanFlow: Flow<Boolean> = context.dataStore.data.map { it[Keys.BIND_LAN] ?: false }
    val noSeccompFlow: Flow<Boolean> = context.dataStore.data.map { it[Keys.NO_SECCOMP] ?: false }
    val androidStorageEnabledFlow: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.ANDROID_STORAGE_ENABLED] ?: false }

    companion object {
        const val DEFAULT_DNS = "nameserver 1.1.1.1\nnameserver 8.8.8.8\n"
    }

    fun setPort(value: Int) {
        _port.value = value
        scope.launch { context.dataStore.edit { it[Keys.PORT] = value } }
    }

    fun setWifiOnly(value: Boolean) {
        _wifiOnly.value = value
        scope.launch { context.dataStore.edit { it[Keys.WIFI_ONLY] = value } }
    }

    fun setStartOnBoot(value: Boolean) {
        _startOnBoot.value = value
        scope.launch { context.dataStore.edit { it[Keys.START_ON_BOOT] = value } }
    }

    fun setKeepCpuAwake(value: Boolean) {
        _keepCpuAwake.value = value
        scope.launch { context.dataStore.edit { it[Keys.KEEP_CPU_AWAKE] = value } }
    }

    fun setDns(value: String) {
        _dns.value = value
        scope.launch { context.dataStore.edit { it[Keys.DNS] = value } }
    }

    fun setBindLan(value: Boolean) {
        _bindLan.value = value
        scope.launch { context.dataStore.edit { it[Keys.BIND_LAN] = value } }
    }

    fun setNoSeccomp(value: Boolean) {
        _noSeccomp.value = value
        scope.launch { context.dataStore.edit { it[Keys.NO_SECCOMP] = value } }
    }

    fun setAndroidStorageEnabled(value: Boolean) {
        _androidStorageEnabled.value = value
        scope.launch { context.dataStore.edit { it[Keys.ANDROID_STORAGE_ENABLED] = value } }
    }
}
