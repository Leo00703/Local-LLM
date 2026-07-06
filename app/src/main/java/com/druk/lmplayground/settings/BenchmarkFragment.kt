package com.druk.lmplayground.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.navigation.fragment.findNavController
import com.druk.lmplayground.App
import com.druk.lmplayground.benchmark.BenchmarkUiState
import com.druk.lmplayground.conversation.ConversationViewModel
import com.druk.lmplayground.data.BenchmarkResultEntity
import com.druk.lmplayground.models.ModelInfo
import com.druk.lmplayground.theme.PlaygroundTheme

class BenchmarkFragment : Fragment() {

    // Shared (activity-scoped) so we drive the SAME model owner as the chat.
    private val viewModel: ConversationViewModel by activityViewModels()

    override fun onResume() {
        super.onResume()
        // Refresh the downloaded-models list so the picker is up to date.
        viewModel.loadModelList()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(inflater.context).apply {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
        val repo = (requireActivity().application as App).benchmarkRepository
        setContent {
            PlaygroundTheme {
                val allModels by viewModel.models.observeAsState(emptyList())
                val state by viewModel.benchmarkState.observeAsState(BenchmarkUiState.Idle)

                // Downloaded on-device models only (skip remote + not-downloaded).
                val models = remember(allModels) {
                    allModels.filter { it.isDownloaded && !it.model.filename.startsWith("remote:") }
                }

                var selected by remember { mutableStateOf<ModelInfo?>(null) }
                LaunchedEffect(models) {
                    if (selected == null || models.none { it.model.filename == selected?.filename }) {
                        selected = models.firstOrNull()?.model
                    }
                }

                val historyLive: LiveData<List<BenchmarkResultEntity>> =
                    remember(selected?.filename) {
                        selected?.let { repo.getForModelLive(it.filename) }
                            ?: MutableLiveData(emptyList())
                    }
                val history by historyLive.observeAsState(emptyList())

                BenchmarkScreen(
                    models = models,
                    selectedModel = selected,
                    state = state,
                    history = history,
                    onSelectModel = { selected = it },
                    onRun = { model, hw, config -> viewModel.runBenchmarkSuite(model, hw, config) },
                    onCancel = { viewModel.cancelBenchmark() },
                    onBackClick = { findNavController().popBackStack() },
                )
            }
        }
    }
}
