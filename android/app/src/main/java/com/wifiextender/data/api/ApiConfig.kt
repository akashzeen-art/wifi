package com.wifiextender.data.api

import android.content.Context
import com.wifiextender.BuildConfig

object ApiConfig {

    private const val PREFS = "wifi_extender_prefs"
    private const val KEY_BASE_URL = "api_base_url"

    fun getBaseUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_BASE_URL, null)?.trim().orEmpty()
        return normalize(saved.ifBlank { BuildConfig.BASE_URL })
    }

    fun setBaseUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BASE_URL, normalize(url.trim()))
            .apply()
    }

    fun normalize(url: String): String {
        var value = url.trim()
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://$value"
        }
        if (!value.endsWith("/")) value += "/"
        return value
    }
}
