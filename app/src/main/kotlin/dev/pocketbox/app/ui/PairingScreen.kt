package dev.pocketbox.app.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dev.pocketbox.runtime.supervisor.RuntimeStatus
import dev.pocketbox.runtime.supervisor.ServerState
import java.util.Timer
import kotlin.concurrent.timerTask

/** Clipboard helper: marks content sensitive and auto-clears after 60 s (brief 8.2.3). */
fun copySensitive(context: Context, label: String, value: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val clip = ClipData(ClipDescription(label, arrayOf("text/plain")), ClipData.Item(value))
    // Mark sensitive so password managers/keyboard logging avoid capturing it.
    clip.description.setExtras(
        android.os.PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    )
    cm.setPrimaryClip(clip)
    Timer().schedule(
        timerTask {
            try {
                val cm2 = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                if (cm2.primaryClip?.description?.label == label) cm2.clearPrimaryClip()
            } catch (_: Exception) {
            }
        },
        60_000,
    )
}

fun qrBitmap(content: String, size: Int = 512): Bitmap {
    val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8")
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) for (y in 0 until size) {
        bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
    return bmp
}

/** Known client apps we can try to open (manifest <queries>). */
private val CLIENT_PACKAGES = listOf(
    "cc.agentlabs.opencode",
    "app.getopencode",
)

@Composable
fun PairingScreen(status: RuntimeStatus, username: String, password: String, onClose: () -> Unit) {
    val context = LocalContext.current
    var reveal by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    val installedClients = remember {
        CLIENT_PACKAGES.filter {
            try {
                context.packageManager.getPackageInfo(it, 0); true
            } catch (_: Exception) {
                false
            }
        }
    }
    // Pairing is primarily for same-device clients; LAN mode users can replace
    // 127.0.0.1 with the device's LAN IP in the client.
    val url = "http://127.0.0.1:${status.port}"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Connect a client", style = MaterialTheme.typography.headlineSmall)
        Text(
            "In your OpenCode client app, add a server with these details:",
            style = MaterialTheme.typography.bodyMedium,
        )

        PairingRow("URL", url, onCopy = { copySensitive(context, "Server URL", url) })
        PairingRow("Username", username, onCopy = { copySensitive(context, "Username", username) })
        PairingRow(
            "Password",
            if (reveal) password else "•".repeat(20),
            onCopy = { copySensitive(context, "Password", password) },
            trailing = {
                TextButton(onClick = { reveal = !reveal }) {
                    Icon(if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null)
                }
            },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "Pocketbox OpenCode server\nURL: $url\nUsername: $username\nPassword: $password",
                    )
                }
                context.startActivity(Intent.createChooser(share, "Share server details"))
            }) {
                Icon(Icons.Filled.Share, null); Spacer(Modifier.width(6.dp)); Text("Share")
            }
            OutlinedButton(onClick = { showQr = !showQr }) {
                Text(if (showQr) "Hide QR" else "QR for another device")
            }
        }

        if (showQr) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Image(
                    bitmap = qrBitmap("$url\n$username:$password").asImageBitmap(),
                    contentDescription = "Server pairing QR code",
                    modifier = Modifier.size(240.dp).padding(8.dp),
                )
            }
        }

        if (installedClients.isNotEmpty()) {
            Text("Open a client app", style = MaterialTheme.typography.titleMedium)
            for (pkg in installedClients) {
                Button(
                    onClick = {
                        try {
                            context.startActivity(
                                context.packageManager.getLaunchIntentForPackage(pkg)
                            )
                        } catch (_: Exception) {
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open $pkg") }
            }
        } else {
            Text(
                "No known client apps installed. Install one from F-Droid or Google Play to connect.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        HorizontalDivider()
        Text(
            "Troubleshooting: if the client cannot connect, make sure Pocketbox shows “Running”, " +
                "and check that the client allows cleartext HTTP to 127.0.0.1.",
            style = MaterialTheme.typography.bodySmall,
        )

        TextButton(onClick = onClose) { Text("Done") }
    }
}

@Composable
private fun PairingRow(
    label: String,
    value: String,
    onCopy: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(shape = RoundedCornerShape(10.dp), tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
            trailing?.invoke()
            IconButton(onClick = onCopy) {
                Icon(Icons.Filled.ContentCopy, "Copy $label")
            }
        }
    }
}
