package com.druk.lmplayground.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.storage.StoragePreferences
import com.druk.lmplayground.theme.AppThemeState
import com.druk.lmplayground.theme.ThemeColor

/**
 * Settings row that opens a colour-swatch dialog to pick the app's UI accent.
 * Self-contained: applies the choice to [AppThemeState] (live, app-wide) and
 * persists it in [StoragePreferences]. Same look as the other settings rows.
 */
@Composable
fun ThemeColorRow() {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }
    val current = AppThemeState.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDialog = true }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Palette,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.theme_color),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(current.labelRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(current.seed ?: MaterialTheme.colorScheme.primary)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
        )
    }
    if (showDialog) {
        ThemeColorDialog(
            current = current,
            onSelect = { choice ->
                AppThemeState.current = choice
                StoragePreferences(context).themeColor = choice.key
                showDialog = false
            },
            onDismiss = { showDialog = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeColorDialog(
    current: ThemeColor,
    onSelect: (ThemeColor) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.theme_color)) },
        text = {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                for (choice in ThemeColor.values()) {
                    val selected = choice == current
                    val swatch = choice.seed ?: MaterialTheme.colorScheme.primary
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(64.dp)
                            .clickable { onSelect(choice) },
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(swatch)
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    shape = CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp),
                                    tint = if (swatch.luminance() > 0.5f) Color.Black else Color.White,
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(choice.labelRes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.close)) }
        },
    )
}
