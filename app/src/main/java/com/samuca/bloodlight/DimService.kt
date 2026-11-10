package com.samuca.bloodlight

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.WindowManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Sessão de escurecimento: mantém a notificação e a camada comum (quando a de acessibilidade está desligada). */
class DimService : Service() {

    lateinit var overlay: DimOverlay
        private set

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        overlay = DimOverlay(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, false)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null && !Prefs.enabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        if (!Settings.canDrawOverlays(this) && !Dimmer.strongAvailable()) {
            stopSelf()
            return START_NOT_STICKY
        }
        Dimmer.apply(this, Prefs.level(this))
        DimTileService.refresh(this)
        return START_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DimService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val icon = Icon.createWithResource(this, R.drawable.ic_tile)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(icon, getString(R.string.notif_off), stop).build())
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    override fun onDestroy() {
        overlay.hide()
        if (instance === this) instance = null
        DimAccessibilityService.instance?.overlay?.hide()
        DimTileService.refresh(this)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.samuca.bloodlight.STOP"
        private const val CHANNEL_ID = "bloodlight_dim"
        private const val NOTIF_ID = 1

        @Volatile
        var instance: DimService? = null

        fun isRunning(): Boolean = instance != null

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, DimService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, DimService::class.java))
        }
    }
}
