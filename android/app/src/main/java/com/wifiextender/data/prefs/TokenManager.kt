package com.wifiextender.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.wifiextender.data.model.UserInfo

class TokenManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("wifi_extender_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun saveTokens(accessToken: String, refreshToken: String) {
        prefs.edit()
            .putString(KEY_ACCESS, accessToken)
            .putString(KEY_REFRESH, refreshToken)
            .apply()
    }

    fun saveUser(user: UserInfo) {
        prefs.edit().putString(KEY_USER, gson.toJson(user)).apply()
    }

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS, null)
    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH, null)

    fun getUser(): UserInfo? {
        val json = prefs.getString(KEY_USER, null) ?: return null
        return try {
            gson.fromJson(json, UserInfo::class.java)
        } catch (_: Exception) {
            prefs.edit().remove(KEY_USER).apply()
            null
        }
    }

    fun isLoggedIn(): Boolean = getAccessToken() != null

    fun isAdmin(): Boolean = getUser()?.role == "ADMIN"

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_ACCESS  = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_USER    = "user"
    }
}
