package com.example.dshclient.data

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class TokenRepositoryTest {
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var context: Context
    private lateinit var repo: TokenRepository

    @Before
    fun setup() {
        context = mock(Context::class.java)
        sharedPrefs = mock(SharedPreferences::class.java)
        editor = mock(SharedPreferences.Editor::class.java)
        
        `when`(context.getSharedPreferences("dsh_prefs", Context.MODE_PRIVATE)).thenReturn(sharedPrefs)
        `when`(sharedPrefs.edit()).thenReturn(editor)
        `when`(editor.putString(anyString(), anyString())).thenReturn(editor)
        
        repo = TokenRepository(context)
    }

    @Test
    fun testSaveAndGetToken() {
        `when`(sharedPrefs.getString("auth_token", null)).thenReturn("my_token")
        val token = repo.getToken()
        assertEquals("my_token", token)
    }

    @Test
    fun testSaveToken() {
        repo.saveToken("new_token")
        org.mockito.Mockito.verify(editor).putString("auth_token", "new_token")
        org.mockito.Mockito.verify(editor).apply()
    }
}
