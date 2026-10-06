package com.example.dshclient.ssh

import com.example.dshclient.data.SshConfig
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * Thrown when the remote SSH host key does not match the pinned host key fingerprint.
 */
class HostKeyVerificationException(
    message: String,
    val expectedFingerprint: String? = null,
    val actualFingerprint: String? = null
) : JSchException(message)

/**
 * Thrown when the token scraper command succeeds in SSH execution but no valid token or port
 * could be extracted from the journalctl output.
 */
class TokenExtractionException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Result returned from [SshTunnelManager.testConnection].
 */
data class ConnectionTestResult(
    val success: Boolean,
    val hostKeyFingerprint: String? = null,
    val errorMessage: String? = null
) {
    val isSuccess: Boolean get() = success
    val fingerprint: String? get() = hostKeyFingerprint
}

/**
 * Result returned from a successful [SshTunnelManager.connect].
 */
data class TunnelResult(
    val port: Int,
    val token: String,
    val url: String,
    val hostKeyFingerprint: String?
)

/**
 * Output from executing a command over SSH ChannelExec.
 */
data class CommandResult(
    val exitStatus: Int,
    val stdout: String,
    val stderr: String
)

/**
 * HostKeyRepository implementing Trust-On-First-Use (TOFU) host-key pinning.
 *
 * If [pinnedHostKey] is null or blank, the first encountered host key is captured
 * and allowed (TOFU). If [pinnedHostKey] is set, any mismatch throws [HostKeyVerificationException].
 */
class TofuHostKeyRepository(
    private val pinnedHostKey: String?
) : HostKeyRepository {

    var capturedFingerprint: String? = null
        private set

    var capturedKeyBlob: ByteArray? = null
        private set

    override fun check(host: String, key: ByteArray): Int {
        val fingerprint = SshTunnelManager.calculateSha256Fingerprint(key)
        capturedFingerprint = fingerprint
        capturedKeyBlob = key

        if (pinnedHostKey.isNullOrBlank()) {
            // First-time connection (TOFU): capture fingerprint and allow
            return HostKeyRepository.OK
        }

        if (SshTunnelManager.matchesHostKey(pinnedHostKey, key, fingerprint)) {
            return HostKeyRepository.OK
        }

        throw HostKeyVerificationException(
            message = "Host key verification failed. The remote server key does not match pinned key. Expected: $pinnedHostKey, Actual: $fingerprint",
            expectedFingerprint = pinnedHostKey,
            actualFingerprint = fingerprint
        )
    }

    override fun add(hostkey: HostKey?, ui: UserInfo?) {}
    override fun remove(host: String?, type: String?) {}
    override fun remove(host: String?, type: String?, key: ByteArray?) {}
    override fun getKnownHostsRepositoryID(): String = "dsh-tofu"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}

/**
 * Manages SSH connectivity to Q, TOFU host key verification, remote command execution,
 * systemd service recovery, and local port forwarding.
 */
class SshTunnelManager(
    private val jschProvider: () -> JSch = { JSch() }
) {

    companion object {
        init {
            try {
                java.security.Security.removeProvider("BC")
                java.security.Security.insertProviderAt(org.bouncycastle.jce.provider.BouncyCastleProvider(), 1)
            } catch (_: Throwable) {}
        }

        const val DEFAULT_CONNECT_TIMEOUT_MS = 10000

        const val TOKEN_SCRAPER_COMMAND =
            "since=$(systemctl show dsh-web -p ActiveEnterTimestamp --value); journalctl -u dsh-web --since \"\$since\" --no-pager | grep -o 'http://127.0.0.1:[0-9]*/?token=[^[:space:]]*' | tail -1"

        const val RESTART_DSH_COMMAND = "systemctl restart dsh-web"

        /**
         * Calculates the standard OpenSSH SHA-256 fingerprint from a public key byte blob.
         * Formatted as: `SHA256:<base64-without-padding>`
         */
        fun calculateSha256Fingerprint(keyBlob: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(keyBlob)
            val b64 = Base64.getEncoder().withoutPadding().encodeToString(digest)
            return "SHA256:$b64"
        }

        /**
         * Matches a user-pinned host key string against the remote key blob and computed fingerprint.
         * Supports:
         * - `SHA256:...`
         * - Fingerprint without `SHA256:` prefix
         * - Raw base64 key blob
         * - OpenSSH line format: `ssh-ed25519 AAAAC3... [comment]`
         */
        fun matchesHostKey(pinned: String, keyBlob: ByteArray, calculatedFp: String): Boolean {
            val trimmedPinned = pinned.trim()
            val rawBase64 = Base64.getEncoder().encodeToString(keyBlob)
            val rawBase64NoPadding = Base64.getEncoder().withoutPadding().encodeToString(keyBlob)
            val calculatedNoPrefix = calculatedFp.removePrefix("SHA256:")

            if (trimmedPinned == calculatedFp) return true
            if (trimmedPinned == calculatedNoPrefix) return true
            if (trimmedPinned.equals(calculatedFp, ignoreCase = false)) return true
            if (trimmedPinned.equals(calculatedNoPrefix, ignoreCase = false)) return true
            if (trimmedPinned == rawBase64 || trimmedPinned == rawBase64NoPadding) return true
            if (trimmedPinned.contains(rawBase64) || trimmedPinned.contains(rawBase64NoPadding)) return true

            if (trimmedPinned.startsWith("ssh-")) {
                val parts = trimmedPinned.split("\\s+".toRegex())
                if (parts.size >= 2 && (parts[1] == rawBase64 || parts[1] == rawBase64NoPadding)) {
                    return true
                }
            }

            return false
        }
    }

    @Volatile
    var activeTofuRepo: TofuHostKeyRepository? = null
        internal set

    @Volatile
    private var activeSession: Session? = null

    @Volatile
    private var activePortForward: Int? = null

    @Volatile
    private var lastConfig: SshConfig? = null

    /**
     * Performs a lightweight SSH handshake to verify host reachability, credentials,
     * and capture/verify host key pinning without opening port forwards.
     */
    fun testConnection(
        config: SshConfig,
        timeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS
    ): ConnectionTestResult {
        if (!config.isValid) {
            return ConnectionTestResult(
                success = false,
                hostKeyFingerprint = null,
                errorMessage = "Invalid SSH configuration"
            )
        }

        var session: Session? = null
        return try {
            val jsch = jschProvider()
            val privateKeyBytes = config.privateKey.trim().toByteArray(Charsets.UTF_8)
            val passphraseBytes = config.passphrase?.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
            jsch.addIdentity("dsh-test-key", privateKeyBytes, null, passphraseBytes)

            session = jsch.getSession(config.user, config.host, config.port)
            val tofuRepo = TofuHostKeyRepository(config.pinnedHostKey)
            activeTofuRepo = tofuRepo
            session.setHostKeyRepository(tofuRepo)
            session.setConfig("StrictHostKeyChecking", "yes")

            session.connect(timeoutMs)

            val capturedFp = tofuRepo.capturedFingerprint
                ?: session.hostKey?.let {
                    try {
                        calculateSha256Fingerprint(Base64.getDecoder().decode(it.key))
                    } catch (_: Exception) {
                        null
                    }
                }

            ConnectionTestResult(
                success = true,
                hostKeyFingerprint = capturedFp,
                errorMessage = null
            )
        } catch (e: HostKeyVerificationException) {
            ConnectionTestResult(
                success = false,
                hostKeyFingerprint = e.actualFingerprint ?: activeTofuRepo?.capturedFingerprint,
                errorMessage = e.message
            )
        } catch (e: Exception) {
            ConnectionTestResult(
                success = false,
                hostKeyFingerprint = activeTofuRepo?.capturedFingerprint,
                errorMessage = e.message ?: e.toString()
            )
        } finally {
            try {
                session?.disconnect()
            } catch (_: Exception) {}
        }
    }

    /**
     * Connects an SSH session, verifies/pins the host key, executes the token scraper query,
     * sets up dynamic local port forwarding for the remote port, and returns [TunnelResult].
     */
    fun connect(
        config: SshConfig,
        timeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS
    ): TunnelResult {
        require(config.isValid) { "Invalid SSH configuration" }

        disconnect()
        lastConfig = config

        val jsch = jschProvider()
        val privateKeyBytes = config.privateKey.trim().toByteArray(Charsets.UTF_8)
        val passphraseBytes = config.passphrase?.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
        jsch.addIdentity("dsh-tunnel-key", privateKeyBytes, null, passphraseBytes)

        val session = jsch.getSession(config.user, config.host, config.port)
        val tofuRepo = TofuHostKeyRepository(config.pinnedHostKey)
        activeTofuRepo = tofuRepo
        session.setHostKeyRepository(tofuRepo)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setServerAliveInterval(5000)
        session.setServerAliveCountMax(2)

        session.connect(timeoutMs)
        activeSession = session

        val tokenOutput = try {
            val cmdResult = executeCommand(session, TOKEN_SCRAPER_COMMAND, timeoutMs)
            cmdResult.stdout.ifBlank { cmdResult.stderr }
        } catch (e: Exception) {
            disconnect()
            throw TokenExtractionException("Failed executing token scraper command: ${e.message}", e)
        }

        val parsed = TokenParser.parse(tokenOutput)
        if (parsed == null) {
            disconnect()
            throw TokenExtractionException("Failed to extract port and token from journal output: $tokenOutput")
        }

        val (port, token) = parsed

        try {
            session.setPortForwardingL(port, "127.0.0.1", port)
            activePortForward = port
        } catch (e: Exception) {
            disconnect()
            throw e
        }

        val capturedFp = tofuRepo.capturedFingerprint
            ?: session.hostKey?.let {
                try {
                    calculateSha256Fingerprint(Base64.getDecoder().decode(it.key))
                } catch (_: Exception) {
                    null
                }
            }

        return TunnelResult(
            port = port,
            token = token,
            url = "http://127.0.0.1:$port/?token=$token",
            hostKeyFingerprint = capturedFp
        )
    }

    /**
     * Executes the token scraper query on the currently active SSH session.
     */
    fun scrapeToken(timeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS): Pair<Int, String> {
        val session = activeSession ?: throw IllegalStateException("SSH session is not connected")
        if (!session.isConnected) {
            throw IllegalStateException("SSH session is disconnected")
        }

        val cmdResult = executeCommand(session, TOKEN_SCRAPER_COMMAND, timeoutMs)
        val output = cmdResult.stdout.ifBlank { cmdResult.stderr }
        return TokenParser.parse(output)
            ?: throw TokenExtractionException("Failed to extract port and token from journal output: $output")
    }

    /**
     * Restarts the remote `dsh-web` service via `systemctl restart dsh-web`.
     * Uses the active SSH session if available, otherwise opens a temporary connection using [config].
     */
    fun restartDshService(
        timeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        config: SshConfig? = null
    ): Boolean {
        val currentSession = activeSession
        if (currentSession != null && currentSession.isConnected) {
            return try {
                val result = executeCommand(currentSession, RESTART_DSH_COMMAND, timeoutMs)
                result.exitStatus == 0
            } catch (_: Exception) {
                false
            }
        }

        val cfg = config ?: lastConfig ?: return false
        if (!cfg.isValid) return false

        var tempSession: Session? = null
        return try {
            val jsch = jschProvider()
            val privateKeyBytes = cfg.privateKey.trim().toByteArray(Charsets.UTF_8)
            val passphraseBytes = cfg.passphrase?.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
            jsch.addIdentity("dsh-restart-key", privateKeyBytes, null, passphraseBytes)

            tempSession = jsch.getSession(cfg.user, cfg.host, cfg.port)
            val tofuRepo = TofuHostKeyRepository(cfg.pinnedHostKey)
            tempSession.setHostKeyRepository(tofuRepo)
            tempSession.setConfig("StrictHostKeyChecking", "yes")

            tempSession.connect(timeoutMs)
            val result = executeCommand(tempSession, RESTART_DSH_COMMAND, timeoutMs)
            result.exitStatus == 0
        } catch (_: Exception) {
            false
        } finally {
            try {
                tempSession?.disconnect()
            } catch (_: Exception) {}
        }
    }

    /**
     * Executes a command on the active SSH session.
     */
    fun executeCommand(
        command: String,
        timeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS
    ): CommandResult {
        val session = activeSession ?: throw IllegalStateException("SSH session is not connected")
        return executeCommand(session, command, timeoutMs)
    }

    /**
     * Executes a command on the given SSH session via ChannelExec.
     */
    fun executeCommand(
        session: Session,
        command: String,
        timeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS
    ): CommandResult {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)

        val stdoutStream = ByteArrayOutputStream()
        val stderrStream = ByteArrayOutputStream()
        channel.setOutputStream(stdoutStream)
        channel.setErrStream(stderrStream)

        try {
            channel.connect(timeoutMs)

            val startTime = System.currentTimeMillis()
            while (!channel.isClosed && channel.exitStatus == -1 && (System.currentTimeMillis() - startTime) < timeoutMs) {
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }

            // Grace period for exitStatus
            val graceStart = System.currentTimeMillis()
            while (channel.exitStatus == -1 && (System.currentTimeMillis() - graceStart) < 500) {
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }

            val exitStatus = channel.exitStatus
            val stdout = stdoutStream.toString(Charsets.UTF_8.name())
            val stderr = stderrStream.toString(Charsets.UTF_8.name())
            return CommandResult(exitStatus, stdout, stderr)
        } finally {
            channel.disconnect()
        }
    }

    /**
     * Disconnects the active SSH session and removes any local port forwarding.
     */
    fun disconnect() {
        try {
            activePortForward?.let { port ->
                activeSession?.delPortForwardingL(port)
            }
        } catch (_: Exception) {}

        try {
            activeSession?.disconnect()
        } catch (_: Exception) {}

        activePortForward = null
        activeSession = null
    }

    /**
     * Checks if the active SSH session is currently connected.
     */
    fun isConnected(): Boolean {
        return activeSession?.isConnected == true
    }

    /**
     * Returns the currently active locally forwarded port, if any.
     */
    fun getPortForward(): Int? = activePortForward

    /**
     * Returns the captured host key fingerprint for the active session.
     */
    fun getActiveHostKeyFingerprint(): String? = activeTofuRepo?.capturedFingerprint
}
