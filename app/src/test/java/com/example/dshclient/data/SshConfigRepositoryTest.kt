package com.example.dshclient.data

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.security.GeneralSecurityException

class SshConfigRepositoryTest {

    private lateinit var context: Context
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var repository: SshConfigRepository

    @Before
    fun setup() {
        context = mock(Context::class.java)
        sharedPrefs = mock(SharedPreferences::class.java)
        editor = mock(SharedPreferences.Editor::class.java)

        `when`(sharedPrefs.edit()).thenReturn(editor)
        `when`(editor.putString(anyString(), anyString())).thenReturn(editor)
        `when`(editor.putInt(anyString(), anyInt())).thenReturn(editor)
        `when`(editor.putBoolean(anyString(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(editor)
        `when`(editor.remove(anyString())).thenReturn(editor)
        `when`(editor.clear()).thenReturn(editor)

        repository = SshConfigRepository(
            context = context,
            prefsProvider = { sharedPrefs }
        )
    }

    @Test
    fun saveConfig_persistsAllFields() {
        val config = SshConfig(
            host = "192.0.2.1",
            port = 22,
            user = "q",
            privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\ntest\n-----END OPENSSH PRIVATE KEY-----",
            passphrase = "test-passphrase",
            pinnedHostKey = "ssh-ed25519 AAAAC3... SHA256:abc123"
        )

        repository.saveConfig(config)

        verify(editor).putString(SshConfigRepository.KEY_HOST, "192.0.2.1")
        verify(editor).putInt(SshConfigRepository.KEY_PORT, 22)
        verify(editor).putString(SshConfigRepository.KEY_USER, "q")
        verify(editor).putString(SshConfigRepository.KEY_PRIVATE_KEY, config.privateKey)
        verify(editor).putString(SshConfigRepository.KEY_PASSPHRASE, "test-passphrase")
        verify(editor).putString(SshConfigRepository.KEY_PINNED_HOST_KEY, "ssh-ed25519 AAAAC3... SHA256:abc123")
        verify(editor).putBoolean(SshConfigRepository.KEY_IS_CONFIGURED, true)
        verify(editor).apply()
    }

    @Test
    fun getConfig_retrievesAllFields() {
        `when`(sharedPrefs.getBoolean(SshConfigRepository.KEY_IS_CONFIGURED, false)).thenReturn(true)
        `when`(sharedPrefs.getString(SshConfigRepository.KEY_HOST, "")).thenReturn("10.0.0.1")
        `when`(sharedPrefs.getInt(SshConfigRepository.KEY_PORT, 22)).thenReturn(2222)
        `when`(sharedPrefs.getString(SshConfigRepository.KEY_USER, "")).thenReturn("testuser")
        `when`(sharedPrefs.getString(SshConfigRepository.KEY_PRIVATE_KEY, "")).thenReturn("private-key-data")
        `when`(sharedPrefs.getString(SshConfigRepository.KEY_PASSPHRASE, null)).thenReturn("my-secret")
        `when`(sharedPrefs.getString(SshConfigRepository.KEY_PINNED_HOST_KEY, null)).thenReturn("pinned-fingerprint")

        val config = repository.getConfig()

        assertNotNull(config)
        assertEquals("10.0.0.1", config?.host)
        assertEquals(2222, config?.port)
        assertEquals("testuser", config?.user)
        assertEquals("private-key-data", config?.privateKey)
        assertEquals("my-secret", config?.passphrase)
        assertEquals("pinned-fingerprint", config?.pinnedHostKey)
    }

    @Test
    fun getConfig_returnsNullWhenNotConfigured() {
        `when`(sharedPrefs.getBoolean(SshConfigRepository.KEY_IS_CONFIGURED, false)).thenReturn(false)

        val config = repository.getConfig()
        assertNull(config)
    }

    @Test
    fun hasValidConfig_validatesRequiredFields() {
        // Not configured
        `when`(sharedPrefs.getBoolean(SshConfigRepository.KEY_IS_CONFIGURED, false)).thenReturn(false)
        assertFalse(repository.hasValidConfig())

        // Configured with empty private key
        `when`(sharedPrefs.getBoolean(SshConfigRepository.KEY_IS_CONFIGURED, false)).thenReturn(true)
        `when`(sharedPrefs.getString(SshConfigRepository.KEY_HOST, "")).thenReturn("10.0.0.1")
        `when`(sharedPrefs.getInt(SshConfigRepository.KEY_PORT, 22)).thenReturn(22)
        `when`(sharedPrefs.getString(SshConfigRepository.KEY_USER, "")).thenReturn("testuser")
        `when`(sharedPrefs.getString(SshConfigRepository.KEY_PRIVATE_KEY, "")).thenReturn("")
        assertFalse(repository.hasValidConfig())

        // Configured with valid private key
        `when`(sharedPrefs.getString(SshConfigRepository.KEY_PRIVATE_KEY, "")).thenReturn("valid-key")
        assertTrue(repository.hasValidConfig())
    }

    @Test
    fun clearConfig_clearsPreferences() {
        repository.clearConfig()

        verify(editor).clear()
        verify(editor).apply()
    }

    @Test
    fun updatePinnedHostKey_savesAndClearsFingerprint() {
        repository.savePinnedHostKey("SHA256:fingerprint")
        verify(editor).putString(SshConfigRepository.KEY_PINNED_HOST_KEY, "SHA256:fingerprint")

        repository.clearPinnedHostKey()
        verify(editor).remove(SshConfigRepository.KEY_PINNED_HOST_KEY)
    }

    @Test
    fun masterKeyRotationRecovery_recreatesStorageOnFailure() {
        var attempts = 0
        val recoveredPrefs = mock(SharedPreferences::class.java)
        val recoveredEditor = mock(SharedPreferences.Editor::class.java)
        `when`(recoveredPrefs.edit()).thenReturn(recoveredEditor)
        `when`(recoveredEditor.putString(anyString(), anyString())).thenReturn(recoveredEditor)
        `when`(recoveredEditor.putInt(anyString(), anyInt())).thenReturn(recoveredEditor)
        `when`(recoveredEditor.putBoolean(anyString(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(recoveredEditor)

        val repo = SshConfigRepository(
            context = context,
            prefsProvider = {
                attempts++
                if (attempts == 1) {
                    throw GeneralSecurityException("Simulated master key rotated/corrupted")
                }
                recoveredPrefs
            }
        )

        assertEquals(2, attempts)
        verify(context).deleteSharedPreferences(SshConfigRepository.PREFS_FILE_NAME)

        // Verify operations succeed on recovered prefs
        val config = SshConfig(privateKey = "my-key")
        repo.saveConfig(config)
        verify(recoveredEditor).putString(SshConfigRepository.KEY_PRIVATE_KEY, "my-key")
    }

    @Test
    fun runtimeDecryptionFailure_recoversStorageGracefully() {
        var attempts = 0
        val recoveredPrefs = mock(SharedPreferences::class.java)
        `when`(recoveredPrefs.getBoolean(SshConfigRepository.KEY_IS_CONFIGURED, false)).thenReturn(false)

        val repo = SshConfigRepository(
            context = context,
            prefsProvider = {
                attempts++
                if (attempts == 1) {
                    sharedPrefs
                } else {
                    recoveredPrefs
                }
            }
        )

        // Simulate decryption failure when reading string from corrupted master key
        `when`(sharedPrefs.getBoolean(SshConfigRepository.KEY_IS_CONFIGURED, false))
            .thenThrow(SecurityException("AEADBadTagException or key corrupted"))

        val config = repo.getConfig()
        assertNull(config)
        verify(context).deleteSharedPreferences(SshConfigRepository.PREFS_FILE_NAME)
        assertEquals(2, attempts)
    }

    @Test
    fun savePinnedHostKey_retriesOnSecurityException() {
        var attempts = 0
        val recoveredPrefs = mock(SharedPreferences::class.java)
        val recoveredEditor = mock(SharedPreferences.Editor::class.java)
        `when`(recoveredPrefs.edit()).thenReturn(recoveredEditor)
        `when`(recoveredEditor.putString(anyString(), anyString())).thenReturn(recoveredEditor)

        val repo = SshConfigRepository(
            context = context,
            prefsProvider = {
                attempts++
                if (attempts == 1) {
                    sharedPrefs
                } else {
                    recoveredPrefs
                }
            }
        )

        `when`(sharedPrefs.edit()).thenThrow(SecurityException("Master key corrupted"))

        repo.savePinnedHostKey("SHA256:new-fingerprint")

        verify(recoveredEditor).putString(SshConfigRepository.KEY_PINNED_HOST_KEY, "SHA256:new-fingerprint")
        verify(recoveredEditor).apply()
        assertEquals(2, attempts)
    }

    @Test
    fun initPreferences_fallsBackToUnencryptedWhenEncryptedInitFails() {
        val fallbackPrefs = mock(SharedPreferences::class.java)
        `when`(context.getSharedPreferences(SshConfigRepository.PREFS_FILE_NAME, Context.MODE_PRIVATE))
            .thenReturn(fallbackPrefs)

        val repo = SshConfigRepository(
            context = context,
            prefsProvider = {
                throw GeneralSecurityException("Keystore totally broken")
            }
        )

        verify(context).getSharedPreferences(SshConfigRepository.PREFS_FILE_NAME, Context.MODE_PRIVATE)
        assertNotNull(repo)
    }
}
