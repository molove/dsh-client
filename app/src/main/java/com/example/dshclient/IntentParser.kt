package com.example.dshclient

import android.content.Intent

object IntentParser {
    fun extractToken(intent: Intent?): String? {
        return intent?.data?.getQueryParameter("token")
    }
}
