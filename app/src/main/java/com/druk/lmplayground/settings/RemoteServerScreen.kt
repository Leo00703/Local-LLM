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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.models.ServerLogo
import com.druk.lmplayground.remote.FoundServer
import com.druk.lmplayground.remote.SavedServer
import com.druk.lmplayground.remote.SavedServers

@Composable
fun RemoteServerScreen(
    serverName: String,
    serverUrl: String,
    apiKey: String,
    enabled: Boolean,
    scanning: Boolean,
    foundServers: List<FoundServer>,
    savedServers: List<SavedServer>,
    editingId: String?,
    saving: Boolean,
    onNameChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onScan: () -> Unit,
    onUseServer: (FoundServer) -> Unit,
    onSave: () -> Unit,
    onEditServer: (SavedServer) -> Unit,
    onDeleteServer: (SavedServer) -> Unit,
    onNewServer: () -> Unit,
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
            serverName = serverName,
            serverUrl = serverUrl,
            apiKey = apiKey,
            enabled = enabled,
            scanning = scanning,
            foundServers = foundServers,
            savedServers = savedServers,
            editingId = editingId,
            saving = saving,
            onNameChange = onNameChange,
            onUrlChange = onUrlChange,
            onApiKeyChange = onApiKeyChange,
            onEnabledChange = onEnabledChange,
            onScan = onScan,
            onUseServer = onUseServer,
            onSave = onSave,
            onEditServer = onEditServer,
            onDeleteServer = onDeleteServer,
            onNewServer = onNewServer,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}

@Composable
fun RemoteServerContent(
    serverName: String,
    serverUrl: String,
    apiKey: String,
    enabled: Boolean,
    scanning: Boolean,
    foundServers: List<FoundServer>,
    savedServers: List<SavedServer>,
    editingId: String?,
    saving: Boolean,
    onNameChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onScan: () -> Unit,
    onUseServer: (FoundServer) -> Unit,
    onSave: () -> Unit,
    onEditServer: (SavedServer) -> Unit,
    onDeleteServer: (SavedServer) -> Unit,
    onNewServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The server awaiting a "remove it?" answer.
    var pendingDelete by remember { mutableStateOf<SavedServer?>(null) }
    pendingDelete?.let { server ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(server.label) },
            text = { Text(stringResource(R.string.delete_server_message)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDeleteServer(server)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        // Master switch for the feature: when off, no server shows in the model picker.
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

        // The servers saved so far; tapping one loads it into the form below.
        if (savedServers.isNotEmpty()) {
            Text(
                text = stringResource(R.string.saved_servers),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
            )
            savedServers.forEach { server ->
                val selected = server.id == editingId
                // The software and the address, leaving out whichever the title already shows.
                val subtitle = listOf(server.type, SavedServers.hostOf(server.url))
                    .filter { it.isNotBlank() && it != server.label }
                    .joinToString(" · ")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEditServer(server) }
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ServerLogo(serverType = server.type)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = server.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                        if (subtitle.isNotBlank()) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                    IconButton(onClick = { pendingDelete = server }) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.delete),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }

        Text(
            text = stringResource(if (editingId != null) R.string.edit_server else R.string.add_server),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
        )

        // Server name (above the URL, per design) — a friendly label shown in
        // the model picker.
        OutlinedTextField(
            value = serverName,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.server_name)) },
            placeholder = { Text(stringResource(R.string.server_name_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

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

        // Optional API key — only llama.cpp servers started with --api-key need one.
        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            label = { Text(stringResource(R.string.server_api_key)) },
            placeholder = { Text(stringResource(R.string.server_api_key_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        // Save (adds the server to the list, or updates the one being edited) and, while editing,
        // a way back to an empty form.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onSave, enabled = serverUrl.isNotBlank() && !saving) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.save))
            }
            if (editingId != null) {
                TextButton(onClick = onNewServer) { Text(stringResource(R.string.new_server)) }
            }
        }

        // Scan
        OutlinedButton(
            onClick = onScan,
            enabled = !scanning,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (scanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
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
                            text = server.serverType + " · " + (server.firstModel
                                ?: stringResource(R.string.server_models_count, server.modelCount)),
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
