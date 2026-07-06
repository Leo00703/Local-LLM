package com.druk.lmplayground.conversation

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.benchmark.BenchmarkConfig
import com.druk.lmplayground.benchmark.BenchmarkUiState
import com.druk.lmplayground.data.BenchmarkResultEntity
import kotlin.math.roundToInt

/**
 * The Benchmark tab of the model params sheet: configure prefill/decode/runs,
 * run against the loaded local model, watch live progress, and see this model's
 * saved history. Cross-model comparison + charts + peak memory live in the
 * separate Settings screen (Build 2). All state comes from the conversation VM.
 */
@Composable
fun BenchmarkPanel(
    state: BenchmarkUiState,
    history: List<BenchmarkResultEntity>,
    onRun: (BenchmarkConfig) -> Unit,
) {
    var prefill by remember { mutableIntStateOf(BenchmarkConfig().prefillTokens) }
    var decode by remember { mutableIntStateOf(BenchmarkConfig().decodeTokens) }
    var runs by remember { mutableIntStateOf(BenchmarkConfig().runs) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.benchmark_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        val running = state is BenchmarkUiState.Running
        BenchStepSlider(
            label = stringResource(R.string.benchmark_prefill_tokens),
            value = prefill,
            min = BenchmarkConfig.MIN_TOKENS,
            max = BenchmarkConfig.MAX_TOKENS,
            step = 32,
            enabled = !running,
            onChange = { prefill = it }
        )
        BenchStepSlider(
            label = stringResource(R.string.benchmark_decode_tokens),
            value = decode,
            min = BenchmarkConfig.MIN_TOKENS,
            max = BenchmarkConfig.MAX_TOKENS,
            step = 32,
            enabled = !running,
            onChange = { decode = it }
        )
        BenchStepSlider(
            label = stringResource(R.string.benchmark_runs),
            value = runs,
            min = BenchmarkConfig.MIN_RUNS,
            max = BenchmarkConfig.MAX_RUNS,
            step = 1,
            enabled = !running,
            onChange = { runs = it }
        )
        Spacer(Modifier.height(8.dp))

        when (state) {
            is BenchmarkUiState.Running -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.benchmark_running, state.current, state.total),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            else -> {
                Button(
                    onClick = { onRun(BenchmarkConfig(prefill, decode, runs)) },
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
        }

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
                BenchmarkResultRow(result)
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun BenchStepSlider(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "$value",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = {
                val snapped = (it / step).roundToInt() * step
                onChange(snapped.coerceIn(min, max))
            },
            valueRange = min.toFloat()..max.toFloat(),
            steps = (((max - min) / step) - 1).coerceAtLeast(0),
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun BenchmarkResultRow(result: BenchmarkResultEntity) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
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
                Metric(stringResource(R.string.benchmark_metric_prefill), result.prefillTokPerSecAvg, R.string.benchmark_unit_tps)
                Metric(stringResource(R.string.benchmark_metric_decode), result.decodeTokPerSecAvg, R.string.benchmark_unit_tps)
                MetricInt(stringResource(R.string.benchmark_metric_ttft), result.ttftMsAvg.roundToInt(), "ms")
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: Float, unitRes: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = "%.1f %s".format(value, stringResource(unitRes)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun MetricInt(label: String, value: Int, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = "$value $unit",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
