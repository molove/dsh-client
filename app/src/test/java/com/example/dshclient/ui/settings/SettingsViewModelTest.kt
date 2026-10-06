package com.example.dshclient.ui.settings

import com.example.dshclient.data.SshConfig
import com.example.dshclient.data.SshConfigRepository
import com.example.dshclient.ssh.ConnectionTestResult
import com.example.dshclient.ssh.SshTunnelManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private lateinit var mockRepository: SshConfigRepository
    private lateinit var mockTunnelManager: SshTunnelManager
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setup() {
        mockRepository = mock(SshConfigRepository::class.java)
        mockTunnelManager = mock(SshTunnelManager::class.java)
    }

    @Test
    fun initialState_whenRepositoryHasNoConfig_usesDefaults() {
        `when`(mockRepository.getConfig()).thenReturn(null)

        val viewModel = SettingsViewModel(
            repository = mockRepository,
            tunnelManager = mockTunnelManager,
            dispatcher = testDispatcher
        )

        val state = viewModel.uiState.value
        assertEquals("", state.host)
        assertEquals("22", state.port)
        assertEquals("", state.user)
        assertEquals("", state.privateKey)
        assertEquals("", state.passphrase)
        assertNull(state.pinnedHostKey)
        assertNull(state.publicKeyDisplay)
        assertFalse(state.isTesting)
        assertNull(state.testStatus)
    }

    @Test
    fun initialState_whenRepositoryHasConfig_populatesState() {
        val savedConfig = SshConfig(
            host = "10.0.0.1",
            port = 2222,
            user = "testuser",
            privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\ntest\n-----END OPENSSH PRIVATE KEY-----",
            passphrase = "secret-passphrase",
            pinnedHostKey = "SHA256:abc123pinned"
        )
        `when`(mockRepository.getConfig()).thenReturn(savedConfig)

        val viewModel = SettingsViewModel(
            repository = mockRepository,
            tunnelManager = mockTunnelManager,
            dispatcher = testDispatcher
        )

        val state = viewModel.uiState.value
        assertEquals("10.0.0.1", state.host)
        assertEquals("2222", state.port)
        assertEquals("testuser", state.user)
        assertEquals("-----BEGIN OPENSSH PRIVATE KEY-----\ntest\n-----END OPENSSH PRIVATE KEY-----", state.privateKey)
        assertEquals("secret-passphrase", state.passphrase)
        assertEquals("SHA256:abc123pinned", state.pinnedHostKey)
        assertNull(state.publicKeyDisplay)
        assertFalse(state.isTesting)
        assertNull(state.testStatus)
    }

    @Test
    fun updateFields_updatesUiState() {
        `when`(mockRepository.getConfig()).thenReturn(null)
        val viewModel = SettingsViewModel(
            repository = mockRepository,
            tunnelManager = mockTunnelManager,
            dispatcher = testDispatcher
        )

        viewModel.updateHost("testhost.internal")
        assertEquals("testhost.internal", viewModel.uiState.value.host)

        viewModel.updatePort("2222")
        assertEquals("2222", viewModel.uiState.value.port)

        viewModel.updateUser("admin")
        assertEquals("admin", viewModel.uiState.value.user)

        viewModel.updatePrivateKey("sample-private-key")
        assertEquals("sample-private-key", viewModel.uiState.value.privateKey)

        viewModel.updatePassphrase("my-passphrase")
        assertEquals("my-passphrase", viewModel.uiState.value.passphrase)
    }

    @Test
    fun generateKeypair_updatesPrivateKeyAndPublicKeyDisplay() {
        `when`(mockRepository.getConfig()).thenReturn(null)
        val viewModel = SettingsViewModel(
            repository = mockRepository,
            tunnelManager = mockTunnelManager,
            dispatcher = testDispatcher
        )

        viewModel.generateKeypair("test-key")

        val state = viewModel.uiState.value
        assertTrue(state.privateKey.isNotEmpty())
        assertTrue(state.privateKey.contains("BEGIN OPENSSH PRIVATE KEY"))
        assertNotNull(state.publicKeyDisplay)
        assertTrue(state.publicKeyDisplay?.startsWith("ssh-ed25519") == true)
        assertTrue(state.publicKeyDisplay?.contains("test-key") == true)
    }

    @Test
    fun resetPinnedHostKey_clearsPinnedKeyInStateAndRepository() {
        val savedConfig = SshConfig(
            pinnedHostKey = "SHA256:somePinnedKey"
        )
        `when`(mockRepository.getConfig()).thenReturn(savedConfig)

        val viewModel = SettingsViewModel(
            repository = mockRepository,
            tunnelManager = mockTunnelManager,
            dispatcher = testDispatcher
        )

        assertEquals("SHA256:somePinnedKey", viewModel.uiState.value.pinnedHostKey)

        viewModel.resetPinnedHostKey()

        assertNull(viewModel.uiState.value.pinnedHostKey)
        verify(mockRepository).clearPinnedHostKey()
    }

    @Test
    fun testConnection_success_updatesIsTestingAndTestStatus() = testScope.runTest {
        val savedConfig = SshConfig(
            host = "192.0.2.1",
            port = 22,
            user = "q",
            privateKey = "valid-key"
        )
        `when`(mockRepository.getConfig()).thenReturn(savedConfig)
        `when`(mockTunnelManager.testConnection(savedConfig, SshTunnelManager.DEFAULT_CONNECT_TIMEOUT_MS)).thenReturn(
            ConnectionTestResult(
                success = true,
                hostKeyFingerprint = "SHA256:testFp123",
                errorMessage = null
            )
        )

        val viewModel = SettingsViewModel(
            repository = mockRepository,
            tunnelManager = mockTunnelManager,
            dispatcher = testDispatcher
        )

        viewModel.testConnection()

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isTesting)
        assertTrue(state.testStatus is ConnectionTestStatus.Success)
        val successStatus = state.testStatus as ConnectionTestStatus.Success
        assertEquals("SHA256:testFp123", successStatus.hostKeyFingerprint)
    }

    @Test
    fun testConnection_failure_updatesIsTestingAndTestStatus() = testScope.runTest {
        val savedConfig = SshConfig(
            host = "192.0.2.1",
            port = 22,
            user = "q",
            privateKey = "valid-key"
        )
        `when`(mockRepository.getConfig()).thenReturn(savedConfig)
        `when`(mockTunnelManager.testConnection(savedConfig, SshTunnelManager.DEFAULT_CONNECT_TIMEOUT_MS)).thenReturn(
            ConnectionTestResult(
                success = false,
                hostKeyFingerprint = null,
                errorMessage = "Host key mismatch"
            )
        )

        val viewModel = SettingsViewModel(
            repository = mockRepository,
            tunnelManager = mockTunnelManager,
            dispatcher = testDispatcher
        )

        viewModel.testConnection()

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isTesting)
        assertTrue(state.testStatus is ConnectionTestStatus.Error)
        val errorStatus = state.testStatus as ConnectionTestStatus.Error
        assertEquals("Host key mismatch", errorStatus.message)
    }

    @Test
    fun saveConfig_whenValid_persistsToRepositoryAndReturnsTrue() {
        `when`(mockRepository.getConfig()).thenReturn(null)
        val viewModel = SettingsViewModel(
            repository = mockRepository,
            tunnelManager = mockTunnelManager,
            dispatcher = testDispatcher
        )

        viewModel.updateHost("192.0.2.1")
        viewModel.updatePort("22")
        viewModel.updateUser("q")
        viewModel.updatePrivateKey("sample-private-key")
        viewModel.updatePassphrase("my-pass")

        val result = viewModel.saveConfig()

        assertTrue(result)
        val expectedConfig = SshConfig(
            host = "192.0.2.1",
            port = 22,
            user = "q",
            privateKey = "sample-private-key",
            passphrase = "my-pass",
            pinnedHostKey = null
        )
        verify(mockRepository).saveConfig(expectedConfig)
    }

    @Test
    fun saveConfig_whenInvalid_doesNotPersistAndReturnsFalse() {
        `when`(mockRepository.getConfig()).thenReturn(null)
        val viewModel = SettingsViewModel(
            repository = mockRepository,
            tunnelManager = mockTunnelManager,
            dispatcher = testDispatcher
        )

        // privateKey is empty by default -> invalid
        val result = viewModel.saveConfig()

        assertFalse(result)
        verify(mockRepository, never()).saveConfig(anyConfig())
    }

    @Test
    fun clearTestStatus_resetsTestStatusToNull() = testScope.runTest {
        val savedConfig = SshConfig(
            host = "192.0.2.1",
            port = 22,
            user = "q",
            privateKey = "valid-key"
        )
        `when`(mockRepository.getConfig()).thenReturn(savedConfig)
        `when`(mockTunnelManager.testConnection(savedConfig, SshTunnelManager.DEFAULT_CONNECT_TIMEOUT_MS)).thenReturn(
            ConnectionTestResult(success = true, hostKeyFingerprint = "SHA256:fingerprint")
        )

        val viewModel = SettingsViewModel(
            repository = mockRepository,
            tunnelManager = mockTunnelManager,
            dispatcher = testDispatcher
        )

        viewModel.testConnection()
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.testStatus)
        viewModel.clearTestStatus()
        assertNull(viewModel.uiState.value.testStatus)
    }

    @Test
    fun saveConfig_withInvalidPort_returnsFalse() {
        `when`(mockRepository.getConfig()).thenReturn(null)
        val viewModel = SettingsViewModel(
            repository = mockRepository,
            tunnelManager = mockTunnelManager,
            dispatcher = testDispatcher
        )

        viewModel.updateHost("192.0.2.1")
        viewModel.updateUser("q")
        viewModel.updatePrivateKey("valid-key")
        viewModel.updatePort("not-a-number")

        assertFalse(viewModel.saveConfig())

        viewModel.updatePort("70000") // out of range
        assertFalse(viewModel.saveConfig())
    }

    @Test
    fun saveConfig_trimsWhitespaceAndTreatsBlankPassphraseAsNull() {
        `when`(mockRepository.getConfig()).thenReturn(null)
        val viewModel = SettingsViewModel(
            repository = mockRepository,
            tunnelManager = mockTunnelManager,
            dispatcher = testDispatcher
        )

        viewModel.updateHost("  192.0.2.1  ")
        viewModel.updatePort(" 22 ")
        viewModel.updateUser("  q  ")
        viewModel.updatePrivateKey("  valid-key  ")
        viewModel.updatePassphrase("   ") // blank -> null

        assertTrue(viewModel.saveConfig())

        val expected = SshConfig(
            host = "192.0.2.1",
            port = 22,
            user = "q",
            privateKey = "valid-key",
            passphrase = null,
            pinnedHostKey = null
        )
        verify(mockRepository).saveConfig(expected)
    }

    private fun anyConfig(): SshConfig {
        any(SshConfig::class.java)
        return SshConfig()
    }
}
