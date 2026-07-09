package com.druk.lmplayground.conversation

import android.app.Application
import android.net.Uri
import android.text.format.Formatter
import androidx.annotation.MainThread
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
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
import com.druk.lmplayground.benchmark.BenchmarkConfig
import com.druk.lmplayground.benchmark.BenchmarkHardware
import com.druk.lmplayground.benchmark.BenchmarkRunner
import com.druk.lmplayground.benchmark.BenchmarkUiState
import com.druk.lmplayground.data.BenchmarkResultEntity
import com.druk.lmplayground.data.ChatMessageEntity
import com.druk.lmplayground.data.ChatRepository
import com.druk.lmplayground.data.ChatSessionEntity
import com.druk.lmplayground.data.FolderEntity
import com.druk.lmplayground.data.ConversationMetadata
import com.druk.lmplayground.data.MemoryNoteEntity
import com.druk.lmplayground.data.MemoryRepository
import com.druk.lmplayground.data.SystemPromptEntity
import com.druk.lmplayground.data.SystemPromptRepository
import com.druk.lmplayground.models.DeviceCapability
import com.druk.lmplayground.models.MmprojPairing
import com.druk.lmplayground.models.ModelInfo
import com.druk.lmplayground.models.ModelInfoProvider
import com.druk.lmplayground.models.ModelWithStatus
import com.druk.lmplayground.models.resolveCapabilities
import com.druk.lmplayground.files.Attachment
import com.druk.lmplayground.files.AttachmentKind
import com.druk.lmplayground.files.FileExtractionResult
import com.druk.lmplayground.files.FileTextExtractor
import com.druk.lmplayground.files.ImageTranscoder
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
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
    // The active compute backend of the loaded local model — e.g.
    // "GPU (OpenCL): QUALCOMM Adreno(TM) 830 (29/29 layers)" or "CPU". Lets the
    // user verify whether the experimental GPU toggle actually runs on the GPU.
    // Null for remote models. Read from the native model report at load time.
    private val _computeBackend = MutableLiveData<String?>(null)

    // Captured at load time and reused as the foreground-notification
    // description across load -> generating -> ready transitions, so the
    // silent notification reflects what the model is currently doing
    // without ever re-posting noisily. The "loaded" state shows the size
    // line ("<name> - <size>"); generating/ready swap in a live token
    // count ("<name> · <N> tokens"), so the model name is kept too.
    private var notificationModelLine: String? = null
    private var notificationModelName: String? = null
    // Status line shown under the model name once loaded (the formatted size).
    // Cached so transient states ("Loading vision…") can restore it after.
    private var loadedModelDescription: String? = null
    // True once the current model's mmproj has been loaded into the :llama
    // process (lazy — happens on the FIRST image send, never at model load;
    // eager projector load froze text decode on-device, v1.9.28→30).
    @Volatile private var projectorLoaded = false
    // Reason the last projector load failed (native mtmd/clip message or the
    // copy failure), surfaced to the user so a rejected projector says WHY.
    @Volatile private var projectorError: String? = null
    // Vision info of sent images, keyed by the image's app-private localPath:
    // token weight (from the native image turn) + a downscaled "model view" copy
    // at the resolution the model actually received. The sent bubble shows the
    // token count and swaps in the model-view image.
    private val _imageTokenCounts = MutableLiveData<Map<String, SentImageInfo>>(emptyMap())
    private val _models = MutableLiveData<List<ModelWithStatus>>(emptyList())
    private val _supportsThinking = MutableLiveData(false)
    private val _thinkingEnabled = MutableLiveData(false)
    private val _generationParams = MutableLiveData(GenerationParams())
    private val _maxContextSize = MutableLiveData(4096)
    private val _sessionModelHint = MutableLiveData<Pair<String, String>?>(null) // (modelName, modelFilename)
    private val _supportsToolCalling = MutableLiveData(false)
    // True when the loaded local model has a vision projector (mmproj) attached.
    private val _supportsVision = MutableLiveData(false)
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
     * One-shot native diagnostic (memory + logcat tail) captured when a
     * projector load fails/crashes the :llama service. The UI shows it in a
     * copyable dialog so the on-device crash reason is visible without adb.
     */
    private val _projectorDiagnostic = MutableLiveData<String?>(null)
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

    // MTP (speculative decoding) for the LiteRT engine. A load-time flag, so
    // flipping it reloads the loaded .litertlm model. Exposed to the params
    // sheet as a Switch (shown only for LiteRT models).
    private val _liteRtMtpEnabled = MutableLiveData(storagePreferences.mtpEnabled)
    val liteRtMtpEnabled: LiveData<Boolean> = _liteRtMtpEnabled

    @MainThread
    fun setLiteRtMtpEnabled(enabled: Boolean) {
        storagePreferences.mtpEnabled = enabled
        _liteRtMtpEnabled.postValue(enabled)
        val m = _loadedModel.value
        if (m != null && m.filename.endsWith(".litertlm")) loadModel(m, forceLoad = true)
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
    val computeBackend: LiveData<String?> = _computeBackend

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
    val supportsVision: LiveData<Boolean> = _supportsVision
    val systemPrompt: LiveData<String> = _systemPrompt
    val systemPromptId: LiveData<String?> = _systemPromptId
    val userError: LiveData<String?> = _userError
    val imageTokenCounts: LiveData<Map<String, SentImageInfo>> = _imageTokenCounts
    val projectorDiagnostic: LiveData<String?> = _projectorDiagnostic
    val pendingRamWarning: LiveData<RamWarning?> = _pendingRamWarning
    val modelLoadError: LiveData<String?> = _modelLoadError

    /** Called by the UI after surfacing the error (e.g. as a Toast). */
    @MainThread
    fun consumeUserError() { _userError.value = null }

    /** Called by the UI after showing the projector-crash diagnostic dialog. */
    @MainThread
    fun consumeProjectorDiagnostic() { _projectorDiagnostic.value = null }

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
    // The folder the drawer is currently "inside" (null = root). Set by entering a
    // folder or opening a chat that lives in one; a NEW chat inherits it (see
    // ensureSession) and the empty-chat background shows its name.
    private val _currentFolderId = MutableLiveData<String?>(null)
    val currentFolderId: LiveData<String?> = _currentFolderId
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

    /**
     * Full saved system-prompt library (updatedAt DESC), shown in the model
     * settings sheet's Prompt tab so prompts can be picked / edited / deleted
     * / created inline. Applies to both local and remote models.
     */
    val savedPrompts: LiveData<List<SystemPromptEntity>> =
        systemPromptRepository?.getAll() ?: MutableLiveData(emptyList())

    // --- Memory v2 injection ---
    //
    // Saved memories are injected into the system prompt (opt-in, gated by
    // StoragePreferences.memoryEnabled). composeSystemPrompt runs on several
    // threads (main + background), and Room forbids main-thread reads, so we do
    // NOT hit the DB there. Instead we observe the notes LiveData (delivered
    // off-main by Room) and keep a pre-formatted block cached; rebuilding it is
    // pure string work, safe on the observer's main thread. Injection is a
    // session-build snapshot, so a note the model saves mid-chat lands on the
    // next session, matching the "view memory first" model.
    private val memoryRepository: MemoryRepository? = (app as? App)?.memoryRepository
    private val memoryNotesLive: LiveData<List<MemoryNoteEntity>>? = memoryRepository?.getAllLive()

    @Volatile
    private var memoryPromptBlock: String = ""

    private val memoryObserver = Observer<List<MemoryNoteEntity>> { notes ->
        memoryPromptBlock = buildMemoryBlock(notes)
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
        sweepOrphanAttachments()
        memoryNotesLive?.observeForever(memoryObserver)
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
        memoryNotesLive?.removeObserver(memoryObserver)
        val job = generatingJob
        val bench = benchmarkJob
        val session = llamaSession
        val model = llamaModel
        val handle = modelFileHandle
        generatingJob = null
        benchmarkJob = null
        llamaSession = null
        llamaModel = null
        modelFileHandle = null

        CoroutineScope(Dispatchers.Default).launch {
            job?.cancel()
            job?.join()
            // A benchmark run holds the SAME model handle; wait for it to stop
            // before unloading the model, or teardown races an in-flight session.
            bench?.cancel()
            try { bench?.join() } catch (_: Throwable) {}
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
                // Projector (mmproj) companions aren't models — collect their
                // names for pairing, exclude them from the selectable list.
                val mmprojNames = modelFiles.map { it.name }.filter { MmprojPairing.isMmproj(it) }
                val customModels = modelFiles
                    .filter { it.name !in ModelInfoProvider.knownFilenames && !MmprojPairing.isMmproj(it.name) }
                    .mapNotNull { file ->
                        val cached = storagePreferences.getCustomModelMetadata(file.name)
                            ?: return@mapNotNull null
                        if (!cached.second) return@mapNotNull null
                        val mmproj = MmprojPairing.findMmprojFor(file.name, mmprojNames)
                        ModelInfoProvider.createCustomModelInfo(file.name, cached.first, file.sizeBytes, mmproj)
                    }
                // Sideloaded LiteRT-LM models (Gemma 4 E2B/E4B) live in a
                // separate folder and aren't tracked by the GGUF catalog, so
                // synthesize selectable entries for them. Their `.litertlm`
                // filename drives the router in [loadModel].
                val liteRtModels = (
                    File(app.getExternalFilesDir(null), "litert")
                        .listFiles { f -> f.name.endsWith(".litertlm") } ?: emptyArray()
                    ).map { f ->
                        ModelWithStatus(
                            model = ModelInfo(
                                name = "Gemma 4 " + shortLiteRtName(f.name) + " (LiteRT)",
                                filename = f.name,
                                remoteUri = null,
                                description = "Google · LiteRT-LM · MTP",
                                logoRes = ModelInfoProvider.logoForModelId("gemma"),
                            ),
                            isDownloaded = true,
                        )
                    }
                _models.postValue(
                    ModelInfoProvider.getModelsWithStatus(downloadedFilenames, customModels, mmprojNames)
                        .map { it.copy(model = it.model.resolveCapabilities(storagePreferences)) } + liteRtModels
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
            // Remote vision: the image is sent to the server as a base64 image_url
            // part (no on-device projector). Ollama reports `capabilities` via
            // /api/show, so gate on "vision" there; LM Studio doesn't report them
            // at all, so an empty list is "unknown" -> allow attaching and let the
            // server reject a non-vision model, rather than hide the option for
            // every LM Studio model.
            val caps = details?.capabilities ?: emptyList()
            val remoteVision = caps.isEmpty() || caps.any { it.equals("vision", ignoreCase = true) }
            _supportsVision.postValue(remoteVision)
            // Compute backend is a local (on-device) concept; the server owns its own.
            _computeBackend.postValue(null)
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
                params.thinkingBudget, effectiveSystemPrompt, params.kvCacheType
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
     * Load a local LiteRT-LM model (Gemma 4 `.litertlm`) as the active engine.
     * Mirrors [loadRemoteModel]'s in-process adapter flow: tears down any loaded
     * model/session, builds a [com.druk.lmplayground.litert.LiteRtEngine] +
     * [com.druk.lmplayground.litert.LiteRtModel] session behind the shared
     * GenerationModel/GenerationBackend abstraction, then replays on-screen
     * history. Routed here from [loadModel] by the `.litertlm` extension.
     */
    private fun loadLiteRtModel(modelInfo: ModelInfo) {
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

            // GPU vs CPU backend (opt-in, default off). MTP (speculative
            // decoding) is an independent user toggle so it can run on either
            // backend; it is applied at engine-load time.
            val useGpu = storagePreferences.gpuAccelerationEnabled
            val useMtp = storagePreferences.mtpEnabled
            // E2B/E4B are the multimodal bundles (they carry the vision encoder); a
            // text-only .litertlm stays vision-off. Vision is enabled at engine load.
            val liteRtVision = modelInfo.filename.contains("E2B") ||
                               modelInfo.filename.contains("E4B")

            _loadedModel.postValue(modelInfo)
            _thinkingEnabled.postValue(false)
            // Gemma 4 (LiteRT) streams reasoning on its "thought" channel (toggle
            // default off) and supports native function calling (manual tool loop).
            _supportsThinking.postValue(true)
            _supportsToolCalling.postValue(true)
            _supportsVision.postValue(liteRtVision)
            // Compute backend is surfaced only for the llama path; keep it null.
            _computeBackend.postValue(null)
            _toolEnabledStates.postValue(emptyMap())

            // Gemma 4 E2B/E4B support up to a 32k context. Load the user's saved
            // params (context/temp/topK persist per model); a fresh model defaults
            // to 8192. LiteRT's context (maxNumTokens) is fixed at engine load, so
            // the context slider triggers a full reload (see updateGenerationParams).
            // Bigger context = more KV-cache RAM (LiteRT can't quantize the KV), so
            // the slider lets the user pick the context/RAM trade-off directly.
            val liteRtMaxCtx = 32768
            val savedMap = storagePreferences.getModelGenerationParams(modelInfo.filename)
            val params = if (savedMap != null) GenerationParams.fromMap(savedMap)
                         else GenerationParams(contextSize = 8192)
            val ctx = params.contextSize.coerceIn(512, liteRtMaxCtx)
            _generationParams.postValue(params)
            _systemPrompt.postValue("")
            _systemPromptId.postValue(null)
            _maxContextSize.postValue(liteRtMaxCtx)
            _contextUsedTokens.postValue(0)
            _sessionModelHint.postValue(null)

            val path = File(
                app.getExternalFilesDir(null), "litert/" + modelInfo.filename
            ).absolutePath

            // Memory guard. A .litertlm model is allocated in full at load; the
            // heavy config (E4B ~3.66GB + GPU + speculative decoding) can exceed the
            // free RAM on a busy device, and the system's Low Memory Killer then
            // SIGKILLs the app (a native death we can't try/catch). Estimate the peak
            // footprint (weights + runtime/KV + GPU buffers + MTP drafter) and refuse
            // up front with a clear message instead of letting the app be killed.
            val am = app.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            val modelBytes = File(path).length()
            val baseOverhead = when {
                useGpu && useMtp -> 1_500_000_000L
                useGpu || useMtp -> 1_100_000_000L
                else -> 800_000_000L
            }
            // Vision loads the encoder EAGERLY at Engine.initialize() (~500MB resident).
            // Enable it only if it fits on top of the base footprint; otherwise load the
            // model text-only so a large model (E4B) still works for chat instead of
            // being refused/OOM-killed just for the (unused) encoder.
            val enableVision = liteRtVision &&
                (modelBytes <= 0 || memInfo.availMem >= modelBytes + baseOverhead + 500_000_000L)
            if (liteRtVision && !enableVision) {
                _supportsVision.postValue(false) // capable, but not enough free RAM for the encoder
            }
            val overhead = baseOverhead + (if (enableVision) 500_000_000L else 0L)
            if (modelBytes > 0 && memInfo.availMem < modelBytes + overhead) {
                android.util.Log.w(
                    "ConversationViewModel",
                    "LiteRT load refused: availMem=${memInfo.availMem} < model=$modelBytes + overhead=$overhead (gpu=$useGpu mtp=$useMtp vision=$enableVision)"
                )
                _loadedModelStatus.postValue(
                    app.getString(
                        com.druk.lmplayground.R.string.litert_low_memory,
                        modelInfo.name
                    )
                )
                _isModelReady.postValue(false)
                _loadedModel.postValue(null)
                return@launch
            }

            // No real % is available during the ~10s engine.load, so animate a
            // logarithmic estimate behind the loading hairline (mirrors the
            // local/remote fallback).
            _loadedModelStatus.postValue(
                app.getString(com.druk.lmplayground.R.string.remote_loading)
            )
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

            // engine.load() blocks ~10s and touches native runtime; keep it off
            // the main thread.
            val loaded = withContext(Dispatchers.Default) {
                try {
                    val engine = com.druk.lmplayground.litert.LiteRtEngine()
                    engine.load(
                        path, app.cacheDir.path, useGpu, useMtp = useMtp,
                        maxNumTokens = ctx, enableVision = enableVision,
                        // Image-detail cap: snap the mtmd-era slider to a Gemma-4-valid
                        // visual-token budget (set on the experimental flag inside load).
                        visualTokenBudget =
                            if (enableVision) nearestGemma4Budget(params.imageMaxTokens) else null,
                    )
                    val effectiveSystemPrompt = composeSystemPrompt("")
                    currentEffectiveSystemPrompt = effectiveSystemPrompt
                    val model = com.druk.lmplayground.litert.LiteRtModel(engine, liteRtMaxCtx)
                    val session = model.createSession(
                        params.contextSize, params.temperature, params.topP,
                        params.repetitionPenalty, params.topK, params.minP, params.seed,
                        params.thinkingBudget, effectiveSystemPrompt, params.kvCacheType
                    )
                    llamaModel = model
                    llamaSession = session
                    true
                } catch (t: Throwable) {
                    android.util.Log.e("ConversationViewModel", "LiteRT load failed", t)
                    false
                }
            }

            progressJob.cancel()
            _modelLoadingProgress.postValue(0f)

            if (!loaded) {
                _loadedModelStatus.postValue(
                    app.getString(com.druk.lmplayground.R.string.inference_engine_crashed)
                )
                _isModelReady.postValue(false)
                return@launch
            }

            val session = llamaSession
            val messages = uiState.messages.toList()
            if (session != null && messages.isNotEmpty()) {
                try { replayHistoryToSession(session, messages) } catch (_: Throwable) {}
            }

            _loadedModelStatus.postValue(shortLiteRtName(modelInfo.filename) + " · LiteRT")
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
        // LiteRT-LM models route to the separate on-device engine (Gemma 4
        // .litertlm). It launches its own coroutine, so return immediately.
        if (modelInfo.filename.endsWith(".litertlm")) {
            loadLiteRtModel(modelInfo)
            return
        }
        val llamaCpp = llamaCpp ?: return

        viewModelScope.launch {
          modelLifecycleMutex.withLock {
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
                _supportsVision.postValue(false)
                _computeBackend.postValue(null)
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
                        // Experimental GPU acceleration (opt-in, default off):
                        // offload all LLM layers to Vulkan + run vision on GPU.
                        // 0 = CPU (safe default, LLM pinned to CPU devices).
                        gpuLayers = if (storagePreferences.gpuAccelerationEnabled) 999 else 0,
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
                    _maxContextSize.postValue(minOf(nCtxTrain, 32768))
                    // Surface the active compute backend (GPU-OpenCL vs CPU) so
                    // the user can verify the GPU toggle really took effect.
                    _computeBackend.postValue(
                        try {
                            llamaModel.getModelReport()
                                .lineSequence()
                                .firstOrNull { it.startsWith("Compute:") }
                                ?.substringAfter("Compute:")
                                ?.trim()
                        } catch (_: Throwable) { null }
                    )
                    // Load saved per-model params, or use defaults. For a model the
                    // user hasn't configured yet, default the context window to the
                    // RAM-safe maximum (capped natively at the model's trained
                    // context) so long documents fit instead of being truncated.
                    val savedMap = storagePreferences.getModelGenerationParams(modelInfo.filename)
                    val params = if (savedMap != null) {
                        GenerationParams.fromMap(savedMap)
                    } else {
                        val defaults = GenerationParams()
                        val gpuOn = storagePreferences.gpuAccelerationEnabled
                        val recommended = try {
                            llamaModel.getRecommendedContextSize(
                                deviceRamBytes(),
                                kvBytesPerElemX16(defaults.kvCacheType, gpuOn),
                            )
                        } catch (_: Throwable) { 0 }
                        if (recommended >= 2048) defaults.copy(contextSize = recommended) else defaults
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
                    // Vision: the attach UI lights up from the mmproj pairing
                    // alone — the CLIP projector itself loads LAZILY on the
                    // first image send (ensureVisionReady), NEVER here. The
                    // v1.9.28→30 on-device A/B proved that merely loading the
                    // projector at model-load froze the subsequent text decode
                    // (mechanism OS-level, not reproducible statically), so
                    // text-only chat must stay structurally projector-free.
                    projectorLoaded = false
                    _supportsVision.postValue(modelInfo.mmprojFilename != null)
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
                    loadedModelDescription = modelDescription
                    _loadedModelStatus.postValue(modelDescription)
                    _sessionModelHint.postValue(null)
                    // Seed the ring with the fixed preamble (system prompt + enabled
                    // tools) so it reflects the tool overhead before the first turn.
                    // Pass the freshly-detected capability directly: _supportsToolCalling
                    // was just postValue'd above and its .value is not updated yet.
                    _contextUsedTokens.postValue(preambleTokenEstimate(toolCallingSupported))

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
        val parts = mutableListOf<String>()
        if (storagePreferences.includeDateTimeInPrompt) {
            val now = java.text.DateFormat.getDateInstance(
                java.text.DateFormat.FULL,
                java.util.Locale.getDefault()
            ).format(java.util.Date())
            parts.add(app.getString(com.druk.lmplayground.R.string.system_prompt_datetime, now))
        }
        // Opt-in memory injection (StoragePreferences.memoryEnabled). The block
        // is the pre-formatted, sanitized cache maintained by [memoryObserver];
        // reading it here is a plain field access, safe on any thread.
        if (storagePreferences.memoryEnabled) {
            val block = memoryPromptBlock
            if (block.isNotEmpty()) parts.add(block)
        }
        if (userPrompt.isNotBlank()) parts.add(userPrompt)
        return parts.joinToString("\n\n")
    }

    /**
     * Renders the enabled saved notes into a delimited `<user_memory>` block,
     * bounded by note count and total characters so a large memory can't blow
     * the context. Each note is sanitized (control chars stripped, our own
     * delimiters removed) to keep free-text notes from injecting prompt
     * structure. Returns "" when there is nothing to inject.
     */
    private fun buildMemoryBlock(notes: List<MemoryNoteEntity>): String {
        if (notes.isEmpty()) return ""
        val body = StringBuilder()
        var budget = MEMORY_INJECT_CHAR_BUDGET
        var included = 0
        for (note in notes) {
            if (included >= MEMORY_INJECT_MAX_NOTES) break
            val clean = sanitizeMemory(note.content)
            if (clean.isEmpty()) continue
            val cat = note.category?.let { sanitizeMemory(it) }?.takeIf { it.isNotEmpty() }
            val line = if (cat != null) "- $clean ($cat)" else "- $clean"
            if (line.length > budget && included > 0) break
            body.append(line).append('\n')
            budget -= line.length
            included++
        }
        if (included == 0) return ""
        return buildString {
            append("<user_memory>\n")
            append("Saved notes about the user, to use when relevant:\n")
            append(body)
            append("</user_memory>")
        }
    }

    private fun sanitizeMemory(s: String): String =
        s.replace(Regex("[\\u0000-\\u001F]"), " ")     // control chars -> space
            .replace(Regex("(?i)</?user_memory>"), "")  // strip our own delimiter
            .replace(Regex(" {2,}"), " ")
            .trim()

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
                effective,
                params.kvCacheType
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

        // The image-detail (max tokens) preference is baked into the projector at
        // load time (mtmd image_max_tokens), so a change needs a projector reload.
        // Force it on the next image send; ensureProjectorLoaded re-reads the value.
        if (oldParams.imageMaxTokens != params.imageMaxTokens) {
            projectorLoaded = false
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
            // LiteRT's context (maxNumTokens) is baked in at engine load, so a
            // context change needs a full engine RELOAD (which re-reads the saved
            // params we just persisted), not just a session rebuild.
            val loaded = _loadedModel.value
            if (loaded != null && loaded.filename.endsWith(".litertlm")) {
                _currentSessionId.value = null
                uiState.resetMessages()
                _contextUsedTokens.postValue(0)
                loadModel(loaded, forceLoad = true)
                return
            }
            val model = llamaModel ?: return
            viewModelScope.launch {
                generatingJob?.cancel()
                generatingJob?.join()
                generatingJob = null

                val prevSession = llamaSession

                _currentSessionId.value = null
                uiState.resetMessages()
                _contextUsedTokens.postValue(preambleTokenEstimate())

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
            // PDFs get an app-private copy so their pages can be re-rendered later
            // (the visual preview), even after the SAF read grant is gone.
            val isPdf = mimeType == "application/pdf" ||
                filename.substringAfterLast('.', "").equals("pdf", ignoreCase = true)
            val localPath = if (isPdf) copyToAttachments(uri) else null
            // The chip may have been removed / the message sent while we copied or
            // extracted; if the item is gone, discard the copy instead of leaking it.
            if (localPath != null && _stagedAttachments.value.orEmpty().none { it.id == id }) {
                deleteAttachmentFile(localPath)
                return@launch
            }
            val state = when (val r = FileTextExtractor.extract(app, uri, filename, mimeType)) {
                is FileExtractionResult.Success ->
                    StagedState.Ready(r.text, r.charCount, r.truncated, r.rawText, localPath)
                is FileExtractionResult.Empty ->
                    // A scanned / image-only PDF has no text but can still preview visually.
                    if (localPath != null) StagedState.Ready("", 0, false, null, localPath)
                    else StagedState.Error(app.getString(com.druk.lmplayground.R.string.attachment_empty, filename))
                is FileExtractionResult.Unsupported -> {
                    deleteAttachmentFile(localPath)
                    StagedState.Error(app.getString(com.druk.lmplayground.R.string.attachment_unsupported, filename))
                }
                is FileExtractionResult.Failure -> {
                    deleteAttachmentFile(localPath)
                    StagedState.Error(app.getString(com.druk.lmplayground.R.string.attachment_failed, filename))
                }
            }
            if (_stagedAttachments.value.orEmpty().none { it.id == id }) {
                deleteAttachmentFile(localPath)
                return@launch
            }
            _stagedAttachments.value = _stagedAttachments.value.orEmpty()
                .map { if (it.id == id) it.copy(state = state) else it }
        }
    }

    /**
     * Pick-time: stage a gallery image for the vision pipeline. The image is
     * transcoded off-main to a bounded JPEG (see [ImageTranscoder]) and copied
     * into app-private storage; no text extraction. One image per message: a
     * newly picked image replaces any image already staged.
     */
    @MainThread
    fun stageImageAttachment(uri: Uri, filename: String?) {
        // v1: a message carries at most one image (the native turn evaluates
        // exactly one bitmap) — picking again swaps the staged image.
        _stagedAttachments.value.orEmpty()
            .filter { it.kind == AttachmentKind.IMAGE }
            .forEach { removeStagedAttachment(it.id) }
        val id = ++stagedIdCounter
        val name = filename?.takeIf { it.isNotBlank() }
            ?: app.getString(com.druk.lmplayground.R.string.attach_image)
        _stagedAttachments.value = _stagedAttachments.value.orEmpty() +
            StagedAttachment(id, uri, name, "image/jpeg", AttachmentKind.IMAGE)
        viewModelScope.launch {
            val localPath = withContext(Dispatchers.IO) {
                val bytes = ImageTranscoder.transcode(app, uri) ?: return@withContext null
                val out = File(attachmentsDir.apply { mkdirs() }, UUID.randomUUID().toString() + ".jpg")
                try {
                    out.writeBytes(bytes)
                    out.absolutePath
                } catch (t: Throwable) {
                    out.delete()
                    null
                }
            }
            // Removed while transcoding? Discard the copy instead of leaking it.
            if (_stagedAttachments.value.orEmpty().none { it.id == id }) {
                deleteAttachmentFile(localPath)
                return@launch
            }
            val state = if (localPath != null) {
                StagedState.Ready("", 0, false, null, localPath)
            } else {
                StagedState.Error(app.getString(com.druk.lmplayground.R.string.image_attach_failed))
            }
            _stagedAttachments.value = _stagedAttachments.value.orEmpty()
                .map { if (it.id == id) it.copy(state = state) else it }
        }
    }

    @MainThread
    fun removeStagedAttachment(id: Long) {
        val current = _stagedAttachments.value.orEmpty()
        // Drop the file copy of a removed (never-sent) PDF so it doesn't leak.
        (current.firstOrNull { it.id == id }?.state as? StagedState.Ready)?.localPath
            ?.let { deleteAttachmentFile(it) }
        _stagedAttachments.value = current.filter { it.id != id }
    }

    @MainThread
    fun clearStagedAttachments() {
        _stagedAttachments.value.orEmpty().forEach { sa ->
            (sa.state as? StagedState.Ready)?.localPath?.let { deleteAttachmentFile(it) }
        }
        _stagedAttachments.value = emptyList()
    }

    private val attachmentsDir: File get() = File(app.filesDir, "attachments")

    /** Copy a picked file into app-private storage; returns the path, or null on failure/too-large. */
    private suspend fun copyToAttachments(uri: Uri): String? = withContext(Dispatchers.IO) {
        val maxBytes = 50L * 1024 * 1024
        val out = File(attachmentsDir.apply { mkdirs() }, UUID.randomUUID().toString() + ".pdf")
        try {
            var tooBig = false
            app.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) { tooBig = true; break }
                        os.write(buf, 0, n)
                    }
                }
            } ?: return@withContext null
            if (tooBig) { out.delete(); return@withContext null }
            out.absolutePath
        } catch (t: Throwable) {
            out.delete()
            null
        }
    }

    private fun deleteAttachmentFile(path: String?) {
        if (path == null) return
        runCatching { File(path).delete() }
    }

    /**
     * Delete app-private attachment copies that no persisted message references.
     * Safe at startup: staged attachments live only in memory, so at process start
     * every file not named by a saved message is a leftover (removed-before-send or
     * a deleted chat).
     */
    private fun sweepOrphanAttachments() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val files = attachmentsDir.listFiles() ?: return@launch
                if (files.isEmpty()) return@launch
                val referenced = HashSet<String>()
                chatRepository?.getAllAttachmentsJson()?.forEach { json ->
                    runCatching {
                        val arr = JSONArray(json)
                        for (i in 0 until arr.length()) {
                            arr.getJSONObject(i).optString("path")
                                .takeIf { it.isNotEmpty() }?.let { referenced.add(it) }
                        }
                    }
                }
                files.forEach { f -> if (f.absolutePath !in referenced) f.delete() }
            } catch (_: Throwable) {
            }
        }
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
        val hasReadyImage = _stagedAttachments.value.orEmpty()
            .any { it.kind == AttachmentKind.IMAGE && it.state is StagedState.Ready }
        // Lazy vision: the CLIP projector loads only now, at the first image
        // send — never at model load (eager load froze text decode on-device,
        // v1.9.28→30). On failure the chips stay staged and nothing is sent,
        // matching the vision_load_failed copy. Remote models have no projector
        // (the image is base64'd straight into the request), so skip this.
        if (hasReadyImage && _supportsVision.value == true && !projectorLoaded && !isRemoteModel && !isLiteRtModel) {
            viewModelScope.launch {
                if (!ensureProjectorLoaded()) {
                    _userError.postValue(visionLoadFailedMessage())
                    return@launch
                }
                if (_isGenerating.value != true) dispatchUserMessage(content)
            }
            return
        }
        dispatchUserMessage(content)
    }

    @MainThread
    private fun dispatchUserMessage(content: String) {
        // A fresh user turn is not a regeneration: drop any pending variant list
        // so this reply doesn't inherit variants from an earlier regenerate.
        pendingRegenVariants = null
        val staged = _stagedAttachments.value.orEmpty()
        _stagedAttachments.value = emptyList()
        var ready = staged.mapNotNull { s -> (s.state as? StagedState.Ready)?.let { s to it } }
        // An image staged for a model that can't see (switched models after
        // picking) can't be sent — drop it with a heads-up, keep documents.
        if (_supportsVision.value != true &&
            ready.any { (s, _) -> s.kind == AttachmentKind.IMAGE }
        ) {
            ready.filter { (s, _) -> s.kind == AttachmentKind.IMAGE }
                .forEach { (_, r) -> deleteAttachmentFile(r.localPath) }
            ready = ready.filter { (s, _) -> s.kind != AttachmentKind.IMAGE }
            _userError.postValue(app.getString(com.druk.lmplayground.R.string.image_attach_failed))
        }
        val attachments = if (ready.isEmpty()) emptyList()
            else buildAttachments(ready, content)
        // Heads-up when this turn (typically a large attached document) is bigger
        // than the model's context window: the engine trims/truncates it to fit.
        val ctxSize = _generationParams.value?.contextSize ?: 0
        if (ctxSize > 0 &&
            estimateTokens(buildModelPrompt(Message("User", content, attachments = attachments))) > ctxSize
        ) {
            _userError.postValue(app.getString(com.druk.lmplayground.R.string.context_overflow_warning))
        }
        // If the user paged back to an older regenerated variant (the shown one
        // isn't the newest), the live session's KV cache / remote history still
        // holds the newest answer. Rebuild the context to the SELECTED variant by
        // replaying the current messages before appending this turn, so the chat
        // continues from what's on screen. The common case (no variants, or the
        // newest shown) falls through to the plain append path, unchanged.
        val lastAssistant = uiState.messages.lastOrNull { it.author == "Assistant" }
        val continueFromOldVariant = lastAssistant != null &&
            lastAssistant.variants.size > 1 &&
            lastAssistant.variantIndex != lastAssistant.variants.lastIndex
        if (continueFromOldVariant) {
            resendUserTurn(uiState.messages.toList(), content, attachments)
        } else {
            addMessage(Message("User", content, attachments = attachments))
        }
    }

    /**
     * True when the loaded model is an OpenAI-compatible remote server: it has no
     * on-device CLIP projector, so vision turns stage the image straight to the
     * backend (base64 image_url) instead of loading/attaching a projector.
     */
    private val isRemoteModel: Boolean
        get() = llamaModel is RemoteOpenAiModel

    /** True for the on-device LiteRT engine: no mmproj/projector; the vision encoder
     *  is configured at engine load and the image is staged straight to
     *  LiteRtBackend.setImageData (same no-projector shape as the remote path). */
    private val isLiteRtModel: Boolean
        get() = llamaModel is com.druk.lmplayground.litert.LiteRtModel

    /** Snap the mtmd-era imageMaxTokens slider (64..320) to a Gemma-4 visual-token
     *  budget (the only accepted values), for LiteRT's ExperimentalFlags.visualTokenBudget. */
    private fun nearestGemma4Budget(v: Int): Int =
        intArrayOf(70, 140, 280, 560, 1120).minByOrNull { kotlin.math.abs(it - v) } ?: 280

    /** Total device RAM in bytes, for the RAM-safe context-size recommendation. */
    private fun deviceRamBytes(): Long = try {
        val am = app.getSystemService(android.content.Context.ACTIVITY_SERVICE)
            as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        mi.totalMem
    } catch (_: Throwable) { 0L }

    /**
     * KV-cache element size in bytes * 16 for the recommendation formula. The GPU
     * (OpenCL) path forces F16; on CPU it follows the KV type (default Q8_0).
     * Encoding matches [GenerationParams.kvCacheType]: 0=F16, 1=Q8_0, 2=Q4_0.
     */
    private fun kvBytesPerElemX16(kvCacheType: Int, gpuEnabled: Boolean): Int {
        if (gpuEnabled) return 32 // F16 on GPU (OpenCL flash-attention is F16/F32 only)
        return when (kvCacheType) {
            2 -> 9    // Q4_0 (~0.5625 bytes/elem)
            1 -> 17   // Q8_0 (~1.0625 bytes/elem)
            else -> 32 // F16
        }
    }

    /**
     * Load the current model's mmproj/CLIP projector into the :llama process —
     * once per loaded model ([projectorLoaded]). Shows the transient
     * "Loading vision…" status while the SAF copy + native load run (can take
     * seconds for a multi-hundred-MB projector), then restores the size line.
     */
    private suspend fun ensureProjectorLoaded(): Boolean {
        if (projectorLoaded) return true
        val model = llamaModel ?: return false
        val mmproj = _loadedModel.value?.mmprojFilename ?: return false
        _loadedModelStatus.postValue(app.getString(com.druk.lmplayground.R.string.loading_vision))
        // null reason == success; a non-null string is the failure reason to show.
        // Apply the user's "image detail" preference before the projector loads
        // (mtmd reads image_max_tokens at init). Higher = more image resolution.
        model.setImageMaxTokens(_generationParams.value?.imageMaxTokens ?: 256)
        val reason: String? = withContext(Dispatchers.IO) {
            try {
                val path = storageRepository.resolveMmprojToPath(mmproj)
                    ?: return@withContext "couldn't read the projector file"
                if (model.loadMmproj(path)) null
                else model.getMmprojError().ifBlank { "projector failed to load" }
            } catch (t: Throwable) {
                android.util.Log.e("ConversationViewModel", "projector load failed", t)
                t.message ?: "projector load error"
            }
        }
        _loadedModelStatus.postValue(loadedModelDescription)
        projectorError = reason
        if (reason == null) {
            projectorLoaded = true
        } else {
            // The load failed (often the :llama service crashed/was OOM-killed,
            // so getMmprojError() couldn't be read). Capture memory + the native
            // logcat tail so the real cause is visible without adb. Off-main.
            viewModelScope.launch(Dispatchers.IO) {
                val diag = buildString {
                    append("Reason: ").append(reason).append("\n")
                    append(com.druk.lmplayground.files.NativeDiagnostics.memorySnapshot(app)).append("\n\n")
                    append(com.druk.lmplayground.files.NativeDiagnostics.captureRecentLog())
                }
                _projectorDiagnostic.postValue(diag)
            }
        }
        return reason == null
    }

    /**
     * The user-facing message for a failed projector load — leads with the
     * native mtmd/clip reason (why the projector was rejected) so it's visible
     * in the toast, trimmed to one line.
     */
    private fun visionLoadFailedMessage(): String {
        val reason = projectorError
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(180)
        return if (!reason.isNullOrBlank()) {
            app.getString(com.druk.lmplayground.R.string.vision_load_failed_reason, reason)
        } else {
            app.getString(com.druk.lmplayground.R.string.vision_load_failed)
        }
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
            if (s.kind == AttachmentKind.IMAGE) {
                // Images carry no prompt text (the pixels go through the vision
                // pipeline via localPath) and don't consume the text budget.
                return@map Attachment(s.filename, s.mimeType, AttachmentKind.IMAGE, "", 0, false, null, r.localPath)
            }
            val text = if (r.text.length > remaining) r.text.take(remaining) else r.text
            val truncated = r.truncated || text.length < r.text.length
            remaining = (remaining - text.length).coerceAtLeast(0)
            Attachment(s.filename, s.mimeType, AttachmentKind.DOCUMENT, text, text.length, truncated, r.rawText, r.localPath)
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
     * Fixed prompt overhead the model always carries: the composed system prompt
     * PLUS the enabled tool definitions (rendered into the prompt by the chat
     * template). Counting the tools here is why the context ring now reflects
     * that "many tools = many tokens" even before the first turn. Matches what
     * the generate loop actually sends (tools only when the model supports them
     * and the user has some enabled).
     */
    private fun preambleTokenEstimate(
        toolCallingSupported: Boolean = _supportsToolCalling.value == true
    ): Int {
        var tokens = estimateTokens(composeSystemPrompt(_systemPrompt.value.orEmpty()))
        if (toolCallingSupported && toolRegistry.hasEnabledTools()) {
            tokens += estimateTokens(toolRegistry.toOpenAIToolsJson())
        }
        return tokens
    }

    /**
     * Rough token estimate of a whole conversation's context (preamble = system
     * prompt + enabled tools, then all messages incl. their attachment text).
     * Fills the context ring when switching chats, instead of resetting it to 0
     * until the next turn.
     */
    private fun estimateContextTokens(messages: List<Message>): Int {
        var tokens = preambleTokenEstimate()
        for (m in messages) {
            tokens += estimateTokens(if (m.author == "User") buildModelPrompt(m) else m.content)
        }
        return tokens
    }

    /**
     * After the FIRST assistant reply, ask the loaded model for a short chat title
     * (opt-in). Runs on a THROWAWAY session with a small context so it never
     * disturbs the live chat's KV cache, and is destroyed on every exit path.
     * Best-effort: any failure just keeps the placeholder title.
     */
    private fun maybeAutoNameChat(sessionId: String) {
        if (!storagePreferences.autoNameChats) return
        if (_isGenerating.value == true) return
        val msgs = uiState.messages
        if (msgs.count { it.author == "Assistant" } != 1) return
        val firstUser = msgs.firstOrNull { it.author == "User" }?.content
            ?.takeIf { it.isNotBlank() } ?: return
        val firstAssistant = msgs.lastOrNull { it.author == "Assistant" }?.content.orEmpty()
        val model = llamaModel ?: return
        viewModelScope.launch(Dispatchers.IO) {
            // Only while the title is still the placeholder (first 50 chars of the
            // first user message) — never clobber an already auto/user-set title
            // (e.g. on a later regenerate or edit-resend of a single-turn chat).
            if (chatRepository?.getSession(sessionId)?.title != firstUser.take(50)) return@launch
            var backend: GenerationBackend? = null
            try {
                backend = model.createSession(1024, 0.3f, 0.9f, 1.0f, 40, 0.0f, 0, 0, "", 0)
                    ?: return@launch
                backend.addMessage(buildTitlePrompt(firstUser, firstAssistant), false)
                var out = ""
                backend.generateAll(object : LlamaGenerationCallback {
                    override fun onFullResponse(response: String) { out = response }
                })
                val title = cleanTitle(out)
                // '⚠' (U+26A0) prefixes a swallowed remote-backend error — don't title with it.
                if (title.isNotBlank() && !out.trimStart().startsWith('⚠')) {
                    chatRepository?.updateSessionTitle(sessionId, title)
                }
            } catch (_: Throwable) {
                // keep the placeholder title
            } finally {
                try { backend?.destroy() } catch (_: Throwable) {}
            }
        }
    }

    private fun buildTitlePrompt(user: String, assistant: String): String =
        "Generate a very short title (3-6 words, no quotes, no trailing punctuation) " +
            "for this conversation. Reply with ONLY the title.\n\n" +
            "User: " + user.take(500) + "\n" +
            "Assistant: " + assistant.take(500)

    /** Strip reasoning/quotes/extra lines from the model's title reply; cap length. */
    private fun cleanTitle(raw: String): String {
        var s = raw
        val thinkEnd = s.indexOf("</think>")
        if (thinkEnd >= 0) s = s.substring(thinkEnd + "</think>".length)
        s = s.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
        s = s.trim('"', '\'', '“', '”', '‘', '’', ' ', '.', '#', '*', ':')
        return s.take(50)
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
        val promptTokenEstimate = estimateTokens(modelPrompt)
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
                    // Image turn: make sure the projector is loaded (covers
                    // regenerate/edit-resend, where sendUserMessage's gate is
                    // bypassed) AND attached to THIS session — sessions are
                    // recreated on param/system-prompt changes and only pick up
                    // the projector at create time. Then stage the JPEG bytes;
                    // the native addMessage takes the mtmd path when both are
                    // set. On any failure the turn degrades to text-only.
                    // Set to the image's localPath only when the image was
                    // actually evaluated (mtmd path taken), so we read its token
                    // weight after addMessage and never show a stale count.
                    var evaluatedImagePath: String? = null
                    val imageAtt = message.attachments.firstOrNull {
                        it.kind == AttachmentKind.IMAGE && it.localPath != null
                    }
                    if (imageAtt != null) {
                        if (isRemoteModel || isLiteRtModel) {
                            // Remote / LiteRT vision: no per-session projector — stage the
                            // transcoded JPEG bytes; the backend attaches them on the next
                            // turn (RemoteOpenAiBackend base64s an image_url part; LiteRT
                            // sends Content.ImageBytes). No native token readback.
                            val staged = runCatching {
                                File(imageAtt.localPath!!).readBytes()
                                    .takeIf { it.isNotEmpty() && it.size <= InferenceLimits.MAX_PAYLOAD_BYTES }
                                    ?.also { llamaSession.setImageData(it) } != null
                            }.getOrDefault(false)
                            if (!staged) {
                                android.util.Log.w("ConversationViewModel", "remote image staging failed, sending text-only")
                                _userError.postValue(
                                    app.getString(com.druk.lmplayground.R.string.image_attach_failed)
                                )
                            }
                        } else if (!ensureProjectorLoaded()) {
                            // Projector rejected — surface WHY (native reason).
                            android.util.Log.w("ConversationViewModel", "image turn: projector not loaded, text-only")
                            _userError.postValue(visionLoadFailedMessage())
                        } else {
                            val staged = runCatching {
                                llamaSession.attachProjector() &&
                                    File(imageAtt.localPath!!).readBytes()
                                        .takeIf { it.isNotEmpty() && it.size <= InferenceLimits.MAX_PAYLOAD_BYTES }
                                        ?.also { llamaSession.setImageData(it) } != null
                            }.getOrDefault(false)
                            if (!staged) {
                                android.util.Log.w("ConversationViewModel", "image staging failed, sending text-only")
                                _userError.postValue(
                                    app.getString(com.druk.lmplayground.R.string.image_attach_failed)
                                )
                            } else {
                                evaluatedImagePath = imageAtt.localPath
                            }
                        }
                    }
                    llamaSession.addMessage(modelPrompt, enableThinking)
                    // Image token weight: how much context the image consumed
                    // (set natively in the image turn). Keyed by localPath so the
                    // sent bubble can show "🖼 N token".
                    evaluatedImagePath?.let { path ->
                        val n = runCatching { llamaSession.getLastImageTokens() }.getOrDefault(0)
                        if (n > 0) {
                            _imageTokenCounts.postValue(
                                (_imageTokenCounts.value ?: emptyMap()) + (path to SentImageInfo(n))
                            )
                            // Render a copy at the resolution the model actually
                            // received, then swap it into the bubble so the user
                            // sees the true (lower-res) image the model "saw".
                            viewModelScope.launch(Dispatchers.IO) {
                                val mv = ImageTranscoder.renderModelView(path, n)
                                if (mv != null) {
                                    val cur = _imageTokenCounts.value ?: emptyMap()
                                    _imageTokenCounts.postValue(cur + (path to SentImageInfo(n, mv)))
                                }
                            }
                        }
                    }
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
                        // Consume the regenerate variant list now (before the
                        // superseded guard) so it can never leak to a later turn;
                        // it's applied below only when this is still our message.
                        val regenVariants = pendingRegenVariants
                        pendingRegenVariants = null
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
                            // Regenerate: fold the prior reply(ies) + this fresh
                            // one into a ‹ N/M › variant list on this message.
                            regenVariants?.let { uiState.commitRegenVariants(it) }
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
                            maybeAutoNameChat(sessionId)
                        }
                    }
                }
            }
        }
    }

    /**
     * Prior assistant reply(ies) captured when the user taps Regenerate, folded
     * into the fresh reply's [Message.variants] at finalize so the user can page
     * ‹ N/M › between answers. Consumed (and cleared) exactly once per turn; a
     * normal send / edit-resend clears it so they never inherit variants.
     */
    private var pendingRegenVariants: List<String>? = null

    /**
     * Re-run the most recent user turn to produce a fresh assistant reply.
     * No-op while generating, with no model loaded, or when there's no user
     * message to re-send. Everything after that user turn is discarded, but the
     * old reply is kept as a variant for ‹ N/M › paging.
     */
    @MainThread
    fun regenerateLastResponse() {
        if (_isGenerating.value == true) return
        if (llamaModel == null) return
        val msgs = uiState.messages.toList()
        val lastUserIdx = msgs.indexOfLast { it.author == "User" }
        if (lastUserIdx < 0) return
        // Capture the reply being regenerated as a variant (carrying any existing
        // variants forward), before resendUserTurn truncates the conversation.
        val lastAssistant = msgs.subList(lastUserIdx + 1, msgs.size)
            .lastOrNull { it.author == "Assistant" }
        val priorVariants = when {
            lastAssistant == null -> emptyList()
            lastAssistant.variants.isNotEmpty() -> lastAssistant.variants
            lastAssistant.content.isNotEmpty() -> listOf(lastAssistant.content)
            else -> emptyList()
        }
        pendingRegenVariants = priorVariants.ifEmpty { null }
        val userContent = msgs[lastUserIdx].content
        val prior = msgs.subList(0, lastUserIdx).toList()
        resendUserTurn(prior, userContent, msgs[lastUserIdx].attachments)
    }

    /**
     * Switch which stored variant of an assistant message is shown (UI-only, no
     * regeneration). Applied to [Message.content] so a continued chat replays
     * the selected answer.
     */
    @MainThread
    fun selectMessageVariant(messageId: Long, index: Int) {
        Snapshot.withMutableSnapshot {
            uiState.selectVariant(messageId, index)
        }
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
        // Edit-resend is a new user turn, not a regeneration: never inherit a
        // pending variant list from an earlier (possibly failed) regenerate.
        pendingRegenVariants = null
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
                a.localPath?.let { put("path", it) }
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
                        localPath = o.optString("path").ifEmpty { null },
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
                systemPrompt = _systemPrompt.value.orEmpty(),
                // A chat created while the drawer is "inside" a folder files itself
                // there; null (root) leaves it unfiled.
                folderId = _currentFolderId.value
            )
        )
        _currentSessionId.postValue(id)
        return id
    }

    /** Enter a folder in the drawer: show its chats and file new chats into it. */
    @MainThread
    fun enterFolder(folderId: String) {
        _currentFolderId.value = folderId
    }

    /** Leave the current folder, back to the root chat/folder list. */
    @MainThread
    fun exitFolder() {
        _currentFolderId.value = null
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
            // Follow the opened chat's folder so the drawer context + new-chat
            // inheritance match where this chat lives (null = root / unfiled).
            _currentFolderId.value = sessionEntity?.folderId

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
                    thinkingBudget = sessionEntity.thinkingBudget,
                    // kvCacheType isn't a per-session Room column; restore it from
                    // the per-model preference (which does persist it) so reopening
                    // a saved chat keeps the model's chosen KV cache type instead of
                    // silently reverting to the Q8_0 default.
                    kvCacheType = storagePreferences.getModelGenerationParams(sessionEntity.modelFilename)
                        ?.get("kvCacheType")?.toInt() ?: 1
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

    /**
     * Rewrite a saved library prompt's text (from the settings sheet's Prompt
     * tab). If that prompt is the one currently applied to this session, the
     * session is re-created with the new text so the change takes effect now.
     */
    @MainThread
    fun updateSavedPrompt(id: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val repo = systemPromptRepository ?: return
        viewModelScope.launch {
            val existing = repo.getById(id) ?: return@launch
            repo.update(existing.copy(text = trimmed, updatedAt = System.currentTimeMillis()))
            // Re-apply only if this entry backs the active session prompt.
            if (_systemPromptId.value == id) {
                applySystemPrompt(id, trimmed)
            }
        }
    }

    /**
     * Delete a saved library prompt. If it is the one currently applied to this
     * session, the session prompt is cleared too.
     */
    @MainThread
    fun deleteSavedPrompt(id: String) {
        val repo = systemPromptRepository ?: return
        if (_systemPromptId.value == id) {
            clearSystemPrompt()
        }
        viewModelScope.launch {
            repo.delete(id)
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
        // Re-pair the model's mmproj sibling (off the main thread) so a restored
        // vision model keeps its projector — for BOTH catalog and custom models,
        // exactly like the picker path in loadModelList().
        viewModelScope.launch {
            val files = withContext(Dispatchers.IO) { storageRepository.getModelFiles() }
            val mmprojNames = files.map { it.name }.filter { MmprojPairing.isMmproj(it) }
            val mmproj = MmprojPairing.findMmprojFor(filename, mmprojNames)
            val known = ModelInfoProvider.getByFilename(filename)
            val info = if (known != null) {
                if (known.mmprojFilename == null && mmproj != null) known.copy(mmprojFilename = mmproj) else known
            } else {
                val sizeBytes = files.find { it.name == filename }?.sizeBytes ?: 0L
                ModelInfoProvider.createCustomModelInfo(
                    filename, filename.removeSuffix(".gguf"), sizeBytes, mmproj
                )
            }
            loadModel(info)
        }
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

    // --- Benchmark (Edge-Gallery-style dedicated screen; loads models itself) ---

    private val _benchmarkState = MutableLiveData<BenchmarkUiState>(BenchmarkUiState.Idle)
    val benchmarkState: LiveData<BenchmarkUiState> = _benchmarkState
    private var benchmarkJob: kotlinx.coroutines.Job? = null

    /**
     * Serializes EVERY model-lifecycle operation (chat loadModel, benchmark
     * teardown+load, and the post-benchmark restore) so that on the shared,
     * activity-scoped VM they can never race over the single model the :llama
     * service holds. An in-flight chat load holds this lock, so a benchmark
     * waits for it to finish before tearing it down, and vice versa.
     */
    private val modelLifecycleMutex = Mutex()

    /**
     * Benchmark a downloaded LOCAL [target] across the selected [hardware] (CPU /
     * GPU / both, in sequence). The chat's current model is torn down first; the
     * target is loaded via a CONTAINED loader (bypassing the chat's complex
     * loadModel, which stays untouched); each hardware is measured over
     * [config].runs fresh sessions; results are saved; and finally whatever model
     * was loaded before is restored. Progress + results surface via
     * [benchmarkState]. The screen blocks the user for the whole run, so nothing
     * else touches the single shared model in the meantime.
     */
    fun runBenchmarkSuite(target: ModelInfo, hardware: BenchmarkHardware, config: BenchmarkConfig) {
        if (benchmarkJob?.isActive == true) return
        val cpp = llamaCpp
        if (cpp == null || target.filename.startsWith("remote:")) {
            _benchmarkState.value = BenchmarkUiState.Error(
                app.getString(com.druk.lmplayground.R.string.benchmark_error_remote)
            )
            return
        }
        val repo = (app as? App)?.benchmarkRepository
        val gpuFlags = hardware.gpuFlags()
        val previous = _loadedModel.value
        benchmarkJob = viewModelScope.launch {
            val results = mutableListOf<BenchmarkResultEntity>()
            try {
                // Flip the UI into the blocking "running" view IMMEDIATELY (before
                // the multi-second model load), otherwise the config screen stays
                // up during the load and the user re-taps Run thinking nothing
                // happened. This also covers waiting for the lock below.
                _benchmarkState.postValue(
                    BenchmarkUiState.Running(
                        app.getString(com.druk.lmplayground.R.string.benchmark_loading), 0f
                    )
                )
                // Hold the lock across teardown + every hardware load so no chat
                // load (or a re-entered benchmark) can touch the shared model in
                // the meantime. The restore in the finally is OUTSIDE this lock.
                modelLifecycleMutex.withLock {
                _isModelReady.postValue(false)
                teardownLoadedModel()
                val disableRepack = shouldDisableRepack(target)
                val totalRuns = gpuFlags.size * config.runs
                var completed = 0
                for (gpu in gpuFlags) {
                    val hwLabel = if (gpu) "GPU" else "CPU"
                    // Show the load phase for this hardware (also covers the
                    // CPU -> GPU reload in "both" mode).
                    _benchmarkState.postValue(
                        BenchmarkUiState.Running(
                            app.getString(
                                com.druk.lmplayground.R.string.benchmark_loading_hw,
                                target.name, hwLabel
                            ),
                            completed.toFloat() / totalRuns
                        )
                    )
                    val handle = withContext(Dispatchers.IO) {
                        storageRepository.openModelFile(target.filename)
                    } ?: throw BenchmarkRunner.BenchmarkException(
                        app.getString(com.druk.lmplayground.R.string.benchmark_error_generic)
                    )
                    val model = withContext(Dispatchers.Default) {
                        cpp.loadModel(
                            handle.pfd,
                            object : LlamaProgressCallback {
                                override fun onProgress(progress: Float) {}
                            },
                            disableRepack = disableRepack,
                            gpuLayers = if (gpu) 999 else 0,
                        )
                    }
                    try {
                        val accelerator = runCatching {
                            model.getModelReport().lineSequence()
                                .firstOrNull { it.startsWith("Compute:") }
                                ?.substringAfter("Compute:")?.trim()
                        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: hwLabel
                        val runner = BenchmarkRunner(model)
                        val runs = mutableListOf<BenchmarkRunner.RunMetrics>()
                        val hwStart = System.currentTimeMillis()
                        for (i in 1..config.runs) {
                            _benchmarkState.postValue(
                                BenchmarkUiState.Running(
                                    "${target.name} · $hwLabel · $i/${config.runs}",
                                    completed.toFloat() / totalRuns
                                )
                            )
                            runs.add(runner.runOnce(config))
                            completed++
                        }
                        val hwDurationMs = System.currentTimeMillis() - hwStart
                        val result = aggregateBenchmark(runs, config, target.filename, target.name, accelerator, hwDurationMs)
                        repo?.insert(result)
                        results.add(result)
                    } finally {
                        withContext(NonCancellable + Dispatchers.Default) { model.unloadModel() }
                        handle.close()
                    }
                }
                }
                _benchmarkState.postValue(BenchmarkUiState.Done(results))
            } catch (e: kotlinx.coroutines.CancellationException) {
                _benchmarkState.postValue(BenchmarkUiState.Idle)
                throw e
            } catch (e: Exception) {
                _benchmarkState.postValue(
                    BenchmarkUiState.Error(e.message ?: app.getString(com.druk.lmplayground.R.string.benchmark_error_generic))
                )
            } finally {
                benchmarkJob = null
                // Restore whatever chat model was loaded before the benchmark.
                // forceLoad=true: it was already accepted (past any RAM warning)
                // before the benchmark, so don't re-trigger the warning gate.
                withContext(NonCancellable) {
                    if (previous != null) loadModel(previous, forceLoad = true)
                }
            }
        }
    }

    // --- LiteRT proof-of-life (dev, Step 2 of the LiteRT-LM pivot). Loads a Gemma 4
    // .litertlm and streams a few tokens on the CPU backend to confirm the
    // LiteRT-LM engine actually runs on this device. Runs IN-PROCESS for now; the
    // crash-isolating :litert service comes later. The model is expected at
    // getExternalFilesDir()/litert/gemma-4-E2B-it.litertlm (adb-pushed for the test). ---
    private val _liteRtTest = MutableLiveData("")
    val liteRtTest: LiveData<String> = _liteRtTest

    private val _liteRtChart = MutableLiveData<List<com.druk.lmplayground.benchmark.LiteRtBenchEntry>>(emptyList())
    val liteRtChart: LiveData<List<com.druk.lmplayground.benchmark.LiteRtBenchEntry>> = _liteRtChart

    fun testLiteRt() {
        viewModelScope.launch(Dispatchers.Default) {
            val litertDir = File(app.getExternalFilesDir(null), "litert")
            // Discover every adb-pushed .litertlm model (E2B, E4B, ...) and bench each
            // across the CPU/GPU x MTP-off/on matrix.
            val modelFiles = litertDir.listFiles { f -> f.name.endsWith(".litertlm") }
                ?.sortedBy { it.name }.orEmpty()
            if (modelFiles.isEmpty()) {
                android.util.Log.e("LiteRtTest", "no .litertlm in ${litertDir.absolutePath}")
                _liteRtTest.postValue("No .litertlm models in\n${litertDir.absolutePath}")
                return@launch
            }
            // The chart wants the decode RATE (tok/s), not the full count, so cap the
            // generation at a fixed char budget to keep the 8-run matrix quick. Greedy
            // temp-0 counting prompt => each digit/newline is a 1-char token, so
            // chars/sec == tok/s. A parity hash guards that MTP output == base output.
            val targetChars = 500
            val results = StringBuilder()
            val chart = mutableListOf<com.druk.lmplayground.benchmark.LiteRtBenchEntry>()
            for (mf in modelFiles) {
                val modelName = shortLiteRtName(mf.name)
                results.append("== ").append(modelName).append(" ==\n")
                var baseHash: Int? = null
                for (useGpu in listOf(false, true)) {
                    for (useMtp in listOf(false, true)) {
                        val cfg = "${if (useGpu) "GPU" else "CPU"} ${if (useMtp) "MTP" else "base"}"
                        _liteRtTest.postValue("${results}Running $modelName $cfg ...")
                        val engine = com.druk.lmplayground.litert.LiteRtEngine()
                        try {
                            val t0 = System.currentTimeMillis()
                            engine.load(mf.absolutePath, app.cacheDir.path, useGpu, useMtp)
                            val loadMs = System.currentTimeMillis() - t0
                            var chars = 0
                            var emissions = 0
                            var first = 0L
                            var last = 0L
                            val sb = StringBuilder()
                            engine.generate("Count from 1 to 300, one number per line.", 1, 1.0, 0.0)
                                .takeWhile { chars < targetChars }
                                .collect { tok ->
                                    val now = System.currentTimeMillis()
                                    if (first == 0L) first = now
                                    last = now
                                    emissions++
                                    chars += tok.length
                                    sb.append(tok)
                                }
                            val decodeSec = (last - first) / 1000.0
                            val tps = if (decodeSec > 0) chars / decodeSec else 0.0
                            val chPerEmit = if (emissions > 0) chars.toDouble() / emissions else 0.0
                            val hash = sb.toString().take(400).hashCode()
                            if (baseHash == null) baseHash = hash
                            val parityOk = hash == baseHash
                            chart.add(com.druk.lmplayground.benchmark.LiteRtBenchEntry(
                                modelName, cfg, tps.toFloat(), parityOk))
                            _liteRtChart.postValue(chart.toList())
                            val line = ("$modelName $cfg: %.0f tok/s (%d ch, %.1f ch/emit, " +
                                "load %dms, hash %d%s)").format(
                                tps, chars, chPerEmit, loadMs, hash, if (parityOk) "" else " PARITY!")
                            android.util.Log.i("LiteRtTest", line)
                            results.append(line).append("\n")
                            _liteRtTest.postValue(results.toString())
                        } catch (t: Throwable) {
                            android.util.Log.e("LiteRtTest", "$modelName $cfg FAILED", t)
                            chart.add(com.druk.lmplayground.benchmark.LiteRtBenchEntry(
                                modelName, cfg, 0f, false, failed = true))
                            _liteRtChart.postValue(chart.toList())
                            results.append("$modelName $cfg: FAILED (${t.message})\n")
                            _liteRtTest.postValue(results.toString())
                        } finally {
                            engine.close()
                        }
                    }
                }
            }
            android.util.Log.i("LiteRtTest", "MATRIX DONE:\n$results")
        }
    }

    private fun shortLiteRtName(filename: String): String = when {
        filename.contains("E2B") -> "E2B"
        filename.contains("E4B") -> "E4B"
        else -> filename.removeSuffix(".litertlm").take(16)
    }

    /** Tear down the loaded chat model (no UI posts); used before a benchmark. */
    private suspend fun teardownLoadedModel() {
        generatingJob?.cancel()
        generatingJob?.join()
        generatingJob = null
        val s = llamaSession
        val m = llamaModel
        val h = modelFileHandle
        llamaSession = null
        llamaModel = null
        modelFileHandle = null
        withContext(Dispatchers.Default) {
            s?.destroy()
            m?.unloadModel()
        }
        h?.close()
    }

    /** Mirror loadModel's over-budget -> mmap decision for the contained loader. */
    private suspend fun shouldDisableRepack(modelInfo: ModelInfo): Boolean {
        if (storagePreferences.disableRepack) return true
        val fileSize = withContext(Dispatchers.IO) {
            storageRepository.getModelFiles().find { it.name == modelInfo.filename }?.sizeBytes ?: 0L
        }
        return DeviceCapability.exceedsRamBudget(fileSize, DeviceCapability.totalRamBytes(app))
    }

    fun cancelBenchmark() {
        benchmarkJob?.cancel()
    }

    /** Reset the state after the user has seen a Done/Error (so it doesn't re-show). */
    fun clearBenchmarkState() {
        _benchmarkState.value = BenchmarkUiState.Idle
    }

    private fun aggregateBenchmark(
        runs: List<BenchmarkRunner.RunMetrics>,
        config: BenchmarkConfig,
        filename: String,
        name: String,
        accelerator: String,
        durationMs: Long,
    ): BenchmarkResultEntity {
        val ttft = runs.map { it.ttftMs.toFloat() }
        val prefill = runs.map { it.prefillTokPerSec }
        val decode = runs.map { it.decodeTokPerSec }
        val series = org.json.JSONObject()
            .put("ttft", statsJson(ttft))
            .put("prefill", statsJson(prefill))
            .put("decode", statsJson(decode))
            .put("perRun", org.json.JSONArray().apply {
                for (r in runs) put(
                    org.json.JSONObject()
                        .put("ttft", r.ttftMs)
                        .put("prefill", r.prefillTokPerSec.toDouble())
                        .put("decode", r.decodeTokPerSec.toDouble())
                        .put("genTokens", r.generatedTokens)
                )
            })
        // Surface the experimental MTP status (from the native report) next to the
        // hardware, so the user can see in-app whether the model's MTP head built
        // ("active", with the average draft acceptance %) or is absent ("n/a")
        // without reading adb logs.
        val acceptPcts = runs.map { it.mtpAcceptPct }.filter { it >= 0 }
        val avgAccept = if (acceptPcts.isNotEmpty()) acceptPcts.sum() / acceptPcts.size else -1
        val acceleratorLabel = when (runs.firstOrNull()?.mtpStatus) {
            "active" -> if (avgAccept >= 0) "$accelerator · MTP $avgAccept%" else "$accelerator · MTP"
            "unsupported" -> "$accelerator · MTP n/a"
            else -> accelerator
        }
        return BenchmarkResultEntity(
            modelFilename = filename,
            modelName = name,
            accelerator = acceleratorLabel,
            prefillTokens = config.prefillTokens,
            decodeTokens = config.decodeTokens,
            runs = config.runs,
            ttftMsAvg = ttft.averageOrZero(),
            prefillTokPerSecAvg = prefill.averageOrZero(),
            decodeTokPerSecAvg = decode.averageOrZero(),
            loadTimeMs = runs.firstOrNull()?.loadTimeMs ?: 0,
            peakMemoryMb = null, // Build 2
            contextUsed = runs.maxOfOrNull { it.contextUsed } ?: 0,
            durationMs = durationMs,
            kvCacheType = config.kvCacheType,
            appVersion = com.druk.lmplayground.BuildConfig.VERSION_NAME,
            createdAt = System.currentTimeMillis(),
            seriesJson = series.toString(),
        )
    }

    private fun List<Float>.averageOrZero(): Float =
        if (isEmpty()) 0f else (sum() / size)

    private fun statsJson(values: List<Float>): org.json.JSONObject {
        if (values.isEmpty()) {
            return org.json.JSONObject().put("min", 0).put("max", 0).put("avg", 0).put("median", 0)
        }
        val sorted = values.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2]
            else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
        return org.json.JSONObject()
            .put("min", sorted.first().toDouble())
            .put("max", sorted.last().toDouble())
            .put("avg", (values.sum() / values.size).toDouble())
            .put("median", median.toDouble())
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
            _supportsVision.postValue(false)
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

        // Bounds on the injected <user_memory> block so a large saved memory
        // can't crowd out the conversation. Notes past these limits are simply
        // not injected (they remain available via the memory tool's list action).
        private const val MEMORY_INJECT_MAX_NOTES = 40
        private const val MEMORY_INJECT_CHAR_BUDGET = 4000

        private val HEX = "0123456789abcdef".toCharArray()
    }
}
