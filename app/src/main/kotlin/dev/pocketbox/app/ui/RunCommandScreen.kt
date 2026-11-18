package dev.pocketbox.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.pocketbox.runtime.install.PRootOneShot
import dev.pocketbox.runtime.install.RuntimePaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Bounded "Run command" screen (brief 9.5b): one-shot commands inside the
 * container with output shown. Intended for `opencode auth login` and
 * diagnostics (pb-doctor) on clients that cannot configure providers
 * themselves. Not a terminal: no PTY, no interactivity, 10-minute timeout.
 */
@Composable
fun RunCommandScreen(paths: RuntimePaths, abi: String, onBack: () -> Unit) {
    var command by remember { mutableStateOf("/usr/local/bin/pb-doctor") }
    var output by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Run command in container", style = MaterialTheme.typography.headlineSmall)
        Text(
            "One-shot, non-interactive, 10 minute timeout. Runs as root inside the Alpine " +
                "container through PRoot. Examples: pb-doctor, opencode auth login (will exit " +
                "immediately if it needs interaction), git --version.",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            label = { Text("Command") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    running = true
                    output = ""
                    scope.launch(Dispatchers.IO) {
                        val result = try {
                            PRootOneShot.exec(
                                paths = paths,
                                abi = abi,
                                command = command.trim(),
                            )
                        } catch (e: Exception) {
                            dev.pocketbox.runtime.install.ExecResult(-1, e.message ?: "error")
                        }
                        output = "exit code: ${result.exitCode}\n\n${result.output}"
                        running = false
                    }
                },
                enabled = !running && command.isNotBlank(),
            ) { Text(if (running) "Running…" else "Run") }
            OutlinedButton(onClick = onBack, enabled = !running) { Text("Back") }
        }

        if (output.isNotEmpty()) {
            Text(
                output.takeLast(20_000),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
