package dev.pocketbox.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.pocketbox.runtime.install.RuntimePaths
import java.io.File

@Composable
fun LogsScreen(paths: RuntimePaths, onBack: () -> Unit) {
    val context = LocalContext.current
    var logText by remember { mutableStateOf(loadLog(paths)) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            logText = loadLog(paths)
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Server logs", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                val f = paths.serverLog
                if (f.exists()) {
                    val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, f.readText().takeLast(50_000))
                    }
                    context.startActivity(
                        android.content.Intent.createChooser(share, "Share logs")
                    )
                }
            }) { Icon(Icons.Filled.Share, "Share logs") }
            IconButton(onClick = {
                paths.serverLog.delete()
                for (i in 1 until 5) File(paths.logs, "server.log.$i").delete()
                logText = ""
            }) { Icon(Icons.Filled.Delete, "Clear logs") }
        }
        Text(
            logText.ifEmpty { "(no logs yet)" },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        )
        TextButton(onClick = onBack) { Text("Back") }
    }
}

private fun loadLog(paths: RuntimePaths): String = try {
    paths.serverLog.readText().takeLast(30_000)
} catch (_: Exception) {
    ""
}
