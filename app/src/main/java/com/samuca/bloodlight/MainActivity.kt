package com.samuca.bloodlight

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var bar: BloodBarView
    private lateinit var permissionButton: Button
    private lateinit var strongButton: Button
    private lateinit var strongHint: TextView
    private lateinit var infoButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density

        val root = FrameLayout(this)
        bar = BloodBarView(this)
        bar.level = Prefs.level(this)
        bar.onLevelChanged = { Dimmer.apply(this, it) }
        bar.onLevelCommitted = { Prefs.setLevel(this, it) }
        bar.onOutsideTap = { finish() }
        root.addView(
            bar,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        fun styled(b: Button) {
            b.isAllCaps = false
            b.setTextColor(0xFFF0E2DD.toInt())
            b.background = GradientDrawable().apply {
                cornerRadius = 22 * density
                setColor(0xE04A080C.toInt())
                setStroke((1 * density).toInt(), 0xFF9A8F90.toInt())
            }
            val p = (18 * density).toInt()
            b.setPadding(p, 0, p, 0)
        }

        permissionButton = Button(this).apply {
            text = getString(R.string.grant_overlay)
            styled(this)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        strongButton = Button(this).apply {
            text = getString(R.string.strong_button)
            styled(this)
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        infoButton = Button(this).apply {
            text = getString(R.string.app_info)
            styled(this)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        strongHint = TextView(this).apply {
            text = getString(R.string.strong_hint)
            setTextColor(0xFFE8D9D4.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            setShadowLayer(4 * density, 0f, 0f, 0xFF000000.toInt())
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val gap = (10 * density).toInt()
        for (v in listOf<View>(permissionButton, strongButton, strongHint, infoButton)) {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = gap
            column.addView(v, lp)
        }
        val clp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        )
        val side = (24 * density).toInt()
        clp.setMargins(side, 0, side, (48 * density).toInt())
        root.addView(column, clp)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val basic = Settings.canDrawOverlays(this)
        val strong = Dimmer.strongAvailable()
        if (basic || strong) {
            permissionButton.visibility = View.GONE
            Prefs.setEnabled(this, true)
            if (DimService.isRunning()) Dimmer.apply(this, bar.level) else DimService.start(this)
            askForNotificationsOnce()
        } else {
            permissionButton.visibility = View.VISIBLE
        }
        val showStrong = if (strong) View.GONE else View.VISIBLE
        strongButton.visibility = showStrong
        strongHint.visibility = showStrong
        infoButton.visibility = showStrong
    }

    override fun onPause() {
        Prefs.setLevel(this, bar.level)
        super.onPause()
    }

    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33 || Prefs.askedNotifications(this)) return
        Prefs.setAskedNotifications(this)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
