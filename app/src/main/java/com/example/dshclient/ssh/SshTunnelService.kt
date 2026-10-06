package com.example.dshclient.ssh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.dshclient.MainActivity
import com.example.dshclient.R
import com.example.dshclient.data.SshConfig
import com.example.dshclient.data.SshConfigRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that maintains the SSH tunnel connection to Q, handles automatic
 * exponential backoff reconnection, acquires and releases WakeLock, and displays a
 * persistent notification with interactive controls.
 */
class SshTunnelService : Service() {

    companion object {
        private const val TAG = "SshTunnelService"

        const val CHANNEL_ID = "dsh_tunnel_channel"
        const val CHANNEL_NAME = "dsh client"
        const val NOTIFICATION_ID = 1001
        const val NOTIFICATION_TITLE = "dsh client"

        const val ACTION_CONNECT = "com.example.dshclient.ssh.CONNECT"
        const val ACTION_DISCONNECT = "com.example.dshclient.ssh.DISCONNECT"
        const val ACTION_CANCEL_RECONNECT = "com.example.dshclient.ssh.CANCEL_RECONNECT"

        const val WAKELOCK_TIMEOUT_MS = 10 * 60 * 1000L // 10 minutes failsafe

        /**
         * Calculates exponential backoff reconnect delay:
         * 2s, 4s, 8s, max 15s.
         */
        fun calculateBackoffSeconds(attempt: Int): Int = when {
            attempt <= 1 -> 2
            attempt == 2 -> 4
            attempt == 3 -> 8
            else -> 15
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): SshTunnelService = this@SshTunnelService
    }

    private val binder = LocalBinder()

    private val _state = MutableStateFlow<TunnelState>(TunnelState.Disconnected)
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    // Testable / injectable hooks
    internal var tunnelManager: SshTunnelManager = SshTunnelManager()
    internal var configRepository: SshConfigRepository? = null
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    internal var coroutineScopeOverride: CoroutineScope? = null
    internal var monitorCheckIntervalMs: Long = 2000L
    internal var tickerDelayMs: Long = 1000L

    internal var wakeLockAcquirer: (() -> Unit)? = null
    internal var wakeLockReleaser: (() -> Unit)? = null
    internal var notificationUpdater: ((TunnelState) -> Unit)? = null

    private var defaultScope: CoroutineScope? = null
    private val scope: CoroutineScope
        get() = coroutineScopeOverride ?: defaultScope ?: CoroutineScope(ioDispatcher + SupervisorJob()).also {
            defaultScope = it
        }

    private var wakeLock: PowerManager.WakeLock? = null
    private var monitorJob: Job? = null
    private var reconnectJob: Job? = null
    private var connectJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        if (configRepository == null) {
            configRepository = SshConfigRepository(applicationContext)
        }
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification(_state.value)
        when (intent?.action) {
            ACTION_CONNECT -> connect()
            ACTION_DISCONNECT -> disconnect()
            ACTION_CANCEL_RECONNECT -> cancelReconnect()
        }
        return START_STICKY
    }

    /**
     * Initiates connection to the remote SSH server.
     *
     * @param config Optional explicit configuration; if null, retrieved from [configRepository].
     */
    fun connect(config: SshConfig? = null, force: Boolean = false) {
        if (!force && _state.value is TunnelState.Connected && config == null) {
            Log.d(TAG, "Already connected, ignoring redundant connect() request")
            return
        }
        reconnectJob?.cancel()
        reconnectJob = null
        monitorJob?.cancel()
        monitorJob = null
        connectJob?.cancel()

        _state.value = TunnelState.Connecting
        updateNotification(_state.value)

        connectJob = scope.launch(ioDispatcher) {
            val targetConfig = config ?: configRepository?.getConfig()
            if (targetConfig == null || !targetConfig.isValid) {
                _state.value = TunnelState.Error(
                    message = "Invalid or missing SSH configuration",
                    canRestartService = false
                )
                updateNotification(_state.value)
                return@launch
            }

            try {
                val result = tunnelManager.connect(targetConfig)
                acquireWakeLock()
                _state.value = TunnelState.Connected(
                    url = result.url,
                    hostKeyFingerprint = result.hostKeyFingerprint
                )
                updateNotification(_state.value)
                startMonitoringConnection(targetConfig)
            } catch (e: HostKeyVerificationException) {
                releaseWakeLock()
                _state.value = TunnelState.Error(
                    message = "Host key verification failed: ${e.message}",
                    canRestartService = false
                )
                updateNotification(_state.value)
            } catch (e: TokenExtractionException) {
                releaseWakeLock()
                _state.value = TunnelState.Error(
                    message = "Failed to extract token: ${e.message}",
                    canRestartService = true
                )
                updateNotification(_state.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Initial connect failed: ${e.message}, initiating auto-reconnect")
                triggerReconnect(targetConfig)
            }
        }
    }

    /**
     * Disconnects the SSH tunnel, cancels monitoring and reconnect jobs, and releases WakeLock.
     */
    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        monitorJob?.cancel()
        monitorJob = null

        releaseWakeLock()
        try {
            tunnelManager.disconnect()
        } catch (_: Exception) {}

        _state.value = TunnelState.Disconnected
        updateNotification(_state.value)
    }

    /**
     * Aborts any active reconnect attempts and transitions to [TunnelState.Disconnected].
     */
    fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        connectJob?.cancel()
        connectJob = null
        monitorJob?.cancel()
        monitorJob = null

        releaseWakeLock()
        try {
            tunnelManager.disconnect()
        } catch (_: Exception) {}

        _state.value = TunnelState.Disconnected
        updateNotification(_state.value)
    }

    /**
     * Restarts the remote `dsh-web` service via SSH.
     */
    suspend fun restartDshService(config: SshConfig? = null): Boolean = withContext(ioDispatcher) {
        val targetConfig = config ?: configRepository?.getConfig()
        tunnelManager.restartDshService(config = targetConfig)
    }

    /**
     * Blocking variant of [restartDshService] for non-coroutine callers.
     */
    fun restartDshServiceBlocking(config: SshConfig? = null): Boolean {
        val targetConfig = config ?: configRepository?.getConfig()
        return tunnelManager.restartDshService(config = targetConfig)
    }

    private fun startMonitoringConnection(config: SshConfig) {
        monitorJob?.cancel()
        monitorJob = scope.launch(ioDispatcher) {
            while (isActive) {
                delay(monitorCheckIntervalMs)
                if (!tunnelManager.isConnected()) {
                    Log.w(TAG, "SSH tunnel dropped unexpectedly. Triggering reconnect...")
                    triggerReconnect(config)
                    break
                }
            }
        }
    }

    private fun triggerReconnect(config: SshConfig) {
        if (reconnectJob?.isActive == true) return
        monitorJob?.cancel()
        releaseWakeLock()

        reconnectJob = scope.launch(ioDispatcher) {
            var attempt = 1
            while (isActive) {
                val backoffSeconds = calculateBackoffSeconds(attempt)
                for (remaining in backoffSeconds downTo 1) {
                    if (!isActive) return@launch
                    _state.value = TunnelState.Reconnecting(
                        attempt = attempt,
                        nextRetrySeconds = remaining
                    )
                    updateNotification(_state.value)
                    delay(tickerDelayMs)
                }

                if (!isActive) return@launch
                _state.value = TunnelState.Connecting
                updateNotification(_state.value)

                val activeConfig = configRepository?.getConfig() ?: config
                if (!activeConfig.isValid) {
                    _state.value = TunnelState.Error(
                        message = "Invalid SSH configuration during reconnect",
                        canRestartService = false
                    )
                    updateNotification(_state.value)
                    return@launch
                }

                try {
                    val result = tunnelManager.connect(activeConfig)
                    acquireWakeLock()
                    _state.value = TunnelState.Connected(
                        url = result.url,
                        hostKeyFingerprint = result.hostKeyFingerprint
                    )
                    updateNotification(_state.value)
                    startMonitoringConnection(activeConfig)
                    return@launch
                } catch (e: HostKeyVerificationException) {
                    releaseWakeLock()
                    _state.value = TunnelState.Error(
                        message = "Host key verification failed: ${e.message}",
                        canRestartService = false
                    )
                    updateNotification(_state.value)
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Reconnect attempt $attempt failed: ${e.message}")
                    attempt++
                }
            }
        }
    }

    private fun acquireWakeLock() {
        wakeLockAcquirer?.let {
            it.invoke()
            return
        }
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dsh:tunnel_wake_lock")?.apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.let {
                if (!it.isHeld) {
                    it.acquire(WAKELOCK_TIMEOUT_MS)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wake lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        wakeLockReleaser?.let {
            it.invoke()
            return
        }
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release wake lock: ${e.message}")
        }
    }

    fun buildNotificationContent(state: TunnelState): String = when (state) {
        is TunnelState.Disconnected -> "Disconnected"
        is TunnelState.Connecting -> "Connecting to Q..."
        is TunnelState.Connected -> {
            val portMatch = ":(\\d+)".toRegex().find(state.url)
            val portStr = portMatch?.groupValues?.get(1)?.let { " on :$it" } ?: ""
            "Connected to Q$portStr"
        }
        is TunnelState.Reconnecting -> {
            "Reconnecting to Q (attempt ${state.attempt}, retry in ${state.nextRetrySeconds}s)..."
        }
        is TunnelState.Error -> {
            "Error: ${state.message}"
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                if (notificationManager?.getNotificationChannel(CHANNEL_ID) == null) {
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "dsh SSH tunnel notifications"
                        setShowBadge(false)
                    }
                    notificationManager?.createNotificationChannel(channel)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed creating notification channel: ${e.message}")
            }
        }
    }

    fun buildNotification(state: TunnelState): Notification {
        createNotificationChannel()

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val contentText = buildNotificationContent(state)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(NOTIFICATION_TITLE)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_dsh_launcher_monochrome)
            .setOngoing(state is TunnelState.Connected || state is TunnelState.Connecting || state is TunnelState.Reconnecting)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        when (state) {
            is TunnelState.Reconnecting -> {
                val cancelIntent = getServicePendingIntent(1, ACTION_CANCEL_RECONNECT)
                builder.addAction(0, "Cancel Reconnect", cancelIntent)
            }
            is TunnelState.Connected -> {
                val disconnectIntent = getServicePendingIntent(2, ACTION_DISCONNECT)
                builder.addAction(0, "Disconnect", disconnectIntent)
            }
            is TunnelState.Disconnected, is TunnelState.Error -> {
                val connectIntent = getServicePendingIntent(3, ACTION_CONNECT)
                builder.addAction(0, "Connect", connectIntent)
            }
            else -> {}
        }

        return builder.build()
    }

    private fun getServicePendingIntent(requestCode: Int, actionStr: String): PendingIntent {
        val intent = Intent(this, SshTunnelService::class.java).apply {
            action = actionStr
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, requestCode, intent, flags)
        } else {
            PendingIntent.getService(this, requestCode, intent, flags)
        }
    }

    fun updateNotification(state: TunnelState) {
        notificationUpdater?.invoke(state) ?: run {
            try {
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                val notification = buildNotification(state)
                notificationManager?.notify(NOTIFICATION_ID, notification)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update notification: ${e.message}")
            }
        }
    }

    fun startForegroundWithNotification(state: TunnelState) {
        try {
            val notification = buildNotification(state)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to startForeground: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
        defaultScope?.cancel()
    }
}
