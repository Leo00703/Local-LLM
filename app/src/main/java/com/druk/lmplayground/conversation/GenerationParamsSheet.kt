package com.druk.lmplayground.conversation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.vector.ImageVector
import com.druk.lmplayground.data.SystemPromptEntity
import com.druk.lmplayground.remote.ServerModelDetails
import com.druk.lmplayground.settings.EditorBottomSheet
import com.druk.lmplayground.settings.EditorTarget
import com.druk.lmplayground.tools.Tool
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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
    // Vision model: shows the "Image detail" (max image tokens) slider.
    supportsVision: Boolean = false,
    tools: List<Tool> = emptyList(),
    toolEnabledStates: Map<String, Boolean> = emptyMap(),
    onToolEnabledChanged: (String, Boolean) -> Unit = { _, _ -> },
    systemPrompt: String = "",
    canUpdateLinkedPrompt: Boolean = false,
    // Remote model: when true the context-size / thinking-budget / seed controls
    // (which are server-owned or local-only) are hidden, and the server's
    // metadata is shown as info pills atop the Parameters tab.
    isRemote: Boolean = false,
    serverDetails: ServerModelDetails? = null,
    // Active compute backend of a loaded local model ("GPU (OpenCL): … " or
    // "CPU"); shown atop the Parameters tab so the user can verify GPU use.
    computeBackend: String? = null,
    // Saved system-prompt library, shown in the Prompt tab so the user can
    // pick / edit / delete / create prompts without leaving the sheet.
    savedPrompts: List<SystemPromptEntity> = emptyList(),
    activePromptId: String? = null,
    onSelectSavedPrompt: (id: String, text: String) -> Unit = { _, _ -> },
    onUpdateSavedPrompt: (id: String, text: String) -> Unit = { _, _ -> },
    onDeleteSavedPrompt: (id: String) -> Unit = {},
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
    // Editor target for the saved-prompt library (New / Edit); null = hidden.
    var savedEditorTarget by remember { mutableStateOf<EditorTarget?>(null) }

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
    // Swipe-down-to-dismiss: dragging the top handle translates the sheet and,
    // once past a threshold, commits the edits and closes it.
    val dismissThresholdPx = with(LocalDensity.current) { 120.dp.toPx() }
    var dragOffsetY by remember { mutableStateOf(0f) }
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
                .offset { IntOffset(0, dragOffsetY.roundToInt()) }
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
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = if (frosted) 0.dp else 6.dp,
            shadowElevation = 8.dp,
        ) {
          Box(modifier = if (frosted) Modifier.hazeEffect(hazeState!!, hazeStyle) else Modifier) {
            Column(modifier = Modifier.fillMaxWidth()) {
                DragHandle(
                    onDrag = { delta -> dragOffsetY = (dragOffsetY + delta).coerceAtLeast(0f) },
                    onDragStopped = {
                        if (dragOffsetY > dismissThresholdPx) commitAndDismiss()
                        else dragOffsetY = 0f
                    }
                )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Prompt / Tools / Parameters split into tabs (Tools tab only when the
            // model supports tool calling). Default to the Parameters tab.
            val hasToolsTab = supportsToolCalling && tools.isNotEmpty()
            val tabKeys = remember(hasToolsTab) {
                buildList { add("prompt"); if (hasToolsTab) add("tools"); add("params") }
            }
            var selectedTab by remember(tabKeys.size) {
                mutableIntStateOf(tabKeys.indexOf("params").coerceAtLeast(0))
            }
            val tabKey = tabKeys.getOrElse(selectedTab) { "params" }
            // Transparent container so the tab strip blends into the frosted
            // sheet (just the selected-tab underline + a hairline divider),
            // instead of painting a solid dark box over the glass.
            TabRow(
                selectedTabIndex = selectedTab.coerceIn(0, tabKeys.lastIndex),
                containerColor = Color.Transparent,
            ) {
                tabKeys.forEachIndexed { i, key ->
                    Tab(
                        selected = selectedTab == i,
                        onClick = { selectedTab = i },
                        text = {
                            Text(
                                stringResource(
                                    when (key) {
                                        "prompt" -> R.string.settings_tab_prompt
                                        "tools" -> R.string.tools
                                        else -> R.string.settings_tab_params
                                    }
                                )
                            )
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            if (tabKey == "params") {
                // Remote models: show the server's metadata (quant / arch /
                // context / capabilities) as info pills at the top, then the
                // adjustable sliders below.
                if (isRemote) {
                    ServerInfoHeader(details = serverDetails, maxContext = maxContextSize)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        // Reset sampling defaults, but keep the server-owned
                        // context window for remote models (it feeds the ring
                        // and isn't user-editable here).
                        editedParams = if (isRemote) {
                            GenerationParams().copy(contextSize = params.contextSize)
                        } else {
                            GenerationParams()
                        }
                    }) {
                        Text(stringResource(R.string.reset))
                    }
                }
            }

            if (tabKey == "prompt") {
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
            Spacer(modifier = Modifier.height(16.dp))

            // Saved prompt library — pick, edit, delete or create prompts
            // without leaving the sheet. Tapping a card applies it to this
            // session; the pencil opens the editor. Works for both local and
            // remote models (the prompt is applied at the session level).
            Text(
                text = stringResource(R.string.system_prompts),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            NewSavedPromptCard(onClick = { savedEditorTarget = EditorTarget.New })
            for (prompt in savedPrompts) {
                Spacer(modifier = Modifier.height(8.dp))
                SavedPromptRow(
                    prompt = prompt,
                    selected = prompt.id == activePromptId,
                    onSelect = { onSelectSavedPrompt(prompt.id, prompt.text) },
                    onEdit = { savedEditorTarget = EditorTarget.Edit(prompt) },
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            }

            // Tools section (only for models that support tool calling)
            if (tabKey == "tools" && supportsToolCalling && tools.isNotEmpty()) {
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

            if (tabKey == "params") {
            // Compute backend of the loaded local model — lets the user verify
            // whether the GPU toggle really took effect (GPU value highlighted).
            if (!isRemote && computeBackend != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.compute_backend_label),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = computeBackend,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (computeBackend.startsWith("GPU")) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Context Size — local models only. A remote server owns its own
            // context window (shown as a pill above), so there's nothing to set.
            if (!isRemote) {
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
            }

            // Thinking Budget — a local token cap; not applicable to remote
            // models (their reasoning is server-side, toggled not budgeted).
            if (supportsThinking && !isRemote) {
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

            // Image detail (vision only): caps how many tokens an attached image
            // may use. Higher = more resolution/detail, more context, slower.
            // Applied to the projector (mtmd image_max_tokens) on the next image.
            if (supportsVision && !isRemote) {
                val imgMin = 64
                val imgMax = 320
                val imgStep = 32
                ParamSlider(
                    label = stringResource(R.string.image_detail_label),
                    value = editedParams.imageMaxTokens.coerceIn(imgMin, imgMax).toFloat(),
                    valueRange = imgMin.toFloat()..imgMax.toFloat(),
                    steps = ((imgMax - imgMin) / imgStep) - 1,
                    valueDisplay = stringResource(R.string.tokens_value, editedParams.imageMaxTokens),
                    subtitle = stringResource(R.string.image_detail_subtitle),
                    onValueChange = {
                        val snapped = (it / imgStep).roundToInt() * imgStep
                        editedParams = editedParams.copy(
                            imageMaxTokens = snapped.coerceIn(imgMin, imgMax)
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

            // KV cache — local models only (a remote server owns its own KV
            // cache). Q8_0 default: ~half the KV memory at near-zero quality loss.
            if (!isRemote) {
                KvCacheSelector(
                    selected = editedParams.kvCacheType,
                    onSelect = { editedParams = editedParams.copy(kvCacheType = it) }
                )
                // On the GPU the KV cache stays F16 (OpenCL has no quantized-KV
                // Flash Attention), so the picker above only takes effect on CPU.
                if (computeBackend?.startsWith("GPU") == true) {
                    Text(
                        text = stringResource(R.string.kv_cache_gpu_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

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

                    // Seed — local only (the remote OpenAI backend doesn't
                    // forward a seed).
                    if (!isRemote) {
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
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
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

    // Saved-prompt library editor (New / Edit) — reuses the same bottom-sheet
    // editor as the full System Prompts screen. onAdd creates + applies the new
    // prompt; onUpdate / onDelete manage the existing library entry.
    EditorBottomSheet(
        target = savedEditorTarget,
        onAdd = onSaveAsNewPrompt,
        onUpdate = { id, text -> onUpdateSavedPrompt(id, text) },
        onDelete = { id -> onDeleteSavedPrompt(id) },
        onDismiss = { savedEditorTarget = null },
    )
}

/**
 * Server-metadata info pills + capability badges for a remote model, shown at
 * the top of the Parameters tab. Mirrors what the old standalone remote-details
 * card displayed. [details] is null until the server's native API responds.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServerInfoHeader(details: ServerModelDetails?, maxContext: Int) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
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

/** "+ New" tile that opens the prompt editor in create mode. */
@Composable
private fun NewSavedPromptCard(onClick: () -> Unit) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.system_prompt_new),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * One saved library prompt: tapping the card body applies it to the session
 * (a primary border marks the active one); the pencil opens the editor for
 * rename / delete.
 */
@Composable
private fun SavedPromptRow(
    prompt: SystemPromptEntity,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedCard(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onSelect),
            shape = RoundedCornerShape(12.dp),
            border = if (selected) {
                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            } else {
                CardDefaults.outlinedCardBorder()
            },
        ) {
            Text(
                text = prompt.text,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = stringResource(R.string.edit),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Compact token count, e.g. 262144 -> "262K". */
private fun formatTokens(n: Int): String =
    if (n >= 1000) "${n / 1000}K" else "$n"

/** Grab bar at the top of the sheet; a downward drag past the threshold hides
 *  the card (see [GenerationParamsSheet]). The whole top strip is draggable so
 *  it is easy to grab. */
@Composable
private fun DragHandle(
    onDrag: (Float) -> Unit,
    onDragStopped: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta -> onDrag(delta) },
                onDragStopped = { onDragStopped() }
            )
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
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
 * KV-cache quantization picker (local models only). Three mutually-exclusive
 * choices — FP16 (full), Q8_0 (standard, default) and Q4_0 (compact) — with a
 * one-line description of the currently selected tier. Codes: 0=F16, 1=Q8_0,
 * 2=Q4_0, matching [GenerationParams.kvCacheType] and the native SamplerParams.
 */
@Composable
private fun KvCacheSelector(
    selected: Int,
    onSelect: (Int) -> Unit
) {
    val options = listOf(
        0 to stringResource(R.string.kv_cache_fp16),
        1 to stringResource(R.string.kv_cache_q8),
        2 to stringResource(R.string.kv_cache_q4),
    )
    val descRes = when (selected) {
        1 -> R.string.kv_cache_desc_q8
        2 -> R.string.kv_cache_desc_q4
        else -> R.string.kv_cache_desc_fp16
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = stringResource(R.string.kv_cache_label),
            style = MaterialTheme.typography.bodyMedium
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { (code, label) ->
                FilterChip(
                    selected = selected == code,
                    onClick = { onSelect(code) },
                    label = { Text(label) }
                )
            }
        }
        Text(
            text = stringResource(descRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/**
 * Friendly, user-facing name for a tool keyed by its registry [Tool.name].
 * The raw name (e.g. "run_javascript") is model-facing; falls back to it for
 * any tool without a mapped string.
 */
@Composable
internal fun toolFriendlyName(name: String): String = when (name) {
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
internal fun toolShortDescription(name: String): String? = when (name) {
    "run_javascript" -> stringResource(R.string.tool_run_javascript_desc)
    "web_search" -> stringResource(R.string.tool_web_search_desc)
    "web_fetch" -> stringResource(R.string.tool_web_fetch_desc)
    else -> null
}
