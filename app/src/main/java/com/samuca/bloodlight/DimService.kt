package com.samuca.bloodlight

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Mantém uma camada preta por cima de toda a tela. Quanto menor o nível, mais escura.
 *
 * A opacidade da janela fica fixa em MAX_WINDOW_OPACITY (< 0,8): acima disso o Android 12+
 * bloqueia os toques nos outros apps. O escurecimento real é controlado pela cor da view.
 */
class DimService : Service() {

    private var wm: WindowManager? = null
    private var overlay: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null && !Prefs.enabled(this)) {
            // reinício automático depois que o sistema matou o processo, mas o usuário tinha desligado
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        showOverlay()
        applyLevel(Prefs.level(this))
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
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    private fun showOverlay() {
        if (overlay != null) return
        val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = View(this)
        view.setBackgroundColor(Color.TRANSPARENT)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.alpha = MAX_WINDOW_OPACITY
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        manager.addView(view, lp)
        wm = manager
        overlay = view
    }

    /** level: 0 = escuro ao máximo, 1 = sem escurecimento. */
    fun applyLevel(level: Float) {
        val view = overlay ?: return
        val l = level.coerceIn(0f, 1f)
        // Escala logarítmica: cada passo do controle muda o brilho percebido na mesma proporção.
        val remaining = MIN_LUMINANCE.pow(1f - l)
        val dim = 1f - remaining                       // 0 .. (1 - MIN_LUMINANCE)
        val alpha = (dim / MAX_WINDOW_OPACITY * 255f).roundToInt().coerceIn(0, 255)
        view.setBackgroundColor(Color.argb(alpha, 0, 0, 0))
    }

    override fun onDestroy() {
        try {
            overlay?.let { wm?.removeView(it) }
        } catch (_: Exception) {
        }
        overlay = null
        if (instance === this) instance = null
        DimTileService.refresh(this)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.samuca.bloodlight.STOP"
        private const val CHANNEL_ID = "bloodlight_dim"
        private const val NOTIF_ID = 1

        /** Luminosidade que sobra no nível mais escuro (0,21 = a tela fica com 21% do brilho). */
        const val MIN_LUMINANCE = 0.21f

        /** Opacidade da janela. Mantida abaixo de 0,8 para o Android não bloquear toques. */
        const val MAX_WINDOW_OPACITY = 1f - MIN_LUMINANCE

        @Volatile
        var instance: DimService? = null

        fun isRunning(): Boolean = instance != null

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, DimService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, DimService::class.java))
        }

        fun setLevel(level: Float) {
            instance?.applyLevel(level)
        }
    }
}
