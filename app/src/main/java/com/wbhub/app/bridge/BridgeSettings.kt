package com.wbhub.app.bridge

import android.content.Context

/**
 * User preferences for the status panel.
 *
 * Kept in shared preferences rather than the credential file: these are
 * presentation choices that must survive a restart, and they are not secrets.
 */
object BridgeSettings {

    private const val PREFS = "wb-overlay"
    private const val KEY_OPACITY = "opacity"
    private const val KEY_LOCKED = "locked"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"

    fun opacity(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_OPACITY, 0.94f)

    fun setOpacity(context: Context, value: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_OPACITY, value.coerceIn(0.15f, 1f)).apply()
    }

    fun locked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LOCKED, false)

    fun setLocked(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LOCKED, value).apply()
    }

    fun position(context: Context): Pair<Int, Int> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_X, -1) to prefs.getInt(KEY_Y, -1)
    }

    fun setPosition(context: Context, x: Int, y: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply()
    }
}
