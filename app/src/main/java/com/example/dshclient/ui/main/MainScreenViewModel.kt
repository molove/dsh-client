package com.example.dshclient.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.dshclient.data.SshConfigRepository
import com.example.dshclient.ssh.SshTunnelService
import com.example.dshclient.ssh.TunnelState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for MainScreen.
 *
 * @property tunnelState Current SSH tunnel lifecycle state.
 * @property url Current forwarded local URL for dsh-web (e.g. `http://127.0.0.1:3080/?token=...`).
 * @property hasValidConfig Whether valid SSH configuration exists in repository.
 * @property isRestartingService True if a remote `systemctl restart dsh-web` request is currently running.
 */
data class MainScreenUiState(
    val tunnelState: TunnelState = TunnelState.Disconnected,
    val url: String? = null,
    val hasValidConfig: Boolean = false,
    val isRestartingService: Boolean = false
)

/**
 * ViewModel managing MainScreen state, SshTunnelService state observation,
 * and delegating user actions (connect, disconnect, cancelReconnect, restartDshService).
 */
class MainScreenViewModel(
    private val configRepository: SshConfigRepository,
    initialService: SshTunnelService? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) : ViewModel() {

    private var tunnelService: SshTunnelService? = null
    private var serviceJob: Job? = null

    private val _uiState = MutableStateFlow(
        MainScreenUiState(
            hasValidConfig = configRepository.hasValidConfig()
        )
    )
    val uiState: StateFlow<MainScreenUiState> = _uiState.asStateFlow()

    init {
        if (initialService != null) {
            setTunnelService(initialService)
        }
    }

    /**
     * Checks if valid SSH configuration exists.
     */
    fun hasValidConfig(): Boolean = configRepository.hasValidConfig()

    /**
     * Refreshes the SSH configuration validity status from the repository.
     */
    fun refreshConfig() {
        _uiState.update { it.copy(hasValidConfig = configRepository.hasValidConfig()) }
    }

    /**
     * Attaches or detaches the [SshTunnelService] instance and observes its state StateFlow.
     */
    fun setTunnelService(service: SshTunnelService?) {
        tunnelService = service
        serviceJob?.cancel()
        if (service != null) {
            serviceJob = viewModelScope.launch(dispatcher) {
                service.state.collect { state ->
                    _uiState.update { current ->
                        val newUrl = (state as? TunnelState.Connected)?.url ?: current.url
                        current.copy(
                            tunnelState = state,
                            url = newUrl
                        )
                    }
                }
            }
        } else {
            _uiState.update { it.copy(tunnelState = TunnelState.Disconnected) }
        }
    }

    /**
     * Triggers SSH connection on the attached service.
     */
    fun connect() {
        refreshConfig()
        tunnelService?.connect()
    }

    /**
     * Disconnects the SSH tunnel on the attached service.
     */
    fun disconnect() {
        tunnelService?.disconnect()
    }

    /**
     * Cancels any active reconnection attempts on the attached service.
     */
    fun cancelReconnect() {
        tunnelService?.cancelReconnect()
    }

    /**
     * Restarts the remote `dsh-web` service and automatically reconnects on success.
     */
    fun restartDshService(onComplete: ((Boolean) -> Unit)? = null) {
        if (_uiState.value.isRestartingService) return
        _uiState.update { it.copy(isRestartingService = true) }
        viewModelScope.launch(dispatcher) {
            val success = try {
                tunnelService?.restartDshService() ?: false
            } catch (_: Exception) {
                false
            }
            _uiState.update { it.copy(isRestartingService = false) }
            onComplete?.invoke(success)
            if (success) {
                connect()
            }
        }
    }

    public override fun onCleared() {
        super.onCleared()
        setTunnelService(null)
    }
}

/**
 * Factory for creating [MainScreenViewModel] with dependencies.
 */
class MainScreenViewModelFactory(
    private val configRepository: SshConfigRepository,
    private val initialService: SshTunnelService? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MainScreenViewModel(configRepository, initialService, dispatcher) as T
    }
}
