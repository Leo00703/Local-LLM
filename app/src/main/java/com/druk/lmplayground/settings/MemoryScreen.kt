@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.settings

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.data.MemoryNoteEntity

/**
 * Full-screen Memory management page reached from Settings. Users can toggle the
 * opt-in injection, view saved notes, add/edit/delete them by hand, or clear
 * everything. The same notes are what the model reads and writes via the
 * "memory" tool.
 */
@Composable
fun MemoryScreen(
    notes: List<MemoryNoteEntity>,
    memoryEnabled: Boolean,
    onBackClick: () -> Unit,
    onToggleMemory: (Boolean) -> Unit,
    onAdd: (String) -> Unit,
    onUpdate: (id: Long, text: String) -> Unit,
    onDelete: (id: Long) -> Unit,
    onClearAll: () -> Unit,
) {
    var editorTarget by remember { mutableStateOf<MemoryEditorTarget?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.memory)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    if (notes.isNotEmpty()) {
                        IconButton(onClick = { showClearConfirm = true }) {
                            Icon(
                                Icons.Outlined.DeleteSweep,
                                contentDescription = stringResource(R.string.memory_clear_all)
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editorTarget = MemoryEditorTarget.New }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add))
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            MemoryToggleRow(enabled = memoryEnabled, onToggle = onToggleMemory)
            HorizontalDivider()
            MemoryContent(
                notes = notes,
                onSelect = { editorTarget = MemoryEditorTarget.Edit(it) },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    MemoryEditorSheet(
        target = editorTarget,
        onAdd = onAdd,
        onUpdate = onUpdate,
        onDelete = onDelete,
        onDismiss = { editorTarget = null }
    )

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.memory_clear_all)) },
            text = { Text(stringResource(R.string.memory_clear_all_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    onClearAll()
                    showClearConfirm = false
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun MemoryToggleRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!enabled) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.memory_enabled_title),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(R.string.memory_enabled_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

/**
 * The notes list (or empty state). Kept separate so the header toggle stays
 * fixed above it while the cards scroll.
 */
@Composable
fun MemoryContent(
    notes: List<MemoryNoteEntity>,
    onSelect: (MemoryNoteEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (notes.isEmpty()) {
        Box(
            modifier = modifier.padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.memory_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    val configuration = LocalConfiguration.current
    val columns = if (configuration.screenWidthDp >= 840) {
        GridCells.Adaptive(minSize = 280.dp)
    } else {
        GridCells.Fixed(1)
    }

    LazyVerticalGrid(
        state = rememberLazyGridState(),
        columns = columns,
        modifier = modifier.fillMaxHeight(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(notes, key = { it.id }) { note ->
            MemoryCard(note = note, onClick = { onSelect(note) })
        }
    }
}

@Composable
private fun MemoryCard(note: MemoryNoteEntity, onClick: () -> Unit) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 84.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = note.content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val ts = if (note.updatedAt > note.createdAt) note.updatedAt else note.createdAt
                Text(
                    text = DateUtils.getRelativeTimeSpanString(
                        ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
                    ).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val category = note.category
                if (!category.isNullOrBlank()) {
                    Spacer(Modifier.width(8.dp))
                    CategoryChip(category)
                }
            }
        }
    }
}

@Composable
private fun CategoryChip(label: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/**
 * Bottom-sheet editor for a single memory. New = content only; Edit adds a
 * Delete button. Category is model-managed (shown on the card) so the manual
 * editor stays a plain text box.
 */
@Composable
fun MemoryEditorSheet(
    target: MemoryEditorTarget?,
    onAdd: (String) -> Unit,
    onUpdate: (id: Long, text: String) -> Unit,
    onDelete: (id: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    when (target) {
        MemoryEditorTarget.New -> MemoryEditorSheetBody(
            initialText = "",
            title = stringResource(R.string.memory_new),
            onPrimary = onAdd,
            onDelete = null,
            onDismiss = onDismiss
        )
        is MemoryEditorTarget.Edit -> MemoryEditorSheetBody(
            initialText = target.note.content,
            title = stringResource(R.string.memory_edit),
            onPrimary = { text -> onUpdate(target.note.id, text) },
            onDelete = { onDelete(target.note.id) },
            onDismiss = onDismiss
        )
        null -> Unit
    }
}

@Composable
private fun MemoryEditorSheetBody(
    initialText: String,
    title: String,
    onPrimary: (String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember(initialText) { mutableStateOf(initialText) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                placeholder = { Text(stringResource(R.string.memory_hint)) },
                textStyle = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onDelete != null) {
                    OutlinedButton(onClick = {
                        onDelete()
                        onDismiss()
                    }) { Text(stringResource(R.string.delete)) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = text.trim().isNotEmpty(),
                    onClick = {
                        onPrimary(text.trim())
                        onDismiss()
                    }
                ) { Text(stringResource(R.string.save)) }
            }
        }
    }
}

sealed class MemoryEditorTarget {
    object New : MemoryEditorTarget()
    data class Edit(val note: MemoryNoteEntity) : MemoryEditorTarget()
}
