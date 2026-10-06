package com.example.dshclient.ssh

/**
 * Models the lifecycle states of the SSH tunnel and remote connection to Q.
 */
sealed class TunnelState {
    /**
     * Tunnel is not connected and no connection attempts are active.
     */
    data object Disconnected : TunnelState()

    /**
     * Tunnel connection is in progress (initial or user-initiated retry).
     */
    data object Connecting : TunnelState()

    /**
     * Tunnel is active and local port forwarding is established.
     *
     * @property url The local forwarded URL to load in the WebView (e.g. `http://127.0.0.1:3080/?token=...`).
     * @property hostKeyFingerprint The verified OpenSSH SHA-256 host key fingerprint of the remote server.
     */
    data class Connected(
        val url: String,
        val hostKeyFingerprint: String? = null
    ) : TunnelState()

    /**
     * Connection was severed or failed; automatic exponential backoff retry is active.
     *
     * @property attempt The current retry attempt number (1-based).
     * @property nextRetrySeconds Remaining seconds until the next connection attempt.
     */
    data class Reconnecting(
        val attempt: Int,
        val nextRetrySeconds: Int
    ) : TunnelState()

    /**
     * Unrecoverable error occurred or max retries exceeded.
     *
     * @property message Human-readable error description.
     * @property canRestartService True if the error might be resolved by restarting the remote dsh service.
     */
    data class Error(
        val message: String,
        val canRestartService: Boolean = false
    ) : TunnelState()
}
