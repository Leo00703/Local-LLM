@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.settings

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    onSelectModel: (ModelInfo) -> Unit,
    onRun: (ModelInfo, BenchmarkHardware, BenchmarkConfig) -> Unit,
    onCancel: () -> Unit,
    onBackClick: () -> Unit,
) {
    val running = state is BenchmarkUiState.Running

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
                Spacer(Modifier.height(16.dp))
            } else {
                ConfigSection(
                    models = models,
                    selectedModel = selectedModel,
                    state = state,
                    onSelectModel = onSelectModel,
                    onRun = onRun,
                )
                Spacer(Modifier.height(16.dp))
            }

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
                    BenchmarkResultRow(result)
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
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
    Spacer(Modifier.height(12.dp))

    Button(
        onClick = { selectedModel?.let { onRun(it, hardware, BenchmarkConfig(prefill, decode, runs)) } },
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
private fun BenchmarkResultRow(result: BenchmarkResultEntity) {
    OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
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
                Text(
                    text = result.accelerator,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (result.accelerator.startsWith("GPU")) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
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
