package com.druk.lmplayground.conversation

import android.app.Application
import android.net.Uri
import android.text.format.Formatter
import androidx.annotation.MainThread
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import com.druk.llamacpp.InferenceLimits
import com.druk.llamacpp.InferenceState
import com.druk.llamacpp.InferenceUnavailableException
import com.druk.llamacpp.GenerationBackend
import com.druk.llamacpp.GenerationModel
import com.druk.llamacpp.LlamaCpp
import com.druk.llamacpp.LlamaGenerationCallback
import com.druk.llamacpp.LlamaProgressCallback
import com.druk.lmplayground.remote.RemoteOpenAiClient
import com.druk.lmplayground.remote.RemoteOpenAiModel
import com.druk.lmplayground.remote.ServerModelDetails
import com.druk.llamacpp.PayloadTooLargeException
import com.druk.lmplayground.App
import com.druk.lmplayground.data.ChatMessageEntity
import com.druk.lmplayground.data.ChatRepository
import com.druk.lmplayground.data.ChatSessionEntity
import com.druk.lmplayground.data.FolderEntity
import com.druk.lmplayground.data.ConversationMetadata
import com.druk.lmplayground.data.SystemPromptEntity
import com.druk.lmplayground.data.SystemPromptRepository
import com.druk.lmplayground.models.DeviceCapability
import com.druk.lmplayground.models.ModelInfo
import com.druk.lmplayground.models.ModelInfoProvider
import com.druk.lmplayground.models.ModelWithStatus
import com.druk.lmplayground.models.resolveCapabilities
import com.druk.lmplayground.files.Attachment
import com.druk.lmplayground.files.AttachmentKind
import com.druk.lmplayground.files.FileExtractionResult
import com.druk.lmplayground.files.FileTextExtractor
import com.druk.lmplayground.files.StagedAttachment
import com.druk.lmplayground.files.StagedState
import com.druk.lmplayground.storage.StoragePreferences
import com.druk.lmplayground.storage.StorageRepository
import com.druk.lmplayground.tools.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.round

// Minimum gap between per-token haptic ticks. ~60 ms (≈16/s) reads as
// distinct typewriter taps rather than a continuous buzz on fast streams.
private const val HAPTIC_MIN_INTERVAL_MS = 60L

class ConversationViewModel(val app: Application) : AndroidViewModel(app) {

    private val llamaCpp: LlamaCpp? = (app as? App)?.llamaCpp
    private var llamaModel: GenerationModel? = null
    private var llamaSession: GenerationBackend? = null
    private var generatingJob: Job? = null

    // Keep strong reference to prevent GC from closing the file descriptor
    private var modelFileHandle: StorageRepository.ModelFileHandle? = null

    private val _isGenerating = MutableLiveData(false)
    private val _isModelReady = MutableLiveData(false)
    private val _modelLoadingProgress = MutableLiveData(0f)
    private val _loadedModel = MutableLiveData<ModelInfo?>(null)
    private val _loadedModelStatus = MutableLiveData<String?>(null)

    // Captured at load time and reused as the foreground-notification
    // description across load -> generating -> ready transitions, so the
    // silent notification reflects what the model is currently doing
    // without ever re-posting noisily. The "loaded" state shows the size
    // line ("<name> - <size>"); generating/ready swap in a live token
    // count ("<name> · <N> tokens"), so the model name is kept too.
    private var notificationModelLine: String? = null
    private var notificationModelName: String? = null
    private val _models = MutableLiveData<List<ModelWithStatus>>(emptyList())
    private val _supportsThinking = MutableLiveData(false)
    private val _thinkingEnabled = MutableLiveData(false)
    private val _generationParams = MutableLiveData(GenerationParams())
    private val _maxContextSize = MutableLiveData(4096)
    private val _sessionModelHint = MutableLiveData<Pair<String, String>?>(null) // (modelName, modelFilename)
    private val _supportsToolCalling = MutableLiveData(false)
    private val _toolEnabledStates = MutableLiveData<Map<String, Boolean>>(emptyMap())
    val toolRegistry = ToolRegistry.createDefault(app)
    val toolEnabledStates: LiveData<Map<String, Boolean>> = _toolEnabledStates
    // Files the user picked but hasn't sent yet (the composer chips). Text is
    // extracted at pick time (so the chip shows the token cost); the combined
    // context budget + prompt injection happen at send time.
    private val _stagedAttachments = MutableLiveData<List<StagedAttachment>>(emptyList())
    val stagedAttachments: LiveData<List<StagedAttachment>> = _stagedAttachments
    private var stagedIdCounter = 0L
    private val _systemPrompt = MutableLiveData("")
    private val _systemPromptId = MutableLiveData<String?>(null)
    /**
     * One-shot user-facing error messages (e.g. "message too long").
     * The UI shows a Toast and resets to null via [consumeUserError].
     */
    private val _userError = MutableLiveData<String?>(null)
    /**
     * Set when [loadModel] hits the RAM-fit gate. The UI surfaces a
     * confirmation dialog so the user can override and load anyway.
     * Carries the (modelInfo, neededRam, totalRam) tuple so the dialog
     * can show concrete numbers without re-querying.
     */
    private val _pendingRamWarning =
        MutableLiveData<RamWarning?>(null)

    /**
     * Set when the native loader returns null — the GGUF is corrupt,
     * unreadable, or uses an architecture this build of llama.cpp
     * doesn't recognize. The UI surfaces a one-shot AlertDialog and
     * resets to null via [consumeModelLoadError].
     */
    private val _modelLoadError = MutableLiveData<String?>(null)

    private val storagePreferences = StoragePreferences(app)
    val storageRepository = StorageRepository(app, storagePreferences)

    // Whether to show the What's New "Set up tools" button. Shown until the
    // user has opened the Tools settings once (the flag is set there, not on
    // tap, so the button doesn't visibly vanish under the user's finger).
    // Re-read on resume so it disappears after returning from Tools settings.
    private val _showToolsSetup = MutableLiveData(!storagePreferences.toolsSetupSeen)
    val showToolsSetup: LiveData<Boolean> = _showToolsSetup

    @MainThread
    fun refreshToolsSetupVisibility() {
        _showToolsSetup.value = !storagePreferences.toolsSetupSeen
    }

    // Whether per-message generation stats are shown (Settings → Sound,
    // Haptics & Stats). Re-read on resume so a change in Settings takes effect
    // when the user returns to the chat.
    private val _showGenerationStats = MutableLiveData(storagePreferences.showGenerationStats)
    val showGenerationStats: LiveData<Boolean> = _showGenerationStats

    @MainThread
    fun refreshShowGenerationStats() {
        _showGenerationStats.value = storagePreferences.showGenerationStats
    }

    // Context-window meter: tokens currently occupying the KV cache. `used` is
    // parsed from the native session report after each turn; the total comes
    // from the session's configured context size (GenerationParams.contextSize
    // == llama_n_ctx). 0 when no session / fresh conversation.
    private val _contextUsedTokens = MutableLiveData(0)
    val contextUsedTokens: LiveData<Int> = _contextUsedTokens

    /**
     * Parse the "Context: <used> / <total> tokens" line emitted by the native
     * session report (see LlamaGenerationSession.cpp getReport) and post the
     * used count to [contextUsedTokens]. Best-effort: a missing or changed
     * format leaves the meter at its last value rather than crashing.
     */
    private fun postContextFromReport(report: String?) {
        val line = report?.lineSequence()?.firstOrNull { it.contains("Context:") } ?: return
        val used = line.substringAfter("Context:")
            .substringBefore("/")
            .trim()
            .toIntOrNull() ?: return
        _contextUsedTokens.postValue(used.coerceAtLeast(0))
    }

    val isGenerating: LiveData<Boolean> = _isGenerating
    val isModelReady: LiveData<Boolean> = _isModelReady
    val modelLoadingProgress: LiveData<Float> = _modelLoadingProgress
    val loadedModel: LiveData<ModelInfo?> = _loadedModel
    val loadedModelStatus: LiveData<String?> = _loadedModelStatus

    // Remote (OpenAI-compatible) server, surfaced in the model picker.
    private val _remoteServerAvailable = MutableLiveData(false)
    val remoteServerAvailable: LiveData<Boolean> = _remoteServerAvailable
    private val _remoteServerLabel = MutableLiveData("")
    val remoteServerLabel: LiveData<String> = _remoteServerLabel
    private val _remoteModels = MutableLiveData<List<String>>(emptyList())
    val remoteModels: LiveData<List<String>> = _remoteModels
    private val _remoteModelsLoading = MutableLiveData(false)
    val remoteModelsLoading: LiveData<Boolean> = _remoteModelsLoading
    private val _remoteServerType = MutableLiveData("")
    val remoteServerType: LiveData<String> = _remoteServerType
    // Native metadata of the currently-loaded remote model (for the details card).
    private val _serverModelDetails = MutableLiveData<ServerModelDetails?>(null)
    val serverModelDetails: LiveData<ServerModelDetails?> = _serverModelDetails
    val models: LiveData<List<ModelWithStatus>> = _models
    val supportsThinking: LiveData<Boolean> = _supportsThinking
    val thinkingEnabled: LiveData<Boolean> = _thinkingEnabled
    val generationParams: LiveData<GenerationParams> = _generationParams
    val maxContextSize: LiveData<Int> = _maxContextSize
    val sessionModelHint: LiveData<Pair<String, String>?> = _sessionModelHint
    val supportsToolCalling: LiveData<Boolean> = _supportsToolCalling
    val systemPrompt: LiveData<String> = _systemPrompt
    val systemPromptId: LiveData<String?> = _systemPromptId
    val userError: LiveData<String?> = _userError
    val pendingRamWarning: LiveData<RamWarning?> = _pendingRamWarning
    val modelLoadError: LiveData<String?> = _modelLoadError

    /** Called by the UI after surfacing the error (e.g. as a Toast). */
    @MainThread
    fun consumeUserError() { _userError.value = null }

    @MainThread
    fun consumeModelLoadError() { _modelLoadError.value = null }

    @MainThread
    fun dismissRamWarning() { _pendingRamWarning.value = null }

    @MainThread
    fun confirmLoadDespiteRamWarning() {
        val pending = _pendingRamWarning.value ?: return
        _pendingRamWarning.value = null
        loadModel(pending.modelInfo, forceLoad = true)
    }

    val uiState = ConversationUiState(
        initialMessages = emptyList()
    )

    // Session persistence
    private val chatRepository: ChatRepository? = (app as? App)?.chatRepository
    private val systemPromptRepository: SystemPromptRepository? =
        (app as? App)?.systemPromptRepository
    private val _currentSessionId = MutableLiveData<String?>(null)
    val currentSessionId: LiveData<String?> = _currentSessionId
    val sessions: LiveData<List<ChatSessionEntity>> =
        chatRepository?.getAllSessions() ?: MutableLiveData(emptyList())
    val folders: LiveData<List<FolderEntity>> =
        chatRepository?.getAllFolders() ?: MutableLiveData(emptyList())
    /**
     * Per-model MRU list. When the loaded model changes, switchMap swaps in
     * the corresponding query so the picker reflects "prompts I've used on
     * *this* model" with the most-recently-used one first.
     */
    val recentSystemPrompts: LiveData<List<SystemPromptEntity>> =
        _loadedModel.switchMap { model ->
            val repo = systemPromptRepository
            val filename = model?.filename
            if (repo == null || filename.isNullOrEmpty()) {
                MutableLiveData(emptyList())
            } else {
                repo.getRecentForModelLive(filename)
            }
        }

    init {
        // Surface :llama process death to the UI. When the inference engine
        // crashes, the app process keeps running — we just need to tear
        // down stale handles, mark the in-flight assistant message as
        // interrupted, and let the user reload the model.
        val client = (app as? App)?.inferenceClient
        if (client != null) {
            viewModelScope.launch {
                client.state.collect { s ->
                    if (s is InferenceState.Crashed) onInferenceCrashed()
                }
            }
        }
    }

    private fun onInferenceCrashed() {
        // Disable Send IMMEDIATELY (synchronously) so a tap that lands
        // between the crash and the recovery flow can't enqueue a new
        // generation through the stale UI state. setValue is safe here —
        // we're already on the main dispatcher (state.collect runs
        // inside viewModelScope.launch which uses Dispatchers.Main).
        _isModelReady.value = false
        _isGenerating.value = false

        // Snapshot the references that were live AT THE TIME OF THE
        // CRASH. We need these because our cleanup runs *after* a
        // potentially long wait — during which the user may have
        // acknowledged the crash and successfully loaded a NEW model.
        // We must only clear handles that still point at the dead
        // session/model; otherwise we'd close the new model's PFD and
        // null the new session, leaving the UI ready with no engine.
        val staleSession = llamaSession
        val staleModel = llamaModel
        val staleHandle = modelFileHandle

        viewModelScope.launch {
            // The cancelled generation coroutine isn't done yet —
            // generateAll() suspends up to 30 s waiting for the dead
            // worker to drain. Until that finally block has run, the
            // job's NonCancellable cleanup could still mutate
            // uiState.messages.lastOrNull() and persist whatever it
            // sees. We MUST wait for it to drain before we touch any
            // shared state, or it'll persist a future placeholder
            // against the now-stale sessionId.
            val priorJob = generatingJob
            generatingJob = null
            try {
                priorJob?.cancelAndJoin()
            } catch (_: Throwable) { /* job is dead either way */ }

            // If the user already reloaded a model during the wait, the
            // current handles are NOT the stale ones — they belong to a
            // working session on a fresh :llama process. Bail without
            // touching anything; the new load already set
            // _isModelReady=true and a sensible status.
            if (llamaModel !== staleModel ||
                llamaSession !== staleSession ||
                modelFileHandle !== staleHandle) {
                return@launch
            }

            // Still pointing at the dead handles — clean them up.
            llamaSession = null
            llamaModel = null
            modelFileHandle?.close()
            modelFileHandle = null
            _loadedModelStatus.value = app.getString(
                com.druk.lmplayground.R.string.inference_engine_crashed,
            )
            Snapshot.withMutableSnapshot {
                // If the assistant was mid-response when the engine died,
                // append a clear marker so the user understands the
                // message stopped because of a crash, not because the
                // model finished.
                val last = uiState.messages.lastOrNull()
                if (last != null && last.author == "Assistant" && last.responseStartTimeMs > 0) {
                    val suffix = "\n\n_${app.getString(com.druk.lmplayground.R.string.inference_engine_crashed)}_"
                    uiState.updateLastMessage(
                        last.content + suffix,
                        thinkingTokens = last.thinkingTokens,
                        responseTokens = last.responseTokens,
                    )
                }
                uiState.finalizeLastMessage()
            }
        }
    }

    override fun onCleared() {
        val job = generatingJob
        val session = llamaSession
        val model = llamaModel
        val handle = modelFileHandle
        generatingJob = null
        llamaSession = null
        llamaModel = null
        modelFileHandle = null

        CoroutineScope(Dispatchers.Default).launch {
            job?.cancel()
            job?.join()
            session?.destroy()
            model?.unloadModel()
            handle?.close()
        }
        super.onCleared()
    }

    @MainThread
    fun loadModelList() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val modelFiles = storageRepository.getModelFiles()
                val downloadedFilenames = modelFiles.map { it.name }.toSet()
                val customModels = modelFiles
                    .filter { it.name !in ModelInfoProvider.knownFilenames }
                    .mapNotNull { file ->
                        val cached = storagePreferences.getCustomModelMetadata(file.name)
                            ?: return@mapNotNull null
                        if (!cached.second) return@mapNotNull null
                        ModelInfoProvider.createCustomModelInfo(file.name, cached.first, file.sizeBytes)
                    }
                _models.postValue(
                    ModelInfoProvider.getModelsWithStatus(downloadedFilenames, customModels)
                        .map { it.copy(model = it.model.resolveCapabilities(storagePreferences)) }
                )
                val remoteUrl = storagePreferences.remoteServerUrl
                _remoteServerAvailable.postValue(
                    storagePreferences.remoteServerEnabled && !remoteUrl.isNullOrBlank()
                )
                val remoteName = storagePreferences.remoteServerName
                _remoteServerLabel.postValue(
                    if (!remoteName.isNullOrBlank()) remoteName
                    else remoteUrl.orEmpty().substringAfter("://").ifEmpty { remoteUrl.orEmpty() }
                )
                _remoteServerType.postValue(storagePreferences.remoteServerType.orEmpty())
            }
        }
    }

    /** Fetch the remote server's model list for the picker's remote section. */
    fun fetchRemoteModels() {
        val url = storagePreferences.remoteServerUrl
        if (url.isNullOrBlank()) {
            _remoteModels.postValue(emptyList())
            return
        }
        if (_remoteModelsLoading.value == true) return
        _remoteModelsLoading.postValue(true)
        viewModelScope.launch {
            val models = RemoteOpenAiClient(url).listModels()
            _remoteModels.postValue(models)
            _remoteModelsLoading.postValue(false)
        }
    }

    /**
     * Connect to the configured remote server and use [modelId] as the active
     * model — no GGUF is loaded. Tears down any local model/session first and
     * replays the on-screen history into the new remote session.
     */
    fun loadRemoteModel(modelId: String) {
        val url = storagePreferences.remoteServerUrl
        if (url.isNullOrBlank()) {
            _userError.postValue(
                app.getString(com.druk.lmplayground.R.string.remote_server_not_configured)
            )
            return
        }
        viewModelScope.launch {
            _models.postValue(emptyList())
            _isModelReady.postValue(false)

            generatingJob?.cancel()
            generatingJob?.join()
            generatingJob = null

            val prevSession = llamaSession
            val prevModel = llamaModel
            val prevHandle = modelFileHandle
            llamaSession = null
            llamaModel = null
            modelFileHandle = null
            withContext(Dispatchers.Default) {
                prevSession?.destroy()
                prevModel?.unloadModel()
            }
            prevHandle?.close()

            // Read the server model's real metadata (context window, quant, …)
            // so the context ring and the details card are accurate.
            val details = RemoteOpenAiClient(url).fetchModelDetails(modelId)
            _serverModelDetails.postValue(details)
            val maxContext = details?.maxContext?.takeIf { it > 0 }
                ?: RemoteOpenAiModel.DEFAULT_CONTEXT

            val host = url.substringAfter("://").ifEmpty { url }
            val modelInfo = ModelInfo(
                name = ModelInfoProvider.prettifyModelId(modelId),
                filename = "remote:$modelId",
                description = app.getString(
                    com.druk.lmplayground.R.string.remote_model_description, host
                ),
                logoRes = ModelInfoProvider.logoForModelId(modelId),
            )
            _loadedModel.postValue(modelInfo)
            _thinkingEnabled.postValue(false)
            // Keep the thinking toggle available for remote models; enableThinking
            // is forwarded to the server as chat_template_kwargs when disabled.
            _supportsThinking.postValue(true)
            // OpenAI-compatible servers accept the `tools` param; the same VM tool
            // loop drives RemoteOpenAiBackend (tools only sent when the user enables
            // them, so non-tool models are unaffected by default).
            _supportsToolCalling.postValue(true)
            _toolEnabledStates.postValue(emptyMap())

            val params = GenerationParams(contextSize = maxContext)
            _generationParams.postValue(params)
            _systemPrompt.postValue("")
            _systemPromptId.postValue(null)
            _maxContextSize.postValue(maxContext)
            _contextUsedTokens.postValue(0)
            _sessionModelHint.postValue(null)

            val model = RemoteOpenAiModel(url, modelId, maxContext)
            val effectiveSystemPrompt = composeSystemPrompt("")
            currentEffectiveSystemPrompt = effectiveSystemPrompt
            val session = model.createSession(
                params.contextSize, params.temperature, params.topP,
                params.repetitionPenalty, params.topK, params.minP, params.seed,
                params.thinkingBudget, effectiveSystemPrompt
            )
            llamaModel = model
            llamaSession = session

            val messages = uiState.messages.toList()
            if (messages.isNotEmpty()) {
                try { replayHistoryToSession(session, messages) } catch (_: Throwable) {}
            }
            storagePreferences.remoteServerModel = modelId

            // Preload the model server-side behind the same loading hairline as
            // local models. No real % is available, so animate a logarithmic
            // estimate (mirrors the local fallback) while the warmup runs.
            _loadedModelStatus.postValue(app.getString(com.druk.lmplayground.R.string.remote_loading))
            val progressJob = launch {
                val start = System.currentTimeMillis()
                while (isActive) {
                    val elapsed = (System.currentTimeMillis() - start) / 1000f
                    _modelLoadingProgress.postValue(
                        minOf(0.9f, kotlin.math.ln(1f + elapsed) / kotlin.math.ln(31f))
                    )
                    kotlinx.coroutines.delay(100)
                }
            }
            withContext(Dispatchers.IO) { RemoteOpenAiClient(url).warmUp(modelId) }
            progressJob.cancel()
            _modelLoadingProgress.postValue(0f)
            _loadedModelStatus.postValue(host)
            _isModelReady.postValue(true)

            val sessionId = _currentSessionId.value
            if (sessionId != null) {
                chatRepository?.updateSessionModel(sessionId, modelInfo.filename, modelInfo.name)
            }
        }
    }

    /**
     * Silently flip the foreground-service notification to reflect the
     * current inference state (loaded / generating / ready). [text]
     * defaults to the cached "<name> - <size>" line used by the loaded
     * state; generating/ready pass a token-count line. No-op until a
     * model has been loaded. Safe to call from any thread — the
     * underlying AIDL call swallows binder failures.
     */
    private fun updateInferenceNotification(
        titleRes: Int,
        text: String? = notificationModelLine,
        actionBody: String? = null,
    ) {
        if (text == null) return
        llamaCpp?.setForegroundContent(app.getString(titleRes), text, actionBody)
    }

    /** "<name> · <N> tokens" for the generating/ready notification line. */
    private fun notificationTokensLine(tokens: Int): String? {
        val name = notificationModelName ?: return null
        val tokenStr = app.resources.getQuantityString(
            com.druk.lmplayground.R.plurals.inference_notification_tokens, tokens, tokens
        )
        return app.getString(
            com.druk.lmplayground.R.string.inference_notification_tokens_line, name, tokenStr
        )
    }

    @MainThread
    fun loadModel(modelInfo: ModelInfo, forceLoad: Boolean = false) {
        val llamaCpp = llamaCpp ?: return

        viewModelScope.launch {
            // RAM-fit check. Run BEFORE we tear down the currently-loaded
            // model so the user can cancel the warning and keep their
            // existing session intact.
            //
            // A model over the RAM budget isn't refused — instead we load it
            // memory-mapped by disabling weight repacking (disableRepack
            // below). Repacking would copy the quantized weights into RAM and
            // OOM-kill the :llama process; mmap keeps the footprint small (at
            // the cost of slower matmuls). The warning still fires once so the
            // user knows it'll be slower; "load anyway" re-enters with
            // forceLoad=true and the same disableRepack decision.
            val fileSizeBytes = withContext(Dispatchers.IO) {
                storageRepository.getModelFiles()
                    .find { it.name == modelInfo.filename }?.sizeBytes ?: 0L
            }
            val totalRamBytes = DeviceCapability.totalRamBytes(app)
            val exceedsRam = DeviceCapability.exceedsRamBudget(fileSizeBytes, totalRamBytes)
            // Advanced setting: when the user has globally disabled weight
            // repacking, load everything memory-mapped and skip the over-budget
            // warning (they've already accepted the mmap speed trade-off).
            val userDisableRepack = storagePreferences.disableRepack
            if (!forceLoad && exceedsRam && !userDisableRepack) {
                _pendingRamWarning.value = RamWarning(
                    modelInfo = modelInfo,
                    neededRam = Formatter.formatFileSize(app, fileSizeBytes),
                    totalRam = Formatter.formatFileSize(app, totalRamBytes),
                )
                return@launch
            }

            _models.postValue(emptyList())
            _isModelReady.postValue(false)


            // If we're recovering from a `:llama` crash, the InferenceClient
            // is in sticky `Crashed` state. Acknowledge it so the next AIDL
            // call uses the freshly auto-rebound service. Safe no-op when
            // the state is already Connected.
            (app as? App)?.inferenceClient?.let { ic ->
                if (ic.state.value is InferenceState.Crashed) {
                    ic.acknowledgeCrash()
                    // The auto-rebound service may still be landing — wait
                    // up to 5s for the next Connected transition before
                    // proceeding with loadModel.
                    try {
                        kotlinx.coroutines.withTimeout(5_000) {
                            ic.awaitConnected()
                        }
                    } catch (_: Throwable) {
                        _loadedModelStatus.postValue(
                            app.getString(com.druk.lmplayground.R.string.inference_engine_crashed)
                        )
                        return@launch
                    }
                }
            }

            // Stop any in-flight generation and tear down previous model
            generatingJob?.cancel()
            generatingJob?.join()
            generatingJob = null

            // Capture and null references on main thread to prevent races
            val prevSession = llamaSession
            val prevModel = llamaModel
            val prevHandle = modelFileHandle
            llamaSession = null
            llamaModel = null
            modelFileHandle = null

            withContext(Dispatchers.Default) {
                prevSession?.destroy()
                prevModel?.unloadModel()
            }

            prevHandle?.close()

            withContext(Dispatchers.Default) {
                _modelLoadingProgress.postValue(0f)
                _loadedModel.postValue(modelInfo)
                _thinkingEnabled.postValue(false)
                _supportsThinking.postValue(false)
                _loadedModelStatus.postValue("Loading...")

                val fileHandle = storageRepository.openModelFile(modelInfo.filename)
                if (fileHandle == null) {
                    _loadedModelStatus.postValue("Cannot open file")
                    return@withContext
                }

                modelFileHandle = fileHandle

                // llama.cpp only reports progress during tensor pointer setup,
                // which is near-instant with mmap. The slow parts (GGUF metadata
                // parsing, mmap init, buffer allocation) report nothing.
                // Animate estimated progress as a fallback so the bar moves
                // during the silent phases; the real callback overrides as
                // soon as the first real value arrives.
                val realProgressSeen = java.util.concurrent.atomic.AtomicBoolean(false)
                val progressJob = CoroutineScope(Dispatchers.Main).launch {
                    val startTime = System.currentTimeMillis()
                    while (isActive) {
                        if (!realProgressSeen.get()) {
                            val elapsed = (System.currentTimeMillis() - startTime) / 1000f
                            // Logarithmic curve: rises quickly then slows, caps at 0.9
                            val estimated = min(0.9f, ln(1f + elapsed) / ln(1f + 30f))
                            _modelLoadingProgress.postValue(estimated)
                            _loadedModelStatus.postValue("${round(100 * estimated).toInt()}%")
                        }
                        delay(100)
                    }
                }

                // Wrap the entire load + session setup so that the
                // progress-animation job is cancelled on every exit
                // path (success, exception, coroutine cancel). Without
                // this, a binder failure during loadModel would leave
                // the progress job ticking forever, overwriting the
                // crash status with bogus "85%" updates.
                try {
                    // Send the PFD across the binder. The service dups the
                    // FD into its own process and builds a process-local
                    // fd:N string. The app keeps `fileHandle` (the original
                    // PFD) alive via `modelFileHandle` for the model's
                    // lifetime.
                    val llamaModel = llamaCpp.loadModel(
                        fileHandle.pfd,
                        object: LlamaProgressCallback {
                            override fun onProgress(progress: Float) {
                                realProgressSeen.set(true)
                                _modelLoadingProgress.postValue(progress)
                                _loadedModelStatus.postValue(
                                    "${round(100 * progress).toInt()}%"
                                )
                            }
                        },
                        // Disable repack if the model is over budget (auto, to
                        // avoid an OOM-kill) OR the user turned it off globally.
                        disableRepack = exceedsRam || userDisableRepack,
                    )
                    val modelSize = llamaModel.getModelSize()
                    val modelDescription = Formatter.formatFileSize(app, modelSize)
                    // Surface "Model is loaded" + "<name> - <size>" in the
                    // FGS notification (otherwise hidden under MIN importance,
                    // but visible when the user expands the Silent group in
                    // the shade). The description line is cached so generation
                    // can later flip the title to "Generating…"/"Response
                    // ready" without re-deriving it.
                    notificationModelLine = "${modelInfo.name} - $modelDescription"
                    notificationModelName = modelInfo.name
                    updateInferenceNotification(
                        com.druk.lmplayground.R.string.inference_notification_loaded_title
                    )
                    val nCtxTrain = llamaModel.getContextTrainSize()
                    _maxContextSize.postValue(minOf(nCtxTrain, 16384))
                    // Load saved per-model params, or use defaults
                    val savedMap = storagePreferences.getModelGenerationParams(modelInfo.filename)
                    val params = if (savedMap != null) {
                        GenerationParams.fromMap(savedMap)
                    } else {
                        GenerationParams()
                    }
                    _generationParams.postValue(params)
                    // Every model load starts without a system prompt. Per-model
                    // MRU is surfaced in the picker row so the user can one-tap
                    // re-apply their most-recent prompt for this model.
                    _systemPrompt.postValue("")
                    _systemPromptId.postValue(null)
                    val llamaSession = createSessionWithParams(llamaModel, params, "")
                    if (llamaSession == null) {
                        _loadedModelStatus.postValue("Failed to create session")
                        llamaModel.unloadModel()
                        return@withContext
                    }
                    this@ConversationViewModel.llamaModel = llamaModel
                    this@ConversationViewModel.llamaSession = llamaSession
                    val thinkingSupported = llamaModel.supportsThinking()
                    _supportsThinking.postValue(thinkingSupported)
                    val toolCallingSupported = llamaModel.supportsToolCalling()
                    _supportsToolCalling.postValue(toolCallingSupported)
                    // Cache the real, template-detected capabilities so the model
                    // list can show accurate badges for this model (and any custom
                    // GGUF) without having to load it again.
                    storagePreferences.setDetectedCaps(
                        modelInfo.filename, toolCallingSupported, thinkingSupported
                    )
                    if (toolCallingSupported) {
                        val states = mutableMapOf<String, Boolean>()
                        for (tool in toolRegistry.getAllTools()) {
                            // Per-model override wins, else the global default.
                            val enabled = storagePreferences.effectiveToolEnabled(
                                modelInfo.filename, tool.name
                            )
                            toolRegistry.setToolEnabled(tool.name, enabled)
                            states[tool.name] = enabled
                        }
                        _toolEnabledStates.postValue(states)
                    } else {
                        _toolEnabledStates.postValue(emptyMap())
                    }
                    _modelLoadingProgress.postValue(0f)
                    _loadedModelStatus.postValue(modelDescription)
                    _sessionModelHint.postValue(null)
                    _contextUsedTokens.postValue(0)

                    // Replay history into the new session BEFORE marking the
                    // model ready. If a persisted message exceeds the
                    // 700 KB binder ceiling, replayHistory throws — and we
                    // do NOT want the user to start a new turn against a
                    // session that's missing prior context (the model
                    // would answer follow-up questions as if they were
                    // fresh prompts). Tear the session+model down on
                    // failure and surface a clear error.
                    val messages = uiState.messages.toList()
                    if (messages.isNotEmpty()) {
                        try {
                            replayHistoryToSession(llamaSession, messages)
                        } catch (e: PayloadTooLargeException) {
                            this@ConversationViewModel.llamaSession = null
                            this@ConversationViewModel.llamaModel = null
                            try { llamaSession.destroy() } catch (_: Throwable) {}
                            try { llamaModel.unloadModel() } catch (_: Throwable) {}
                            _loadedModelStatus.postValue(
                                app.getString(
                                    com.druk.lmplayground.R.string.replay_history_too_large
                                )
                            )
                            return@withContext
                        }
                    }
                    _isModelReady.postValue(true)

                    // Update session model info if we have an active session
                    val sessionId = _currentSessionId.value
                    if (sessionId != null) {
                        chatRepository?.updateSessionModel(
                            sessionId, modelInfo.filename, modelInfo.name
                        )
                    }
                } catch (t: Throwable) {
                    // Surface the failure to the user instead of leaving
                    // the picker stuck on "Loading…" forever.
                    _modelLoadingProgress.postValue(0f)
                    val statusMsg = app.getString(
                        com.druk.lmplayground.R.string.model_load_failed_status
                    )
                    _loadedModelStatus.postValue(statusMsg)
                    fileHandle.close()
                    if (t !is kotlinx.coroutines.CancellationException) {
                        android.util.Log.w("ConversationViewModel", "loadModel failed", t)
                        _modelLoadError.postValue(
                            app.getString(
                                com.druk.lmplayground.R.string.model_load_failed_message,
                                modelInfo.name,
                            )
                        )
                    } else {
                        throw t
                    }
                } finally {
                    progressJob.cancel()
                }
            }
        }
    }

    /**
     * Pre-flight check for every session-recreation path. Verifies the
     * system prompt and each persisted message fit under the AIDL
     * binder cap so the recreate doesn't half-succeed: destroying the
     * old session and then throwing inside `createSession` /
     * `replayHistory` leaves the UI with `_isModelReady=true` but
     * `llamaSession=null`, and the next Send breaks.
     *
     * Returns `null` if all payloads are within budget. Returns a
     * user-facing localized error string (already posted to
     * `_userError`) if anything is too large; the caller MUST then
     * abort without mutating session state.
     */
    private fun validateReplaySize(systemPrompt: String, messages: List<Message>): Boolean {
        val promptBytes = systemPrompt.length * 2
        if (promptBytes > InferenceLimits.MAX_PAYLOAD_BYTES) {
            _userError.postValue(
                app.getString(
                    com.druk.lmplayground.R.string.system_prompt_too_large,
                    promptBytes / 1024,
                    InferenceLimits.MAX_PAYLOAD_BYTES / 1024,
                )
            )
            return false
        }
        for (msg in messages) {
            // Measure the COMPOSED prompt (incl. any attachment text), not just
            // the visible content, so an oversized file is caught here at
            // pre-flight rather than overflowing the binder at replay.
            if (buildModelPrompt(msg).length * 2 > InferenceLimits.MAX_PAYLOAD_BYTES) {
                _userError.postValue(
                    app.getString(com.druk.lmplayground.R.string.history_message_too_large)
                )
                return false
            }
        }
        return true
    }

    private fun replayHistoryToSession(session: GenerationBackend, messages: List<Message>) {
        val userMessages = mutableListOf<String>()
        val assistantMessages = mutableListOf<String>()

        var i = 0
        while (i < messages.size) {
            val msg = messages[i]
            if (msg.author == "User" && i + 1 < messages.size && messages[i + 1].author == "Assistant") {
                userMessages.add(buildModelPrompt(msg))
                assistantMessages.add(messages[i + 1].content)
                i += 2
            } else {
                i++
            }
        }

        if (userMessages.isNotEmpty()) {
            session.replayHistory(
                userMessages.toTypedArray(),
                assistantMessages.toTypedArray()
            )
        }
    }

    /**
     * Swap [newSession] in as the live session after replaying [messages]
     * into it, destroying [prevSession] only once the replay succeeds (so a
     * late failure leaves the prior session intact instead of stranding the
     * UI session-less).
     *
     * Centralises the create-then-replace error handling shared by
     * [updateGenerationParams], [loadSession] and [applySystemPrompt]:
     *   - [PayloadTooLargeException]: a persisted message exceeds the binder
     *     cap; tear the new session down and keep the old one.
     *   - [InferenceUnavailableException]: the :llama service died mid-replay
     *     (or never re-connected). Previously this escaped the viewModelScope
     *     coroutine and crashed the app process — surfacing on Google Play as
     *     withService / requireConnected IUE. Now we tear the half-built
     *     session down and surface a recoverable error; the crash-recovery
     *     flow ([onInferenceCrashed]) cleans up the stale handles.
     *
     * Returns true if the swap happened, false if the caller should abort.
     */
    private fun swapInSessionWithReplay(
        newSession: GenerationBackend,
        prevSession: GenerationBackend?,
        messages: List<Message>,
    ): Boolean {
        try {
            if (messages.isNotEmpty()) {
                replayHistoryToSession(newSession, messages)
            }
        } catch (e: PayloadTooLargeException) {
            try { newSession.destroy() } catch (_: Throwable) {}
            _userError.postValue(
                app.getString(com.druk.lmplayground.R.string.history_message_too_large)
            )
            return false
        } catch (e: InferenceUnavailableException) {
            android.util.Log.w(
                "ConversationViewModel",
                "replayHistory failed: service unavailable", e
            )
            try { newSession.destroy() } catch (_: Throwable) {}
            _userError.postValue(
                app.getString(com.druk.lmplayground.R.string.inference_engine_unavailable)
            )
            return false
        }
        this@ConversationViewModel.llamaSession = newSession
        prevSession?.destroy()
        // The KV cache is rebuilt lazily on the next turn, but show the loaded
        // conversation's estimated context now (switching chats used to reset the
        // ring to 0). The real getReport() corrects it after the next generation.
        _contextUsedTokens.postValue(estimateContextTokens(messages))
        return true
    }

    @MainThread
    fun toggleThinking() {
        _thinkingEnabled.value = _thinkingEnabled.value != true
    }

    /**
     * Effective system prompt of the CURRENT session, captured when it was
     * created. The preamble-cache fingerprint reuses this exact string so the
     * cache key matches what was actually fed to the model (and tracks the date
     * when [composeSystemPrompt] prepends it — otherwise a stale "yesterday"
     * preamble could be reused).
     */
    private var currentEffectiveSystemPrompt: String = ""

    /**
     * The system prompt actually sent to the model. When the "include date"
     * setting is on, the current local date is prepended to [userPrompt] so the
     * model knows what day it is (it otherwise has no idea). Date only, not time:
     * a snapshot preamble would freeze the clock at session creation anyway, and
     * a minute-precision string would change every session and defeat the
     * preamble KV cache. Date granularity stays stable for a whole day, so the
     * cache is reused within the day and invalidates correctly at the rollover.
     */
    private fun composeSystemPrompt(userPrompt: String): String {
        if (!storagePreferences.includeDateTimeInPrompt) return userPrompt
        val now = java.text.DateFormat.getDateInstance(
            java.text.DateFormat.FULL,
            java.util.Locale.getDefault()
        ).format(java.util.Date())
        val line = app.getString(com.druk.lmplayground.R.string.system_prompt_datetime, now)
        return if (userPrompt.isBlank()) line else "$line\n\n$userPrompt"
    }

    private fun createSessionWithParams(
        model: GenerationModel,
        params: GenerationParams,
        systemPrompt: String = _systemPrompt.value.orEmpty()
    ): GenerationBackend? {
        val effective = composeSystemPrompt(systemPrompt)
        currentEffectiveSystemPrompt = effective
        return try {
            model.createSession(
                params.contextSize,
                params.temperature,
                params.topP,
                params.repetitionPenalty,
                params.topK,
                params.minP,
                params.seed,
                params.thinkingBudget,
                effective
            )
        } catch (e: InferenceUnavailableException) {
            // The :llama service died (or hasn't bound yet). Surface a
            // recoverable error to the UI rather than letting the AIDL
            // exception propagate and crash the app process.
            android.util.Log.w("ConversationViewModel", "createSession failed: service unavailable", e)
            _userError.postValue(
                app.getString(com.druk.lmplayground.R.string.inference_engine_unavailable)
            )
            null
        }
    }

    @MainThread
    fun updateGenerationParams(params: GenerationParams) {
        val oldParams = _generationParams.value ?: GenerationParams()
        val systemPrompt = _systemPrompt.value.orEmpty()

        // Pre-validate BEFORE mutating any state. Without this, an
        // oversized prompt or saved message would set _generationParams
        // (UI shows the new params) and persist the update to Room,
        // then fail to recreate the session — leaving the UI showing
        // the new params but the engine running on the old session.
        val messagesToReplay = if (oldParams.contextSize != params.contextSize) {
            // Context-size change resets the conversation, no replay.
            emptyList()
        } else {
            uiState.messages.toList()
        }
        if (llamaModel != null && !validateReplaySize(systemPrompt, messagesToReplay)) return

        _generationParams.value = params

        // Save as per-model defaults
        val modelFilename = _loadedModel.value?.filename
        if (modelFilename != null) {
            storagePreferences.setModelGenerationParams(modelFilename, params.toMap())
        }

        // Persist to Room if we have an active session
        val sessionId = _currentSessionId.value
        if (sessionId != null) {
            viewModelScope.launch {
                chatRepository?.updateSessionParams(
                    sessionId,
                    params.contextSize, params.temperature, params.topP,
                    params.repetitionPenalty, params.topK, params.minP, params.seed,
                    params.thinkingBudget
                )
            }
        }

        // If context size changed, must recreate session (resets conversation)
        if (oldParams.contextSize != params.contextSize) {
            val model = llamaModel ?: return
            viewModelScope.launch {
                generatingJob?.cancel()
                generatingJob?.join()
                generatingJob = null

                val prevSession = llamaSession

                _currentSessionId.value = null
                uiState.resetMessages()
                _contextUsedTokens.postValue(0)

                withContext(Dispatchers.Default) {
                    val newSession = createSessionWithParams(model, params, systemPrompt)
                    if (newSession != null) {
                        this@ConversationViewModel.llamaSession = newSession
                        prevSession?.destroy()
                    } else {
                        // Keep using the old session if we couldn't make a new one.
                        prevSession?.destroy()
                        this@ConversationViewModel.llamaSession = null
                    }
                }
            }
        } else {
            // Other params: recreate session but replay history. We
            // already pre-validated at the top, so no validation here.
            val model = llamaModel ?: return
            val messages = uiState.messages.toList()
            viewModelScope.launch {
                generatingJob?.cancel()
                generatingJob?.join()
                generatingJob = null

                val prevSession = llamaSession

                withContext(Dispatchers.Default) {
                    // Create the new session FIRST. Only after a successful
                    // create + replay do we destroy the old one — this way
                    // a late failure leaves the prior session intact and
                    // usable instead of stranding the UI session-less.
                    val newSession = createSessionWithParams(model, params, systemPrompt)
                        ?: return@withContext
                    swapInSessionWithReplay(newSession, prevSession, messages)
                }
            }
        }
    }

    /** Pick-time: stage the file immediately (Extracting), then extract its text. */
    @MainThread
    fun stageAttachment(uri: Uri, filename: String, mimeType: String?) {
        val id = ++stagedIdCounter
        _stagedAttachments.value = _stagedAttachments.value.orEmpty() +
            StagedAttachment(id, uri, filename, mimeType)
        viewModelScope.launch {
            val state = when (val r = FileTextExtractor.extract(app, uri, filename, mimeType)) {
                is FileExtractionResult.Success -> StagedState.Ready(r.text, r.charCount, r.truncated, r.rawText)
                is FileExtractionResult.Empty ->
                    StagedState.Error(app.getString(com.druk.lmplayground.R.string.attachment_empty, filename))
                is FileExtractionResult.Unsupported ->
                    StagedState.Error(app.getString(com.druk.lmplayground.R.string.attachment_unsupported, filename))
                is FileExtractionResult.Failure ->
                    StagedState.Error(app.getString(com.druk.lmplayground.R.string.attachment_failed, filename))
            }
            _stagedAttachments.value = _stagedAttachments.value.orEmpty()
                .map { if (it.id == id) it.copy(state = state) else it }
        }
    }

    @MainThread
    fun removeStagedAttachment(id: Long) {
        _stagedAttachments.value = _stagedAttachments.value.orEmpty().filter { it.id != id }
    }

    @MainThread
    fun clearStagedAttachments() {
        _stagedAttachments.value = emptyList()
    }

    /**
     * Send a user turn with any staged files attached. Text was already extracted
     * at pick time; here we apply a combined context-window budget across all
     * ready files. Files still extracting or in error are skipped (their chip
     * already showed the state). The visible content stays the user's typed text;
     * [buildModelPrompt] folds the file text into the prompt.
     */
    @MainThread
    fun sendUserMessage(content: String) {
        if (_isGenerating.value == true) return
        val staged = _stagedAttachments.value.orEmpty()
        _stagedAttachments.value = emptyList()
        val ready = staged.mapNotNull { s -> (s.state as? StagedState.Ready)?.let { s to it } }
        if (ready.isEmpty()) {
            addMessage(Message("User", content))
            return
        }
        addMessage(Message("User", content, attachments = buildAttachments(ready, content)))
    }

    /** Distribute one shared budget (~half the context window, under the binder cap) across files. */
    private fun buildAttachments(
        ready: List<Pair<StagedAttachment, StagedState.Ready>>,
        userContent: String,
    ): List<Attachment> {
        val contextSize = _generationParams.value?.contextSize ?: 4096
        val byteCeilingChars = (InferenceLimits.MAX_PAYLOAD_BYTES / 2) - userContent.length - 512
        var remaining = minOf((contextSize * 0.5).toInt() * 4, byteCeilingChars).coerceAtLeast(0)
        return ready.map { (s, r) ->
            val text = if (r.text.length > remaining) r.text.take(remaining) else r.text
            val truncated = r.truncated || text.length < r.text.length
            remaining = (remaining - text.length).coerceAtLeast(0)
            Attachment(s.filename, s.mimeType, AttachmentKind.DOCUMENT, text, text.length, truncated, r.rawText)
        }
    }

    /**
     * Compose the text actually sent to the model for [m]: each DOCUMENT
     * attachment's extracted text fenced and prepended, then the user's content.
     * IMAGE attachments are skipped (they route to the vision pipeline). This is
     * the ONE composition point used by every backend callsite; Message.content
     * stays the user's typed text (display + share/copy).
     */
    private fun buildModelPrompt(m: Message): String {
        val docs = m.attachments.filter {
            it.kind == AttachmentKind.DOCUMENT && it.extractedText.isNotBlank()
        }
        if (docs.isEmpty()) return m.content
        return buildString {
            for (a in docs) {
                append("[Attached file: ").append(a.name).append("]\n```\n")
                append(a.extractedText)
                if (a.truncated) append("\n[… file truncated to fit the context window …]")
                append("\n```\n\n")
            }
            append(m.content)
        }
    }

    /**
     * Rough token estimate of a whole conversation's context (system prompt + all
     * messages, user turns incl. their attachment text). Fills the context ring
     * when switching chats, instead of resetting it to 0 until the next turn.
     */
    private fun estimateContextTokens(messages: List<Message>): Int {
        var chars = composeSystemPrompt(_systemPrompt.value.orEmpty()).length
        for (m in messages) {
            chars += if (m.author == "User") buildModelPrompt(m).length else m.content.length
        }
        return chars / 4
    }

    @MainThread
    fun addMessage(message: Message) {
        val enableThinking = _thinkingEnabled.value == true

        // Pre-validate the message size BEFORE we mutate any UI state.
        // If we appended the user/assistant placeholder first, an
        // oversized message would throw later — leaving the chat stuck
        // with `_isGenerating=true` and a half-empty assistant bubble.
        // A clean abort here matches what the user expects: nothing
        // visibly happened, but the input shows an error.
        // The model receives the user's text PLUS any attachment's extracted
        // text (buildModelPrompt); size-guard and feed the native side that.
        val modelPrompt = buildModelPrompt(message)
        // Live context-ring estimate: jump up on send (prompt incl. attachment),
        // grow per output token below; the real getReport() corrects it at the end.
        val ctxBaseline = _contextUsedTokens.value ?: 0
        val promptTokenEstimate = modelPrompt.length / 4
        val sizeBytes = modelPrompt.length * 2
        if (sizeBytes > InferenceLimits.MAX_PAYLOAD_BYTES) {
            _userError.postValue(
                app.getString(
                    com.druk.lmplayground.R.string.message_too_large,
                    sizeBytes / 1024,
                    InferenceLimits.MAX_PAYLOAD_BYTES / 1024,
                )
            )
            return
        }

        Snapshot.withMutableSnapshot {
            uiState.addMessage(message)
            val now = System.currentTimeMillis()
            uiState.addMessage(
                Message(
                    "Assistant",
                    "",
                    thinkingStartTimeMs = if (enableThinking) now else 0L,
                    responseStartTimeMs = now
                )
            )
        }

        _isGenerating.postValue(true)
        // Marker on the assistant placeholder we just added — used by the
        // cleanup path below to confirm the still-active message in
        // uiState is OURS and not a placeholder for some later turn the
        // user added after a crash + reload. Must be a field that
        // *every* phase of the placeholder preserves: previously this
        // was responseStartTimeMs, but addToolCallsToLastMessage resets
        // it to start the post-tool timer, so after the first tool call
        // the cleanup would always bail out and leave _isGenerating
        // stuck at true. Message.id is auto-incremented and never
        // changes through .copy() updates — the right identity field.
        val ourMessageId = uiState.messages.lastOrNull()?.id ?: -1L
        generatingJob = viewModelScope.launch {
            val ourJob = coroutineContext[Job]

            // Persist user message
            val sessionId = ensureSession(message)
            persistMessage(sessionId, message)

            withContext(Dispatchers.Default) {
                val llamaSession = llamaSession ?: return@withContext

                // Re-hydrate per-tool enablement from prefs before every turn so
                // a tool toggled in Settings -> Tools (global default) or a
                // model's params sheet (per-model override) takes effect on the
                // next message — without this, enabling a tool after the model
                // was loaded had no effect (the registry was only hydrated at
                // load time), so the model never saw any tools.
                _loadedModel.value?.filename?.let { filename ->
                    val states = mutableMapOf<String, Boolean>()
                    for (tool in toolRegistry.getAllTools()) {
                        val enabled = storagePreferences.effectiveToolEnabled(filename, tool.name)
                        toolRegistry.setToolEnabled(tool.name, enabled)
                        states[tool.name] = enabled
                    }
                    _toolEnabledStates.postValue(states)
                }

                // Tools are active when model supports it and user has tools enabled
                val toolsActive = _supportsToolCalling.value == true
                    && toolRegistry.hasEnabledTools()
                try {
                    val toolsJson = if (toolsActive) toolRegistry.toOpenAIToolsJson() else "[]"
                    llamaSession.setTools(toolsJson)
                    // Persistent preamble KV cache: must be set after setTools
                    // (the fingerprint covers the active tool set) and before
                    // addMessage (the lazy load/save runs on the first
                    // addMessage of the session). It's a no-op if the model
                    // info is unavailable. Pruning the cache directory is
                    // best-effort; failures don't block generation.
                    applyPreambleCache(llamaSession, toolsJson)
                    llamaSession.addMessage(modelPrompt, enableThinking)
                    // Reflect the prompt (incl. any attachment) in the ring now,
                    // before the first token, so it doesn't sit at the old value.
                    _contextUsedTokens.postValue(ctxBaseline + promptTokenEstimate)
                } catch (e: InferenceUnavailableException) {
                    android.util.Log.w("ConversationViewModel", "addMessage failed: service unavailable", e)
                    _userError.postValue(
                        app.getString(com.druk.lmplayground.R.string.inference_engine_unavailable)
                    )
                    Snapshot.withMutableSnapshot {
                        // Drop the empty assistant placeholder so the chat
                        // doesn't sit forever on a half-blank bubble.
                        if ((uiState.messages.lastOrNull() as? Message)?.id == ourMessageId) {
                            uiState.removeLastMessage()
                        }
                    }
                    _isGenerating.postValue(false)
                    return@withContext
                }

                // Resolve the haptic gate once per turn: the in-app setting
                // AND the system-wide haptic toggle (a ContentResolver query
                // — too heavy to run per token).
                val hapticsAllowed = storagePreferences.hapticOnGeneration &&
                    GenerationHaptics.isSystemHapticsEnabled(app)
                val callback = object: LlamaGenerationCallback {
                    var totalTokens = 0
                    var thinkingTokenCount = 0
                    var thinkingComplete = !enableThinking
                    var modelIsThinking = enableThinking
                    // Throttle the silent token-count notification update to
                    // ~1/sec: setForegroundContent is a blocking binder call,
                    // so we must not fire it on every streamed token.
                    var lastNotifUpdateMs = 0L
                    // Throttle the per-token haptic tick so fast streams feel
                    // like rapid typing instead of one continuous buzz.
                    var lastHapticMs = 0L
                    // Throttle the live context-ring estimate update.
                    var lastCtxMs = 0L
                    override fun onFullResponse(response: String) {
                        totalTokens++
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - lastCtxMs >= 300L) {
                            lastCtxMs = nowMs
                            _contextUsedTokens.postValue(ctxBaseline + promptTokenEstimate + totalTokens)
                        }
                        if (nowMs - lastNotifUpdateMs >= 1000L) {
                            lastNotifUpdateMs = nowMs
                            updateInferenceNotification(
                                com.druk.lmplayground.R.string.inference_notification_generating_title,
                                notificationTokensLine(totalTokens),
                            )
                        }
                        var string = ResponseProcessor.process(response)

                        // Detect thinking from model output even when the
                        // toggle is off (models like LFM 2.5 always think)
                        var thinkingJustStarted = false
                        if (!modelIsThinking && string.startsWith("<think>")) {
                            modelIsThinking = true
                            thinkingComplete = false
                            thinkingJustStarted = true
                        }

                        if (!thinkingComplete && string.contains("</think>")) {
                            thinkingComplete = true
                            thinkingTokenCount = totalTokens
                        }
                        val currentThinkingTokens = if (thinkingComplete) thinkingTokenCount else totalTokens

                        // Typewriter-style haptic: a light tick per *output*
                        // token. Gated on thinkingComplete so the stream only
                        // buzzes for the visible answer, never the hidden
                        // thinking. Throttled, and only while the chat is
                        // on-screen (also satisfies the OS rule that bars
                        // background vibration).
                        if (hapticsAllowed &&
                            thinkingComplete &&
                            nowMs - lastHapticMs >= HAPTIC_MIN_INTERVAL_MS &&
                            (app as? App)?.isAppInForeground == true
                        ) {
                            lastHapticMs = nowMs
                            GenerationHaptics.tick(app)
                        }

                        val finalString = string
                        Snapshot.withMutableSnapshot {
                            if (thinkingJustStarted) {
                                uiState.markThinkingStarted()
                            }
                            uiState.updateLastMessage(
                                finalString,
                                thinkingTokens = currentThinkingTokens,
                                responseTokens = totalTokens - currentThinkingTokens
                            )
                        }
                    }
                }
                // Drive the generation loop, draining tool calls between
                // rounds. `generateAll()` is a single AIDL call that runs
                // service-side until the worker stops; it returns 2 when
                // the model emitted tool calls, 0 on natural stop, or
                // non-zero on error / cancel.
                //
                // Cancellation flows through the coroutine: cancelling
                // generatingJob cancels the suspend inside generateAll(),
                // which calls service.cancelGeneration() under the hood
                // and re-throws CancellationException once the worker
                // exits. We never re-enter the loop after a cancel.
                //
                // Silently flip the notification to "Generating…" with a
                // starting token count; the callback above bumps the count
                // ~1/sec as tokens stream. The finally block below freezes
                // it at the final total under "Response ready".
                updateInferenceNotification(
                    com.druk.lmplayground.R.string.inference_notification_generating_title,
                    notificationTokensLine(0),
                )
                try {
                    var toolRounds = 0
                    val maxToolRounds = 5
                    while (true) {
                        val rc = llamaSession.generateAll(callback)
                        if (rc != 2 || toolRounds >= maxToolRounds || !this.isActive) {
                            break
                        }
                        toolRounds++

                        val toolCallsJson = llamaSession.getToolCallsJson()
                        android.util.Log.d(
                            "ConversationVM",
                            "Tool calls (round $toolRounds): $toolCallsJson",
                        )

                        val toolStartTime = System.currentTimeMillis()
                        // Run the (blocking) tool execution on IO and await it so
                        // Stop can interrupt it: on cancel we abort in-flight
                        // network requests, which unblocks the call promptly.
                        val toolResults = withContext(Dispatchers.IO) {
                            val exec = async { toolRegistry.executeToolCalls(toolCallsJson) }
                            try {
                                exec.await()
                            } catch (e: CancellationException) {
                                toolRegistry.cancelInFlight()
                                throw e
                            }
                        }
                        val toolDurationMs = System.currentTimeMillis() - toolStartTime
                        android.util.Log.d(
                            "ConversationVM",
                            "Tool results (${toolDurationMs}ms): $toolResults",
                        )

                        val toolCallInfoList = buildToolCallInfoList(
                            toolCallsJson, toolResults, toolDurationMs,
                        )
                        Snapshot.withMutableSnapshot {
                            uiState.addToolCallsToLastMessage(toolCallInfoList)
                        }

                        // Force thinking on for the response phase if the
                        // model supports it, regardless of the user toggle.
                        // Gemma 4 and harmony-style models emit an empty
                        // content channel after tool calls when thinking is
                        // off — the chat would otherwise show a blank
                        // assistant bubble after every tool call. Reasoning
                        // still routes to the collapsed thinking section via
                        // the always-on DEEPSEEK extraction in the parser,
                        // so visible content stays clean. For models without
                        // a thinking mode this is a no-op (the flag is
                        // silently ignored). See testReproduceAppBehavior
                        // for the canonical repro.
                        val supportsThinking = _supportsThinking.value == true
                        val responseThinking = supportsThinking || enableThinking
                        llamaSession.submitToolResults(toolResults, responseThinking)

                        // Reset the streaming callback's per-round counters
                        // so the next generateAll() reports a fresh
                        // thinking-vs-response token split. The callback
                        // tracks WHICH phase the model is in for THIS round,
                        // so it follows responseThinking (the just-submitted
                        // flag) — not the user toggle — so the UI shows the
                        // thinking indicator while we wait for the answer.
                        callback.totalTokens = 0
                        callback.thinkingTokenCount = 0
                        callback.thinkingComplete = !responseThinking
                        callback.modelIsThinking = responseThinking

                        // Restart the thinking timer for the post-tool phase:
                        // addToolCallsToLastMessage reset thinkingStartTimeMs to 0,
                        // and the callback's modelIsThinking is pre-set true so the
                        // streaming path won't fire markThinkingStarted itself —
                        // without this the post-tool "Thinking" duration stays 0s.
                        if (responseThinking) {
                            Snapshot.withMutableSnapshot {
                                uiState.markThinkingStarted()
                            }
                        }
                    }
                } catch (e: InferenceUnavailableException) {
                    android.util.Log.w("ConversationViewModel", "generateAll failed: service unavailable", e)
                    _userError.postValue(
                        app.getString(com.druk.lmplayground.R.string.inference_engine_unavailable)
                    )
                } finally {
                    // Cleanup must complete even if the coroutine was
                    // cancelled (Stop tapped). NonCancellable lets us
                    // finish the Room writes and UI tear-down without
                    // re-throwing CancellationException mid-cleanup.
                    withContext(kotlinx.coroutines.NonCancellable) {
                        try { llamaSession.printReport() } catch (_: Throwable) {}

                        // If a newer generation has taken over this slot
                        // (crash + reload + new prompt while we were
                        // draining the dead worker), our cleanup must NOT
                        // touch any UI/persistence — uiState.messages
                        // now belongs to the new turn, finalizing it
                        // would clobber the in-flight new generation.
                        // The new job's own finally will handle its
                        // state. We just exit quietly.
                        val supersededByNewer = generatingJob !== ourJob
                        // Belt-and-suspenders: also confirm the last
                        // message in uiState is still our placeholder
                        // by stable Message.id identity.
                        val last = uiState.messages.lastOrNull()
                        val stillOurMessage = last != null &&
                            last.author == "Assistant" &&
                            last.id == ourMessageId
                        if (supersededByNewer || !stillOurMessage) {
                            return@withContext
                        }

                        Snapshot.withMutableSnapshot {
                            uiState.finalizeLastMessage()
                            // Remote backends report authoritative token/timing
                            // (the per-chunk counter is wrong for SSE); apply it.
                            val rs = try { llamaSession.lastStats() } catch (_: Throwable) { null }
                            if (rs != null) {
                                uiState.applyRemoteStats(rs.completionTokens, rs.ttftMs, rs.decodeMs)
                            }
                        }
                        _isGenerating.postValue(false)
                        // Refresh the context-window meter from this turn's real
                        // KV-cache usage (parsed from the session report).
                        val ctxReport = try { llamaSession.getReport() } catch (_: Throwable) { null }
                        postContextFromReport(ctxReport)
                        // Generation (or cancellation) is done — freeze the
                        // silent notification on "Response ready" with the
                        // final token count, and attach Copy/Share actions
                        // bound to the finalized response (think-tags
                        // stripped, matching the in-chat share/copy). Skipped
                        // on the superseded path above, so a newer in-flight
                        // turn's "Generating…" line is preserved.
                        val readyBody = (uiState.messages.lastOrNull()
                            ?.takeIf { it.author == "Assistant" }
                            ?.content
                            ?.let { stripThinkTags(it) })
                            ?.takeIf { it.isNotBlank() }
                        updateInferenceNotification(
                            com.druk.lmplayground.R.string.inference_notification_ready_title,
                            notificationTokensLine(callback.totalTokens),
                            actionBody = readyBody,
                        )

                        // If the user isn't looking at the app, play a short
                        // chime so they know the answer is ready. Gated on the
                        // in-app setting, a non-blank response (so a cancelled/
                        // empty turn stays quiet), and background state; the
                        // helper itself also respects silent/vibrate/DND.
                        if (storagePreferences.soundOnCompletion &&
                            readyBody != null &&
                            (app as? App)?.isAppInForeground == false
                        ) {
                            com.druk.lmplayground.inference.ResponseSound.playIfAudible(app)
                        }

                        // Persist whatever the assistant produced — including
                        // a partially-streamed response on cancel — so
                        // reload-from-DB matches what the user saw on screen.
                        val assistantMessage = uiState.messages.lastOrNull()
                        if (assistantMessage != null && assistantMessage.author == "Assistant") {
                            try {
                                persistMessage(sessionId, assistantMessage)
                                chatRepository?.updateSessionTimestamp(
                                    sessionId,
                                    System.currentTimeMillis(),
                                )
                                persistConversationMetadata(sessionId)
                            } catch (_: Throwable) { /* best-effort */ }
                        }
                    }
                }
            }
        }
    }

    /**
     * Re-run the most recent user turn to produce a fresh assistant reply.
     * No-op while generating, with no model loaded, or when there's no user
     * message to re-send. Everything after that user turn is discarded.
     */
    @MainThread
    fun regenerateLastResponse() {
        if (_isGenerating.value == true) return
        if (llamaModel == null) return
        val msgs = uiState.messages.toList()
        val lastUserIdx = msgs.indexOfLast { it.author == "User" }
        if (lastUserIdx < 0) return
        val userContent = msgs[lastUserIdx].content
        val prior = msgs.subList(0, lastUserIdx).toList()
        resendUserTurn(prior, userContent, msgs[lastUserIdx].attachments)
    }

    /**
     * Replace the text of the user message identified by [messageId] and
     * re-send it, discarding everything after it (LM Studio-style edit &
     * resend). No-op while generating, with no model loaded, when the target
     * isn't a user message, or when the new text is blank.
     */
    @MainThread
    fun editAndResend(messageId: Long, newText: String) {
        if (_isGenerating.value == true) return
        if (llamaModel == null) return
        val trimmed = newText.trim()
        if (trimmed.isEmpty()) return
        val msgs = uiState.messages.toList()
        val idx = msgs.indexOfFirst { it.id == messageId }
        if (idx < 0 || msgs[idx].author != "User") return
        val prior = msgs.subList(0, idx).toList()
        // Keep the original turn's attachment when re-sending the edited text.
        resendUserTurn(prior, trimmed, msgs[idx].attachments)
    }

    /**
     * Shared core of regenerate / edit-and-resend: rebuild the native session
     * so its KV cache reflects only [priorMessages], then re-send
     * [newUserContent] through the normal [addMessage] path (which appends the
     * user + assistant bubbles, persists them, and drives generation + tools).
     *
     * The native rebuild happens FIRST: if the engine is unavailable the
     * conversation is left untouched rather than truncated with nothing to show.
     */
    private fun resendUserTurn(
        priorMessages: List<Message>,
        newUserContent: String,
        attachments: List<Attachment> = emptyList(),
    ) {
        val model = llamaModel ?: return
        val params = _generationParams.value ?: GenerationParams()
        val systemPrompt = _systemPrompt.value.orEmpty()

        // The resent turn carries the original attachment; size-guard the COMPOSED
        // prompt (text + attachment) so we never half-rebuild then fail on payload.
        val resentMessage = Message("User", newUserContent, attachments = attachments)
        val sizeBytes = buildModelPrompt(resentMessage).length * 2
        if (sizeBytes > InferenceLimits.MAX_PAYLOAD_BYTES) {
            _userError.postValue(
                app.getString(
                    com.druk.lmplayground.R.string.message_too_large,
                    sizeBytes / 1024,
                    InferenceLimits.MAX_PAYLOAD_BYTES / 1024,
                )
            )
            return
        }
        if (!validateReplaySize(systemPrompt, priorMessages)) return

        val sessionId = _currentSessionId.value
        viewModelScope.launch {
            generatingJob?.cancel()
            generatingJob?.join()
            generatingJob = null

            // Rebuild the native session to the surviving prefix before we touch
            // any UI/persistence — a failure here leaves the prior session and
            // conversation intact.
            val prevSession = llamaSession
            val ok = withContext(Dispatchers.Default) {
                val newSession = createSessionWithParams(model, params, systemPrompt)
                    ?: return@withContext false
                swapInSessionWithReplay(newSession, prevSession, priorMessages)
            }
            if (!ok) return@launch

            // Reset the visible conversation and persisted history to the prefix;
            // addMessage re-persists the resent user turn and the new reply.
            uiState.setMessages(priorMessages)
            if (sessionId != null) {
                chatRepository?.replaceSessionMessages(
                    sessionId,
                    priorMessages.map { it.toChatMessageEntity(sessionId) }
                )
            }

            addMessage(resentMessage)
        }
    }

    private fun Message.toChatMessageEntity(sessionId: String): ChatMessageEntity {
        return ChatMessageEntity(
            sessionId = sessionId,
            author = author,
            content = content,
            thinkingDurationSeconds = thinkingDurationSeconds,
            thinkingTokens = thinkingTokens,
            responseTokens = responseTokens,
            responseDurationSeconds = responseDurationSeconds,
            responseDecodeSeconds = responseDecodeSeconds,
            timestamp = timestamp,
            // attachmentsJson is the source of truth (supports multiple files);
            // the single-attachment columns are kept only for reading old rows.
            attachmentsJson = attachmentsToJson(attachments),
        )
    }

    private fun attachmentsToJson(list: List<Attachment>): String? {
        if (list.isEmpty()) return null
        val arr = JSONArray()
        for (a in list) {
            arr.put(JSONObject().apply {
                put("name", a.name)
                put("mime", a.mime ?: "")
                put("kind", a.kind.name.lowercase())
                put("text", a.extractedText)
                put("charCount", a.charCount)
                put("truncated", a.truncated)
                a.rawText?.let { put("raw", it) }
            })
        }
        return arr.toString()
    }

    /** Re-hydrate a Message's attachments: prefer attachmentsJson, else the old columns. */
    private fun attachmentsFrom(e: ChatMessageEntity): List<Attachment> {
        e.attachmentsJson?.let { json ->
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Attachment(
                        name = o.getString("name"),
                        mime = o.optString("mime").ifEmpty { null },
                        kind = if (o.optString("kind") == "image") AttachmentKind.IMAGE else AttachmentKind.DOCUMENT,
                        extractedText = o.optString("text"),
                        charCount = o.optInt("charCount"),
                        truncated = o.optBoolean("truncated"),
                        rawText = o.optString("raw").ifEmpty { null },
                    )
                }
            } catch (_: Throwable) {
                emptyList()
            }
        }
        // Fallback: v1.9.12 single-column rows.
        val name = e.attachmentName ?: return emptyList()
        val kind = if (e.attachmentKind == "image") AttachmentKind.IMAGE else AttachmentKind.DOCUMENT
        val text = e.attachmentText.orEmpty()
        return listOf(Attachment(name, e.attachmentMime, kind, text, text.length, e.attachmentTruncated))
    }

    private suspend fun ensureSession(firstUserMessage: Message): String {
        val existing = _currentSessionId.value
        if (existing != null) return existing

        val modelInfo = _loadedModel.value
        val params = _generationParams.value ?: GenerationParams()
        val id = UUID.randomUUID().toString()
        val title = firstUserMessage.content.take(50)
        val now = System.currentTimeMillis()
        chatRepository?.insertSession(
            ChatSessionEntity(
                id = id,
                title = title,
                modelFilename = modelInfo?.filename ?: "",
                modelName = modelInfo?.name ?: "Unknown",
                createdAt = now,
                updatedAt = now,
                contextSize = params.contextSize,
                temperature = params.temperature,
                topP = params.topP,
                repetitionPenalty = params.repetitionPenalty,
                topK = params.topK,
                minP = params.minP,
                seed = params.seed,
                thinkingBudget = params.thinkingBudget,
                systemPrompt = _systemPrompt.value.orEmpty()
            )
        )
        _currentSessionId.postValue(id)
        return id
    }

    /**
     * Snapshot the web_search link references into the conversation's metadata
     * so a returned ref still resolves after the app is restarted and the
     * conversation reopened. Read-modify-write to preserve any other metadata
     * keys. No-op when there are no references to save.
     */
    private suspend fun persistConversationMetadata(sessionId: String) {
        val repo = chatRepository ?: return
        val links = toolRegistry.webLinkStore.snapshot()
        if (links.isEmpty()) return
        val existing = repo.getSession(sessionId)?.metadata
        val updated = ConversationMetadata.parse(existing)
            .putStringMap(ConversationMetadata.KEY_WEB_LINKS, links)
            .toJson()
        repo.updateSessionMetadata(sessionId, updated)
    }

    private suspend fun persistMessage(sessionId: String, message: Message) {
        // Single mapper so attachment columns are never written by only one path.
        chatRepository?.insertMessage(message.toChatMessageEntity(sessionId))
    }

    @MainThread
    fun loadSession(sessionId: String) {
        viewModelScope.launch {
            val messages = chatRepository?.getMessages(sessionId) ?: return@launch
            val sessionEntity = chatRepository.getSession(sessionId)
            val uiMessages = messages.map { entity ->
                Message(
                    author = entity.author,
                    content = entity.content,
                    thinkingDurationSeconds = entity.thinkingDurationSeconds,
                    thinkingTokens = entity.thinkingTokens,
                    responseTokens = entity.responseTokens,
                    responseDurationSeconds = entity.responseDurationSeconds,
                    responseDecodeSeconds = entity.responseDecodeSeconds,
                    timestamp = entity.timestamp,
                    attachments = attachmentsFrom(entity)
                )
            }

            // Pre-flight the saved chat against the AIDL payload cap
            // BEFORE switching any UI state. If a persisted message
            // (or the session's saved system prompt) is too large for
            // the binder, refuse the swap entirely — keep the user on
            // their current chat instead of half-loading a session
            // whose generated output would silently come from the
            // OLD session's KV cache.
            val newSystemPrompt = sessionEntity?.systemPrompt ?: ""
            if (!validateReplaySize(newSystemPrompt, uiMessages)) {
                return@launch
            }

            _currentSessionId.value = sessionId

            // Drop the previous conversation's web_search references; the loaded
            // session's own references (if any) are restored just below.
            toolRegistry.webLinkStore.clear()

            // Restore generation params from session
            if (sessionEntity != null) {
                val params = GenerationParams(
                    contextSize = sessionEntity.contextSize,
                    temperature = sessionEntity.temperature,
                    topP = sessionEntity.topP,
                    repetitionPenalty = sessionEntity.repetitionPenalty,
                    topK = sessionEntity.topK,
                    minP = sessionEntity.minP,
                    seed = sessionEntity.seed,
                    thinkingBudget = sessionEntity.thinkingBudget
                )
                _generationParams.value = params
                _systemPrompt.value = sessionEntity.systemPrompt
                // Try to rehydrate the library id from the stored text so that
                // "Update prompt" in the Generation Params sheet can target the
                // same library entry when it still matches.
                val stored = sessionEntity.systemPrompt
                if (stored.isEmpty()) {
                    _systemPromptId.value = null
                } else {
                    val entity = systemPromptRepository?.findByText(stored)
                    _systemPromptId.value = entity?.id
                }

                // Restore web_search link references saved with this conversation
                // so the model can still web_fetch a previously-returned ref.
                toolRegistry.webLinkStore.restore(
                    ConversationMetadata.parse(sessionEntity.metadata)
                        .getStringMap(ConversationMetadata.KEY_WEB_LINKS)
                )
            }

            // Show model hint if session used a different model
            if (sessionEntity != null &&
                sessionEntity.modelFilename.isNotEmpty() &&
                sessionEntity.modelFilename != _loadedModel.value?.filename
            ) {
                _sessionModelHint.value = Pair(sessionEntity.modelName, sessionEntity.modelFilename)
            } else {
                _sessionModelHint.value = null
            }

            uiState.setMessages(uiMessages)
            // The new session's KV cache is rebuilt lazily on the next turn, so
            // reset the meter to 0 until then.
            _contextUsedTokens.postValue(0)

            // Recreate native session with restored params and replay history
            val model = llamaModel
            if (model != null) {
                val systemPrompt = _systemPrompt.value.orEmpty()
                // Pre-validation already happened at the top of this
                // function (before any UI state mutation). The
                // try/catch below is defense-in-depth in case the
                // saved system prompt diverges from sessionEntity's.
                val prevSession = llamaSession
                withContext(Dispatchers.Default) {
                    val params = _generationParams.value ?: GenerationParams()
                    val newSession = createSessionWithParams(model, params, systemPrompt)
                        ?: return@withContext
                    swapInSessionWithReplay(newSession, prevSession, uiMessages)
                }
            }
        }
    }

    @MainThread
    fun newConversation() {
        viewModelScope.launch {
            generatingJob?.cancel()
            generatingJob?.join()
            generatingJob = null

            _currentSessionId.value = null
            _sessionModelHint.value = null
            uiState.resetMessages()
            // Fresh conversation starts with no web_search references.
            toolRegistry.webLinkStore.clear()
            _contextUsedTokens.postValue(0)

            // Recreate native session with clean KV cache
            val model = llamaModel
            if (model != null) {
                val prevSession = llamaSession
                llamaSession = null
                withContext(Dispatchers.Default) {
                    prevSession?.destroy()
                    val params = _generationParams.value ?: GenerationParams()
                    val newSession = createSessionWithParams(model, params) ?: return@withContext
                    this@ConversationViewModel.llamaSession = newSession
                }
            }
        }
    }

    /**
     * Apply a system prompt to the current session. Recreates the native session
     * so the new prompt takes effect, replays any existing messages, and bumps
     * the library entry's `lastUsedAt` when [promptId] is non-null.
     *
     * The intended caller is the picker row on an empty conversation, but the
     * method also supports mid-chat swaps (history replay handles it).
     */
    @MainThread
    fun applySystemPrompt(promptId: String?, text: String) {
        val current = _systemPrompt.value.orEmpty()
        val currentId = _systemPromptId.value
        if (current == text && currentId == promptId) return

        // Pre-validate BEFORE mutating any state. Without this, an
        // oversized prompt would set _systemPrompt (UI shows the new
        // prompt), destroy the old session, then throw inside
        // createSession — leaving the user with an in-flight UI but
        // a null llamaSession. The next Send would hit the early-
        // return inside addMessage and the placeholder would never
        // get cleaned up.
        val messages = uiState.messages.toList()
        if (!validateReplaySize(text, messages)) return

        _systemPrompt.value = text
        _systemPromptId.value = promptId

        // Persist on the active session row if one exists.
        val sessionId = _currentSessionId.value
        if (sessionId != null) {
            viewModelScope.launch {
                chatRepository?.updateSessionSystemPrompt(sessionId, text)
            }
        }

        // Bump per-model MRU for library-sourced picks.
        if (promptId != null) {
            val modelFilename = _loadedModel.value?.filename
            if (!modelFilename.isNullOrEmpty()) {
                viewModelScope.launch {
                    systemPromptRepository?.touchUsage(promptId, modelFilename)
                }
            }
        }

        // Recreate the native session so the prompt lands as message[0].
        val model = llamaModel ?: return
        val params = _generationParams.value ?: GenerationParams()
        viewModelScope.launch {
            generatingJob?.cancel()
            generatingJob?.join()
            generatingJob = null

            val prevSession = llamaSession

            withContext(Dispatchers.Default) {
                // Create-then-destroy: keep the old session alive as a
                // fallback if creation throws (defense-in-depth on top
                // of the validateReplaySize pre-check).
                val newSession = try {
                    createSessionWithParams(model, params, text)
                } catch (e: PayloadTooLargeException) {
                    _userError.postValue(
                        app.getString(
                            com.druk.lmplayground.R.string.system_prompt_too_large,
                            text.length * 2 / 1024,
                            InferenceLimits.MAX_PAYLOAD_BYTES / 1024,
                        )
                    )
                    null
                } ?: return@withContext
                swapInSessionWithReplay(newSession, prevSession, messages)
            }
        }
    }

    @MainThread
    fun clearSystemPrompt() = applySystemPrompt(null, "")

    /**
     * Overwrite the text of the library entry currently backing this session
     * (if any) and apply the new text to the session. Used by the
     * Generation Params "Update prompt" button.
     */
    @MainThread
    fun updateLinkedSystemPrompt(text: String) {
        val trimmed = text.trim()
        val id = _systemPromptId.value
        val repo = systemPromptRepository
        if (id == null || repo == null) {
            applySystemPrompt(null, trimmed)
            return
        }
        viewModelScope.launch {
            val existing = repo.getById(id) ?: return@launch
            repo.update(existing.copy(text = trimmed))
            applySystemPrompt(id, trimmed)
        }
    }

    /**
     * Persist a brand-new system prompt to the library and apply it to the
     * current session.
     */
    @MainThread
    fun createAndApplySystemPrompt(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val repo = systemPromptRepository ?: run {
            applySystemPrompt(null, trimmed)
            return
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val entity = SystemPromptEntity(
                id = UUID.randomUUID().toString(),
                text = trimmed,
                createdAt = now,
                updatedAt = now
            )
            repo.insert(entity)
            applySystemPrompt(entity.id, entity.text)
        }
    }

    @MainThread
    fun renameSession(sessionId: String, newTitle: String) {
        viewModelScope.launch {
            chatRepository?.updateSessionTitle(sessionId, newTitle)
        }
    }

    @MainThread
    fun pinSession(sessionId: String, pinned: Boolean) {
        viewModelScope.launch {
            chatRepository?.updateSessionPinned(sessionId, pinned)
        }
    }

    @MainThread
    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            chatRepository?.deleteSession(sessionId)
            if (_currentSessionId.value == sessionId) {
                _currentSessionId.value = null
                uiState.resetMessages()
            }
        }
    }

    // --- Folders ---

    @MainThread
    fun createFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            chatRepository?.insertFolder(
                FolderEntity(
                    id = UUID.randomUUID().toString(),
                    name = trimmed,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    @MainThread
    fun renameFolder(folderId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            chatRepository?.updateFolderName(folderId, trimmed)
        }
    }

    /**
     * Delete a folder and every chat inside it. If the currently open
     * conversation lives in that folder, clear it so the UI doesn't keep
     * showing a deleted chat.
     */
    @MainThread
    fun deleteFolder(folderId: String) {
        viewModelScope.launch {
            val affected = sessions.value
                ?.filter { it.folderId == folderId }
                ?.map { it.id }
                .orEmpty()
            chatRepository?.deleteFolderAndChats(folderId)
            if (_currentSessionId.value in affected) {
                _currentSessionId.value = null
                uiState.resetMessages()
            }
        }
    }

    @MainThread
    fun moveSessionToFolder(sessionId: String, folderId: String?) {
        viewModelScope.launch {
            chatRepository?.updateSessionFolder(sessionId, folderId)
        }
    }

    @MainThread
    fun cancelGeneration() {
        generatingJob?.cancel()
    }

    fun dismissSessionModelHint() {
        _sessionModelHint.value = null
    }

    @MainThread
    fun loadModelByFilename(filename: String) {
        _sessionModelHint.value = null
        val modelInfo = ModelInfoProvider.getByFilename(filename)
            ?: ModelInfoProvider.createCustomModelInfo(filename, filename.removeSuffix(".gguf"), 0)
        loadModel(modelInfo)
    }

    fun getReport(): String? {
        // Invoked synchronously on the main thread from the token-count tap.
        // Both proxy calls go over AIDL and throw InferenceUnavailableException
        // if the :llama service crashed or hasn't bound — there's nothing to
        // report in that case, so swallow it instead of crashing the app
        // (seen on Google Play as withService / requireConnected IUE).
        return try {
            val modelReport = llamaModel?.getModelReport() ?: return null
            val sessionReport = llamaSession?.getReport() ?: return null
            modelReport + "\n" + sessionReport
        } catch (e: InferenceUnavailableException) {
            android.util.Log.w("ConversationViewModel", "getReport failed: service unavailable", e)
            null
        }
    }

    fun unloadModel() {
        viewModelScope.launch {
            // Tear down native handles only when something is actually
            // loaded — but always clear the user-visible LiveData state
            // below. The failed-load case (e.g. RAM gate refused) leaves
            // _loadedModel + _loadedModelStatus set with null native
            // handles; without this, tapping Unload was a no-op for that
            // path.
            if (modelFileHandle != null || llamaModel != null) {
                generatingJob?.cancel()
                generatingJob?.join()
                generatingJob = null

                // Capture and null references on main thread to prevent races
                val prevSession = llamaSession
                val prevModel = llamaModel
                val prevHandle = modelFileHandle
                llamaSession = null
                llamaModel = null
                modelFileHandle = null

                withContext(Dispatchers.Default) {
                    prevSession?.destroy()
                    prevModel?.unloadModel()
                }

                prevHandle?.close()
            }

            _loadedModel.postValue(null)
            _loadedModelStatus.postValue(null)
            _isModelReady.postValue(false)
            _supportsThinking.postValue(false)
            _supportsToolCalling.postValue(false)
            _toolEnabledStates.postValue(emptyMap())
            _contextUsedTokens.postValue(0)
        }
    }

    /**
     * Toggle a tool for the currently loaded model. This records a per-model
     * override (which takes precedence over the global default set in
     * Settings → Tools) so changing a tool here only affects this model.
     */
    @MainThread
    fun setToolEnabled(toolName: String, enabled: Boolean) {
        toolRegistry.setToolEnabled(toolName, enabled)
        _loadedModel.value?.filename?.let { filename ->
            storagePreferences.setToolOverride(filename, toolName, enabled)
        }
        val states = _toolEnabledStates.value.orEmpty().toMutableMap()
        states[toolName] = enabled
        _toolEnabledStates.value = states
    }

    /**
     * Set up the persistent preamble (system prompt + tools) KV cache for
     * [session]. Path is `<filesDir>/kv_preamble/<fingerprint>` where
     * fingerprint is SHA-1 over (model filename, system prompt, tools
     * JSON). Cache files are shared across sessions: any new conversation
     * with the same model / sys prompt / tool set re-uses the same disk
     * cache. LRU prune keeps disk footprint bounded ([KV_PREAMBLE_KEEP]
     * most-recent files).
     */
    private fun applyPreambleCache(
        session: GenerationBackend,
        toolsJson: String,
    ) {
        try {
            val modelInfo = _loadedModel.value
            val modelName = modelInfo?.filename
            if (modelName.isNullOrEmpty()) {
                // Model info isn't ready (shouldn't happen here but be safe).
                session.setPreambleCachePath("", "")
                return
            }
            // Include the loaded model's byte size in the fingerprint so a
            // replaced-but-same-named model file invalidates stale caches.
            // Filename alone wouldn't catch re-quantization or upgrades
            // where the filename was kept; the byte size differs in
            // virtually all real cases. getModelSize() is a cheap AIDL
            // call backed by an in-memory llama_model field.
            val modelSize = try { llamaModel?.getModelSize() ?: 0L } catch (_: Throwable) { 0L }
            val modelKey = "$modelName:$modelSize"
            // The exact effective prompt the session was created with (may carry
            // a date/time prefix), so the cache key tracks it and never reuses a
            // preamble built for a different day's date line.
            val systemPrompt = currentEffectiveSystemPrompt
            val fingerprint = sha1Hex(
                "$modelKey $systemPrompt $toolsJson"
            )
            val dir = kvPreambleDir().apply { mkdirs() }
            val path = java.io.File(dir, fingerprint).absolutePath
            session.setPreambleCachePath(path, fingerprint)
            pruneOldKvPreambles(KV_PREAMBLE_KEEP)
        } catch (t: Throwable) {
            android.util.Log.w(
                "ConversationViewModel",
                "applyPreambleCache failed (continuing without cache)", t
            )
            try { session.setPreambleCachePath("", "") } catch (_: Throwable) {}
        }
    }

    private fun kvPreambleDir(): java.io.File =
        java.io.File(app.filesDir, "kv_preamble")

    private fun pruneOldKvPreambles(keep: Int) {
        try {
            val dir = kvPreambleDir()
            val bins = dir.listFiles()?.filter { it.name.endsWith(".bin") } ?: return
            if (bins.size <= keep) return
            val ordered = bins.sortedByDescending { it.lastModified() }
            for (i in keep until ordered.size) {
                val bin = ordered[i]
                bin.delete()
                java.io.File(bin.absolutePath.removeSuffix(".bin") + ".json").delete()
            }
        } catch (t: Throwable) {
            android.util.Log.w("ConversationViewModel", "pruneOldKvPreambles failed", t)
        }
    }

    private fun sha1Hex(input: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    private fun buildToolCallInfoList(
        toolCallsJson: String,
        toolResultsJson: String,
        totalDurationMs: Long
    ): List<ToolCallInfo> {
        val calls = JSONArray(toolCallsJson)
        val results = JSONArray(toolResultsJson)
        val resultMap = mutableMapOf<String, String>()
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            resultMap[r.getString("id")] = r.getString("content")
        }
        val count = calls.length().coerceAtLeast(1)
        val perCallMs = totalDurationMs / count
        return (0 until calls.length()).map { i ->
            val call = calls.getJSONObject(i)
            val id = call.getString("id")
            ToolCallInfo(
                name = call.getString("name"),
                arguments = call.getString("arguments"),
                result = resultMap[id] ?: "",
                durationMs = perCallMs
            )
        }
    }

    fun resetModelList() {
        _models.postValue(emptyList())
    }

    data class RamWarning(
        val modelInfo: ModelInfo,
        val neededRam: String,
        val totalRam: String,
    )

    private companion object {
        // Number of preamble cache files to retain (LRU by mtime). Each
        // file is small relative to the model itself but scales with
        // (system_prompt + tools_description) token count — typically a
        // few KB to a few hundred KB. 8 covers "user has 8 different
        // model + tool-set combinations they use regularly" without
        // bloating /data.
        private const val KV_PREAMBLE_KEEP = 8

        private val HEX = "0123456789abcdef".toCharArray()
    }
}
