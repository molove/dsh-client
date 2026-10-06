package com.example.dshclient.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

class SshConfigRepository(
    private val context: Context,
    private val prefsProvider: ((Context) -> SharedPreferences)? = null
) {
    companion object {
        private const val TAG = "SshConfigRepository"
        const val PREFS_FILE_NAME = "dsh_ssh_secure_prefs"

        const val KEY_HOST = "ssh_host"
        const val KEY_PORT = "ssh_port"
        const val KEY_USER = "ssh_user"
        const val KEY_PRIVATE_KEY = "ssh_private_key"
        const val KEY_PASSPHRASE = "ssh_passphrase"
        const val KEY_PINNED_HOST_KEY = "ssh_pinned_host_key"
        const val KEY_IS_CONFIGURED = "ssh_is_configured"
    }

    @Volatile
    private var prefs: SharedPreferences = initPreferences(context, prefsProvider)

    private fun initPreferences(
        ctx: Context,
        provider: ((Context) -> SharedPreferences)?
    ): SharedPreferences {
        val factory = provider ?: { contextToUse -> createEncryptedPreferences(contextToUse) }
        return try {
            factory(ctx)
        } catch (e: Exception) {
            try {
                recoverCorruptedStorage(ctx)
                factory(ctx)
            } catch (fallbackException: Exception) {
                Log.w(
                    TAG,
                    "EncryptedSharedPreferences initialization failed, falling back to unencrypted SharedPreferences",
                    fallbackException
                )
                ctx.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
            }
        }
    }

    private fun createEncryptedPreferences(ctx: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    internal fun recoverCorruptedStorage(ctx: Context) {
        try {
            ctx.deleteSharedPreferences(PREFS_FILE_NAME)
        } catch (e: Throwable) {
            try {
                val prefsDir = File(ctx.applicationInfo?.dataDir ?: "", "shared_prefs")
                val prefsFile = File(prefsDir, "$PREFS_FILE_NAME.xml")
                if (prefsFile.exists()) {
                    prefsFile.delete()
                }
            } catch (_: Throwable) {
            }
        }
    }

    @Synchronized
    fun recoverMasterKey() {
        recoverCorruptedStorage(context)
        prefs = initPreferences(context, prefsProvider)
    }

    fun saveConfig(config: SshConfig) {
        try {
            writeConfig(config)
        } catch (e: SecurityException) {
            recoverMasterKey()
            writeConfig(config)
        }
    }

    private fun writeConfig(config: SshConfig) {
        val editor = prefs.edit()
            .putString(KEY_HOST, config.host)
            .putInt(KEY_PORT, config.port)
            .putString(KEY_USER, config.user)
            .putString(KEY_PRIVATE_KEY, config.privateKey)
            .putBoolean(KEY_IS_CONFIGURED, true)

        if (config.passphrase != null) {
            editor.putString(KEY_PASSPHRASE, config.passphrase)
        } else {
            editor.remove(KEY_PASSPHRASE)
        }

        if (config.pinnedHostKey != null) {
            editor.putString(KEY_PINNED_HOST_KEY, config.pinnedHostKey)
        } else {
            editor.remove(KEY_PINNED_HOST_KEY)
        }

        editor.apply()
    }

    fun getConfig(): SshConfig? {
        return try {
            readConfig()
        } catch (e: SecurityException) {
            recoverMasterKey()
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun readConfig(): SshConfig? {
        val isConfigured = prefs.getBoolean(KEY_IS_CONFIGURED, false)
        if (!isConfigured) {
            return null
        }
        return SshConfig(
            host = prefs.getString(KEY_HOST, "") ?: "",
            port = prefs.getInt(KEY_PORT, 22),
            user = prefs.getString(KEY_USER, "") ?: "",
            privateKey = prefs.getString(KEY_PRIVATE_KEY, "") ?: "",
            passphrase = prefs.getString(KEY_PASSPHRASE, null),
            pinnedHostKey = prefs.getString(KEY_PINNED_HOST_KEY, null)
        )
    }

    fun hasValidConfig(): Boolean {
        val config = getConfig() ?: return false
        return config.isValid
    }

    fun clearConfig() {
        try {
            prefs.edit().clear().apply()
        } catch (e: SecurityException) {
            recoverMasterKey()
        }
    }

    fun savePinnedHostKey(pinnedHostKey: String?) {
        try {
            writePinnedHostKey(pinnedHostKey)
        } catch (e: SecurityException) {
            recoverMasterKey()
            writePinnedHostKey(pinnedHostKey)
        }
    }

    private fun writePinnedHostKey(pinnedHostKey: String?) {
        val editor = prefs.edit()
        if (pinnedHostKey != null) {
            editor.putString(KEY_PINNED_HOST_KEY, pinnedHostKey)
        } else {
            editor.remove(KEY_PINNED_HOST_KEY)
        }
        editor.apply()
    }

    fun clearPinnedHostKey() {
        savePinnedHostKey(null)
    }
}
