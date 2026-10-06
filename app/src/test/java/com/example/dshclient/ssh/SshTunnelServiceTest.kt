package com.example.dshclient.ssh

import com.example.dshclient.data.SshConfig
import com.example.dshclient.data.SshConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class SshTunnelServiceTest {

    private lateinit var service: SshTunnelService
    private lateinit var mockTunnelManager: SshTunnelManager
    private lateinit var mockConfigRepo: SshConfigRepository

    private var wakeLockAcquireCount = 0
    private var wakeLockReleaseCount = 0
    private var updatedNotifications = mutableListOf<TunnelState>()

    private val validConfig = SshConfig(
        host = "192.0.2.1",
        port = 22,
        user = "q",
        privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\ntest\n-----END OPENSSH PRIVATE KEY-----"
    )

    @Before
    fun setup() {
        service = SshTunnelService()
        mockTunnelManager = mock(SshTunnelManager::class.java)
        mockConfigRepo = mock(SshConfigRepository::class.java)

        wakeLockAcquireCount = 0
        wakeLockReleaseCount = 0
        updatedNotifications.clear()

        service.tunnelManager = mockTunnelManager
        service.configRepository = mockConfigRepo
        service.wakeLockAcquirer = { wakeLockAcquireCount++ }
        service.wakeLockReleaser = { wakeLockReleaseCount++ }
        service.notificationUpdater = { updatedNotifications.add(it) }
        service.monitorCheckIntervalMs = 50L
        service.tickerDelayMs = 10L
    }

    @After
    fun tearDown() {
        service.disconnect()
        service.coroutineScopeOverride?.cancel()
    }

    @Test
    fun calculateBackoffSeconds_followsExponentialCurveWithCap() {
        assertEquals(2, SshTunnelService.calculateBackoffSeconds(0))
        assertEquals(2, SshTunnelService.calculateBackoffSeconds(1))
        assertEquals(4, SshTunnelService.calculateBackoffSeconds(2))
        assertEquals(8, SshTunnelService.calculateBackoffSeconds(3))
        assertEquals(15, SshTunnelService.calculateBackoffSeconds(4))
        assertEquals(15, SshTunnelService.calculateBackoffSeconds(5))
        assertEquals(15, SshTunnelService.calculateBackoffSeconds(100))
    }

    @Test
    fun buildNotificationContent_formatsStateDependentStrings() {
        assertEquals("Disconnected", service.buildNotificationContent(TunnelState.Disconnected))
        assertEquals("Connecting to Q...", service.buildNotificationContent(TunnelState.Connecting))
        assertEquals(
            "Connected to Q on :3080",
            service.buildNotificationContent(TunnelState.Connected(url = "http://127.0.0.1:3080/?token=abc123"))
        )
        assertEquals(
            "Connected to Q",
            service.buildNotificationContent(TunnelState.Connected(url = "http://127.0.0.1/?token=abc123"))
        )
        assertEquals(
            "Reconnecting to Q (attempt 2, retry in 4s)...",
            service.buildNotificationContent(TunnelState.Reconnecting(attempt = 2, nextRetrySeconds = 4))
        )
        assertEquals(
            "Error: Connection timed out",
            service.buildNotificationContent(TunnelState.Error(message = "Connection timed out"))
        )
    }

    @Test
    fun connect_withInvalidConfig_transitionsToError() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        service.ioDispatcher = testDispatcher
        service.coroutineScopeOverride = CoroutineScope(testDispatcher + SupervisorJob())

        `when`(mockConfigRepo.getConfig()).thenReturn(null)

        service.connect()
        runCurrent()

        val state = service.state.value
        assertTrue(state is TunnelState.Error)
        val errorState = state as TunnelState.Error
        assertEquals("Invalid or missing SSH configuration", errorState.message)
        assertFalse(errorState.canRestartService)
        assertEquals(0, wakeLockAcquireCount)

        service.disconnect()
        runCurrent()
    }

    @Test
    fun connect_successful_transitionsToConnectedAndAcquiresWakeLock() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        service.ioDispatcher = testDispatcher
        service.coroutineScopeOverride = CoroutineScope(testDispatcher + SupervisorJob())

        `when`(mockConfigRepo.getConfig()).thenReturn(validConfig)
        `when`(mockTunnelManager.connect(validConfig)).thenReturn(
            TunnelResult(
                port = 3080,
                token = "token123",
                url = "http://127.0.0.1:3080/?token=token123",
                hostKeyFingerprint = "SHA256:fingerprint123"
            )
        )
        `when`(mockTunnelManager.isConnected()).thenReturn(true)

        service.connect(validConfig)
        runCurrent()

        val state = service.state.value
        assertTrue(state is TunnelState.Connected)
        val connected = state as TunnelState.Connected
        assertEquals("http://127.0.0.1:3080/?token=token123", connected.url)
        assertEquals("SHA256:fingerprint123", connected.hostKeyFingerprint)
        assertEquals(1, wakeLockAcquireCount)
        assertEquals(0, wakeLockReleaseCount)

        service.disconnect()
        runCurrent()
    }

    @Test
    fun connect_hostKeyMismatch_transitionsToErrorWithoutReconnect() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        service.ioDispatcher = testDispatcher
        service.coroutineScopeOverride = CoroutineScope(testDispatcher + SupervisorJob())

        doAnswer {
            throw HostKeyVerificationException("Remote host key changed", "SHA256:old", "SHA256:new")
        }.`when`(mockTunnelManager).connect(validConfig)

        service.connect(validConfig)
        runCurrent()

        val state = service.state.value
        assertTrue(state is TunnelState.Error)
        val errorState = state as TunnelState.Error
        assertTrue(errorState.message.contains("Host key verification failed"))
        assertFalse(errorState.canRestartService)
        assertEquals(0, wakeLockAcquireCount)

        service.disconnect()
        runCurrent()
    }

    @Test
    fun connect_tokenExtractionError_transitionsToErrorWithCanRestart() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        service.ioDispatcher = testDispatcher
        service.coroutineScopeOverride = CoroutineScope(testDispatcher + SupervisorJob())

        doAnswer {
            throw TokenExtractionException("Failed to extract port and token")
        }.`when`(mockTunnelManager).connect(validConfig)

        service.connect(validConfig)
        runCurrent()

        val state = service.state.value
        assertTrue(state is TunnelState.Error)
        val errorState = state as TunnelState.Error
        assertTrue(errorState.message.contains("Failed to extract token"))
        assertTrue(errorState.canRestartService)
        assertEquals(0, wakeLockAcquireCount)

        service.disconnect()
        runCurrent()
    }

    @Test
    fun disconnect_releasesWakeLockAndClosesTunnel() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        service.ioDispatcher = testDispatcher
        service.coroutineScopeOverride = CoroutineScope(testDispatcher + SupervisorJob())

        `when`(mockTunnelManager.connect(validConfig)).thenReturn(
            TunnelResult(
                port = 3080,
                token = "token123",
                url = "http://127.0.0.1:3080/?token=token123",
                hostKeyFingerprint = "SHA256:fingerprint123"
            )
        )
        `when`(mockTunnelManager.isConnected()).thenReturn(true)

        service.connect(validConfig)
        runCurrent()

        service.disconnect()
        runCurrent()

        assertEquals(TunnelState.Disconnected, service.state.value)
        verify(mockTunnelManager).disconnect()
        assertEquals(1, wakeLockReleaseCount)
    }

    @Test
    fun cancelReconnect_abortsRetryLoopAndSetsDisconnected() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        service.ioDispatcher = testDispatcher
        service.coroutineScopeOverride = CoroutineScope(testDispatcher + SupervisorJob())

        doAnswer {
            throw RuntimeException("Network unreachable")
        }.`when`(mockTunnelManager).connect(validConfig)

        service.connect(validConfig)
        runCurrent()

        assertTrue(service.state.value is TunnelState.Reconnecting)

        service.cancelReconnect()
        runCurrent()

        assertEquals(TunnelState.Disconnected, service.state.value)
        verify(mockTunnelManager).disconnect()
    }

    @Test
    fun socketSever_triggersAutoReconnectLoop() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        service.ioDispatcher = testDispatcher
        service.coroutineScopeOverride = CoroutineScope(testDispatcher + SupervisorJob())

        var connectCount = 0
        doAnswer {
            connectCount++
            if (connectCount == 1) {
                TunnelResult(
                    port = 3080,
                    token = "tok",
                    url = "http://127.0.0.1:3080/?token=tok",
                    hostKeyFingerprint = "SHA256:fp"
                )
            } else {
                throw RuntimeException("Network down")
            }
        }.`when`(mockTunnelManager).connect(validConfig)
        `when`(mockTunnelManager.isConnected()).thenReturn(false)

        service.connect(validConfig)
        runCurrent()
        assertTrue(service.state.value is TunnelState.Connected)

        // Advance to trigger monitor loop check
        testDispatcher.scheduler.advanceTimeBy(service.monitorCheckIntervalMs + 5)
        runCurrent()

        try {
            assertTrue(service.state.value is TunnelState.Reconnecting)
            val reconnecting = service.state.value as TunnelState.Reconnecting
            assertEquals(1, reconnecting.attempt)
            assertEquals(1, wakeLockReleaseCount)
        } finally {
            service.cancelReconnect()
            runCurrent()
        }
    }

    @Test
    fun restartDshService_delegatesToTunnelManager() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        service.ioDispatcher = testDispatcher
        service.coroutineScopeOverride = CoroutineScope(testDispatcher + SupervisorJob())

        `when`(mockConfigRepo.getConfig()).thenReturn(validConfig)
        `when`(mockTunnelManager.restartDshService(config = validConfig)).thenReturn(true)

        val success = service.restartDshService(validConfig)
        assertTrue(success)
        verify(mockTunnelManager).restartDshService(config = validConfig)

        service.disconnect()
        runCurrent()
    }

    @Test
    fun localBinder_returnsServiceInstance() {
        val binder = service.LocalBinder()
        assertEquals(service, binder.getService())
    }
}
