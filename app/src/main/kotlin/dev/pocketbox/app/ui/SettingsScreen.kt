package dev.pocketbox.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.pocketbox.app.settings.SettingsStore
import dev.pocketbox.runtime.backup.BackupManager
import dev.pocketbox.runtime.install.PRootOneShot
import dev.pocketbox.runtime.install.RuntimePaths
import dev.pocketbox.runtime.manifest.RuntimeManifest
import dev.pocketbox.runtime.supervisor.RuntimeStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SettingsScreen(
    settings: SettingsStore,
    paths: RuntimePaths,
    manifest: RuntimeManifest,
    abi: String,
    status: RuntimeStatus,
    manifestVersion: String,
    onApplyDns: (String) -> Unit,
    onBack: () -> Unit,
    onOpenRunCommand: () -> Unit,
    onResetRuntime: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val port by settings.port.collectAsState()
    val wifiOnly by settings.wifiOnly.collectAsState()
    val startOnBoot by settings.startOnBoot.collectAsState()
    val keepAwake by settings.keepCpuAwake.collectAsState()
    val dns by settings.dns.collectAsState()
    val bindLan by settings.bindLan.collectAsState()
    val noSeccomp by settings.noSeccomp.collectAsState()
    var portText by remember(port) { mutableStateOf(port.toString()) }
    var dnsText by remember(dns) { mutableStateOf(dns) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var showLanWarning by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showBackupWarning by remember { mutableStateOf(false) }
    var showLicensesDialog by remember { mutableStateOf(false) }
    var pendingImportTarget by remember { mutableStateOf<String?>(null) }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gzip")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                statusMsg = "Creating backup…"
                val res = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            BackupManager.exportRoot(paths, out)
                        } ?: BackupManager.BackupResult(false, "Could not open destination")
                    } catch (e: Exception) {
                        BackupManager.BackupResult(false, e.message ?: "backup failed")
                    }
                }
                statusMsg = res.message
            }
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                statusMsg = "Restoring backup…"
                val res = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            BackupManager.importRoot(paths, input)
                        } ?: BackupManager.BackupResult(false, "Could not open file")
                    } catch (e: Exception) {
                        BackupManager.BackupResult(false, e.message ?: "restore failed")
                    }
                }
                statusMsg = res.message
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val target = pendingImportTarget
        pendingImportTarget = null
        if (uri != null && target != null) {
            scope.launch {
                statusMsg = "Importing…"
                val res = withContext(Dispatchers.IO) {
                    try {
                        val tmp = File(context.cacheDir, "import-tmp")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            tmp.outputStream().use { input.copyTo(it) }
                        } ?: return@withContext "Could not open file"
                        val installer = dev.pocketbox.runtime.install.Installer(paths, manifest, abi)
                        installer.importFile(tmp, target)
                        tmp.delete()
                        "Imported to $target"
                    } catch (e: Exception) {
                        e.message ?: "import failed"
                    }
                }
                statusMsg = res
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = portText,
            onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
            label = { Text("Server port") },
            supportingText = { Text("Persisted across restarts. Default 4096. Restart the server to apply.") },
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { portText.toIntOrNull()?.let { settings.setPort(it) } }) {
                Text("Save port")
            }
            OutlinedButton(onClick = { settings.setPort(RuntimeStatus.DEFAULT_PORT); portText = "4096" }) {
                Text("Reset to 4096")
            }
        }

        SettingSwitch("Wi-Fi only downloads", wifiOnly) { settings.setWifiOnly(it) }
        SettingSwitch("Start on boot", startOnBoot) { settings.setStartOnBoot(it) }
        SettingSwitch("Keep CPU awake while running", keepAwake) { settings.setKeepCpuAwake(it) }

        SettingSwitch("Bind to LAN (0.0.0.0) — advanced", bindLan) { checked ->
            if (checked) showLanWarning = true else settings.setBindLan(false)
        }
        SettingSwitch("Disable seccomp in PRoot (compatibility)", noSeccomp) { settings.setNoSeccomp(it) }

        OutlinedTextField(
            value = dnsText,
            onValueChange = { dnsText = it },
            label = { Text("Guest DNS (resolv.conf)") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = {
            settings.setDns(dnsText)
            onApplyDns(dnsText)
            statusMsg = "DNS updated (applies inside container)."
        }) { Text("Save DNS") }

        OutlinedButton(onClick = {
            val i = android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:dev.pocketbox.app"),
            )
            try { context.startActivity(i) } catch (_: Exception) { }
        }) { Text("Request battery optimization exemption") }

        HorizontalDivider()
        Text("Backup & restore", style = MaterialTheme.typography.titleMedium)
        Text(
            "Exports/imports /root (projects, OpenCode config, auth). Archives may contain API keys — store them securely.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showBackupWarning = true }) { Text("Export backup") }
            OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("*/*")) }) { Text("Restore backup") }
        }

        HorizontalDivider()
        Text("Extra packages", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch {
                    statusMsg = "Installing node pack…"
                    val r = withContext(Dispatchers.IO) {
                        dev.pocketbox.runtime.install.Installer(paths, manifest, abi).installPack("node")
                    }
                    statusMsg = "node: exit ${r.exitCode}\n${r.output.takeLast(800)}"
                }
            }) { Text("Node.js") }
            OutlinedButton(onClick = {
                scope.launch {
                    statusMsg = "Installing python pack…"
                    val r = withContext(Dispatchers.IO) {
                        dev.pocketbox.runtime.install.Installer(paths, manifest, abi).installPack("python")
                    }
                    statusMsg = "python: exit ${r.exitCode}\n${r.output.takeLast(800)}"
                }
            }) { Text("Python") }
            OutlinedButton(onClick = {
                scope.launch {
                    statusMsg = "Installing build pack…"
                    val r = withContext(Dispatchers.IO) {
                        dev.pocketbox.runtime.install.Installer(paths, manifest, abi).installPack("build")
                    }
                    statusMsg = "build: exit ${r.exitCode}\n${r.output.takeLast(800)}"
                }
            }) { Text("Build tools") }
        }

        HorizontalDivider()
        Text("Advanced", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onOpenRunCommand) { Text("Run command in container…") }
        OutlinedButton(onClick = {
            pendingImportTarget = "/root/.local/share/opencode/auth.json"
            importLauncher.launch(arrayOf("*/*"))
        }) { Text("Import auth/config file to /root/.local/share/opencode/auth.json") }
        OutlinedButton(onClick = {
            pendingImportTarget = "/root/.config/opencode/opencode.json"
            importLauncher.launch(arrayOf("*/*"))
        }) { Text("Import opencode.json config") }
        Text(
            "If a client app cannot configure AI providers, import the provider auth/config " +
                "files into the container paths above, or use the Run command screen for pb-doctor.",
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()
        Text("Runtime", style = MaterialTheme.typography.titleMedium)
        Text("OpenCode pinned version: $manifestVersion", style = MaterialTheme.typography.bodySmall)
        Text(
            "Runtime state: ${status.state.name.lowercase().replace('_', ' ')}",
            style = MaterialTheme.typography.bodySmall,
        )

        Button(
            onClick = { showResetConfirm = true },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) { Text("Reset runtime (deletes everything)") }

        Text(
            "Reset removes the Alpine rootfs, all projects, logs and configuration on this device. " +
                "Export a backup first if you need your data.",
            style = MaterialTheme.typography.bodySmall,
        )

        statusMsg?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }

        HorizontalDivider()
        Text("About", style = MaterialTheme.typography.titleMedium)
        Text(
            "Pocketbox is a runtime host that runs a private OpenCode server on this phone inside an Alpine userland (PRoot). " +
                "It is not a chat client. Open the server with any OpenCode remote-access client at http://127.0.0.1:<port>.\n\n" +
                "Pocketbox is not affiliated with or endorsed by OpenCode or any AI provider. " +
                "Bring your own provider access. No telemetry, ever.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "If the server keeps dying in the background, your vendor's battery manager may be " +
                "killing it. See dontkillmyapp.com for your device, and on Android 12+ consider " +
                "the phantom-process workaround (developer option “Disable child process " +
                "restrictions” where available).",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedButton(onClick = { showLicensesDialog = true }) {
            Text("Open-source notices & licenses")
        }

        TextButton(onClick = onBack) { Text("Done") }
    }

    if (showLicensesDialog) {
        AlertDialog(
            onDismissRequest = { showLicensesDialog = false },
            title = { Text("Open-Source Notices") },
            text = {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Pocketbox: MIT License.\n\n" +
                            "PRoot: GNU General Public License v2 (GPLv2).\n" +
                            "Source code offer: In compliance with GPLv2, full source code and build scripts for the " +
                            "PRoot binary packaged with this app are available at https://github.com/proot-me/proot " +
                            "and in the runtime/native directory of the Pocketbox repository.\n\n" +
                            "libtalloc: GNU Lesser General Public License (LGPLv3+).\n\n" +
                            "OpenCode: MIT License (https://github.com/anomalyco/opencode).\n\n" +
                            "Alpine Linux: Multi-license (GPL, BSD, MIT). See alpine-minirootfs license documentation.\n\n" +
                            "ZXing: Apache License 2.0.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLicensesDialog = false }) { Text("Close") }
            },
        )
    }

    if (showLanWarning) {
        AlertDialog(
            onDismissRequest = { showLanWarning = false },
            title = { Text("Bind to LAN?") },
            text = {
                Text(
                    "This will make the server reachable from other devices on your network " +
                        "(0.0.0.0). The password is still required, but this increases exposure. " +
                        "Only enable on trusted networks. Restart the server to apply."
                )
            },
            confirmButton = {
                TextButton(onClick = { settings.setBindLan(true); showLanWarning = false }) { Text("Enable") }
            },
            dismissButton = {
                TextButton(onClick = { showLanWarning = false }) { Text("Cancel") }
            },
        )
    }
    if (showBackupWarning) {
        AlertDialog(
            onDismissRequest = { showBackupWarning = false },
            title = { Text("Export backup") },
            text = { Text("The backup archive will contain projects, OpenCode config, and API keys/auth stored in /root. Store it securely.") },
            confirmButton = {
                TextButton(onClick = {
                    showBackupWarning = false
                    backupLauncher.launch("pocketbox-backup.tar.gz")
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { showBackupWarning = false }) { Text("Cancel") } },
        )
    }
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset runtime?") },
            text = { Text("This deletes the Alpine rootfs, all projects, logs and configuration on this device. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { showResetConfirm = false; onResetRuntime() }) { Text("Delete everything") }
            },
            dismissButton = { TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
