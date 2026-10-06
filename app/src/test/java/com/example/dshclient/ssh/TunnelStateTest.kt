package com.example.dshclient.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelStateTest {

    @Test
    fun disconnectedState_instantiatesCorrectly() {
        val state: TunnelState = TunnelState.Disconnected
        assertEquals(TunnelState.Disconnected, state)
    }

    @Test
    fun connectingState_instantiatesCorrectly() {
        val state: TunnelState = TunnelState.Connecting
        assertEquals(TunnelState.Connecting, state)
    }

    @Test
    fun connectedState_storesUrlAndFingerprint() {
        val state = TunnelState.Connected(
            url = "http://127.0.0.1:3080/?token=abc123",
            hostKeyFingerprint = "SHA256:fingerprint123"
        )
        assertEquals("http://127.0.0.1:3080/?token=abc123", state.url)
        assertEquals("SHA256:fingerprint123", state.hostKeyFingerprint)

        val stateNoFp = TunnelState.Connected(url = "http://127.0.0.1:3080/?token=xyz")
        assertEquals("http://127.0.0.1:3080/?token=xyz", stateNoFp.url)
        assertNull(stateNoFp.hostKeyFingerprint)
    }

    @Test
    fun reconnectingState_storesAttemptAndNextRetrySeconds() {
        val state = TunnelState.Reconnecting(attempt = 2, nextRetrySeconds = 4)
        assertEquals(2, state.attempt)
        assertEquals(4, state.nextRetrySeconds)
    }

    @Test
    fun errorState_storesMessageAndRestartFlag() {
        val stateDefault = TunnelState.Error(message = "Connection refused")
        assertEquals("Connection refused", stateDefault.message)
        assertFalse(stateDefault.canRestartService)

        val stateWithRestart = TunnelState.Error(
            message = "Token extraction failed",
            canRestartService = true
        )
        assertEquals("Token extraction failed", stateWithRestart.message)
        assertTrue(stateWithRestart.canRestartService)
    }
}
