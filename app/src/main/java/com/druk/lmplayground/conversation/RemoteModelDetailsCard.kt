package com.druk.lmplayground.conversation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.remote.ServerModelDetails
import com.druk.lmplayground.tools.Tool
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect
import kotlin.math.roundToInt

/**
 * Frosted card shown below the top bar for a loaded REMOTE model: info pills
 * (read from the server's native API) on top, generation-parameter sliders
 * below. Same look as the model picker (transparent Surface + Haze blur).
 * Tapping outside dismisses and commits any changed params.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemoteModelDetailsCard(
    modelName: String,
    details: ServerModelDetails?,
    maxContext: Int,
    params: GenerationParams,
    hazeState: HazeState,
    hazeStyle: HazeStyle,
    topPadding: Dp,
    supportsToolCalling: Boolean = false,
    tools: List<Tool> = emptyList(),
    toolEnabledStates: Map<String, Boolean> = emptyMap(),
    onToolEnabledChanged: (String, Boolean) -> Unit = { _, _ -> },
    onParamsChange: (GenerationParams) -> Unit,
    onDismiss: () -> Unit,
) {
    var edited by remember(params) { mutableStateOf(params) }
    val commitAndDismiss = {
        if (edited != params) onParamsChange(edited)
        onDismiss()
    }
    val maxCardHeight = (LocalConfiguration.current.screenHeightDp * 0.7f).dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = commitAndDismiss
            )
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = topPadding, start = 12.dp, end = 12.dp)
                .fillMaxWidth()
                .heightIn(max = maxCardHeight)
                // Consume taps on the card so they don't dismiss.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
            shape = RoundedCornerShape(24.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
        ) {
            Box(modifier = Modifier.hazeEffect(hazeState, hazeStyle)) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = modelName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )

                    // Info pills from the server's native metadata.
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        details?.quantization?.let { Pill(it) }
                        details?.parameterSize?.let { Pill(it) }
                        details?.architecture?.let { Pill(it) }
                        details?.type?.takeIf { it.isNotBlank() }?.let { Pill(it.uppercase()) }
                        details?.format?.takeIf { it.isNotBlank() }?.let { Pill(it.uppercase()) }
                        if (maxContext > 0) Pill(stringResource(R.string.context_pill, formatTokens(maxContext)))
                        details?.publisher?.let { Pill(it) }
                    }

                    // Capability badges (Ollama exposes these in /api/show; LM
                    // Studio doesn't). Shows at a glance if the model can use
                    // tools, read images, or reason.
                    val capTools = stringResource(R.string.capability_tools)
                    val capVision = stringResource(R.string.capability_vision)
                    val capThinking = stringResource(R.string.capability_thinking)
                    val caps = details?.capabilities.orEmpty()
                    val capBadges = buildList {
                        if ("tools" in caps) add(Icons.Outlined.Build to capTools)
                        if ("vision" in caps) add(Icons.Outlined.Image to capVision)
                        if ("thinking" in caps) add(Icons.Outlined.AutoAwesome to capThinking)
                    }
                    if (capBadges.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            capBadges.forEach { (icon, label) -> CapabilityBadge(icon, label) }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    ParamSliderRow(
                        label = stringResource(R.string.temperature),
                        value = edited.temperature,
                        valueRange = 0f..2f,
                        valueDisplay = "%.2f".format(edited.temperature),
                        onValueChange = { edited = edited.copy(temperature = (it * 100).roundToInt() / 100f) },
                    )
                    ParamSliderRow(
                        label = stringResource(R.string.top_p),
                        value = edited.topP,
                        valueRange = 0f..1f,
                        valueDisplay = "%.2f".format(edited.topP),
                        onValueChange = { edited = edited.copy(topP = (it * 100).roundToInt() / 100f) },
                    )
                    ParamSliderRow(
                        label = stringResource(R.string.top_k),
                        value = edited.topK.toFloat(),
                        valueRange = 0f..200f,
                        valueDisplay = "${edited.topK}",
                        onValueChange = { edited = edited.copy(topK = it.roundToInt()) },
                    )
                    ParamSliderRow(
                        label = stringResource(R.string.min_p),
                        value = edited.minP,
                        valueRange = 0f..0.5f,
                        valueDisplay = "%.3f".format(edited.minP),
                        onValueChange = { edited = edited.copy(minP = (it * 1000).roundToInt() / 1000f) },
                    )
                    ParamSliderRow(
                        label = stringResource(R.string.repetition_penalty),
                        value = edited.repetitionPenalty,
                        valueRange = 1f..2f,
                        valueDisplay = "%.2f".format(edited.repetitionPenalty),
                        onValueChange = { edited = edited.copy(repetitionPenalty = (it * 100).roundToInt() / 100f) },
                    )

                    // Tools: enable web_search / web_fetch / run_javascript for this
                    // remote model (same toggles as the local model's params sheet).
                    if (supportsToolCalling && tools.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                        Text(
                            text = stringResource(R.string.tools),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.tools_params_sheet_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        for (tool in tools) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                    Text(
                                        text = toolFriendlyName(tool.name),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    toolShortDescription(tool.name)?.let { desc ->
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = desc,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Switch(
                                    checked = toolEnabledStates[tool.name] ?: false,
                                    onCheckedChange = { onToolEnabledChanged(tool.name, it) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun CapabilityBadge(icon: ImageVector, label: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ParamSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueDisplay: String,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = valueDisplay,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Compact token count, e.g. 262144 -> "262K". */
private fun formatTokens(n: Int): String =
    if (n >= 1000) "${n / 1000}K" else "$n"
