package dev.pocketbox.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import dev.pocketbox.app.server.ServerService
import dev.pocketbox.app.ui.DashboardScreen
import dev.pocketbox.app.ui.LogsScreen
import dev.pocketbox.app.ui.PairingScreen
import dev.pocketbox.app.ui.SettingsScreen
import dev.pocketbox.app.ui.SetupScreen
import dev.pocketbox.runtime.install.RuntimePaths
import dev.pocketbox.runtime.supervisor.ServerState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    enum class Screen { DASHBOARD, PAIRING, LOGS, SETTINGS, RUNCOMMAND }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askNotificationsIfNeeded()

        val container = (application as PocketboxApp).container

        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                PocketboxUi(container)
            }
        }
    }

    private fun askNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun PocketboxUi(container: dev.pocketbox.app.AppContainer) {
    var screen by remember { mutableStateOf(MainActivity.Screen.DASHBOARD) }
    val status by container.supervisor.status.collectAsState()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Mark installed if we already have state (e.g. app restart while installed).
    // We do not auto-start the server here: the user controls Start/Stop (or the
    // QS tile / boot receiver). After setup completes, the setup flow starts it.
    LaunchedEffect(Unit) {
        if (container.paths.installed()) {
            container.supervisor.markInstalled(
                container.manifest.forAbi(RuntimePaths.deviceAbi())?.opencode?.version
            )
            // Ensure guest DNS matches user setting.
            container.installer.applyDns(container.settings.dns.value)
        }
    }

    when (screen) {
        MainActivity.Screen.DASHBOARD -> {
            if (status.state == ServerState.NOT_INSTALLED && !container.paths.installed()) {
                SetupScreen(installer = container.installer, onComplete = {
                    container.credentials.ensureCredentials()
                    container.supervisor.markInstalled(
                        container.manifest.forAbi(RuntimePaths.deviceAbi())?.opencode?.version
                    )
                    scope.launch { ServerService.start(context) }
                })
            } else {
                DashboardScreen(
                    status = status,
                    onStart = { ServerService.start(context) },
                    onStop = { ServerService.start(context, ServerService.ACTION_STOP) },
                    onRestart = { ServerService.start(context, ServerService.ACTION_RESTART) },
                    onOpenPairing = { screen = MainActivity.Screen.PAIRING },
                    onOpenLogs = { screen = MainActivity.Screen.LOGS },
                    onOpenSettings = { screen = MainActivity.Screen.SETTINGS },
                    onRotatePassword = {
                        container.credentials.rotate()
                        scope.launch {
                            container.supervisor.restart()
                        }
                    },
                )
            }
        }
        MainActivity.Screen.PAIRING -> PairingScreen(
            status = status,
            username = container.credentials.getUsername(),
            password = container.credentials.getPassword(),
            onClose = { screen = MainActivity.Screen.DASHBOARD },
        )
        MainActivity.Screen.LOGS -> LogsScreen(container.paths) { screen = MainActivity.Screen.DASHBOARD }
        MainActivity.Screen.SETTINGS -> SettingsScreen(
            settings = container.settings,
            paths = container.paths,
            manifest = container.manifest,
            abi = RuntimePaths.deviceAbi(),
            status = status,
            manifestVersion = container.manifest.forAbi(RuntimePaths.deviceAbi())?.opencode?.version ?: "?",
            onApplyDns = { dns -> container.installer.applyDns(dns) },
            onBack = { screen = MainActivity.Screen.DASHBOARD },
            onOpenRunCommand = { screen = MainActivity.Screen.RUNCOMMAND },
            onResetRuntime = {
                scope.launch {
                    container.supervisor.stop()
                    ServerService.stop(context)
                    container.paths.wipe()
                    screen = MainActivity.Screen.DASHBOARD
                }
            },
        )
        MainActivity.Screen.RUNCOMMAND -> dev.pocketbox.app.ui.RunCommandScreen(
            paths = container.paths,
            abi = RuntimePaths.deviceAbi(),
            onBack = { screen = MainActivity.Screen.SETTINGS },
        )
    }
}
