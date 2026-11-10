package com.samuca.bloodlight

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout

class MainActivity : Activity() {

    private lateinit var bar: BloodBarView
    private lateinit var permissionButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        bar = BloodBarView(this)
        bar.level = Prefs.level(this)
        bar.onLevelChanged = { DimService.setLevel(it) }
        bar.onLevelCommitted = { Prefs.setLevel(this, it) }
        root.addView(
            bar,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val density = resources.displayMetrics.density
        permissionButton = Button(this).apply {
            text = getString(R.string.grant_overlay)
            setBackgroundColor(0xFF5E0C10.toInt())
            setTextColor(0xFFE8D9D4.toInt())
            visibility = View.GONE
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        }
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        )
        lp.bottomMargin = (56 * density).toInt()
        root.addView(permissionButton, lp)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this)) {
            permissionButton.visibility = View.GONE
            Prefs.setEnabled(this, true)
            if (DimService.isRunning()) {
                DimService.setLevel(bar.level)
            } else {
                DimService.start(this)
            }
            askForNotificationsOnce()
        } else {
            permissionButton.visibility = View.VISIBLE
        }
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
