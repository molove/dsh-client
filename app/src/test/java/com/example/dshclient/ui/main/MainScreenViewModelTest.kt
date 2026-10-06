package com.example.dshclient.ui.main

import com.example.dshclient.data.SshConfigRepository
import com.example.dshclient.ssh.SshTunnelService
import com.example.dshclient.ssh.TunnelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class MainScreenViewModelTest {

    private lateinit var mockConfigRepo: SshConfigRepository
    private lateinit var mockService: SshTunnelService
    private val serviceStateFlow = MutableStateFlow<TunnelState>(TunnelState.Disconnected)
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockConfigRepo = mock(SshConfigRepository::class.java)
        mockService = mock(SshTunnelService::class.java)
        `when`(mockService.state).thenReturn(serviceStateFlow)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialState_whenConfigNotValid() {
        `when`(mockConfigRepo.hasValidConfig()).thenReturn(false)

        val viewModel = MainScreenViewModel(mockConfigRepo, dispatcher = testDispatcher)

        assertFalse(viewModel.hasValidConfig())
        assertEquals(TunnelState.Disconnected, viewModel.uiState.value.tunnelState)
        assertNull(viewModel.uiState.value.url)
        assertFalse(viewModel.uiState.value.hasValidConfig)
    }

    @Test
    fun initialState_whenConfigValid() {
        `when`(mockConfigRepo.hasValidConfig()).thenReturn(true)

        val viewModel = MainScreenViewModel(mockConfigRepo, dispatcher = testDispatcher)

        assertTrue(viewModel.hasValidConfig())
        assertTrue(viewModel.uiState.value.hasValidConfig)
    }

    @Test
    fun setTunnelService_observesStateChanges() = runTest(testDispatcher) {
        `when`(mockConfigRepo.hasValidConfig()).thenReturn(true)
        val viewModel = MainScreenViewModel(mockConfigRepo, dispatcher = testDispatcher)

        viewModel.setTunnelService(mockService)
        advanceUntilIdle()

        assertEquals(TunnelState.Disconnected, viewModel.uiState.value.tunnelState)

        // Transition to Connecting
        serviceStateFlow.value = TunnelState.Connecting
        advanceUntilIdle()
        assertEquals(TunnelState.Connecting, viewModel.uiState.value.tunnelState)

        // Transition to Connected
        val connectedUrl = "http://127.0.0.1:3080/?token=abc"
        serviceStateFlow.value = TunnelState.Connected(url = connectedUrl)
        advanceUntilIdle()
        assertEquals(TunnelState.Connected(url = connectedUrl), viewModel.uiState.value.tunnelState)
        assertEquals(connectedUrl, viewModel.uiState.value.url)

        // Transition to Reconnecting
        serviceStateFlow.value = TunnelState.Reconnecting(attempt = 1, nextRetrySeconds = 2)
        advanceUntilIdle()
        assertEquals(TunnelState.Reconnecting(attempt = 1, nextRetrySeconds = 2), viewModel.uiState.value.tunnelState)
        // URL should be retained while reconnecting
        assertEquals(connectedUrl, viewModel.uiState.value.url)

        // Transition to Error
        serviceStateFlow.value = TunnelState.Error(message = "Connection dropped", canRestartService = true)
        advanceUntilIdle()
        assertEquals(TunnelState.Error(message = "Connection dropped", canRestartService = true), viewModel.uiState.value.tunnelState)
    }

    @Test
    fun connect_refreshesConfig_andDelegatesToService() = runTest(testDispatcher) {
        `when`(mockConfigRepo.hasValidConfig()).thenReturn(false).thenReturn(true)
        val viewModel = MainScreenViewModel(mockConfigRepo, initialService = mockService, dispatcher = testDispatcher)

        assertFalse(viewModel.uiState.value.hasValidConfig)

        viewModel.connect()
        advanceUntilIdle()

        verify(mockService).connect()
        assertTrue(viewModel.uiState.value.hasValidConfig)
    }

    @Test
    fun disconnect_delegatesToService() = runTest(testDispatcher) {
        `when`(mockConfigRepo.hasValidConfig()).thenReturn(true)
        val viewModel = MainScreenViewModel(mockConfigRepo, initialService = mockService, dispatcher = testDispatcher)

        viewModel.disconnect()
        advanceUntilIdle()

        verify(mockService).disconnect()
    }

    @Test
    fun cancelReconnect_delegatesToService() = runTest(testDispatcher) {
        `when`(mockConfigRepo.hasValidConfig()).thenReturn(true)
        val viewModel = MainScreenViewModel(mockConfigRepo, initialService = mockService, dispatcher = testDispatcher)

        viewModel.cancelReconnect()
        advanceUntilIdle()

        verify(mockService).cancelReconnect()
    }

    @Test
    fun setTunnelService_null_resetsToDisconnected() = runTest(testDispatcher) {
        `when`(mockConfigRepo.hasValidConfig()).thenReturn(true)
        val viewModel = MainScreenViewModel(mockConfigRepo, initialService = mockService, dispatcher = testDispatcher)
        serviceStateFlow.value = TunnelState.Connected(url = "http://127.0.0.1:3080/?token=abc")
        advanceUntilIdle()

        assertEquals(TunnelState.Connected(url = "http://127.0.0.1:3080/?token=abc"), viewModel.uiState.value.tunnelState)

        viewModel.setTunnelService(null)
        advanceUntilIdle()

        assertEquals(TunnelState.Disconnected, viewModel.uiState.value.tunnelState)
    }

    @Test
    fun restartDshService_whenSuccessful_triggersConnect() = runTest(testDispatcher) {
        `when`(mockConfigRepo.hasValidConfig()).thenReturn(true)
        `when`(mockService.restartDshService(null)).thenReturn(true)
        val viewModel = MainScreenViewModel(mockConfigRepo, initialService = mockService, dispatcher = testDispatcher)

        var resultReceived: Boolean? = null
        viewModel.restartDshService { resultReceived = it }

        advanceUntilIdle()

        assertEquals(true, resultReceived)
        assertFalse(viewModel.uiState.value.isRestartingService)
        verify(mockService).connect()
    }

    @Test
    fun restartDshService_whenFailed_doesNotTriggerConnect() = runTest(testDispatcher) {
        `when`(mockConfigRepo.hasValidConfig()).thenReturn(true)
        `when`(mockService.restartDshService(null)).thenReturn(false)
        val viewModel = MainScreenViewModel(mockConfigRepo, initialService = mockService, dispatcher = testDispatcher)

        var resultReceived: Boolean? = null
        viewModel.restartDshService { resultReceived = it }

        advanceUntilIdle()

        assertEquals(false, resultReceived)
        assertFalse(viewModel.uiState.value.isRestartingService)
        verify(mockService, never()).connect()
    }

    @Test
    fun restartDshService_whenThrowsException_resetsLoadingState() = runTest(testDispatcher) {
        `when`(mockConfigRepo.hasValidConfig()).thenReturn(true)
        `when`(mockService.restartDshService(null)).thenThrow(RuntimeException("SSH error"))
        val viewModel = MainScreenViewModel(mockConfigRepo, initialService = mockService, dispatcher = testDispatcher)

        var resultReceived: Boolean? = null
        viewModel.restartDshService { resultReceived = it }

        advanceUntilIdle()

        assertEquals(false, resultReceived)
        assertFalse(viewModel.uiState.value.isRestartingService)
        verify(mockService, never()).connect()
    }

    @Test
    fun nullService_methodsDoNotThrow() = runTest(testDispatcher) {
        `when`(mockConfigRepo.hasValidConfig()).thenReturn(true)
        val viewModel = MainScreenViewModel(mockConfigRepo, initialService = null, dispatcher = testDispatcher)

        viewModel.connect()
        viewModel.disconnect()
        viewModel.cancelReconnect()
        viewModel.restartDshService()
        advanceUntilIdle()

        assertEquals(TunnelState.Disconnected, viewModel.uiState.value.tunnelState)
        assertFalse(viewModel.uiState.value.isRestartingService)
    }

    @Test
    fun initialService_inConstructor_observesImmediately() = runTest(testDispatcher) {
        serviceStateFlow.value = TunnelState.Connecting
        `when`(mockConfigRepo.hasValidConfig()).thenReturn(true)

        val viewModel = MainScreenViewModel(mockConfigRepo, initialService = mockService, dispatcher = testDispatcher)
        advanceUntilIdle()

        assertEquals(TunnelState.Connecting, viewModel.uiState.value.tunnelState)
    }

    @Test
    fun onCleared_cleansUpService() = runTest(testDispatcher) {
        `when`(mockConfigRepo.hasValidConfig()).thenReturn(true)
        val viewModel = MainScreenViewModel(mockConfigRepo, initialService = mockService, dispatcher = testDispatcher)
        serviceStateFlow.value = TunnelState.Connected(url = "http://127.0.0.1:3080/?token=abc")
        advanceUntilIdle()

        assertEquals(TunnelState.Connected(url = "http://127.0.0.1:3080/?token=abc"), viewModel.uiState.value.tunnelState)

        viewModel.onCleared()
        advanceUntilIdle()

        assertEquals(TunnelState.Disconnected, viewModel.uiState.value.tunnelState)
    }
}
