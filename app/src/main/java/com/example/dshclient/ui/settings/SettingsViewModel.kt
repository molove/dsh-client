package com.example.dshclient.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.dshclient.data.SshConfig
import com.example.dshclient.data.SshConfigRepository
import com.example.dshclient.data.SshKeyHelper
import com.example.dshclient.ssh.SshTunnelManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ConnectionTestStatus {
    data class Success(val hostKeyFingerprint: String?) : ConnectionTestStatus
    data class Error(val message: String) : ConnectionTestStatus
}

data class SettingsUiState(
    val host: String = "",
    val port: String = "22",
    val user: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
    val pinnedHostKey: String? = null,
    val publicKeyDisplay: String? = null,
    val isTesting: Boolean = false,
    val testStatus: ConnectionTestStatus? = null
)

class SettingsViewModel(
    private val repository: SshConfigRepository,
    private val tunnelManager: SshTunnelManager = SshTunnelManager(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(loadInitialState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private fun loadInitialState(): SettingsUiState {
        val savedConfig = repository.getConfig()
        return if (savedConfig != null) {
            SettingsUiState(
                host = savedConfig.host,
                port = savedConfig.port.toString(),
                user = savedConfig.user,
                privateKey = savedConfig.privateKey,
                passphrase = savedConfig.passphrase ?: "",
                pinnedHostKey = savedConfig.pinnedHostKey,
                publicKeyDisplay = null,
                isTesting = false,
                testStatus = null
            )
        } else {
            SettingsUiState()
        }
    }

    fun updateHost(host: String) {
        _uiState.update { it.copy(host = host) }
    }

    fun updatePort(port: String) {
        _uiState.update { it.copy(port = port) }
    }

    fun updateUser(user: String) {
        _uiState.update { it.copy(user = user) }
    }

    fun updatePrivateKey(privateKey: String) {
        _uiState.update { it.copy(privateKey = privateKey) }
    }

    fun updatePassphrase(passphrase: String) {
        _uiState.update { it.copy(passphrase = passphrase) }
    }

    fun clearTestStatus() {
        _uiState.update { it.copy(testStatus = null) }
    }

    fun generateKeypair(comment: String = "dsh-client") {
        val keyPair = SshKeyHelper.generateEd25519KeyPair(comment)
        _uiState.update {
            it.copy(
                privateKey = keyPair.privateKey,
                publicKeyDisplay = keyPair.publicKey
            )
        }
    }

    fun resetPinnedHostKey() {
        repository.clearPinnedHostKey()
        _uiState.update { it.copy(pinnedHostKey = null) }
    }

    fun testConnection() {
        if (_uiState.value.isTesting) return

        val currentState = _uiState.value
        val config = SshConfig(
            host = currentState.host.trim(),
            port = currentState.port.trim().toIntOrNull() ?: 0,
            user = currentState.user.trim(),
            privateKey = currentState.privateKey.trim(),
            passphrase = currentState.passphrase.ifBlank { null },
            pinnedHostKey = currentState.pinnedHostKey
        )

        _uiState.update { it.copy(isTesting = true, testStatus = null) }

        viewModelScope.launch(dispatcher) {
            val result = tunnelManager.testConnection(config)
            _uiState.update {
                it.copy(
                    isTesting = false,
                    testStatus = if (result.success) {
                        ConnectionTestStatus.Success(result.hostKeyFingerprint)
                    } else {
                        ConnectionTestStatus.Error(result.errorMessage ?: "Connection failed")
                    }
                )
            }
        }
    }

    fun saveConfig(): Boolean {
        val currentState = _uiState.value
        val portInt = currentState.port.trim().toIntOrNull() ?: 0
        val config = SshConfig(
            host = currentState.host.trim(),
            port = portInt,
            user = currentState.user.trim(),
            privateKey = currentState.privateKey.trim(),
            passphrase = currentState.passphrase.ifBlank { null },
            pinnedHostKey = currentState.pinnedHostKey
        )

        if (!config.isValid) {
            return false
        }

        repository.saveConfig(config)
        return true
    }
}

class SettingsViewModelFactory(
    private val repository: SshConfigRepository,
    private val tunnelManager: SshTunnelManager = SshTunnelManager(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SettingsViewModel(repository, tunnelManager, dispatcher) as T
    }
}
