package dev.pocketbox.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pocketbox.runtime.install.InstallEvent
import dev.pocketbox.runtime.install.InstallPhase
import dev.pocketbox.runtime.install.InstallStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(
    installer: dev.pocketbox.runtime.install.Installer,
    onComplete: () -> Unit,
) {
    var phase by remember { mutableStateOf(InstallPhase.IDLE) }
    var step by remember { mutableStateOf<InstallStep?>(null) }
    var stepIndex by remember { mutableIntStateOf(0) }
    var log by remember { mutableStateOf("") }
    var downloadPct by remember { mutableFloatStateOf(0f) }
    var downloadLabel by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun start() {
        error = null
        phase = InstallPhase.RUNNING
        log = ""
        job = CoroutineScope(Dispatchers.IO).launch {
            try {
                installer.install().collectLatest { ev ->
                    when (ev) {
                        is InstallEvent.StepChanged -> {
                            step = ev.step
                            stepIndex = ev.index
                        }
                        is InstallEvent.DownloadProgress -> {
                            downloadLabel = ev.what
                            downloadPct =
                                if (ev.totalBytes > 0) ev.downloadedBytes.toFloat() / ev.totalBytes else 0f
                        }
                        is InstallEvent.Log -> log = (log + ev.message + "\n").takeLast(4000)
                        is InstallEvent.Failed -> {
                            error = ev.message
                            phase = InstallPhase.FAILED
                        }
                        InstallEvent.Completed -> {
                            phase = InstallPhase.DONE
                            onComplete()
                        }
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                phase = InstallPhase.FAILED
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
        Text("Set up your Pocketbox", style = MaterialTheme.typography.headlineMedium)
        Text(
            "This will download and install a private OpenCode server that runs entirely on this device:\n" +
                "• Alpine Linux base (~4 MB)\n" +
                "• OpenCode (~80 MB) + tools (git, ripgrep, curl, ssh)\n\n" +
                "Everything stays on your phone. A random password is generated for the local server.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (phase == InstallPhase.RUNNING) {
            LinearProgressIndicator(
                progress = { if (stepIndex >= 2 && stepIndex <= 3) downloadPct else stepIndex / 8f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = step?.name?.lowercase()?.replace('_', ' ') ?: "preparing",
                style = MaterialTheme.typography.labelLarge,
            )
            if (downloadLabel.isNotEmpty() && stepIndex in 1..2) {
                Text("$downloadLabel — ${(downloadPct * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                log,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.heightIn(max = 220.dp),
            )
            OutlinedButton(
                onClick = {
                    job?.cancel()
                    phase = InstallPhase.IDLE
                },
            ) { Text("Cancel") }
        }

        if (phase == InstallPhase.IDLE) {
            Button(onClick = { start() }, modifier = Modifier.fillMaxWidth()) {
                Text("Set up now")
            }
        }

        if (phase == InstallPhase.FAILED) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Install failed", style = MaterialTheme.typography.titleMedium)
                    Text(error ?: "unknown error", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { start() }) { Text("Retry") }
                }
            }
        }
    }
}
