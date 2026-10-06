package com.example.dshclient.data

import android.content.Context

class TokenRepository(context: Context) {
    private val prefs = context.getSharedPreferences("dsh_prefs", Context.MODE_PRIVATE)

    fun saveToken(token: String) {
        prefs.edit().putString("auth_token", token).apply()
    }

    fun getToken(): String? {
        return prefs.getString("auth_token", null)
    }
}
