package com.example.dshclient

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.example.dshclient.data.SshConfigRepository
import com.example.dshclient.data.TokenRepository
import com.example.dshclient.ssh.SshTunnelService
import com.example.dshclient.theme.DshClientTheme
import com.example.dshclient.ui.main.MainScreen
import com.example.dshclient.ui.main.MainScreenViewModel
import com.example.dshclient.ui.main.MainScreenViewModelFactory
import com.example.dshclient.ui.settings.SettingsScreen

/**
 * Top-level destinations in the app.
 */
enum class AppDestination {
    Main,
    Settings
}

/**
 * Main entry activity that binds to SshTunnelService on startup, manages navigation
 * between MainScreen and SettingsScreen, and auto-navigates to Settings when unconfigured.
 */
class MainActivity : ComponentActivity() {

    private lateinit var configRepository: SshConfigRepository
    private lateinit var tokenRepository: TokenRepository
    private lateinit var mainViewModel: MainScreenViewModel

    private var tunnelService: SshTunnelService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? SshTunnelService.LocalBinder
            val service = localBinder?.getService()
            tunnelService = service
            mainViewModel.setTunnelService(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            tunnelService = null
            mainViewModel.setTunnelService(null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configRepository = SshConfigRepository(this)
        tokenRepository = TokenRepository(this)

        mainViewModel = ViewModelProvider(
            this,
            MainScreenViewModelFactory(configRepository)
        )[MainScreenViewModel::class.java]

        handleIntent(intent)

        // Bind to SshTunnelService on startup
        bindTunnelService()

        val hasConfigOnLaunch = configRepository.hasValidConfig()
        if (hasConfigOnLaunch) {
            startTunnelServiceAndConnect()
        }

        enableEdgeToEdge()
        setContent {
            DshClientTheme {
                var currentDestination by rememberSaveable {
                    mutableStateOf(
                        if (hasConfigOnLaunch) AppDestination.Main else AppDestination.Settings
                    )
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when (currentDestination) {
                        AppDestination.Main -> {
                            MainScreen(
                                viewModel = mainViewModel,
                                onOpenSettings = {
                                    currentDestination = AppDestination.Settings
                                }
                            )
                        }
                        AppDestination.Settings -> {
                            BackHandler {
                                currentDestination = AppDestination.Main
                            }
                            SettingsScreen(
                                repository = configRepository,
                                onBack = {
                                    currentDestination = AppDestination.Main
                                },
                                onSaveAndConnect = {
                                    startTunnelServiceAndConnect()
                                    mainViewModel.refreshConfig()
                                    currentDestination = AppDestination.Main
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun bindTunnelService() {
        val serviceIntent = Intent(this, SshTunnelService::class.java)
        isBound = bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun handleIntent(intent: Intent?) {
        val extractedToken = IntentParser.extractToken(intent)
        if (extractedToken != null) {
            tokenRepository.saveToken(extractedToken)
        }
    }

    private fun startTunnelServiceAndConnect() {
        val intent = Intent(this, SshTunnelService::class.java).apply {
            action = SshTunnelService.ACTION_CONNECT
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Failed to start tunnel service: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainViewModel.setTunnelService(null)
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
