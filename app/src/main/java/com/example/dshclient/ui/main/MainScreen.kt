package com.example.dshclient.ui.main

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.dshclient.data.SshConfigRepository
import com.example.dshclient.ssh.TunnelState
import com.example.dshclient.theme.DshClientTheme
import com.example.dshclient.ui.WebViewContainer
import com.example.dshclient.ui.components.DshTopBar

/**
 * Main application screen integrating DshTopBar, SshTunnelService state banners,
 * WebViewContainer with back navigation, and error recovery actions.
 */
@Composable
fun MainScreen(
    viewModel: MainScreenViewModel = viewModel(
        factory = MainScreenViewModelFactory(SshConfigRepository(LocalContext.current))
    ),
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    LaunchedEffect(state.url) {
        if (state.url.isNullOrBlank()) {
            webViewInstance = null
            canGoBack = false
        }
    }

    // Intercept back button if WebView can navigate backwards; otherwise fall through to system
    BackHandler(enabled = canGoBack) {
        webViewInstance?.let { wv ->
            wv.goBack()
            canGoBack = wv.canGoBack()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            DshTopBar(
                tunnelState = state.tunnelState,
                onRefresh = {
                    if (state.tunnelState is TunnelState.Connected) {
                        webViewInstance?.reload()
                    } else {
                        viewModel.connect()
                    }
                },
                onSettingsClick = onOpenSettings
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Reconnecting Banner
            if (state.tunnelState is TunnelState.Reconnecting) {
                val reconnectState = state.tunnelState as TunnelState.Reconnecting
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Reconnecting to Q in ${reconnectState.nextRetrySeconds}s...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.cancelReconnect() }) {
                            Text("Cancel")
                        }
                    }
                }
            }

            // Error Card / Banner
            if (state.tunnelState is TunnelState.Error) {
                val errorState = state.tunnelState as TunnelState.Error
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Connection Error",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = errorState.message,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (errorState.canRestartService) {
                                Button(
                                    onClick = { viewModel.restartDshService() },
                                    enabled = !state.isRestartingService,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    if (state.isRestartingService) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onError
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                    Text("Restart dsh-web")
                                }
                            }
                            OutlinedButton(onClick = onOpenSettings) {
                                Text("Settings")
                            }
                            Button(onClick = { viewModel.connect() }) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }

            // Unconfigured Banner if SSH not set up
            if (!state.hasValidConfig && state.tunnelState !is TunnelState.Error) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "SSH Not Configured",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Please configure your SSH host endpoint and authentication key to connect to Q.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(
                            onClick = onOpenSettings,
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Configure Settings")
                        }
                    }
                }
            }

            // WebView Container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                WebViewContainer(
                    url = state.url,
                    onWebViewCreated = { webViewInstance = it },
                    onCanGoBackChanged = { canGoBack = it },
                    onRelease = {
                        webViewInstance = null
                        canGoBack = false
                    }
                )
            }
        }
    }
}


/**
 * Kept for template navigation compatibility.
 */
@Composable
fun MainScreen(
    onItemClick: (androidx.navigation3.runtime.NavKey) -> Unit,
    modifier: Modifier = Modifier
) {
    MainScreen(
        onOpenSettings = {},
        modifier = modifier
    )
}

/**
 * Kept for template and instrumented test compatibility.
 */
@Composable
internal fun MainScreen(data: List<String>, modifier: Modifier = Modifier) {
    Column(modifier) {
        data.forEach { name ->
            Text(text = "Hello $name!", modifier = Modifier.padding(8.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenDataPreview() {
    DshClientTheme {
        MainScreen(listOf("Android"))
    }
}
