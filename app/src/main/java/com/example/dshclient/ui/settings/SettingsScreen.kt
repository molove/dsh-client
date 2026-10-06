package com.example.dshclient.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.dshclient.R
import com.example.dshclient.data.SshConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    repository: SshConfigRepository,
    onBack: () -> Unit,
    onSaveAndConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModelFactory(repository)
    )
    SettingsScreen(
        viewModel = viewModel,
        onBack = onBack,
        onSaveAndConnect = onSaveAndConnect,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onSaveAndConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val stream = context.contentResolver.openInputStream(uri)
                    if (stream == null) {
                        snackbarHostState.showSnackbar("Unable to open selected file")
                        return@launch
                    }
                    val content = stream.use { input ->
                        val bytes = ByteArray(65536)
                        val bytesRead = input.read(bytes)
                        if (bytesRead <= 0) "" else String(bytes, 0, bytesRead, Charsets.UTF_8)
                    }
                    if (content.isNotBlank()) {
                        viewModel.updatePrivateKey(content.trim())
                        snackbarHostState.showSnackbar("Imported private key from file")
                    } else {
                        snackbarHostState.showSnackbar("Selected file was empty")
                    }
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("Failed to read key file: ${e.message}")
                }
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("SSH Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section: Connection Endpoints
            Text(
                text = "Connection Endpoint",
                style = MaterialTheme.typography.titleMedium
            )

            OutlinedTextField(
                value = state.host,
                onValueChange = viewModel::updateHost,
                label = { Text("Host") },
                placeholder = { Text("e.g. 10.0.0.50 or hostname.lan") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = state.port,
                onValueChange = viewModel::updatePort,
                label = { Text("Port") },
                placeholder = { Text("22") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = state.user,
                onValueChange = viewModel::updateUser,
                label = { Text("SSH User") },
                placeholder = { Text("e.g. ubuntu or debian") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider()

            // Section: Authentication & Private Key
            Text(
                text = "Authentication",
                style = MaterialTheme.typography.titleMedium
            )

            OutlinedTextField(
                value = state.privateKey,
                onValueChange = viewModel::updatePrivateKey,
                label = { Text("OpenSSH Private Key") },
                placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----\n...") },
                minLines = 4,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val clip = clipboardManager.getText()?.text
                        if (!clip.isNullOrBlank()) {
                            viewModel.updatePrivateKey(clip.trim())
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Pasted private key from clipboard")
                            }
                        } else {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Clipboard is empty")
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("📋 Paste")
                }

                OutlinedButton(
                    onClick = { filePickerLauncher.launch("*/*") },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("📁 Import File")
                }
            }

            // Secondary: Generate Keypair
            OutlinedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Need a new key?",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "Generate a new Ed25519 keypair and append the public key to ~/.ssh/authorized_keys on the host.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = {
                            viewModel.generateKeypair()
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Generated Ed25519 keypair")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Generate New Keypair")
                    }

                    if (state.publicKeyDisplay != null) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Text(
                            text = "Public Key:",
                            style = MaterialTheme.typography.labelMedium
                        )
                        SelectionContainer {
                            Text(
                                text = state.publicKeyDisplay ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Button(
                            onClick = {
                                state.publicKeyDisplay?.let { pubKey ->
                                    clipboardManager.setText(AnnotatedString(pubKey))
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Public key copied to clipboard")
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("📋 Copy Public Key")
                        }
                    }
                }
            }

            OutlinedTextField(
                value = state.passphrase,
                onValueChange = viewModel::updatePassphrase,
                label = { Text("Passphrase (Optional)") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider()

            // Section: Pinned Host Key (TOFU)
            Text(
                text = "Host Key Verification (TOFU)",
                style = MaterialTheme.typography.titleMedium
            )

            if (state.pinnedHostKey != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Pinned Host Key:",
                            style = MaterialTheme.typography.labelMedium
                        )
                        SelectionContainer {
                            Text(
                                text = state.pinnedHostKey ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.resetPinnedHostKey()
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("Pinned host key reset")
                                }
                            }
                        ) {
                            Text("Reset Pin")
                        }
                    }
                }
            } else {
                Text(
                    text = "No host key pinned yet. The host key fingerprint will be verified and pinned on first connection (Trust-On-First-Use).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider()

            // Section: Test Connection & Status Banner
            Button(
                onClick = { viewModel.testConnection() },
                enabled = !state.isTesting,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Testing Connection...")
                } else {
                    Text("Test Connection")
                }
            }

            when (val testStatus = state.testStatus) {
                is ConnectionTestStatus.Success -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFE8F5E9),
                            contentColor = Color(0xFF1B5E20)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "✓ Connection Successful!",
                                style = MaterialTheme.typography.titleSmall
                            )
                            if (!testStatus.hostKeyFingerprint.isNullOrBlank()) {
                                Text(
                                    text = "Host Key: ${testStatus.hostKeyFingerprint}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
                is ConnectionTestStatus.Error -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFFEBEE),
                            contentColor = Color(0xFFB71C1C)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "✗ Connection Failed",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = testStatus.message,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                null -> { /* idle */ }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Primary: Save & Connect
            Button(
                onClick = {
                    if (viewModel.saveConfig()) {
                        onSaveAndConnect()
                    } else {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Please fill in valid Host, Port (1-65535), User, and Private Key")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save & Connect")
            }
        }
    }
}
