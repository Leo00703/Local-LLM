package com.druk.lmplayground.models

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.remote.RemoteServerSection
import com.druk.lmplayground.remote.SavedServers
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect

@Composable
fun SelectModelDialog(
    models: List<ModelWithStatus>,
    /**
     * When > 0, the dialog Card is centered inside the chat pane rather than
     * the full window — used on tablet so the picker doesn't appear to cover
     * the sessions sidebar on its way to the chat area. Pass the sidebar's
     * effective width here (e.g. `320.dp`).
     */
    chatPaneStartOffset: Dp = 0.dp,
    hazeState: HazeState? = null,
    hazeStyle: HazeStyle = HazeStyle.Unspecified,
    onLoadModel: (ModelInfo) -> Unit,
    onBrowseModels: () -> Unit,
    /** True when the remote feature is on and at least one server is saved. */
    remoteServersConfigured: Boolean = false,
    /** The saved servers that answered when the picker opened, with their models. */
    remoteSections: List<RemoteServerSection> = emptyList(),
    remoteChecking: Boolean = false,
    /** The server whose section starts open (the one the last remote model came from). */
    initiallyExpandedServerId: String? = null,
    /** Called once when the picker opens: the cue to check which servers are online. */
    onOpened: () -> Unit = {},
    onLoadRemoteModel: (serverId: String, modelId: String) -> Unit = { _, _ -> },
    onDismissRequest: () -> Unit
) {
    // Only show downloaded models, grouped by provider (Qwen, Gemma, …) and
    // alphabetical within each group — same layout as the remote section below.
    val downloadedModels = remember(models) {
        models.filter { it.isDownloaded }
            .sortedWith(
                compareBy(
                    { ModelInfoProvider.providerGroup(it.model.name) },
                    { it.model.name.lowercase() }
                )
            )
    }
    LaunchedEffect(Unit) { onOpened() }
    // Sections the user opened or closed; the others follow the default (the last-used server).
    val expandedByUser = remember { mutableStateMapOf<String, Boolean>() }
    // Each online server's models grouped by provider (Qwen, Gemma, …), alphabetical by their
    // prettified display name within each group; a header precedes each group.
    val sortedSections = remember(remoteSections) {
        remoteSections.map { section ->
            section to section.models.sortedWith(
                compareBy({ ModelInfoProvider.providerGroup(it) }, { ModelInfoProvider.prettifyModelId(it) })
            )
        }
    }

    // Rendered as an in-composition overlay (not a platform Dialog) so the
    // frosted card can blur the chat behind it — Haze can't reach across the
    // separate window a Dialog uses. Already lives inside the chat pane, so
    // chatPaneStartOffset is no longer needed for centering. Back press and
    // taps on the scrim dismiss; taps on the card are consumed.
    BackHandler(onBack = onDismissRequest)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismissRequest
            ),
        contentAlignment = Alignment.Center
    ) {
        val frosted = hazeState != null
        Surface(
            // The card caps at a comfortable 560dp on wide windows while
            // expanding to fill narrow phones.
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                // Keep the card clear of the status/nav bars and cap its height
                // so a long remote model list scrolls inside instead of
                // overflowing past the status bar.
                .windowInsetsPadding(WindowInsets.systemBars)
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.85f).dp)
                .padding(16.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
            // Match the remote model-details card: same rounded-24dp shape,
            // hairline outline border and transparency, so all model surfaces
            // share one frosted look.
            shape = RoundedCornerShape(24.dp),
            color = if (frosted) Color.Transparent else MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = if (frosted) 0.dp else 6.dp,
            shadowElevation = 8.dp,
        ) {
            Box(
                modifier = if (frosted) {
                    Modifier.hazeEffect(hazeState!!, hazeStyle)
                } else Modifier
            ) {
            LazyColumn {
                // Remote servers, above the downloaded models: only the saved servers that answered
                // when the picker opened, each with its own models.
                if (remoteServersConfigured) {
                    if (remoteSections.isEmpty()) {
                        item(key = "remote-status") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                if (remoteChecking) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                }
                                Text(
                                    text = stringResource(
                                        if (remoteChecking) R.string.remote_checking_servers
                                        else R.string.remote_none_online
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    sortedSections.forEach { (section, sortedModels) ->
                        val serverId = section.server.id
                        val expanded = expandedByUser[serverId] ?: (serverId == initiallyExpandedServerId)
                        item(key = "remote-server:$serverId") {
                            RemoteServerHeader(
                                label = section.server.label,
                                serverType = section.server.type,
                                address = SavedServers.hostOf(section.server.url),
                                expanded = expanded,
                                onClick = { expandedByUser[serverId] = !expanded }
                            )
                        }
                        if (expanded) {
                            if (sortedModels.isEmpty()) {
                                item(key = "remote-empty:$serverId") {
                                    Text(
                                        text = stringResource(R.string.remote_no_models),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            } else {
                                itemsIndexed(
                                    items = sortedModels,
                                    key = { _, id -> "remote:$serverId:$id" }
                                ) { index, id ->
                                    val group = ModelInfoProvider.providerGroup(id)
                                    val firstInGroup = index == 0 ||
                                        ModelInfoProvider.providerGroup(sortedModels[index - 1]) != group
                                    Column {
                                        if (firstInGroup) ProviderHeader(group)
                                        RemoteModelRow(modelId = id) {
                                            onDismissRequest()
                                            onLoadRemoteModel(serverId, id)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item(key = "remote-divider") {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp))
                    }
                }
                if (downloadedModels.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.no_downloaded_models),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    itemsIndexed(
                        items = downloadedModels,
                        key = { _, m -> m.model.filename }
                    ) { index, modelWithStatus ->
                        val group = ModelInfoProvider.providerGroup(modelWithStatus.model.name)
                        val firstInGroup = index == 0 ||
                            ModelInfoProvider.providerGroup(downloadedModels[index - 1].model.name) != group
                        Column {
                            if (firstInGroup) ProviderHeader(group)
                            Model(model = modelWithStatus.model) {
                                onDismissRequest()
                                onLoadModel(modelWithStatus.model)
                            }
                        }
                    }
                }

                item {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            onDismissRequest()
                            onBrowseModels()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(stringResource(R.string.browse_more_models))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
        }
    }
}

@Composable
fun Model(
    model: ModelInfo,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            if (model.logoRes != 0) {
                Image(
                    painter = painterResource(id = model.logoRes),
                    contentDescription = null,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Start,
                    maxLines = 1
                )
                Text(
                    text = model.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    maxLines = 1
                )
                if (model.releaseDate != null) {
                    Text(
                        text = model.releaseDateLabel(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start,
                        maxLines = 1
                    )
                }
            }
        }
        ModelCapabilityIcons(
            model = model,
            modifier = Modifier.padding(end = 4.dp)
        )
        Icon(
            imageVector = Icons.Outlined.AutoAwesome,
            modifier = Modifier.padding(4.dp),
            tint = MaterialTheme.colorScheme.onSurface,
            contentDescription = null
        )
    }
}

/**
 * The mark of a server's software (LM Studio, Ollama, llama.cpp), or the generic server icon.
 * The LM Studio and llama.cpp marks are square (llama.cpp's is the two-shard negative-space
 * llama filling the full frame): keep their shape with a rounded-square clip rather than the
 * circle of the round Ollama mark, which would crop their corners.
 */
@Composable
fun ServerLogo(serverType: String, size: Dp = 28.dp) {
    val logoRes = when (serverType) {
        "LM Studio" -> R.drawable.logo_lmstudio
        "Ollama" -> R.drawable.logo_ollama
        "llama.cpp" -> R.drawable.logo_llamacpp
        else -> 0
    }
    if (logoRes != 0) {
        val logoShape = if (serverType == "LM Studio" || serverType == "llama.cpp") {
            RoundedCornerShape(7.dp)
        } else {
            CircleShape
        }
        Image(
            painter = painterResource(id = logoRes),
            contentDescription = null,
            // The llama.cpp mark is a monochrome vector: tint it with the
            // theme's foreground so it stays visible in both light and dark.
            colorFilter = if (serverType == "llama.cpp") {
                ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
            } else null,
            modifier = Modifier
                .size(size)
                .clip(logoShape),
            contentScale = ContentScale.Crop
        )
    } else {
        Icon(
            imageVector = Icons.Outlined.Dns,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(size)
        )
    }
}

@Composable
private fun RemoteServerHeader(
    label: String,
    serverType: String,
    address: String,
    expanded: Boolean,
    onClick: () -> Unit
) {
    // Subtitle: the software and the address, leaving out whichever the title already shows.
    val subtitle = listOf(serverType, address)
        .filter { it.isNotBlank() && it != label }
        .joinToString(" \u00B7 ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ServerLogo(serverType)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ProviderHeader(name: String) {
    Text(
        text = name,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp)
    )
}

@Composable
private fun RemoteModelRow(
    modelId: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(id = ModelInfoProvider.logoForModelId(modelId)),
            contentDescription = null,
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = ModelInfoProvider.prettifyModelId(modelId),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

@Preview
@Composable
fun SelectModelDialogPreview() {
    SelectModelDialog(
        models = ModelInfoProvider.allModels.take(3).mapIndexed { index, model ->
            ModelWithStatus(model = model, isDownloaded = index < 2)
        },
        onLoadModel = { },
        onBrowseModels = { },
        onDismissRequest = { }
    )
}
