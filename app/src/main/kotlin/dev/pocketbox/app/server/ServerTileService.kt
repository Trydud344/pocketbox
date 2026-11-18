package dev.pocketbox.app.server

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.pocketbox.app.PocketboxApp
import dev.pocketbox.runtime.supervisor.ServerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** Quick Settings tile toggles the server (brief section 8.2.6). */
class ServerTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        val app = application as PocketboxApp
        app.container.supervisor.status
            .onEach { updateTile(it.state) }
            .launchIn(scope)
    }

    override fun onStopListening() {
        scope.cancel()
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val app = application as PocketboxApp
        val state = app.container.supervisor.status.value.state
        if (state == ServerState.RUNNING || state == ServerState.STARTING) {
            ServerService.start(applicationContext, ServerService.ACTION_STOP)
        } else if (app.container.paths.installed()) {
            ServerService.start(applicationContext)
        }
    }

    private fun updateTile(state: ServerState) {
        val tile = qsTile ?: return
        val active = state == ServerState.RUNNING || state == ServerState.STARTING
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = if (active) "Running" else "Stopped"
        tile.updateTile()
    }
}
