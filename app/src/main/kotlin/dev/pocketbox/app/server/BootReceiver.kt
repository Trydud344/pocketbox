package dev.pocketbox.app.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.pocketbox.app.PocketboxApp
import kotlinx.coroutines.launch

/** Optional "start on boot" (brief section 8.2.5). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as PocketboxApp
        if (!app.container.settings.startOnBoot.value) return
        if (!app.container.paths.installed()) return
        val pending = goAsync()
        app.container.appScope.launch {
            try {
                ServerService.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
