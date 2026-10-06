package com.example.dshclient.ssh

import com.example.dshclient.data.SshConfig
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.util.Base64

class SshTunnelManagerTest {

    private lateinit var mockJSch: JSch
    private lateinit var mockSession: Session
    private lateinit var mockChannelExec: ChannelExec
    private lateinit var validConfig: SshConfig

    // A sample test Ed25519 public key blob (32 bytes key with wire prefix)
    private val sampleKeyBlob = Base64.getDecoder().decode("AAAAC3NzaC1lZDI1NTE5AAAAIOMq14wS9WkU/zU1K5xVl9GzB8sQ7o3pP1Q2r3s4t5u6")
    private val expectedSha256Fingerprint = SshTunnelManager.calculateSha256Fingerprint(sampleKeyBlob)

    @Before
    fun setUp() {
        mockJSch = mock(JSch::class.java)
        mockSession = mock(Session::class.java)
        mockChannelExec = mock(ChannelExec::class.java)

        `when`(mockJSch.getSession(anyString(), anyString(), anyInt())).thenReturn(mockSession)
        `when`(mockSession.openChannel("exec")).thenReturn(mockChannelExec)

        validConfig = SshConfig(
            host = "192.0.2.1",
            port = 22,
            user = "q",
            privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\ntest\n-----END OPENSSH PRIVATE KEY-----",
            pinnedHostKey = null
        )
    }

    // --- TOFU HostKeyRepository Tests ---

    @Test
    fun tofuRepository_withNullPinnedKey_capturesFingerprintAndAllowsConnection() {
        val repo = TofuHostKeyRepository(pinnedHostKey = null)

        val result = repo.check("192.0.2.1", sampleKeyBlob)

        assertEquals(HostKeyRepository.OK, result)
        assertEquals(expectedSha256Fingerprint, repo.capturedFingerprint)
        assertNotNull(repo.capturedKeyBlob)
    }

    @Test
    fun tofuRepository_withMatchingSha256Fingerprint_allowsConnection() {
        val repo = TofuHostKeyRepository(pinnedHostKey = expectedSha256Fingerprint)

        val result = repo.check("192.0.2.1", sampleKeyBlob)

        assertEquals(HostKeyRepository.OK, result)
        assertEquals(expectedSha256Fingerprint, repo.capturedFingerprint)
    }

    @Test
    fun tofuRepository_withMatchingSha256FingerprintWithoutPrefix_allowsConnection() {
        val noPrefix = expectedSha256Fingerprint.removePrefix("SHA256:")
        val repo = TofuHostKeyRepository(pinnedHostKey = noPrefix)

        val result = repo.check("192.0.2.1", sampleKeyBlob)

        assertEquals(HostKeyRepository.OK, result)
        assertEquals(expectedSha256Fingerprint, repo.capturedFingerprint)
    }

    @Test
    fun tofuRepository_withMatchingRawBase64Key_allowsConnection() {
        val rawBase64 = Base64.getEncoder().encodeToString(sampleKeyBlob)
        val repo = TofuHostKeyRepository(pinnedHostKey = rawBase64)

        val result = repo.check("192.0.2.1", sampleKeyBlob)

        assertEquals(HostKeyRepository.OK, result)
    }

    @Test
    fun tofuRepository_withMatchingOpenSshLine_allowsConnection() {
        val rawBase64 = Base64.getEncoder().encodeToString(sampleKeyBlob)
        val line = "ssh-ed25519 $rawBase64 user@testhost"
        val repo = TofuHostKeyRepository(pinnedHostKey = line)

        val result = repo.check("192.0.2.1", sampleKeyBlob)

        assertEquals(HostKeyRepository.OK, result)
    }

    @Test
    fun tofuRepository_withMismatchedPinnedKey_throwsHostKeyVerificationException() {
        val mismatchedPinned = "SHA256:differentFingerprint1234567890"
        val repo = TofuHostKeyRepository(pinnedHostKey = mismatchedPinned)

        val exception = assertThrows(HostKeyVerificationException::class.java) {
            repo.check("192.0.2.1", sampleKeyBlob)
        }

        assertEquals(mismatchedPinned, exception.expectedFingerprint)
        assertEquals(expectedSha256Fingerprint, exception.actualFingerprint)
        assertTrue(exception.message?.contains("Host key verification failed") == true)
    }

    // --- testConnection Tests ---

    @Test
    fun testConnection_withInvalidConfig_returnsFailureWithoutConnecting() {
        val manager = SshTunnelManager(jschProvider = { mockJSch })
        val invalidConfig = validConfig.copy(host = "")

        val result = manager.testConnection(invalidConfig)

        assertFalse(result.success)
        assertNotNull(result.errorMessage)
        verify(mockJSch, never()).getSession(anyString(), anyString(), anyInt())
    }

    @Test
    fun testConnection_withSuccessfulHandshake_returnsSuccessAndFingerprint() {
        val manager = SshTunnelManager(jschProvider = { mockJSch })

        // Simulate session.connect calling hostKeyRepository.check
        doAnswer {
            val repo = manager.activeTofuRepo
            repo?.check("192.0.2.1", sampleKeyBlob)
            null
        }.`when`(mockSession).connect(anyInt())

        val result = manager.testConnection(validConfig)

        assertTrue(result.success)
        assertEquals(expectedSha256Fingerprint, result.hostKeyFingerprint)
        assertNull(result.errorMessage)
        verify(mockSession).disconnect()
    }

    @Test
    fun testConnection_withHostKeyMismatch_returnsFailureAndFingerprint() {
        val manager = SshTunnelManager(jschProvider = { mockJSch })
        val mismatchedConfig = validConfig.copy(pinnedHostKey = "SHA256:wrongFingerprint")

        doAnswer {
            val repo = manager.activeTofuRepo
            repo?.check("192.0.2.1", sampleKeyBlob)
            null
        }.`when`(mockSession).connect(anyInt())

        val result = manager.testConnection(mismatchedConfig)

        assertFalse(result.success)
        assertEquals(expectedSha256Fingerprint, result.hostKeyFingerprint)
        assertTrue(result.errorMessage?.contains("Host key verification failed") == true)
        verify(mockSession).disconnect()
    }

    @Test
    fun testConnection_withNetworkOrAuthFailure_returnsFailureAndError() {
        val manager = SshTunnelManager(jschProvider = { mockJSch })
        `when`(mockSession.connect(anyInt())).thenThrow(JSchException("Connection refused"))

        val result = manager.testConnection(validConfig)

        assertFalse(result.success)
        assertNull(result.hostKeyFingerprint)
        assertEquals("Connection refused", result.errorMessage)
        verify(mockSession).disconnect()
    }

    // --- connect & Tunnel Lifecycle Tests ---

    @Test
    fun connect_withInvalidConfig_throwsIllegalArgumentException() {
        val manager = SshTunnelManager(jschProvider = { mockJSch })
        val invalidConfig = validConfig.copy(user = "")

        assertThrows(IllegalArgumentException::class.java) {
            manager.connect(invalidConfig)
        }
    }

    @Test
    fun connect_executesScraper_setsPortForwarding_andReturnsTunnelResult() {
        val manager = SshTunnelManager(jschProvider = { mockJSch })

        // Simulate token scraper output
        val scraperOutput = "http://127.0.0.1:3080/?token=scraped_token_xyz\n"
        setupMockChannelExec(mockChannelExec, stdout = scraperOutput, exitStatus = 0)

        doAnswer {
            manager.activeTofuRepo?.check("192.0.2.1", sampleKeyBlob)
            `when`(mockSession.isConnected).thenReturn(true)
            null
        }.`when`(mockSession).connect(anyInt())

        val tunnelResult = manager.connect(validConfig)

        assertEquals(3080, tunnelResult.port)
        assertEquals("scraped_token_xyz", tunnelResult.token)
        assertEquals("http://127.0.0.1:3080/?token=scraped_token_xyz", tunnelResult.url)
        assertEquals(expectedSha256Fingerprint, tunnelResult.hostKeyFingerprint)

        verify(mockSession).setPortForwardingL(3080, "127.0.0.1", 3080)
        assertTrue(manager.isConnected())
        assertEquals(3080, manager.getPortForward())
    }

    @Test
    fun connect_withTokenExtractionFailure_throwsTokenExtractionException() {
        val manager = SshTunnelManager(jschProvider = { mockJSch })

        // Scraper returns blank / no token
        setupMockChannelExec(mockChannelExec, stdout = "Service inactive\n", exitStatus = 1)

        doAnswer {
            `when`(mockSession.isConnected).thenReturn(true)
            null
        }.`when`(mockSession).connect(anyInt())

        assertThrows(TokenExtractionException::class.java) {
            manager.connect(validConfig)
        }
    }

    @Test
    fun scrapeToken_onConnectedSession_returnsExtractedPair() {
        val manager = SshTunnelManager(jschProvider = { mockJSch })

        setupMockChannelExec(mockChannelExec, stdout = "http://127.0.0.1:3232/?token=fresh_token\n", exitStatus = 0)
        `when`(mockSession.isConnected).thenReturn(true)

        // Simulate connect
        doAnswer {
            manager.activeTofuRepo?.check("192.0.2.1", sampleKeyBlob)
            null
        }.`when`(mockSession).connect(anyInt())

        manager.connect(validConfig)

        val pair = manager.scrapeToken()
        assertEquals(3232, pair.first)
        assertEquals("fresh_token", pair.second)
    }

    @Test
    fun restartDshService_executesRestartCommand() {
        val manager = SshTunnelManager(jschProvider = { mockJSch })

        setupMockChannelExec(mockChannelExec, stdout = "", exitStatus = 0)
        `when`(mockSession.isConnected).thenReturn(true)

        // Connect first
        val scraperOutput = "http://127.0.0.1:3080/?token=token123\n"
        setupMockChannelExec(mockChannelExec, stdout = scraperOutput, exitStatus = 0)
        manager.connect(validConfig)

        // Now test restartDshService
        setupMockChannelExec(mockChannelExec, stdout = "", exitStatus = 0)
        val success = manager.restartDshService()

        assertTrue(success)
        verify(mockChannelExec).setCommand("systemctl restart dsh-web")
    }

    @Test
    fun disconnect_cleansUpPortForwardingAndSession() {
        val manager = SshTunnelManager(jschProvider = { mockJSch })

        setupMockChannelExec(mockChannelExec, stdout = "http://127.0.0.1:3080/?token=xyz\n", exitStatus = 0)
        `when`(mockSession.isConnected).thenReturn(true)
        manager.connect(validConfig)

        assertTrue(manager.isConnected())

        manager.disconnect()

        verify(mockSession).delPortForwardingL(3080)
        verify(mockSession).disconnect()
        assertFalse(manager.isConnected())
        assertNull(manager.getPortForward())
    }

    private fun setupMockChannelExec(channel: ChannelExec, stdout: String, exitStatus: Int) {
        `when`(channel.isClosed).thenReturn(true)
        `when`(channel.exitStatus).thenReturn(exitStatus)

        // When channel.setOutputStream is called, or connect is called, write stdout to the output stream
        doAnswer { invocation ->
            val out = invocation.arguments[0] as? OutputStream
            out?.write(stdout.toByteArray(Charsets.UTF_8))
            null
        }.`when`(channel).setOutputStream(any(OutputStream::class.java))

        `when`(channel.inputStream).thenReturn(ByteArrayInputStream(stdout.toByteArray(Charsets.UTF_8)))
    }
}
