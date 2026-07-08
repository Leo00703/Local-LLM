@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.settings

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.druk.lmplayground.R
import com.druk.lmplayground.benchmark.BenchmarkConfig
import com.druk.lmplayground.benchmark.BenchmarkHardware
import com.druk.lmplayground.benchmark.BenchmarkUiState
import com.druk.lmplayground.data.BenchmarkResultEntity
import com.druk.lmplayground.models.ModelInfo
import com.druk.lmplayground.models.ModelWithStatus
import kotlin.math.roundToInt

/**
 * Dedicated Benchmark screen (Settings): pick a downloaded local model, pick the
 * accelerator (CPU / GPU / both in sequence), run a blocking suite, and see the
 * saved history for that model. Driven by the shared conversation VM, which owns
 * the single model and restores the previously-loaded chat model when done.
 */
@Composable
fun BenchmarkScreen(
    models: List<ModelWithStatus>,
    selectedModel: ModelInfo?,
    state: BenchmarkUiState,
    history: List<BenchmarkResultEntity>,
    allResults: List<BenchmarkResultEntity>,
    onSelectModel: (ModelInfo) -> Unit,
    onRun: (ModelInfo, BenchmarkHardware, BenchmarkConfig) -> Unit,
    onCancel: () -> Unit,
    onBackClick: () -> Unit,
    liteRtTest: String = "",
    onTestLiteRt: () -> Unit = {},
) {
    val running = state is BenchmarkUiState.Running
    var detailResult by remember { mutableStateOf<BenchmarkResultEntity?>(null) }
    var compareMode by remember { mutableStateOf(false) }

    // While a benchmark runs, block back navigation so the user can't leave (and
    // load another model) mid-run; they must Cancel explicitly.
    BackHandler(enabled = running) { }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.benchmark)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick, enabled = !running) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            if (running) {
                RunningCard(state as BenchmarkUiState.Running, onCancel)
            } else {
                // Test | Compare toggle.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !compareMode,
                        onClick = { compareMode = false },
                        label = { Text(stringResource(R.string.benchmark_mode_test)) }
                    )
                    FilterChip(
                        selected = compareMode,
                        onClick = { compareMode = true },
                        label = { Text(stringResource(R.string.benchmark_mode_compare)) }
                    )
                }
                Spacer(Modifier.height(16.dp))

                // Dev (temporary): LiteRT-LM proof-of-life. Loads the adb-pushed
                // Gemma 4 .litertlm on the CPU backend and streams a few tokens to
                // confirm the second engine actually runs on this device.
                Button(onClick = onTestLiteRt, modifier = Modifier.fillMaxWidth()) {
                    Text("Test LiteRT (dev)")
                }
                if (liteRtTest.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = liteRtTest,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))

                if (compareMode) {
                    BenchmarkComparison(allResults, onSelect = { detailResult = it })
                } else {
                    ConfigSection(
                        models = models,
                        selectedModel = selectedModel,
                        state = state,
                        onSelectModel = onSelectModel,
                        onRun = onRun,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.benchmark_history),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    if (history.isEmpty()) {
                        Text(
                            text = stringResource(R.string.benchmark_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        for (result in history) {
                            BenchmarkResultRow(result, onClick = { detailResult = result })
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    detailResult?.let { r ->
        BenchmarkDetailDialog(result = r, onDismiss = { detailResult = null })
    }
}

@Composable
private fun RunningCard(state: BenchmarkUiState.Running, onCancel: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(state.status, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { state.fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}

@Composable
private fun ConfigSection(
    models: List<ModelWithStatus>,
    selectedModel: ModelInfo?,
    state: BenchmarkUiState,
    onSelectModel: (ModelInfo) -> Unit,
    onRun: (ModelInfo, BenchmarkHardware, BenchmarkConfig) -> Unit,
) {
    var prefill by remember { mutableIntStateOf(BenchmarkConfig().prefillTokens) }
    var decode by remember { mutableIntStateOf(BenchmarkConfig().decodeTokens) }
    var runs by remember { mutableIntStateOf(BenchmarkConfig().runs) }
    var hardware by remember { mutableStateOf(BenchmarkHardware.CPU) }
    var speculative by remember { mutableStateOf(false) }

    // Model picker (downloaded local models only).
    Text(
        text = stringResource(R.string.benchmark_model),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(4.dp))
    ModelDropdown(models = models, selected = selectedModel, onSelect = onSelectModel)
    Spacer(Modifier.height(16.dp))

    // Hardware.
    Text(
        text = stringResource(R.string.benchmark_hardware),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HardwareChip(stringResource(R.string.benchmark_hw_cpu), hardware == BenchmarkHardware.CPU) { hardware = BenchmarkHardware.CPU }
        HardwareChip(stringResource(R.string.benchmark_hw_gpu), hardware == BenchmarkHardware.GPU) { hardware = BenchmarkHardware.GPU }
        HardwareChip(stringResource(R.string.benchmark_hw_both), hardware == BenchmarkHardware.BOTH) { hardware = BenchmarkHardware.BOTH }
    }
    Spacer(Modifier.height(16.dp))

    BenchStepSlider(stringResource(R.string.benchmark_prefill_tokens), prefill, BenchmarkConfig.MIN_TOKENS, BenchmarkConfig.MAX_TOKENS, 32) { prefill = it }
    BenchStepSlider(stringResource(R.string.benchmark_decode_tokens), decode, BenchmarkConfig.MIN_TOKENS, BenchmarkConfig.MAX_TOKENS, 32) { decode = it }
    BenchStepSlider(stringResource(R.string.benchmark_runs), runs, BenchmarkConfig.MIN_RUNS, BenchmarkConfig.MAX_RUNS, 1) { runs = it }
    Spacer(Modifier.height(8.dp))

    // Experimental self-MTP speculative decoding (only Qwen3.5-class models whose
    // MTP head llama.cpp executes; a no-op on any other model).
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.benchmark_mtp), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = stringResource(R.string.benchmark_mtp_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = speculative, onCheckedChange = { speculative = it })
    }
    Spacer(Modifier.height(12.dp))

    Button(
        onClick = {
            selectedModel?.let {
                onRun(it, hardware, BenchmarkConfig(prefill, decode, runs, speculative = speculative))
            }
        },
        enabled = selectedModel != null,
        modifier = Modifier.fillMaxWidth()
    ) { Text(stringResource(R.string.benchmark_run)) }

    if (state is BenchmarkUiState.Error) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = state.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun ModelDropdown(
    models: List<ModelWithStatus>,
    selected: ModelInfo?,
    onSelect: (ModelInfo) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true },
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selected?.name ?: stringResource(R.string.benchmark_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (m in models) {
                DropdownMenuItem(
                    text = { Text(m.model.name) },
                    onClick = {
                        onSelect(m.model)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun HardwareChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun BenchStepSlider(label: String, value: Int, min: Int, max: Int, step: Int, onChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(text = "$value", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(((it / step).roundToInt() * step).coerceIn(min, max)) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = (((max - min) / step) - 1).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun BenchmarkResultRow(result: BenchmarkResultEntity, onClick: () -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = DateUtils.getRelativeTimeSpanString(
                        result.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
                    ).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Short, consistent badge (CPU / GPU); the full accelerator name
                // lives in the detail dialog. When MTP was requested we surface its
                // outcome inline so the user can confirm the model at a glance.
                val isGpu = result.accelerator.startsWith("GPU")
                val base = if (isGpu) "GPU" else "CPU"
                val badge = when {
                    result.accelerator.contains("MTP n/a") -> "$base · no MTP"
                    result.accelerator.contains("· MTP") -> "$base · " + result.accelerator.substringAfter("· ")
                    else -> base
                }
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isGpu) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Metric(stringResource(R.string.benchmark_metric_prefill), "%.1f".format(result.prefillTokPerSecAvg), stringResource(R.string.benchmark_unit_tps))
                Metric(stringResource(R.string.benchmark_metric_decode), "%.1f".format(result.decodeTokPerSecAvg), stringResource(R.string.benchmark_unit_tps))
                Metric(stringResource(R.string.benchmark_metric_ttft), "${result.ttftMsAvg.roundToInt()}", "ms")
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = "$value $unit", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * Compare view: the LATEST saved result per (model, accelerator), shown as
 * grouped horizontal bars per metric (best first). Tap a bar for its detail.
 */
@Composable
private fun BenchmarkComparison(
    allResults: List<BenchmarkResultEntity>,
    onSelect: (BenchmarkResultEntity) -> Unit,
) {
    if (allResults.isEmpty()) {
        Text(
            text = stringResource(R.string.benchmark_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    // allResults is newest-first, so the first row of each group is the latest.
    val entries = remember(allResults) {
        allResults.groupBy { it.modelFilename to it.accelerator.startsWith("GPU") }
            .map { (_, rs) -> rs.first() }
    }
    val tps = stringResource(R.string.benchmark_unit_tps)

    ComparisonMetric(
        title = "${stringResource(R.string.benchmark_metric_decode)} ($tps)",
        entries = entries.sortedByDescending { it.decodeTokPerSecAvg },
        valueText = { "%.1f".format(it.decodeTokPerSecAvg) },
        score = { scoreHigher(it.decodeTokPerSecAvg, 60f, 8f, 20f) },
        onSelect = onSelect,
    )
    Spacer(Modifier.height(16.dp))
    ComparisonMetric(
        title = "${stringResource(R.string.benchmark_metric_prefill)} ($tps)",
        entries = entries.sortedByDescending { it.prefillTokPerSecAvg },
        valueText = { "%.1f".format(it.prefillTokPerSecAvg) },
        score = { scoreHigher(it.prefillTokPerSecAvg, 120f, 15f, 40f) },
        onSelect = onSelect,
    )
    Spacer(Modifier.height(16.dp))
    ComparisonMetric(
        title = "${stringResource(R.string.benchmark_metric_ttft)} (ms)",
        entries = entries.sortedBy { it.ttftMsAvg },
        valueText = { "${it.ttftMsAvg.roundToInt()}" },
        score = { scoreLower(it.ttftMsAvg, 15000f, 2000f, 6000f) },
        onSelect = onSelect,
    )
}

@Composable
private fun ComparisonMetric(
    title: String,
    entries: List<BenchmarkResultEntity>,
    valueText: (BenchmarkResultEntity) -> String,
    score: (BenchmarkResultEntity) -> Score,
    onSelect: (BenchmarkResultEntity) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    for (e in entries) {
        val label = "${e.modelName} · ${if (e.accelerator.startsWith("GPU")) "GPU" else "CPU"}"
        ComparisonBar(label = label, value = valueText(e), score = score(e), onClick = { onSelect(e) })
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ComparisonBar(label: String, value: String, score: Score, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            val frac = score.fraction.coerceIn(0f, 1f)
            if (frac > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(frac)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(score.color)
                )
            }
        }
    }
}

/**
 * Full detail for one saved benchmark result: header, colored "score" bars per
 * metric (red = poor, yellow = ok, green = good; TTFT is inverted since lower is
 * better), and the run config / duration / KV cache.
 */
@Composable
private fun BenchmarkDetailDialog(result: BenchmarkResultEntity, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Text(result.modelName, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = result.accelerator,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result.accelerator.startsWith("GPU")) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                val tps = stringResource(R.string.benchmark_unit_tps)
                ScoreBar(
                    label = stringResource(R.string.benchmark_metric_prefill),
                    value = "%.1f %s".format(result.prefillTokPerSecAvg, tps),
                    score = scoreHigher(result.prefillTokPerSecAvg, 120f, 15f, 40f)
                )
                ScoreBar(
                    label = stringResource(R.string.benchmark_metric_decode),
                    value = "%.1f %s".format(result.decodeTokPerSecAvg, tps),
                    score = scoreHigher(result.decodeTokPerSecAvg, 60f, 8f, 20f)
                )
                ScoreBar(
                    label = stringResource(R.string.benchmark_metric_ttft),
                    value = "${result.ttftMsAvg.roundToInt()} ms",
                    score = scoreLower(result.ttftMsAvg, 15000f, 2000f, 6000f)
                )

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                DetailRow(
                    stringResource(R.string.benchmark_config),
                    "${result.prefillTokens} / ${result.decodeTokens} / ${result.runs}"
                )
                DetailRow(stringResource(R.string.benchmark_duration), formatMillis(result.durationMs))
                DetailRow(stringResource(R.string.benchmark_context), "${result.contextUsed}")
                DetailRow(stringResource(R.string.kv_cache_label), kvLabel(result.kvCacheType))

                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }
}

private data class Score(val fraction: Float, val color: Color)

private val ScoreRed = Color(0xFFE53935)
private val ScoreYellow = Color(0xFFF9A825)
private val ScoreGreen = Color(0xFF43A047)

/** Higher is better (tok/s): fuller + greener the larger the value. */
private fun scoreHigher(value: Float, scaleMax: Float, redBelow: Float, greenAtLeast: Float): Score {
    val color = when {
        value < redBelow -> ScoreRed
        value < greenAtLeast -> ScoreYellow
        else -> ScoreGreen
    }
    return Score((value / scaleMax).coerceIn(0f, 1f), color)
}

/** Lower is better (TTFT): fuller + greener the SMALLER the value. */
private fun scoreLower(value: Float, scaleMax: Float, greenBelow: Float, redAbove: Float): Score {
    val color = when {
        value < greenBelow -> ScoreGreen
        value <= redAbove -> ScoreYellow
        else -> ScoreRed
    }
    return Score((1f - value / scaleMax).coerceIn(0f, 1f), color)
}

@Composable
private fun ScoreBar(label: String, value: String, score: Score) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            // fillMaxWidth requires a fraction > 0, so only draw the fill when
            // there's something to fill (a 0 score just shows the empty track).
            val frac = score.fraction.coerceIn(0f, 1f)
            if (frac > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(frac)
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(score.color)
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun kvLabel(kv: Int): String = stringResource(
    when (kv) {
        0 -> R.string.kv_cache_fp16
        2 -> R.string.kv_cache_q4
        else -> R.string.kv_cache_q8
    }
)

private fun formatMillis(ms: Long): String {
    val sec = ms / 1000.0
    return if (sec < 60) "%.1f s".format(sec) else "${ms / 60000}m ${(ms % 60000) / 1000}s"
}
