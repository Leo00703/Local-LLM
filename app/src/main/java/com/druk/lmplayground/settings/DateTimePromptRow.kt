package com.druk.lmplayground.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.storage.StoragePreferences

/**
 * Self-contained settings toggle (same pattern as [ThemeColorRow]): reads and
 * writes [StoragePreferences.includeDateTimeInPrompt] directly, no plumbing
 * through the fragment. Takes effect on the next session (new chat / model
 * reload), since the system prompt is captured when a session is created.
 * Lives in the Tools screen, so its typography matches the tool rows there
 * (titleMedium / bodyMedium).
 */
@Composable
fun DateTimePromptRow() {
    val context = LocalContext.current
    val prefs = remember { StoragePreferences(context) }
    var checked by remember { mutableStateOf(prefs.includeDateTimeInPrompt) }
    val toggle = {
        val v = !checked
        checked = v
        prefs.includeDateTimeInPrompt = v
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { toggle() }
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Outlined.Schedule,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.datetime_in_prompt_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.datetime_in_prompt_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = { toggle() },
            modifier = Modifier.align(Alignment.CenterVertically),
        )
    }
}
