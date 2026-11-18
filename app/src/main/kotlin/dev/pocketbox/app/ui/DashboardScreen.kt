package dev.pocketbox.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.pocketbox.runtime.supervisor.RuntimeStatus
import dev.pocketbox.runtime.supervisor.ServerState

@Composable
fun DashboardScreen(
    status: RuntimeStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onOpenPairing: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenSettings: () -> Unit,
    onRotatePassword: () -> Unit,
) {
    val context = LocalContext.current
    val installedClients = remember {
        listOf(
            "ai.opencode.mobilecode",
            "cc.agentlabs.opencode",
            "app.getopencode",
        ).filter {
            try {
                context.packageManager.getPackageInfo(it, 0); true
            } catch (_: Exception) {
                false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pocketbox", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, "Settings")
            }
        }

        StatusChip(status)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onStart,
                enabled = status.state in listOf(ServerState.STOPPED, ServerState.ERROR),
                modifier = Modifier.weight(1f),
            ) { Text("Start") }
            Button(
                onClick = onStop,
                enabled = status.state in listOf(
                    ServerState.RUNNING, ServerState.STARTING, ServerState.BACKOFF,
                ),
                modifier = Modifier.weight(1f),
            ) { Text("Stop") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onRestart, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Restart")
            }
            OutlinedButton(onClick = onRotatePassword, modifier = Modifier.weight(1f)) {
                Icon(Icons.AutoMirrored.Filled.Logout, null); Spacer(Modifier.width(6.dp)); Text("Rotate password")
            }
        }

        InfoCard("Server") {
            InfoLine("Address", "http://127.0.0.1:${status.port}")
            InfoLine("OpenCode", status.version ?: "—")
            status.startedAt?.let {
                InfoLine("Uptime", formatUptime(System.currentTimeMillis() - it))
            }
            status.memoryUsageBytes?.let {
                InfoLine("Memory", formatBytes(it))
            }
        }

        if (status.state == ServerState.ERROR) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Server error", style = MaterialTheme.typography.titleMedium)
                    Text(
                        status.errorMessage ?: "unknown",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Button(onClick = { copySensitive(context, "Diagnostics", status.errorMessage ?: "") }) {
                        Text("Copy diagnostics")
                    }
                }
            }
        }

        if (status.state == ServerState.RUNNING && installedClients.isNotEmpty()) {
            for (pkg in installedClients) {
                Button(
                    onClick = {
                        try {
                            context.startActivity(context.packageManager.getLaunchIntentForPackage(pkg))
                        } catch (_: Exception) {}
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open $pkg")
                }
            }
        }

        Button(onClick = onOpenPairing, modifier = Modifier.fillMaxWidth()) {
            Text("Connect a client app")
        }
        OutlinedButton(onClick = onOpenLogs, modifier = Modifier.fillMaxWidth()) { Text("Logs") }

        Text(
            "The server binds to 127.0.0.1 (this device only) and requires a password. " +
                "Other apps on this device could try to connect, which is why the password is long and random.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun formatBytes(bytes: Long): String {
    val mb = bytes.toDouble() / (1024 * 1024)
    return "%.1f MB".format(mb)
}

private fun formatUptime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return when {
        h > 0 -> "%dh %02dm".format(h, m)
        m > 0 -> "%dm %02ds".format(m, sec)
        else -> "%ds".format(sec)
    }
}

@Composable
private fun StatusChip(status: RuntimeStatus) {
    val (label, color) = when (status.state) {
        ServerState.RUNNING -> "Running" to Color(0xFF1B873B)
        ServerState.STARTING -> "Starting…" to Color(0xFFB26A00)
        ServerState.STOPPING -> "Stopping…" to Color(0xFFB26A00)
        ServerState.BACKOFF -> "Restarting…" to Color(0xFFB26A00)
        ServerState.ERROR -> "Error" to Color(0xFFB3261E)
        ServerState.INSTALLING -> "Installing…" to Color(0xFFB26A00)
        ServerState.STOPPED -> "Stopped" to Color(0xFF5F5F5F)
        ServerState.NOT_INSTALLED -> "Not installed" to Color(0xFF5F5F5F)
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = color.copy(alpha = 0.15f),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(50), color = color, modifier = Modifier.size(10.dp)) {}
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(110.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}
