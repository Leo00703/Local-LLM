package com.druk.lmplayground.conversation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.druk.lmplayground.tools.Tool
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.settings.SystemPromptEditorSheet
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerationParamsSheet(
    params: GenerationParams,
    maxContextSize: Int,
    supportsThinking: Boolean = false,
    supportsToolCalling: Boolean = false,
    tools: List<Tool> = emptyList(),
    toolEnabledStates: Map<String, Boolean> = emptyMap(),
    onToolEnabledChanged: (String, Boolean) -> Unit = { _, _ -> },
    systemPrompt: String = "",
    canUpdateLinkedPrompt: Boolean = false,
    onParamsChanged: (GenerationParams) -> Unit,
    onUpdateLinkedPrompt: (String) -> Unit = {},
    onSaveAsNewPrompt: (String) -> Unit = {},
    onClearSystemPrompt: () -> Unit = {},
    hazeState: HazeState? = null,
    hazeStyle: HazeStyle = HazeStyle.Unspecified,
    onDismiss: () -> Unit
) {
    var editedParams by remember(params) { mutableStateOf(params) }
    var showAdvanced by remember { mutableStateOf(false) }
    var showPromptReviser by remember { mutableStateOf(false) }

    val contextMin = 512
    val contextMax = maxContextSize.coerceAtLeast(512)
    val contextStep = 512

    // Rendered as an in-composition overlay (not a ModalBottomSheet, which
    // lives in a separate window Haze can't blur) so the sheet can frost the
    // chat behind it — same look as the model picker / details card. Tapping
    // the scrim or pressing back commits any edits and dismisses.
    val commitAndDismiss = {
        if (editedParams != params) {
            onParamsChanged(editedParams)
        }
        onDismiss()
    }
    BackHandler(onBack = commitAndDismiss)
    val frosted = hazeState != null
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = commitAndDismiss
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars)
                .imePadding()
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.9f).dp)
                // Consume taps on the sheet so they don't dismiss it.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
            shape = RoundedCornerShape(24.dp),
            color = if (frosted) Color.Transparent else MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = if (frosted) 0.dp else 6.dp,
            shadowElevation = 8.dp,
        ) {
          Box(modifier = if (frosted) Modifier.hazeEffect(hazeState!!, hazeStyle) else Modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.generation_parameters),
                    style = MaterialTheme.typography.titleMedium
                )
                TextButton(onClick = {
                    val defaults = GenerationParams()
                    editedParams = defaults
                }) {
                    Text(stringResource(R.string.reset))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Current System Prompt — always present; tap the card to author
            // or edit the prompt for this session. The Clear button is always
            // rendered so the header height is stable; only its visibility
            // changes when a prompt is active.
            val hasPrompt = systemPrompt.isNotEmpty()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.system_prompt_current),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = onClearSystemPrompt,
                    enabled = hasPrompt,
                    modifier = Modifier.alpha(if (hasPrompt) 1f else 0f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp, vertical = 0.dp
                    )
                ) {
                    Text(stringResource(R.string.clear))
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showPromptReviser = true },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = if (hasPrompt) systemPrompt
                           else stringResource(R.string.system_prompt_current_empty),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasPrompt) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    minLines = 3,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            // Tools section (only for models that support tool calling)
            if (supportsToolCalling && tools.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.tools),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = stringResource(R.string.tools_params_sheet_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                for (tool in tools) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text(
                                text = toolFriendlyName(tool.name),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            toolShortDescription(tool.name)?.let { desc ->
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = toolEnabledStates[tool.name] ?: false,
                            onCheckedChange = { onToolEnabledChanged(tool.name, it) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Context Size
            val contextWarning = editedParams.contextSize != params.contextSize
            ParamSlider(
                label = stringResource(R.string.context_size),
                value = editedParams.contextSize.toFloat(),
                valueRange = contextMin.toFloat()..contextMax.toFloat(),
                steps = (((contextMax - contextMin) / contextStep) - 1).coerceAtLeast(0),
                valueDisplay = "${editedParams.contextSize}",
                warning = if (contextWarning) stringResource(R.string.will_reset_conversation) else null,
                onValueChange = {
                    val snapped = (it / contextStep).roundToInt() * contextStep
                    val newContextSize = snapped.coerceIn(contextMin, contextMax)
                    val oldContextSize = editedParams.contextSize
                    // Auto-scale thinking budget proportionally when context size changes
                    val newBudget = if (oldContextSize > 0) {
                        (editedParams.thinkingBudget.toLong() * newContextSize / oldContextSize).toInt()
                            .coerceIn(64, newContextSize)
                    } else {
                        newContextSize / 4
                    }
                    editedParams = editedParams.copy(contextSize = newContextSize, thinkingBudget = newBudget)
                }
            )

            // Thinking Budget (only for thinking-capable models)
            if (supportsThinking) {
                val budgetMin = 64
                val budgetMax = editedParams.contextSize
                ParamSlider(
                    label = stringResource(R.string.thinking_budget),
                    value = editedParams.thinkingBudget.toFloat(),
                    valueRange = budgetMin.toFloat()..budgetMax.toFloat(),
                    steps = 0,
                    valueDisplay = stringResource(R.string.tokens_value, editedParams.thinkingBudget),
                    onValueChange = {
                        val snapped = (it / 64).roundToInt() * 64
                        editedParams = editedParams.copy(
                            thinkingBudget = snapped.coerceIn(budgetMin, budgetMax)
                        )
                    }
                )
            }

            // Temperature
            ParamSlider(
                label = stringResource(R.string.temperature),
                value = editedParams.temperature,
                valueRange = 0f..2f,
                steps = 0,
                valueDisplay = "%.2f".format(editedParams.temperature),
                onValueChange = {
                    editedParams = editedParams.copy(temperature = (it * 100).roundToInt() / 100f)
                }
            )

            // Top-P
            ParamSlider(
                label = stringResource(R.string.top_p),
                value = editedParams.topP,
                valueRange = 0f..1f,
                steps = 0,
                valueDisplay = "%.2f".format(editedParams.topP),
                subtitle = if (editedParams.topP >= 1f) stringResource(R.string.disabled_label) else null,
                onValueChange = {
                    editedParams = editedParams.copy(topP = (it * 100).roundToInt() / 100f)
                }
            )

            // Repetition Penalty
            ParamSlider(
                label = stringResource(R.string.repetition_penalty),
                value = editedParams.repetitionPenalty,
                valueRange = 1f..2f,
                steps = 0,
                valueDisplay = "%.2f".format(editedParams.repetitionPenalty),
                subtitle = if (editedParams.repetitionPenalty <= 1f) stringResource(R.string.disabled_label) else null,
                onValueChange = {
                    editedParams = editedParams.copy(repetitionPenalty = (it * 100).roundToInt() / 100f)
                }
            )

            // Advanced section
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAdvanced = !showAdvanced }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.advanced),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (showAdvanced) stringResource(R.string.collapse) else stringResource(R.string.expand)
                )
            }

            AnimatedVisibility(visible = showAdvanced) {
                Column {
                    // Top-K
                    ParamSlider(
                        label = stringResource(R.string.top_k),
                        value = editedParams.topK.toFloat(),
                        valueRange = 0f..200f,
                        steps = 0,
                        valueDisplay = "${editedParams.topK}",
                        subtitle = if (editedParams.topK == 0) stringResource(R.string.disabled_label) else null,
                        onValueChange = {
                            editedParams = editedParams.copy(topK = it.roundToInt())
                        }
                    )

                    // Min-P
                    ParamSlider(
                        label = stringResource(R.string.min_p),
                        value = editedParams.minP,
                        valueRange = 0f..0.5f,
                        steps = 0,
                        valueDisplay = "%.3f".format(editedParams.minP),
                        subtitle = if (editedParams.minP <= 0f) stringResource(R.string.disabled_label) else null,
                        onValueChange = {
                            editedParams = editedParams.copy(minP = (it * 1000).roundToInt() / 1000f)
                        }
                    )

                    // Seed
                    var seedText by remember(editedParams.seed) {
                        mutableStateOf(if (editedParams.seed < 0) "" else editedParams.seed.toString())
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.seed),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = seedText,
                            onValueChange = { text ->
                                seedText = text
                                val value = text.toIntOrNull() ?: -1
                                editedParams = editedParams.copy(seed = value)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 16.dp),
                            placeholder = { Text(stringResource(R.string.random)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
          }
        }
    }

    if (showPromptReviser) {
        SystemPromptEditorSheet(
            initialText = systemPrompt,
            title = stringResource(
                if (systemPrompt.isEmpty()) R.string.system_prompt_new
                else R.string.system_prompt_edit
            ),
            primaryLabel = stringResource(R.string.system_prompt_update),
            onPrimary = { text ->
                // If the current session prompt is backed by a library entry,
                // update that entry in place; otherwise create a new library
                // entry and link it to the session.
                if (canUpdateLinkedPrompt && systemPrompt.isNotEmpty()) {
                    onUpdateLinkedPrompt(text)
                } else {
                    onSaveAsNewPrompt(text)
                }
            },
            onDismiss = { showPromptReviser = false }
        )
    }
}

@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueDisplay: String,
    subtitle: String? = null,
    warning: String? = null,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = label, style = MaterialTheme.typography.bodyMedium)
                if (subtitle != null) {
                    Text(
                        text = " ($subtitle)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = valueDisplay,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
        if (warning != null) {
            Text(
                text = warning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/**
 * Friendly, user-facing name for a tool keyed by its registry [Tool.name].
 * The raw name (e.g. "run_javascript") is model-facing; falls back to it for
 * any tool without a mapped string.
 */
@Composable
private fun toolFriendlyName(name: String): String = when (name) {
    "run_javascript" -> stringResource(R.string.tool_run_javascript_title)
    "web_search" -> stringResource(R.string.tool_web_search_title)
    "web_fetch" -> stringResource(R.string.tool_web_fetch_title)
    else -> name
}

/**
 * User-facing description for the params-sheet tool toggle — the same plain-language
 * copy as Settings → Tools (minus the worked example). The registry
 * [Tool.description] is written for the model, so we use these strings instead.
 * Null for unmapped tools (the subtitle is then hidden).
 */
@Composable
private fun toolShortDescription(name: String): String? = when (name) {
    "run_javascript" -> stringResource(R.string.tool_run_javascript_desc)
    "web_search" -> stringResource(R.string.tool_web_search_desc)
    "web_fetch" -> stringResource(R.string.tool_web_fetch_desc)
    else -> null
}
