package com.example.dshclient

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class IntentParserTest {
    @Test
    fun extractsTokenFromDeepLink() {
        val intent = mock(Intent::class.java)
        val uri = mock(Uri::class.java)
        `when`(intent.data).thenReturn(uri)
        `when`(uri.getQueryParameter("token")).thenReturn("abc123token")

        val token = IntentParser.extractToken(intent)
        assertEquals("abc123token", token)
    }

    @Test
    fun returnsNullWhenIntentIsNull() {
        val token = IntentParser.extractToken(null)
        assertNull(token)
    }

    @Test
    fun returnsNullWhenIntentDataIsNull() {
        val intent = mock(Intent::class.java)
        `when`(intent.data).thenReturn(null)

        val token = IntentParser.extractToken(intent)
        assertNull(token)
    }

    @Test
    fun returnsNullWhenTokenQueryParameterIsMissing() {
        val intent = mock(Intent::class.java)
        val uri = mock(Uri::class.java)
        `when`(intent.data).thenReturn(uri)
        `when`(uri.getQueryParameter("token")).thenReturn(null)

        val token = IntentParser.extractToken(intent)
        assertNull(token)
    }
}
