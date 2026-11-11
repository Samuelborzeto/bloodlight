package com.samuca.bloodlight

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** Decide qual camada escurece a tela: a forte (acessibilidade) quando ativa, senão a básica. */
object Dimmer {
    /** Brilho que sobra no nível mais escuro com a camada de acessibilidade (1,2%). */
    const val MIN_LUMINANCE_STRONG = 0.012f

    /** Brilho que sobra com a camada comum: o Android bloqueia toques acima de 80% de opacidade. */
    const val MIN_LUMINANCE_BASIC = 0.21f
    const val BASIC_WINDOW_OPACITY = 1f - MIN_LUMINANCE_BASIC

    fun strongAvailable(): Boolean = DimAccessibilityService.instance != null

    fun dimFor(level: Float, minLuminance: Float): Float =
        1f - minLuminance.pow(1f - level.coerceIn(0f, 1f))

    fun apply(ctx: Context, level: Float) {
        val acc = DimAccessibilityService.instance
        val svc = DimService.instance
        if (svc == null || !Prefs.enabled(ctx)) {
            acc?.overlay?.hide()
            svc?.overlay?.hide()
            return
        }
        if (acc != null) {
            svc.overlay.hide()
            acc.overlay?.show(level)
        } else {
            svc.overlay.show(level)
        }
    }
}

class DimOverlay(private val ctx: Context, private val type: Int, private val strong: Boolean) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null

    fun show(level: Float) {
        val minLum = if (strong) Dimmer.MIN_LUMINANCE_STRONG else Dimmer.MIN_LUMINANCE_BASIC
        val windowOpacity = if (strong) 1f else Dimmer.BASIC_WINDOW_OPACITY
        val alpha = (Dimmer.dimFor(level, minLum) / windowOpacity * 255f).roundToInt().coerceIn(0, 255)
        if (alpha == 0) {
            // Sem escurecimento não deixa janela nenhuma na tela. Uma camada comum, mesmo
            // transparente, faz o Android recusar toques em telas protegidas (troca de conta,
            // permissões, senhas).
            hide()
            return
        }
        val v = view ?: add() ?: return
        v.setBackgroundColor(Color.argb(alpha, 0, 0, 0))
    }

    private fun add(): View? {
        val v = View(ctx)
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        val margin = (100 * dm.density).toInt()
        // quadrado maior que a tela: cobre barras do sistema e as duas orientações
        val side = max(dm.widthPixels, dm.heightPixels) + margin * 2
        val lp = WindowManager.LayoutParams(
            side, side, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = -margin
        lp.y = -margin
        lp.alpha = if (strong) 1f else Dimmer.BASIC_WINDOW_OPACITY
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return try {
            wm.addView(v, lp)
            view = v
            v
        } catch (e: Exception) {
            null
        }
    }

    fun hide() {
        view?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        view = null
    }
}
