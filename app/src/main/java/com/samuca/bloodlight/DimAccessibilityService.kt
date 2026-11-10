package com.samuca.bloodlight

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Serviço de acessibilidade usado só para desenhar a camada escura.
 * Camadas de acessibilidade podem ficar quase opacas sem bloquear o toque nos outros apps.
 */
class DimAccessibilityService : AccessibilityService() {

    var overlay: DimOverlay? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = DimOverlay(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, true)
        instance = this
        Dimmer.apply(this, Prefs.level(this))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        release()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        overlay?.hide()
        if (instance === this) {
            instance = null
            DimService.instance?.let { Dimmer.apply(it, Prefs.level(it)) }
        }
    }

    companion object {
        @Volatile
        var instance: DimAccessibilityService? = null
    }
}
