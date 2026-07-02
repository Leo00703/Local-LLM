package com.druk.lmplayground.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R

/**
 * Trailing capability badges for a model row: a hammer when the model supports
 * tool calling and a lightbulb when it supports thinking. Renders nothing when
 * the model supports neither. The flags come from [ModelInfo] — static catalog
 * values corrected by real, template-detected capabilities once a model has
 * been loaded (see [resolveCapabilities]).
 */
@Composable
fun ModelCapabilityIcons(
    model: ModelInfo,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    iconSize: Dp = 18.dp,
) {
    if (!model.supportsTools && !model.supportsThinking && !model.isVision) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (model.isVision) {
            Icon(
                imageVector = Icons.Outlined.Image,
                contentDescription = stringResource(R.string.cd_supports_vision),
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
        }
        if (model.supportsTools) {
            Icon(
                imageVector = Icons.Outlined.Build,
                contentDescription = stringResource(R.string.cd_supports_tools),
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
        }
        if (model.supportsThinking) {
            Icon(
                imageVector = Icons.Outlined.Lightbulb,
                contentDescription = stringResource(R.string.cd_supports_thinking),
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
