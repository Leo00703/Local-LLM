@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.storage

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.druk.lmplayground.R
import com.druk.lmplayground.models.ModelCapabilityIcons
import com.druk.lmplayground.models.ModelInfo
import com.druk.lmplayground.models.ModelInfoProvider
import com.druk.lmplayground.models.ModelWithStatus
import com.druk.lmplayground.models.releaseDateLabel
import com.druk.lmplayground.models.supportsLanguage
import com.druk.lmplayground.theme.PlaygroundTheme

/**
 * Standalone Models screen used by [ModelsFragment] when reached via
 * navigation on phone. Wraps [ModelsContent] in a Scaffold + back button. The
 * Content composable on its own is what the tablet Settings detail pane
 * embeds.
 */
@Composable
fun ModelsScreen(
    storageInfo: StorageInfo?,
    allModels: List<ModelWithStatus>,
    downloadingModels: Map<String, DownloadProgress>,
    snackbarMessage: String?,
    pendingMigration: MigrationState?,
    migrationProgress: MigrationProgress?,
    deviceLanguage: String,
    onBackClick: () -> Unit,
    onRefresh: () -> Unit = {},
    onChangeFolderClick: () -> Unit,
    onDeleteModel: (ModelInfo) -> Unit,
    onDownloadModel: (ModelInfo) -> Unit,
    onCancelDownload: (ModelInfo) -> Unit,
    onSnackbarDismiss: () -> Unit,
    onConfirmMigration: () -> Unit,
    onSkipMigration: () -> Unit,
    onCancelMigration: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.models)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.models_refresh)
                        )
                    }
                }
            )
        }
    ) { padding ->
        ModelsContent(
            storageInfo = storageInfo,
            allModels = allModels,
            downloadingModels = downloadingModels,
            snackbarMessage = snackbarMessage,
            pendingMigration = pendingMigration,
            migrationProgress = migrationProgress,
            deviceLanguage = deviceLanguage,
            snackbarHostState = snackbarHostState,
            onChangeFolderClick = onChangeFolderClick,
            onDeleteModel = onDeleteModel,
            onDownloadModel = onDownloadModel,
            onCancelDownload = onCancelDownload,
            onSnackbarDismiss = onSnackbarDismiss,
            onConfirmMigration = onConfirmMigration,
            onSkipMigration = onSkipMigration,
            onCancelMigration = onCancelMigration,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}

/**
 * Headless Models content (grid + storage card + dialogs) for embedding into
 * either [ModelsScreen]'s Scaffold or the tablet Settings detail pane. The
 * caller supplies a [snackbarHostState] so snackbars hosted by an outer
 * Scaffold get the messages from here.
 */
@Composable
fun ModelsContent(
    storageInfo: StorageInfo?,
    allModels: List<ModelWithStatus>,
    downloadingModels: Map<String, DownloadProgress>,
    snackbarMessage: String?,
    pendingMigration: MigrationState?,
    migrationProgress: MigrationProgress?,
    deviceLanguage: String,
    snackbarHostState: SnackbarHostState,
    onChangeFolderClick: () -> Unit,
    onDeleteModel: (ModelInfo) -> Unit,
    onDownloadModel: (ModelInfo) -> Unit,
    onCancelDownload: (ModelInfo) -> Unit,
    onSnackbarDismiss: () -> Unit,
    onConfirmMigration: () -> Unit,
    onSkipMigration: () -> Unit,
    onCancelMigration: () -> Unit,
    modifier: Modifier = Modifier,
    // Maximum content width — defaults to 960dp so the grid feels
    // anchored on tablets / Chromebook freeform windows. Callers
    // (e.g. tablet marketing screenshots) can override with
    // [Dp.Infinity] to fill the full pane width.
    maxContentWidth: Dp = 960.dp,
) {
    var modelToDelete by remember { mutableStateOf<ModelInfo?>(null) }

    // Show snackbar when message changes
    LaunchedEffect(snackbarMessage) {
        if (snackbarMessage != null) {
            snackbarHostState.showSnackbar(snackbarMessage)
            onSnackbarDismiss()
        }
    }

    // Split models into downloaded and available
    val downloadedModels = allModels.filter { it.isDownloaded }
    val availableModels = allModels.filter { !it.isDownloaded }

    // When the device language is non-English, separate models that support the user's
    // language from those that don't so users see relevant models first.
    val splitByLanguage = deviceLanguage != "en"
    val supportedModels = if (splitByLanguage) {
        availableModels.filter { it.model.supportsLanguage(deviceLanguage) }
    } else {
        availableModels
    }
    val otherModels = if (splitByLanguage) {
        availableModels.filterNot { it.model.supportsLanguage(deviceLanguage) }
    } else {
        emptyList()
    }

    // Adaptive grid: GridCells.Adaptive lays out as many ~340dp columns as
    // fit. At sw>=600dp the content is also capped to ~960dp wide so on a
    // 7" tablet landscape we get 2 columns, on a Chromebook freeform window
    // we get 2-3 depending on width, and on phones we get a single column.
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    // Scroll-edge divider: shows a HorizontalDivider just below the topbar
    // once the user has scrolled past the first item, signalling that
    // content is now hidden behind the bar (same pattern as the chat scaffold).
    val isScrolled by remember {
        androidx.compose.runtime.derivedStateOf {
            gridState.firstVisibleItemIndex > 0 ||
                gridState.firstVisibleItemScrollOffset > 0
        }
    }
    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = maxContentWidth)
        ) {
            if (isScrolled) {
                // Symmetric 12dp inset (same as the chat scaffold dividers)
                // so the line doesn't run all the way to the pane edges.
                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
            }
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 340.dp),
                modifier = Modifier.fillMaxHeight(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Storage info card spans the full grid width.
                if (storageInfo != null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        StorageInfoCard(
                            storageInfo = storageInfo,
                            onChangeFolderClick = onChangeFolderClick,
                            // No top padding — the card's top edge sits flush
                            // with the divider / topbar bottom so scrolling
                            // visually slides it under the bar.
                            modifier = Modifier.padding(
                                start = 16.dp,
                                end = 16.dp,
                                bottom = 16.dp,
                            )
                        )
                    }
                }

                // Downloaded section header
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = stringResource(R.string.downloaded_models),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                if (downloadedModels.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = stringResource(R.string.no_downloaded_models),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    items(downloadedModels, key = { it.model.filename }) { modelWithStatus ->
                        DownloadedModelItem(
                            model = modelWithStatus.model,
                            onDeleteClick = { modelToDelete = modelWithStatus.model }
                        )
                    }
                }

                // Available models — either a single section (English) or split into
                // language-supported and other models (non-English locales).
                if (supportedModels.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(
                                if (splitByLanguage) R.string.supports_your_language
                                else R.string.available_models
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    items(supportedModels, key = { it.model.filename }) { modelWithStatus ->
                        AvailableModelItem(
                            modelWithStatus = modelWithStatus,
                            downloadProgress = downloadingModels[modelWithStatus.model.name],
                            onDownloadClick = { onDownloadModel(modelWithStatus.model) },
                            onCancelClick = { onCancelDownload(modelWithStatus.model) }
                        )
                    }
                }

                if (otherModels.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.other_models),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    items(otherModels, key = { it.model.filename }) { modelWithStatus ->
                        AvailableModelItem(
                            modelWithStatus = modelWithStatus,
                            downloadProgress = downloadingModels[modelWithStatus.model.name],
                            onDownloadClick = { onDownloadModel(modelWithStatus.model) },
                            onCancelClick = { onCancelDownload(modelWithStatus.model) }
                        )
                    }
                }
            }
        }
    }
    // (one less `}` here than before — the Scaffold lambda now lives in
    // ModelsScreen above and this function ends after the dialogs below.)

    // Delete confirmation dialog
    modelToDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { modelToDelete = null },
            title = { Text(stringResource(R.string.delete_model_confirm, model.name)) },
            text = { Text(stringResource(R.string.delete_model_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteModel(model)
                        modelToDelete = null
                    }
                ) {
                    Text(stringResource(R.string.delete_model))
                }
            },
            dismissButton = {
                TextButton(onClick = { modelToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    
    // Migration confirmation dialog
    pendingMigration?.let { migration ->
        val context = LocalContext.current
        val totalSize = migration.modelsToMigrate.sumOf { it.sizeBytes }
        val sizeFormatted = Formatter.formatFileSize(context, totalSize)
        
        AlertDialog(
            onDismissRequest = onCancelMigration,
            title = { Text(stringResource(R.string.migrate_models_title)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            if (migration.isFromDownloads) {
                                R.string.migrate_models_from_downloads
                            } else {
                                R.string.migrate_models_message
                            },
                            migration.modelsToMigrate.size,
                            sizeFormatted
                        )
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.models_to_migrate),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    migration.modelsToMigrate.forEach { model ->
                        Text(
                            text = "• ${model.displayName}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirmMigration) {
                    Text(stringResource(R.string.migrate))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = onSkipMigration) {
                        Text(stringResource(R.string.skip))
                    }
                    TextButton(onClick = onCancelMigration) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        )
    }
    
    // Migration progress dialog
    migrationProgress?.let { progress ->
        AlertDialog(
            onDismissRequest = { /* Cannot dismiss while migrating */ },
            title = { Text(stringResource(R.string.migrating_models)) },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(
                            R.string.migration_progress,
                            progress.currentIndex,
                            progress.totalCount
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = progress.currentModel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { }
        )
    }
}

@Composable
private fun StorageInfoCard(
    storageInfo: StorageInfo,
    onChangeFolderClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val usedFormatted = Formatter.formatFileSize(context, storageInfo.usedBytes)
    val availableFormatted = Formatter.formatFileSize(context, storageInfo.availableBytes)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        // Compact layout: folder info on the left, change-folder button trailing
        // on the right; storage usage row directly below with progress bar
        // inline with available-bytes label. Trims roughly 40dp of vertical
        // space vs the previous stacked layout.
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (storageInfo.isCustomFolder)
                            stringResource(R.string.custom_folder)
                        else
                            stringResource(R.string.downloads_folder),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = storageInfo.path,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = onChangeFolderClick) {
                    Text(stringResource(R.string.change_folder))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (storageInfo.totalBytes > 0) {
                val progressValue = storageInfo.usedBytes.toFloat() / storageInfo.totalBytes.toFloat()
                LinearProgressIndicator(
                    progress = { progressValue.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.storage_used_models, usedFormatted),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.storage_available, availableFormatted),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.storage_used_models, usedFormatted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DownloadedModelItem(
    model: ModelInfo,
    onDeleteClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
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
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = model.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (model.releaseDate != null) {
                Text(
                    text = model.releaseDateLabel(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        ModelCapabilityIcons(model = model, modifier = Modifier.padding(end = 4.dp))
        IconButton(onClick = onDeleteClick) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.delete_model),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun AvailableModelItem(
    modelWithStatus: ModelWithStatus,
    downloadProgress: DownloadProgress?,
    onDownloadClick: () -> Unit,
    onCancelClick: () -> Unit
) {
    val model = modelWithStatus.model
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
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
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (downloadProgress != null) {
                Text(
                    text = formatDownloadStats(context, downloadProgress),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Text(
                    text = model.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (downloadProgress == null && model.releaseDate != null) {
                Text(
                    text = model.releaseDateLabel(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (downloadProgress == null) {
            ModelCapabilityIcons(model = model, modifier = Modifier.padding(end = 4.dp))
        }

        if (downloadProgress != null) {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                if (downloadProgress.progress >= 0f) {
                    CircularProgressIndicator(
                        progress = { downloadProgress.progress },
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp
                    )
                }
                IconButton(
                    onClick = onCancelClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.cancel),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            IconButton(onClick = onDownloadClick) {
                Icon(
                    imageVector = Icons.Outlined.Download,
                    contentDescription = stringResource(R.string.download_model),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private fun formatDownloadStats(context: Context, progress: DownloadProgress): String {
    if (progress.progress < 0f) return progress.status

    val parts = mutableListOf<String>()

    if (progress.totalBytes > 0) {
        val downloaded = Formatter.formatFileSize(context, progress.bytesDownloaded)
        val total = Formatter.formatFileSize(context, progress.totalBytes)
        parts.add("$downloaded / $total")
    } else if (progress.bytesDownloaded > 0) {
        parts.add(Formatter.formatFileSize(context, progress.bytesDownloaded))
    } else {
        return progress.status
    }

    if (progress.speedBytesPerSec > 0) {
        parts.add("${Formatter.formatFileSize(context, progress.speedBytesPerSec)}/s")
    }

    if (progress.etaSeconds > 0) {
        parts.add(formatEta(progress.etaSeconds))
    }

    return parts.joinToString(" \u2022 ")
}

private fun formatEta(seconds: Long): String {
    return when {
        seconds < 60 -> "${seconds}s left"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s left"
        else -> {
            val hours = seconds / 3600
            val mins = (seconds % 3600) / 60
            "${hours}h ${mins}m left"
        }
    }
}

@Preview
@Composable
private fun ModelsScreenPreview() {
    PlaygroundTheme {
        ModelsScreen(
            storageInfo = StorageInfo(
                path = "/storage/emulated/0/Download",
                usedBytes = 5_000_000_000,
                totalBytes = 64_000_000_000,
                availableBytes = 30_000_000_000,
                isCustomFolder = false
            ),
            allModels = ModelInfoProvider.allModels.take(5).mapIndexed { index, model ->
                ModelWithStatus(model = model, isDownloaded = index < 2)
            },
            downloadingModels = mapOf(
                "Gemma 3 4B" to DownloadProgress(
                    modelName = "Gemma 3 4B",
                    progress = 0.45f,
                    status = "Downloading…",
                    bytesDownloaded = 1_200_000_000L,
                    totalBytes = 2_700_000_000L,
                    speedBytesPerSec = 15_000_000L,
                    etaSeconds = 100L
                )
            ),
            snackbarMessage = null,
            pendingMigration = null,
            migrationProgress = null,
            deviceLanguage = "en",
            onBackClick = {},
            onChangeFolderClick = {},
            onDeleteModel = {},
            onDownloadModel = {},
            onCancelDownload = {},
            onSnackbarDismiss = {},
            onConfirmMigration = {},
            onSkipMigration = {},
            onCancelMigration = {}
        )
    }
}
