package com.druk.lmplayground.conversation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.data.ChatSessionEntity
import com.druk.lmplayground.data.FolderEntity
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect

@Composable
fun SessionListDrawer(
    sessions: List<ChatSessionEntity>,
    folders: List<FolderEntity>,
    currentSessionId: String?,
    onSessionSelected: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onPinSession: (String, Boolean) -> Unit,
    onCreateFolder: (String) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onMoveSessionToFolder: (String, String?) -> Unit,
    onSettingsClicked: () -> Unit,
    currentFolderId: String? = null,
    onEnterFolder: (String) -> Unit = {},
    onExitFolder: () -> Unit = {},
    hazeState: HazeState? = null,
    hazeStyle: HazeStyle = HazeStyle.Unspecified,
) {
    // Floating frosted card: the drawer sheet itself is transparent so the
    // chat blurs through behind an inset, bordered Surface — matching the
    // model picker / details card look. Falls back to a solid surface when no
    // HazeState is supplied.
    val frosted = hazeState != null
    ModalDrawerSheet(
        modifier = Modifier.width(300.dp),
        drawerContainerColor = Color.Transparent,
        drawerTonalElevation = 0.dp,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxHeight()
                .padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            shape = RoundedCornerShape(24.dp),
            color = if (frosted) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
            // A transparent (frosted) Surface yields no implicit content color,
            // so set it explicitly or the text falls back to default black.
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = if (frosted) 0.dp else 2.dp,
        ) {
            Box(modifier = if (frosted) Modifier.hazeEffect(hazeState!!, hazeStyle) else Modifier) {
                SessionListContent(
                    sessions = sessions,
                    folders = folders,
                    currentSessionId = currentSessionId,
                    onSessionSelected = onSessionSelected,
                    onDeleteSession = onDeleteSession,
                    onRenameSession = onRenameSession,
                    onPinSession = onPinSession,
                    onCreateFolder = onCreateFolder,
                    onRenameFolder = onRenameFolder,
                    onDeleteFolder = onDeleteFolder,
                    onMoveSessionToFolder = onMoveSessionToFolder,
                    onSettingsClicked = onSettingsClicked,
                    currentFolderId = currentFolderId,
                    onEnterFolder = onEnterFolder,
                    onExitFolder = onExitFolder
                )
            }
        }
    }
}

@Composable
fun PermanentSessionList(
    sessions: List<ChatSessionEntity>,
    folders: List<FolderEntity>,
    currentSessionId: String?,
    onSessionSelected: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onPinSession: (String, Boolean) -> Unit,
    onCreateFolder: (String) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onMoveSessionToFolder: (String, String?) -> Unit,
    onSettingsClicked: () -> Unit,
    currentFolderId: String? = null,
    onEnterFolder: (String) -> Unit = {},
    onExitFolder: () -> Unit = {},
    width: Dp = 320.dp
) {
    // "Floating card" sidebar (Apple Maps / Liquid Glass style): the drawer
    // sheet itself is transparent so the chat surface bleeds through around
    // the card, and the actual sessions list sits inside an inset Surface
    // with rounded corners and a small tonal step. The card is padded from
    // every edge (status bar at top picked up by safeDrawing insets), so it
    // visibly floats rather than meeting any window edge.
    PermanentDrawerSheet(
        modifier = Modifier.width(width),
        drawerContainerColor = Color.Transparent,
        drawerTonalElevation = 0.dp,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxHeight()
                // Only inset from the left edge — the status bar above and
                // navigation bar below already provide vertical breathing
                // room, and the chat pane handles the right side itself.
                // Small 4dp top breathing room so the card doesn't kiss the
                // status bar. The inner header spacer is dropped to 0 to
                // compensate so "Conversations" / gear stay at the same
                // absolute Y as the chat title / new-chat icon.
                .padding(start = 12.dp, top = 4.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = 2.dp,
        ) {
            SessionListContent(
                sessions = sessions,
                folders = folders,
                currentSessionId = currentSessionId,
                onSessionSelected = onSessionSelected,
                onDeleteSession = onDeleteSession,
                onRenameSession = onRenameSession,
                onPinSession = onPinSession,
                onCreateFolder = onCreateFolder,
                onRenameFolder = onRenameFolder,
                onDeleteFolder = onDeleteFolder,
                onMoveSessionToFolder = onMoveSessionToFolder,
                onSettingsClicked = onSettingsClicked,
                currentFolderId = currentFolderId,
                onEnterFolder = onEnterFolder,
                onExitFolder = onExitFolder,
                // Tablet path runs alongside the compact (40dp) top bar, so
                // the sidebar header shrinks to match — "Conversations" lines
                // up with "Select Model" and the gear icon with the new-chat
                // icon on the right.
                compact = true,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionListContent(
    sessions: List<ChatSessionEntity>,
    folders: List<FolderEntity>,
    currentSessionId: String?,
    onSessionSelected: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onPinSession: (String, Boolean) -> Unit,
    onCreateFolder: (String) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onMoveSessionToFolder: (String, String?) -> Unit,
    onSettingsClicked: () -> Unit,
    // Folder navigation: the drawer shows the root (folders + unfiled chats) when
    // [currentFolderId] is null, or the contents of that folder otherwise. Tapping
    // a folder ENTERS it (onEnterFolder); the back arrow leaves it (onExitFolder).
    currentFolderId: String? = null,
    onEnterFolder: (String) -> Unit = {},
    onExitFolder: () -> Unit = {},
    /**
     * When true, the header row matches the 40dp compact top bar height so
     * the "Conversations" title aligns horizontally with the chat title and
     * the gear icon aligns with the new-chat icon. Defaults to the 64dp
     * phone layout used by the modal drawer.
     */
    compact: Boolean = false
) {
    var renameDialogSession by remember { mutableStateOf<ChatSessionEntity?>(null) }
    var moveDialogSession by remember { mutableStateOf<ChatSessionEntity?>(null) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var renameFolderDialog by remember { mutableStateOf<FolderEntity?>(null) }
    var deleteFolderDialog by remember { mutableStateOf<FolderEntity?>(null) }

    // Group sessions by folder. Sessions whose folderId no longer matches an
    // existing folder fall back to "unfiled" so nothing ever disappears.
    val folderIds = folders.map { it.id }.toSet()
    val grouped = sessions.groupBy { session ->
        session.folderId?.takeIf { it in folderIds }
    }
    val unfiled = grouped[null].orEmpty()
    val hasFolders = folders.isNotEmpty()
    // The folder we're currently inside (null = root). If it was deleted, fall
    // back to root so the list never strands the user in a missing folder.
    val activeFolder = currentFolderId?.let { id -> folders.find { it.id == id } }

    Column(
        modifier = Modifier.fillMaxHeight()
    ) {
        // Header alignment with the chat top bar is handled by the sidebar
        // Surface's 4dp top padding (see PermanentSessionList).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 40.dp else 64.dp)
                .padding(start = if (activeFolder != null) 4.dp else 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (activeFolder != null) {
                // Inside a folder: back arrow + the folder's name as the title.
                IconButton(onClick = onExitFolder) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = activeFolder.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Text(
                    text = stringResource(R.string.conversations),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showCreateFolderDialog = true }) {
                    Icon(
                        imageVector = Icons.Outlined.CreateNewFolder,
                        contentDescription = stringResource(R.string.new_folder),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onSettingsClicked) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.settings),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        LazyColumn(
            // .imePadding() so the list shrinks when the soft keyboard opens —
            // otherwise the LazyColumn extends behind the IME and its bottom
            // items are unreachable.
            modifier = Modifier
                .weight(1f)
                .imePadding()
        ) {
            if (activeFolder != null) {
                // Inside a folder: only its chats. An empty folder shows a hint.
                val folderSessions = grouped[activeFolder.id].orEmpty()
                if (folderSessions.isEmpty()) {
                    item(key = "folder-empty") {
                        Text(
                            text = stringResource(R.string.folder_empty_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                }
                items(folderSessions, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        isSelected = session.id == currentSessionId,
                        indented = false,
                        showMoveAction = true,
                        onSelected = { onSessionSelected(session.id) },
                        onPin = { pinned -> onPinSession(session.id, pinned) },
                        onRenameRequest = { renameDialogSession = session },
                        onDelete = { onDeleteSession(session.id) },
                        onMoveRequest = { moveDialogSession = session }
                    )
                }
            } else {
                // Root: folders (tap to enter), then the unfiled chats.
                items(folders, key = { "folder:${it.id}" }) { folder ->
                    FolderRow(
                        name = folder.name,
                        count = grouped[folder.id].orEmpty().size,
                        onClick = { onEnterFolder(folder.id) },
                        onRenameRequest = { renameFolderDialog = folder },
                        onDeleteRequest = { deleteFolderDialog = folder }
                    )
                }
                items(unfiled, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        isSelected = session.id == currentSessionId,
                        indented = false,
                        showMoveAction = hasFolders,
                        onSelected = { onSessionSelected(session.id) },
                        onPin = { pinned -> onPinSession(session.id, pinned) },
                        onRenameRequest = { renameDialogSession = session },
                        onDelete = { onDeleteSession(session.id) },
                        onMoveRequest = { moveDialogSession = session }
                    )
                }
            }
        }
    }

    // Rename chat dialog
    renameDialogSession?.let { session ->
        var text by remember(session.id) { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { renameDialogSession = null },
            title = { Text(stringResource(R.string.rename_conversation)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameSession(session.id, text.trim())
                        renameDialogSession = null
                    },
                    enabled = text.isNotBlank()
                ) {
                    Text(stringResource(R.string.rename))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameDialogSession = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Move chat to folder dialog. Tapping an option acts immediately.
    moveDialogSession?.let { session ->
        AlertDialog(
            onDismissRequest = { moveDialogSession = null },
            title = { Text(stringResource(R.string.move_to_folder)) },
            text = {
                Column {
                    FolderChoiceRow(
                        label = stringResource(R.string.no_folder),
                        selected = session.folderId == null,
                        onClick = {
                            onMoveSessionToFolder(session.id, null)
                            moveDialogSession = null
                        }
                    )
                    folders.forEach { folder ->
                        FolderChoiceRow(
                            label = folder.name,
                            selected = session.folderId == folder.id,
                            onClick = {
                                onMoveSessionToFolder(session.id, folder.id)
                                moveDialogSession = null
                            }
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { moveDialogSession = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Create folder dialog
    if (showCreateFolderDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text(stringResource(R.string.new_folder)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.folder_name)) }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onCreateFolder(name.trim())
                        showCreateFolderDialog = false
                    },
                    enabled = name.isNotBlank()
                ) {
                    Text(stringResource(R.string.create))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Rename folder dialog
    renameFolderDialog?.let { folder ->
        var text by remember(folder.id) { mutableStateOf(folder.name) }
        AlertDialog(
            onDismissRequest = { renameFolderDialog = null },
            title = { Text(stringResource(R.string.rename_folder)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.folder_name)) }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameFolder(folder.id, text.trim())
                        renameFolderDialog = null
                    },
                    enabled = text.isNotBlank()
                ) {
                    Text(stringResource(R.string.rename))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameFolderDialog = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Delete folder confirmation — deletes the folder AND its chats.
    deleteFolderDialog?.let { folder ->
        val count = grouped[folder.id]?.size ?: 0
        AlertDialog(
            onDismissRequest = { deleteFolderDialog = null },
            title = { Text(stringResource(R.string.delete_folder)) },
            text = { Text(stringResource(R.string.delete_folder_message, folder.name, count)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteFolder(folder.id)
                        deleteFolderDialog = null
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteFolderDialog = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: ChatSessionEntity,
    isSelected: Boolean,
    indented: Boolean,
    showMoveAction: Boolean,
    onSelected: () -> Unit,
    onPin: (Boolean) -> Unit,
    onRenameRequest: () -> Unit,
    onDelete: () -> Unit,
    onMoveRequest: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    Surface(
        // Unselected rows are transparent so they inherit the sidebar's
        // surfaceContainer tint; only the selected pill draws contrast.
        color = if (isSelected)
            MaterialTheme.colorScheme.secondaryContainer
        else
            Color.Transparent,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (indented) 20.dp else 8.dp, end = 8.dp, top = 2.dp, bottom = 2.dp)
            // Clip BEFORE combinedClickable so the ripple / long-press
            // indication is bounded to the pill shape.
            .clip(RoundedCornerShape(24.dp))
            .combinedClickable(
                onClick = onSelected,
                onLongClick = { showMenu = true }
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = session.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (session.pinned) {
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Filled.PushPin,
                    contentDescription = stringResource(R.string.pinned_label),
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text(if (session.pinned) stringResource(R.string.unpin) else stringResource(R.string.pin)) },
                onClick = {
                    onPin(!session.pinned)
                    showMenu = false
                },
                leadingIcon = {
                    Icon(imageVector = Icons.Outlined.PushPin, contentDescription = null)
                }
            )
            if (showMoveAction) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.move_to_folder)) },
                    onClick = {
                        onMoveRequest()
                        showMenu = false
                    },
                    leadingIcon = {
                        Icon(imageVector = Icons.Outlined.DriveFileMove, contentDescription = null)
                    }
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rename)) },
                onClick = {
                    onRenameRequest()
                    showMenu = false
                },
                leadingIcon = {
                    Icon(imageVector = Icons.Outlined.Edit, contentDescription = null)
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete)) },
                onClick = {
                    onDelete()
                    showMenu = false
                },
                leadingIcon = {
                    Icon(imageVector = Icons.Outlined.Delete, contentDescription = null)
                }
            )
        }
    }
}

/**
 * A folder in the root list. Tapping it ENTERS the folder (shows its chats);
 * long-press opens rename / delete. The trailing chevron signals navigation.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(
    name: String,
    count: Int,
    onClick: () -> Unit,
    onRenameRequest: () -> Unit,
    onDeleteRequest: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        Surface(
            color = Color.Transparent,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 2.dp)
                .clip(RoundedCornerShape(24.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showMenu = true }
                )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rename_folder)) },
                onClick = {
                    onRenameRequest()
                    showMenu = false
                },
                leadingIcon = {
                    Icon(imageVector = Icons.Outlined.Edit, contentDescription = null)
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete_folder)) },
                onClick = {
                    onDeleteRequest()
                    showMenu = false
                },
                leadingIcon = {
                    Icon(imageVector = Icons.Outlined.Delete, contentDescription = null)
                }
            )
        }
    }
}

@Composable
private fun FolderChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
