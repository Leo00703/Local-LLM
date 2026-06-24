@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.remote.FoundServer

@Composable
fun RemoteServerScreen(
    serverUrl: String,
    modelName: String,
    enabled: Boolean,
    scanning: Boolean,
    foundServers: List<FoundServer>,
    onUrlChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onScan: () -> Unit,
    onUseServer: (FoundServer) -> Unit,
    onBackClick: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.remote_server)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        RemoteServerContent(
            serverUrl = serverUrl,
            modelName = modelName,
            enabled = enabled,
            scanning = scanning,
            foundServers = foundServers,
            onUrlChange = onUrlChange,
            onModelChange = onModelChange,
            onEnabledChange = onEnabledChange,
            onScan = onScan,
            onUseServer = onUseServer,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}

@Composable
fun RemoteServerContent(
    serverUrl: String,
    modelName: String,
    enabled: Boolean,
    scanning: Boolean,
    foundServers: List<FoundServer>,
    onUrlChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onScan: () -> Unit,
    onUseServer: (FoundServer) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        // Enable toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onEnabledChange(!enabled) }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Dns,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.use_remote_server_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.use_remote_server_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        OutlinedTextField(
            value = serverUrl,
            onValueChange = onUrlChange,
            label = { Text(stringResource(R.string.server_url)) },
            placeholder = { Text(stringResource(R.string.server_url_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        OutlinedTextField(
            value = modelName,
            onValueChange = onModelChange,
            label = { Text(stringResource(R.string.server_model)) },
            placeholder = { Text(stringResource(R.string.server_model_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        // Scan
        Button(
            onClick = onScan,
            enabled = !scanning,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (scanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.scanning))
            } else {
                Icon(Icons.Outlined.Wifi, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.scan_network))
            }
        }

        if (foundServers.isNotEmpty()) {
            Text(
                text = stringResource(R.string.found_servers),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
            )
            foundServers.forEach { server ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onUseServer(server) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(text = server.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = server.firstModel
                                ?: stringResource(R.string.server_models_count, server.modelCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}
