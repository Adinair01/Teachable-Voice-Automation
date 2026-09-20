package com.tva.teachable.net

import android.content.Context

/** Backend base URL, editable from the settings screen. Default works with `adb reverse tcp:3100 tcp:3100`. */
object BackendConfig {
    private const val PREFS = "tva"
    private const val KEY = "backend_url"
    const val DEFAULT_URL = "http://localhost:3100"

    fun url(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, DEFAULT_URL) ?: DEFAULT_URL
    fun setUrl(ctx: Context, url: String) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, url.trim().trimEnd('/')).apply()
    fun wsUrl(ctx: Context) = url(ctx).replaceFirst("http", "ws") + "/ws"
}
