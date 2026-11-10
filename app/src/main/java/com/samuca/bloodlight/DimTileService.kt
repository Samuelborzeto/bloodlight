package com.samuca.bloodlight

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Atalho no painel de configurações rápidas para ligar e desligar o escurecimento. */
class DimTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        if (!Settings.canDrawOverlays(this)) {
            openApp()
            return
        }
        if (Prefs.enabled(this) && DimService.isRunning()) {
            Prefs.setEnabled(this, false)
            DimService.stop(this)
        } else {
            Prefs.setEnabled(this, true)
            try {
                DimService.start(this)
            } catch (e: Exception) {
                openApp()
            }
        }
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val on = Prefs.enabled(this) && DimService.isRunning()
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        fun refresh(ctx: Context) {
            try {
                requestListeningState(ctx, ComponentName(ctx, DimTileService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
