package com.samuca.bloodlight

import android.content.Context

object Prefs {
    private const val FILE = "bloodlight"
    private const val KEY_LEVEL = "level"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ASKED_NOTIF = "asked_notif"

    private fun p(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 0 = tela mais escura, 1 = sem escurecimento. */
    fun level(ctx: Context): Float = p(ctx).getFloat(KEY_LEVEL, 0.5f)

    fun setLevel(ctx: Context, value: Float) {
        p(ctx).edit().putFloat(KEY_LEVEL, value.coerceIn(0f, 1f)).apply()
    }

    fun enabled(ctx: Context): Boolean = p(ctx).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, value: Boolean) {
        p(ctx).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun askedNotifications(ctx: Context): Boolean = p(ctx).getBoolean(KEY_ASKED_NOTIF, false)

    fun setAskedNotifications(ctx: Context) {
        p(ctx).edit().putBoolean(KEY_ASKED_NOTIF, true).apply()
    }
}
