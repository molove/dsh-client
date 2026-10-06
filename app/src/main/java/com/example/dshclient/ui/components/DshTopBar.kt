package com.example.dshclient.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.dshclient.R
import com.example.dshclient.ssh.TunnelState
import com.example.dshclient.theme.DshClientTheme

/**
 * Thin top app bar displaying:
 * - App title ("dsh")
 * - Connection status dot (🟢 Connected, 🟡 Connecting/Reconnecting, 🔴 Disconnected/Error)
 * - Refresh icon button (reloads WebView or triggers reconnect)
 * - Settings icon button (opens SettingsScreen)
 */
@Composable
fun DshTopBar(
    tunnelState: TunnelState,
    onRefresh: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (dotColor, dotDesc) = when (tunnelState) {
        is TunnelState.Connected -> Pair(Color(0xFF4CAF50), "Connected")
        is TunnelState.Connecting -> Pair(Color(0xFFFFB300), "Connecting")
        is TunnelState.Reconnecting -> Pair(Color(0xFFFFB300), "Reconnecting")
        is TunnelState.Disconnected -> Pair(Color(0xFFE53935), "Disconnected")
        is TunnelState.Error -> Pair(Color(0xFFE53935), "Error")
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(48.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "dsh",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor)
                    .semantics { contentDescription = dotDesc }
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onRefresh) {
                Icon(
                    painter = painterResource(R.drawable.ic_refresh),
                    contentDescription = "Refresh",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = onSettingsClick) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings),
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DshTopBarConnectedPreview() {
    DshClientTheme {
        DshTopBar(
            tunnelState = TunnelState.Connected(url = "http://127.0.0.1:3080/"),
            onRefresh = {},
            onSettingsClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun DshTopBarReconnectingPreview() {
    DshClientTheme {
        DshTopBar(
            tunnelState = TunnelState.Reconnecting(attempt = 2, nextRetrySeconds = 4),
            onRefresh = {},
            onSettingsClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun DshTopBarDisconnectedPreview() {
    DshClientTheme {
        DshTopBar(
            tunnelState = TunnelState.Disconnected,
            onRefresh = {},
            onSettingsClick = {}
        )
    }
}
