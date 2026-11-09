package com.aliothmoon.maameow.presentation.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliothmoon.maameow.R
import com.aliothmoon.maameow.ai.AiChatMessage
import com.aliothmoon.maameow.ai.AiControlService
import com.aliothmoon.maameow.ai.AiScriptRecorder
import com.aliothmoon.maameow.ai.AiToolCallCard
import com.aliothmoon.maameow.ai.ChatConfigImport
import com.aliothmoon.maameow.ai.ChatConfigImportResult
import com.aliothmoon.maameow.ai.ChatErrorKind
import com.aliothmoon.maameow.ai.ChatLoopResult
import com.aliothmoon.maameow.ai.ChatRequest
import com.aliothmoon.maameow.ai.ChatToolLoop
import com.aliothmoon.maameow.ai.DifyChatClient
import com.aliothmoon.maameow.ai.DifyStreamEvent
import com.aliothmoon.maameow.ai.MaaChatSystemPrompt
import com.aliothmoon.maameow.ai.MaaToolBridgeInvoker
import com.aliothmoon.maameow.ai.OpenAiChatMessage
import com.aliothmoon.maameow.ai.OpenAiCompatibleChatClient
import com.aliothmoon.maameow.ai.mcp.MaaMcpServerFactory
import com.aliothmoon.maameow.ai.mcp.MaaToolBridge
import com.aliothmoon.maameow.ai.history.AiChatSession
import com.aliothmoon.maameow.ai.history.AiSessionHistoryManager
import com.aliothmoon.maameow.ai.workspace.AgentWorkspaceManager
import com.aliothmoon.maameow.ai.workspace.WorkspaceFileInfo
import com.aliothmoon.maameow.domain.service.MaaCompositionService
import com.aliothmoon.maameow.domain.state.isIdle
import com.aliothmoon.maameow.maa.callback.TaskChainStatusTracker
import com.aliothmoon.maameow.maa.callback.TaskRunInfo
import com.aliothmoon.maameow.maa.callback.TaskRunStatus
import com.aliothmoon.maameow.domain.state.MaaExecutionState
import com.aliothmoon.maameow.utils.i18n.resolve
import com.aliothmoon.maameow.ai.usage.AiMessageUsage
import com.aliothmoon.maameow.ai.usage.AiRequestUsage
import com.aliothmoon.maameow.ai.usage.AiUsageCalculator
import com.aliothmoon.maameow.ai.usage.AiUsageContext
import com.aliothmoon.maameow.ai.usage.AiUsageStats
import com.aliothmoon.maameow.ai.usage.OpenRouterPriceService
import com.aliothmoon.maameow.ai.usage.recordAiUsage
import kotlinx.coroutines.withContext
import java.util.Collections
import timber.log.Timber
import kotlinx.coroutines.flow.map
import com.aliothmoon.maameow.data.model.TaskChainNode
import com.aliothmoon.maameow.data.preferences.AppSettingsManager
import com.aliothmoon.maameow.data.preferences.TaskChainState
import com.aliothmoon.maameow.domain.models.AiChatProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** 「导入链接 / JSON」的一次性结果提示；[serial] 保证同样文案连点也能再弹一次 */
data class ChatImportNotice(val message: String, val success: Boolean, val serial: Long)

class AiControlViewModel(
    private val app: Application,
    private val appSettings: AppSettingsManager,
    private val difyClient: DifyChatClient,
    private val openAiClient: OpenAiCompatibleChatClient,
    private val scriptRecorder: AiScriptRecorder,
    private val taskChainState: TaskChainState,
    private val toolBridge: MaaToolBridge,
    private val sessionHistoryManager: AiSessionHistoryManager = AiSessionHistoryManager(app),
    private val workspaceManager: AgentWorkspaceManager = AgentWorkspaceManager(app),
    private val statusTracker: TaskChainStatusTracker? = null,
    private val compositionService: MaaCompositionService? = null,
    private val jevClient: com.aliothmoon.maameow.ai.jev.JevSystemOneClient? = null,
    val priceService: OpenRouterPriceService? = null,
) : ViewModel() {
    val effectivePriceService: OpenRouterPriceService by lazy {
        priceService ?: OpenRouterPriceService(openAiClient.httpClient)
    }
    /** 与外部 MCP 客户端共用同一个 bridge 单例：状态、校验、副作用完全一致。 */
    private val toolInvoker = MaaToolBridgeInvoker(
        bridge = toolBridge,
        screenshotEnabled = { appSettings.aiScreenshotEnabled.value },
    )

    /** 「请求 → 执行工具 → 回灌结果」循环；默认上限 32 轮 / 64 次，取消即停。 */
    private val toolLoop = ChatToolLoop(
        client = openAiClient,
        invoker = { name, argumentsJson -> toolInvoker.invoke(name, argumentsJson) },
        limitNote = app.getString(R.string.ai_chat_tool_limit_reached),
    )

    private fun obtainJevCascadeController(): com.aliothmoon.maameow.ai.jev.JevCascadeController? {
        val client = jevClient ?: return null
        if (!appSettings.aiJevEnabled.value) return null
        val modeStr = appSettings.aiJevMode.value
        val mode = when (modeStr.uppercase()) {
            "SHADOW" -> com.aliothmoon.maameow.ai.jev.JevMode.SHADOW
            "CASCADE" -> com.aliothmoon.maameow.ai.jev.JevMode.CASCADE
            else -> return null
        }
        val tauTool = appSettings.aiJevToolThreshold.value.toFloatOrNull() ?: 0.90f
        val tauBox = appSettings.aiJevBoxThreshold.value.toFloatOrNull() ?: 0.85f
        val model = appSettings.aiJevModel.value.ifBlank { "jev-1.14" }
        val jevBaseUrl = appSettings.aiJevBaseUrl.value.ifBlank { "https://api.typesafe.ai/v1" }
        val jevApiKey = appSettings.aiJevApiKey.value
        return com.aliothmoon.maameow.ai.jev.JevCascadeController(
            client = client,
            mode = mode,
            tauToolSelect = tauTool,
            tauBox = tauBox,
            defaultModel = model,
            customBaseUrl = jevBaseUrl,
            customApiKey = jevApiKey,
        )
    }

    val aiControlEnabled: StateFlow<Boolean> = appSettings.aiControlEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val aiMcpPort: StateFlow<Int> = appSettings.aiMcpPort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 18080)

    val aiMcpLanEnabled: StateFlow<Boolean> = appSettings.aiMcpLanEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val aiMcpBearerToken: StateFlow<String> = appSettings.aiMcpBearerToken
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val aiScreenshotEnabled: StateFlow<Boolean> = appSettings.aiScreenshotEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val aiDifyBaseUrl: StateFlow<String> = appSettings.aiDifyBaseUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "http://127.0.0.1/v1")

    val aiDifyApiKey: StateFlow<String> = appSettings.aiDifyApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val aiChatProvider: StateFlow<AiChatProvider> = appSettings.aiChatProvider
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AiChatProvider.OPENAI)

    val aiOpenAiBaseUrl: StateFlow<String> = appSettings.aiOpenAiBaseUrl
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            OpenAiCompatibleChatClient.DEFAULT_BASE_URL,
        )

    val aiOpenAiApiKey: StateFlow<String> = appSettings.aiOpenAiApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val aiOpenAiModel: StateFlow<String> = appSettings.aiOpenAiModel
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            OpenAiCompatibleChatClient.DEFAULT_MODEL,
        )


    val aiOpenAiTemperature: StateFlow<Float> = appSettings.aiOpenAiTemperature
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.7f)
    val aiJevEnabled: StateFlow<Boolean> = appSettings.aiJevEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val aiJevApiKey: StateFlow<String> = appSettings.aiJevApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val aiJevBaseUrl: StateFlow<String> = appSettings.aiJevBaseUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "https://api.typesafe.ai/v1")

    val aiJevModel: StateFlow<String> = appSettings.aiJevModel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "jev-1.14")

    val aiJevMode: StateFlow<String> = appSettings.aiJevMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "SHADOW")

    val aiJevToolThreshold: StateFlow<String> = appSettings.aiJevToolThreshold
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "0.90")

    val aiJevBoxThreshold: StateFlow<String> = appSettings.aiJevBoxThreshold
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "0.85")

    /** 顶栏副标题：当前应答方（服务商 · 模型），未配置密钥时为 null */
    val aiChatTargetLabel: StateFlow<String?> = appSettings.aiChatTargetLabel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val runningEndpoint: StateFlow<String?> = AiControlService.runningEndpoint

    private val _chatImportNotice = MutableStateFlow<ChatImportNotice?>(null)
    val chatImportNotice: StateFlow<ChatImportNotice?> = _chatImportNotice.asStateFlow()
    private var chatImportNoticeSerial = 0L

    val isRecordingScript: StateFlow<Boolean> = scriptRecorder.isRecording
    val recordedDraftNodes: StateFlow<List<TaskChainNode>> = scriptRecorder.recordedNodes

    private val _messages = MutableStateFlow<List<AiChatMessage>>(emptyList())
    val messages: StateFlow<List<AiChatMessage>> = _messages.asStateFlow()
    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    private val _sessions = MutableStateFlow<List<AiChatSession>>(emptyList())
    val sessions: StateFlow<List<AiChatSession>> = _sessions.asStateFlow()

    private val _workspaceFiles = MutableStateFlow<List<WorkspaceFileInfo>>(emptyList())
    val workspaceFiles: StateFlow<List<WorkspaceFileInfo>> = _workspaceFiles.asStateFlow()

    val runningTasks: StateFlow<List<TaskRunInfo>> = statusTracker?.tasks
        ?: MutableStateFlow(emptyList())

    val isTaskRunning: StateFlow<Boolean> = (
        compositionService?.state?.map { !it.isIdle }
            ?: statusTracker?.tasks?.map { list -> list.any { it.status == TaskRunStatus.IN_PROGRESS } }
            ?: MutableStateFlow(false)
    ).stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val sessionUsageStats: StateFlow<AiUsageStats> = _messages.map { msgs ->
        val requests = msgs.mapNotNull { it.usage }
            .flatMap { it.requests }
        val hasIncomplete = msgs.any { !it.isUser && (it.usage == null || it.usage.isIncomplete) }
        AiUsageCalculator.computeStats(
            requests = requests,
            isIncomplete = hasIncomplete,
            priceService = effectivePriceService,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AiUsageStats())


    init {
        loadInitialSession()
        refreshWorkspaceFiles()
        viewModelScope.launch {
            priceService?.refreshPrices()
        }
    }

    private fun loadInitialSession() {
        val allSessions = sessionHistoryManager.loadSessions()
        _sessions.value = allSessions

        val activeId = sessionHistoryManager.getActiveSessionId()
        val session = if (activeId != null) {
            allSessions.find { it.id == activeId }
        } else {
            allSessions.firstOrNull()
        } ?: sessionHistoryManager.createNewSession()

        _currentSessionId.value = session.id
        _messages.value = session.messages
        _sessions.value = sessionHistoryManager.loadSessions()
    }

    private fun persistCurrentSession() {
        val sId = _currentSessionId.value ?: return
        val currentMsgs = _messages.value
        val existingSession = _sessions.value.find { it.id == sId }
        val sessionToSave = existingSession?.copy(messages = currentMsgs)
            ?: AiChatSession(id = sId, messages = currentMsgs)
        sessionHistoryManager.saveSession(sessionToSave)
        _sessions.value = sessionHistoryManager.loadSessions()
    }

    fun createNewSession(title: String = "新对话") {
        Timber.tag(TAG).i("createNewSession: title=%s", title)
        stopGeneration()
        val newSession = sessionHistoryManager.createNewSession(title)
        _currentSessionId.value = newSession.id
        _messages.value = emptyList()
        conversationId = ""
        _sessions.value = sessionHistoryManager.loadSessions()
    }

    fun switchSession(sessionId: String) {
        Timber.tag(TAG).i("switchSession: targetId=%s", sessionId)
        if (sessionId == _currentSessionId.value) return
        stopGeneration()
        val session = sessionHistoryManager.switchSession(sessionId) ?: return
        _currentSessionId.value = session.id
        _messages.value = session.messages
        conversationId = ""
        _sessions.value = sessionHistoryManager.loadSessions()
    }

    fun deleteSession(sessionId: String) {
        Timber.tag(TAG).i("deleteSession: targetId=%s", sessionId)
        sessionHistoryManager.deleteSession(sessionId)
        if (_currentSessionId.value == sessionId) {
            val newActive = sessionHistoryManager.getActiveSessionId()
            if (newActive != null) {
                switchSession(newActive)
            } else {
                createNewSession()
            }
        } else {
            _sessions.value = sessionHistoryManager.loadSessions()
        }
    }

    fun clearAllSessions() {
        Timber.tag(TAG).i("clearAllSessions")
        stopGeneration()
        sessionHistoryManager.clearAllSessions()
        createNewSession()
    }

    fun refreshWorkspaceFiles() {
        val files = workspaceManager.listFiles()
        Timber.tag(TAG).d("refreshWorkspaceFiles: loaded %d files", files.size)
        _workspaceFiles.value = files
    }

    fun runWorkspaceScript(name: String, force: Boolean = true) {
        Timber.tag(TAG).i("runWorkspaceScript: name=%s, force=%b", name, force)
        viewModelScope.launch {
            toolBridge.runWorkspaceScript(name, force)
        }
    }

    fun deleteWorkspaceFile(name: String) {
        Timber.tag(TAG).i("deleteWorkspaceFile: name=%s", name)
        workspaceManager.deleteFile(name)
        refreshWorkspaceFiles()
    }

    fun stopTaskExecution() {
        Timber.tag(TAG).i("stopTaskExecution requested")
        viewModelScope.launch {
            val origin = MaaCompositionService.StopOrigin.USER
            val result = compositionService?.stop(origin)
            if (result == null) {
                toolBridge.stop()
            }
            Timber.tag(TAG).i("stopTaskExecution finished: serviceResult=%s", result)
        }
    }

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private var conversationId: String = ""
    private var currentTaskId: String = ""
    private var streamJob: Job? = null

    fun setAiControlEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appSettings.setAiControlEnabled(enabled)
            if (enabled) {
                AiControlService.start(app)
            } else {
                AiControlService.stop(app)
            }
        }
    }

    fun setAiMcpPort(port: Int) {
        viewModelScope.launch {
            appSettings.setAiMcpPort(port)
            if (aiControlEnabled.value) {
                AiControlService.start(app)
            }
        }
    }

    fun setAiMcpLanEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appSettings.setAiMcpLanEnabled(enabled)
            if (aiControlEnabled.value) {
                AiControlService.start(app)
            }
        }
    }

    fun regenerateToken() {
        viewModelScope.launch {
            appSettings.regenerateAiMcpBearerToken()
            if (aiControlEnabled.value) {
                AiControlService.start(app)
            }
        }
    }

    fun setAiScreenshotEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appSettings.setAiScreenshotEnabled(enabled)
        }
    }

    fun setAiDifyBaseUrl(url: String) {
        viewModelScope.launch {
            appSettings.setAiDifyBaseUrl(url)
        }
    }

    fun setAiDifyApiKey(key: String) {
        viewModelScope.launch {
            appSettings.setAiDifyApiKey(key)
        }
    }

    fun setAiChatProvider(provider: AiChatProvider) {
        viewModelScope.launch {
            appSettings.setAiChatProvider(provider)
        }
    }

    fun setAiOpenAiBaseUrl(url: String) {
        viewModelScope.launch {
            appSettings.setAiOpenAiBaseUrl(url)
        }
    }

    fun setAiOpenAiApiKey(key: String) {
        viewModelScope.launch {
            appSettings.setAiOpenAiApiKey(key)
        }
    }

    fun setAiOpenAiModel(model: String) {
        viewModelScope.launch {
            appSettings.setAiOpenAiModel(model)
        }
    }

    fun setAiOpenAiTemperature(temp: Float) {
        viewModelScope.launch {
            appSettings.setAiOpenAiTemperature(temp)
        }
    }

    fun setAiJevEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appSettings.setAiJevEnabled(enabled)
        }
    }

    fun setAiJevApiKey(key: String) {
        viewModelScope.launch {
            appSettings.setAiJevApiKey(key)
        }
    }

    fun setAiJevBaseUrl(url: String) {
        viewModelScope.launch {
            appSettings.setAiJevBaseUrl(url)
        }
    }

    fun setAiJevModel(model: String) {
        viewModelScope.launch {
            appSettings.setAiJevModel(model)
        }
    }

    fun setAiJevMode(mode: String) {
        viewModelScope.launch {
            appSettings.setAiJevMode(mode)
        }
    }

    fun setAiJevToolThreshold(threshold: String) {
        viewModelScope.launch {
            appSettings.setAiJevToolThreshold(threshold)
        }
    }

    fun setAiJevBoxThreshold(threshold: String) {
        viewModelScope.launch {
            appSettings.setAiJevBoxThreshold(threshold)
        }
    }
    /**
     * 「导入链接 / JSON」：解析成功后写入对话配置（不依赖 AI 控制服务是否已启用，
     * 服务只在开关打开时启动），再把结果作为一次性提示交给界面弹出。
     * 失败只回传说明，绝不回显粘贴的密钥。
     */
    fun importChatConfig(input: String) {
        viewModelScope.launch {
            when (val result = ChatConfigImport.parse(input)) {
                is ChatConfigImportResult.Success -> {
                    applyImportedChatConfig(result)
                    publishChatImportNotice(
                        message = importSuccessMessage(result),
                        success = true,
                    )
                }
                is ChatConfigImportResult.Failure -> publishChatImportNotice(
                    message = result.message,
                    success = false,
                )
            }
        }
    }

    fun clearChatImportNotice() {
        _chatImportNotice.value = null
    }

    private suspend fun applyImportedChatConfig(config: ChatConfigImportResult.Success) {
        appSettings.setAiChatProvider(config.provider)
        when (config.provider) {
            AiChatProvider.OPENAI -> {
                config.baseUrl?.let { appSettings.setAiOpenAiBaseUrl(it) }
                appSettings.setAiOpenAiApiKey(config.apiKey)
                config.model?.let { appSettings.setAiOpenAiModel(it) }
            }
            AiChatProvider.DIFY -> {
                config.baseUrl?.let { appSettings.setAiDifyBaseUrl(it) }
                appSettings.setAiDifyApiKey(config.apiKey)
            }
        }
    }

    /** 只显示服务商与模型（密钥整体隐去） */
    private fun importSuccessMessage(config: ChatConfigImportResult.Success): String {
        val label = when (config.provider) {
            AiChatProvider.OPENAI -> OpenAiCompatibleChatClient.vendorLabel(
                config.baseUrl ?: appSettings.aiOpenAiBaseUrl.value,
            )
            AiChatProvider.DIFY -> "Dify"
        }
        val detail = when (config.provider) {
            AiChatProvider.OPENAI -> config.model ?: appSettings.aiOpenAiModel.value
            AiChatProvider.DIFY ->
                config.baseUrl ?: appSettings.aiDifyBaseUrl.value.ifBlank { "http://127.0.0.1/v1" }
        }
        return app.getString(R.string.ai_settings_import_success, label, detail)
    }

    private fun publishChatImportNotice(message: String, success: Boolean) {
        _chatImportNotice.value = ChatImportNotice(
            message = message,
            success = success,
            serial = ++chatImportNoticeSerial,
        )
    }

    fun toggleScriptRecording(start: Boolean) {
        if (start) {
            scriptRecorder.startRecording()
        } else {
            scriptRecorder.stopRecording()
        }
    }

    fun saveRecordedScript(name: String, onSaved: (String) -> Unit = {}) {
        viewModelScope.launch {
            val profile = scriptRecorder.buildProfileFromSnapshot(name) ?: return@launch
            val saved = taskChainState.saveOrUpdateCustomProfile(
                profileId = profile.id,
                name = profile.name,
                chain = profile.chain,
                switchToNew = false,
            )
            scriptRecorder.clearDraft()
            onSaved(saved.id)
        }
    }

    fun sendMessage(query: String) {
        val text = query.trim()
        if (text.isEmpty()) {
            Timber.tag(TAG).d("sendMessage ignored: empty query")
            return
        }
        if (_isStreaming.value) {
            Timber.tag(TAG).w("sendMessage ignored: generation stream already running (_isStreaming = true)")
            return
        }

        Timber.tag(TAG).i("sendMessage: length=%d, isTaskRunning=%b", text.length, isTaskRunning.value)
        val userMsg = AiChatMessage(
            id = UUID.randomUUID().toString(),
            isUser = true,
            content = text,
        )
        val assistantMsgId = UUID.randomUUID().toString()
        val assistantPlaceholder = AiChatMessage(
            id = assistantMsgId,
            isUser = false,
            content = "",
            isStreaming = true,
        )
        _messages.update { it + userMsg + assistantPlaceholder }
        persistCurrentSession()
        _isStreaming.value = true
        currentTaskId = ""

        streamJob = viewModelScope.launch {
            val turnRequests = Collections.synchronizedMap(LinkedHashMap<String, AiRequestUsage>())
            try {
                withContext(AiUsageContext { usage ->
                    turnRequests[usage.requestId] = usage
                    val snapshot = synchronized(turnRequests) { turnRequests.values.toList() }
                    val eval = effectivePriceService.evaluateTotal(snapshot)
                    val currentUsage = AiMessageUsage(
                        requests = snapshot,
                        isIncomplete = false,
                        estimatedCostUsd = eval.totalCostUsd?.toPlainString(),
                        costSource = eval.source,
                        costTimestamp = eval.timestampMillis,
                    )
                    _messages.update { list ->
                        list.map { msg ->
                            if (msg.id == assistantMsgId) msg.copy(usage = currentUsage) else msg
                        }
                    }
                }) {
                    when (appSettings.aiChatProvider.value) {
                        AiChatProvider.OPENAI -> streamOpenAi(assistantMsgId)
                        AiChatProvider.DIFY -> streamDify(text, assistantMsgId)
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    Timber.tag(TAG).i("streamJob cancelled normally")
                    _messages.update { list ->
                        list.map { msg ->
                            if (msg.id == assistantMsgId) {
                                val updatedUsage = msg.usage?.copy(isIncomplete = true)
                                    ?: if (msg.content.isNotBlank() || msg.toolCalls.isNotEmpty()) {
                                        AiMessageUsage(isIncomplete = true)
                                    } else null
                                msg.copy(usage = updatedUsage)
                            } else msg
                        }
                    }
                    throw t
                }
                Timber.tag(TAG).e(t, "streamJob caught uncaught error: %s", t.message)
                failAssistant(
                    assistantMsgId,
                    chatFailure(ChatErrorKind.UNKNOWN, "", 0, 0, t.message),
                )
            } finally {
                _isStreaming.value = false
                _messages.update { list ->
                    list.map { msg ->
                        if (msg.id == assistantMsgId) {
                            val updatedTools = msg.toolCalls.map { tool ->
                                if (!tool.isFinished) {
                                    tool.copy(
                                        isFinished = true,
                                        observation = tool.observation.ifBlank { "已结束" },
                                    )
                                } else tool
                            }
                            msg.copy(
                                isStreaming = false,
                                toolCalls = updatedTools,
                            )
                        } else msg
                    }
                }
                persistCurrentSession()
                Timber.tag(TAG).i("streamJob finished. _isStreaming reset to false. isTaskRunning=%b", isTaskRunning.value)
            }
        }
    }

    /** Dify 应用 API：会话状态由 conversation_id 维护，工具调用渲染为卡片。 */
    private suspend fun streamDify(text: String, assistantMsgId: String) {
        val baseUrl = appSettings.aiDifyBaseUrl.value
        val apiKey = appSettings.aiDifyApiKey.value
        val user = appSettings.aiDifyUser.value
        Timber.tag(TAG).i("streamDify started: baseUrl=%s, user=%s", baseUrl, user)

        if (apiKey.isBlank() || baseUrl.isBlank()) {
            Timber.tag(TAG).w("streamDify aborted: unconfigured apiKey or baseUrl")
            failAssistant(assistantMsgId, notConfiguredFailure(), needsSetup = true)
            return
        }

        difyClient.streamChat(
            baseUrl = baseUrl,
            apiKey = apiKey,
            user = user,
            query = text,
            conversationId = conversationId,
        ).collect { event ->
            when (event) {
                is DifyStreamEvent.TaskInfo -> {
                    Timber.tag(TAG).d("Dify TaskInfo: taskId=%s, convId=%s", event.taskId, event.conversationId)
                    currentTaskId = event.taskId
                    if (event.conversationId.isNotBlank()) {
                        conversationId = event.conversationId
                    }
                }
                is DifyStreamEvent.TextDelta -> appendAssistant(assistantMsgId, event.delta)
                is DifyStreamEvent.ToolCallStarted -> {
                    Timber.tag(TAG).d("Dify ToolCallStarted: id=%s, tool=%s", event.id, event.toolName)
                    _messages.update { list ->
                        list.map { msg ->
                            if (msg.id == assistantMsgId) {
                                val updatedTools = msg.toolCalls.filterNot { it.id == event.id } +
                                    AiToolCallCard(
                                        id = event.id,
                                        toolName = event.toolName,
                                        toolInput = event.toolInput,
                                        isFinished = false,
                                    )
                                msg.copy(toolCalls = updatedTools)
                            } else {
                                msg
                            }
                        }
                    }
                }
                is DifyStreamEvent.ToolCallFinished -> {
                    Timber.tag(TAG).d("Dify ToolCallFinished: id=%s, tool=%s", event.id, event.toolName)
                    _messages.update { list ->
                        list.map { msg ->
                            if (msg.id == assistantMsgId) {
                                val updatedTools = msg.toolCalls.filterNot { it.id == event.id } +
                                    AiToolCallCard(
                                        id = event.id,
                                        toolName = event.toolName,
                                        toolInput = event.toolInput,
                                        observation = event.observation,
                                        isFinished = true,
                                    )
                                msg.copy(toolCalls = updatedTools)
                            } else {
                                msg
                            }
                        }
                    }
                }
                is DifyStreamEvent.Usage -> recordAiUsage(event.usage)
                is DifyStreamEvent.Completed -> {
                    Timber.tag(TAG).i("Dify stream completed: convId=%s", event.conversationId)
                    if (event.conversationId.isNotBlank()) {
                        conversationId = event.conversationId
                    }
                    event.usage?.let { recordAiUsage(it) }
                    finishAssistant(assistantMsgId)
                }
                is DifyStreamEvent.Error -> {
                    Timber.tag(TAG).e("Dify stream error: kind=%s, httpCode=%d, msg=%s", event.kind, event.httpCode, event.message)
                    failAssistant(
                        assistantMsgId,
                        chatFailure(event.kind, event.host, event.port, event.httpCode, event.message),
                    )
                }
            }
        }
    }

    /**
     * OpenAI 兼容 `/chat/completions`：协议无状态，历史由本地消息列表回放。
     *
     * 应用内助手的「真 agent」路径（循环本体在 [ChatToolLoop]，可用 mock HTTP 做 JVM 单测）：
     * 1. 用 [MaaChatSystemPrompt] 注入身份、本机工具清单与用户真实配置；
     * 2. 把 `MaaMcpServerFactory.toolSpecs` 作为 `tools[]` 发给模型；
     * 3. 模型请求工具 → 本地执行（与 MCP 服务端同一个 bridge）→ 结果回灌 → 再次请求，
     *    上限沿用 [ChatToolLoop] 的 32 轮 / 64 次调用。
     *
     * AI 控制关闭时只做纯文本对话：不发送 tools，也不执行任何工具。
     */
    private suspend fun streamOpenAi(assistantMsgId: String) {
        val apiKey = appSettings.aiOpenAiApiKey.value
        val baseUrl = appSettings.aiOpenAiBaseUrl.value
        Timber.tag(TAG).i("streamOpenAi started: baseUrl=%s, model=%s", baseUrl, appSettings.aiOpenAiModel.value)
        if (apiKey.isBlank() || baseUrl.isBlank()) {
            Timber.tag(TAG).w("streamOpenAi aborted: unconfigured apiKey or baseUrl")
            failAssistant(assistantMsgId, notConfiguredFailure(), needsSetup = true)
            return
        }

        val aiEnabled = appSettings.aiControlEnabled.value
        val profileSummary = currentProfileSummary()
        Timber.tag(TAG).d("streamOpenAi profileSummary:\n%s", profileSummary)

        val systemPrompt = MaaChatSystemPrompt.build(
            toolSpecs = MaaMcpServerFactory.toolSpecs,
            aiControlEnabled = aiEnabled,
            profileSummary = profileSummary,
        )

        val historyMessages = _messages.value
            .filter { it.id != assistantMsgId && !it.isError && it.content.isNotBlank() }
            .map { msg ->
                OpenAiChatMessage(
                    role = if (msg.isUser) "user" else "assistant",
                    content = msg.content,
                )
            }
        Timber.tag(TAG).d("streamOpenAi history message count: %d, tools count: %d", historyMessages.size, if (aiEnabled) MaaMcpServerFactory.toolSpecs.size else 0)

        val request = ChatRequest(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = appSettings.aiOpenAiModel.value,
            systemPrompt = systemPrompt,
            messages = historyMessages,
            // 关闭时不带 tools：模型没有任何可调用的能力，只能如实说明限制
            tools = if (aiEnabled) MaaMcpServerFactory.toolSpecs else emptyList(),
            temperature = appSettings.aiOpenAiTemperature.value,
        )
        val cascadeController = obtainJevCascadeController()
        var launchedBackgroundTask = false
        val backgroundTools = setOf(
            "maa_run_profile",
            "maa_execute_adhoc",
            "maa_run_workspace_script",
            "maa_run_script",
            "maa_load_copilot",
        )

        when (
            val result = toolLoop.run(
                request = request,
                onText = { appendAssistant(assistantMsgId, it) },
                onThinking = { appendThinking(assistantMsgId, it) },
                onToolCard = { card, isFinished ->
                    Timber.tag(TAG).d("ToolCard: id=%s toolName=%s isFinished=%b observationLen=%d", card.id, card.toolName, isFinished, card.observation.length)
                    upsertToolCard(assistantMsgId, card)
                    if (isFinished && card.toolName in backgroundTools) {
                        launchedBackgroundTask = true
                        Timber.tag(TAG).i("Background task launched via '%s'. Session forked for background execution.", card.toolName)
                    }
                },
                cascadeController = cascadeController,
            )
        ) {
            ChatLoopResult.Completed, ChatLoopResult.LimitReached -> {
                Timber.tag(TAG).i("ToolLoop finished: %s (launchedBackgroundTask=%b)", result, launchedBackgroundTask)
                if (launchedBackgroundTask) {
                    _messages.update { list ->
                        list.map { msg ->
                            if (msg.id == assistantMsgId && msg.content.isBlank()) {
                                Timber.tag(TAG).i("Providing default acknowledgment since assistant content was blank after background tool call")
                                msg.copy(content = "已为您转入后台执行，您可以继续跟我聊其他事情或安排新任务～")
                            } else {
                                msg
                            }
                        }
                    }
                }
            }
            is ChatLoopResult.Failure -> {
                Timber.tag(TAG).e("ToolLoop failed: kind=%s, host=%s, code=%d, detail=%s", result.kind, result.host, result.httpCode, result.detail)
                failAssistant(
                    assistantMsgId,
                    chatFailure(result.kind, result.host, result.port, result.httpCode, result.detail),
                )
            }
        }
    }

    private fun runningTasksSummary(): String {
        val tasks = runningTasks.value
        if (tasks.isNotEmpty()) {
            return tasks.joinToString(", ") { info ->
                val statusText = when (info.status) {
                    TaskRunStatus.IN_PROGRESS -> "进行中"
                    TaskRunStatus.PENDING -> "等待中"
                    TaskRunStatus.COMPLETED -> "已完成"
                    TaskRunStatus.ERROR -> "异常"
                }
                val label = runCatching {
                    info.logName?.resolve(app)
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: info.taskChain
                "$label[$statusText]"
            }
        }
        val activeProfile = taskChainState.profiles.value.find { it.id == taskChainState.profileId.value }
        val profileName = activeProfile?.name
        val execState = compositionService?.state?.value
        val stateText = when (execState) {
            MaaExecutionState.STARTING -> "启动中"
            MaaExecutionState.RUNNING -> "运行中"
            MaaExecutionState.STOPPING -> "停止中"
            MaaExecutionState.ERROR -> "异常"
            else -> "运行中"
        }
        return if (!profileName.isNullOrBlank()) {
            "$profileName ($stateText)"
        } else {
            "任务执行中 ($stateText)"
        }
    }

    /**
     * 用户当前配置的真实名单（名字 + profile_id + 已启用任务）以及当前后台运行状态，
     * 作为便宜高效的 grounding 放进系统提示词，
     * 避免「把日常跑完」被反问「哪个游戏 / 哪个配置」，并让模型在后台任务运行期间时刻知悉任务进展。
     */
    private suspend fun currentProfileSummary(): String? {
        // 配置还没从 DataStore 载入完时稍等一下，不要给模型一份空名单
        withTimeoutOrNull(1_500) { taskChainState.isLoaded.first { it } }
        val profiles = taskChainState.profiles.value
        val activeId = taskChainState.profileId.value
        val activeChain = taskChainState.chain.value
        val profileLines = if (profiles.isNotEmpty()) {
            profiles.joinToString("\n") { profile ->
                val chain = if (profile.id == activeId) activeChain else profile.chain
                val taskNames = chain.filter { it.enabled }.map { it.name }
                val mark = if (profile.id == activeId) "（当前激活）" else ""
                val nodes = if (taskNames.isEmpty()) "无已启用任务" else taskNames.joinToString(" → ")
                "- ${profile.name}$mark  profile_id=${profile.id}  任务: $nodes"
            }
        } else null

        val runningSummary = if (isTaskRunning.value) {
            val summary = runningTasksSummary()
            Timber.tag(TAG).i("Injecting background task status into prompt summary: %s", summary)
            "[当前后台任务正在运行: $summary]"
        } else {
            null
        }

        return listOfNotNull(runningSummary, profileLines)
            .joinToString("\n\n")
            .takeIf { it.isNotBlank() }
    }

    private fun appendThinking(messageId: String, delta: String) {
        _messages.update { list ->
            list.map { msg ->
                if (msg.id == messageId) msg.copy(thinking = msg.thinking + delta) else msg
            }
        }
    }

    /** 工具卡片先建后收尾（同一个 id 覆盖）：`isFinished=false` 时 UI 显示为进行中。 */
    private fun upsertToolCard(messageId: String, card: AiToolCallCard) {
        _messages.update { list ->
            list.map { msg ->
                if (msg.id == messageId) {
                    msg.copy(toolCalls = msg.toolCalls.filterNot { it.id == card.id } + card)
                } else {
                    msg
                }
            }
        }
    }

    private fun appendAssistant(messageId: String, delta: String) {
        _messages.update { list ->
            list.map { msg ->
                if (msg.id == messageId) msg.copy(content = msg.content + delta) else msg
            }
        }
    }

    private fun finishAssistant(messageId: String) {
        _messages.update { list ->
            list.map { msg ->
                if (msg.id == messageId) msg.copy(isStreaming = false) else msg
            }
        }
    }

    /** 把流内错误分类映射成本地化提示，原始文本单独作为 detail 保留（可复制）。 */
    private fun chatFailure(
        kind: ChatErrorKind,
        host: String,
        port: Int,
        httpCode: Int,
        rawDetail: String?,
    ): Pair<String, String?> {
        val main = when (kind) {
            ChatErrorKind.NETWORK -> app.getString(
                R.string.ai_chat_error_network,
                host.ifBlank { "-" },
                port,
            )
            ChatErrorKind.UNAUTHORIZED -> app.getString(R.string.ai_chat_error_unauthorized)
            ChatErrorKind.NOT_FOUND -> app.getString(R.string.ai_chat_error_not_found)
            ChatErrorKind.RATE_LIMITED -> app.getString(R.string.ai_chat_error_rate_limited)
            ChatErrorKind.SERVER -> app.getString(R.string.ai_chat_error_server, httpCode)
            ChatErrorKind.PROVIDER ->
                rawDetail?.takeIf { it.isNotBlank() } ?: app.getString(R.string.ai_chat_error_unknown)
            ChatErrorKind.UNKNOWN -> app.getString(R.string.ai_chat_error_unknown)
        }
        // 服务商自己的报错本身就是给人看的；其余保留原始异常/响应体作为副行
        val detail = rawDetail?.takeIf { it.isNotBlank() && kind != ChatErrorKind.PROVIDER }
        return main to detail
    }

    private fun notConfiguredFailure(): Pair<String, String?> =
        app.getString(R.string.ai_chat_error_not_configured) to null

    private fun failAssistant(
        messageId: String,
        failure: Pair<String, String?>,
        needsSetup: Boolean = false,
    ) {
        val (message, detail) = failure
        _messages.update { list ->
            list.map { msg ->
                if (msg.id == messageId) {
                    val updatedTools = msg.toolCalls.map { tool ->
                        if (!tool.isFinished) {
                            tool.copy(
                                isFinished = true,
                                observation = tool.observation.ifBlank { "已中断" },
                            )
                        } else tool
                    }
                    val updatedUsage = msg.usage?.copy(isIncomplete = true)
                        ?: if (msg.content.isNotBlank() || msg.toolCalls.isNotEmpty()) {
                            AiMessageUsage(isIncomplete = true)
                        } else null
                    msg.copy(
                        content = if (msg.content.isBlank()) message else "${msg.content}\n\n[Error: $message]",
                        isStreaming = false,
                        isError = true,
                        toolCalls = updatedTools,
                        usage = updatedUsage,
                        errorDetail = detail,
                        errorNeedsSetup = needsSetup,
                    )
                } else {
                    msg
                }
            }
        }
    }


    fun stopGeneration() {
        Timber.tag(TAG).i("stopGeneration requested: currentTaskId=%s", currentTaskId)
        val taskId = currentTaskId
        val provider = appSettings.aiChatProvider.value
        streamJob?.cancel()
        streamJob = null
        _isStreaming.value = false
        _messages.update { list ->
            list.map { msg ->
                if (msg.isStreaming) {
                    val updatedTools = msg.toolCalls.map { tool ->
                        if (!tool.isFinished) {
                            tool.copy(
                                isFinished = true,
                                observation = tool.observation.ifBlank { "已停止" },
                            )
                        } else tool
                    }
                    val updatedUsage = msg.usage?.copy(isIncomplete = true)
                        ?: if (msg.content.isNotBlank() || msg.toolCalls.isNotEmpty()) {
                            AiMessageUsage(isIncomplete = true)
                        } else null
                    msg.copy(
                        isStreaming = false,
                        toolCalls = updatedTools,
                        usage = updatedUsage,
                    )
                } else msg
            }
        }
        persistCurrentSession()
        if (taskId.isNotBlank() && provider == AiChatProvider.DIFY) {
            viewModelScope.launch {
                difyClient.stopGeneration(
                    baseUrl = appSettings.aiDifyBaseUrl.value,
                    apiKey = appSettings.aiDifyApiKey.value,
                    user = appSettings.aiDifyUser.value,
                    taskId = taskId,
                )
            }
        }
    }

    fun clearChat() {
        Timber.tag(TAG).i("clearChat requested")
        stopGeneration()
        conversationId = ""
        _messages.value = emptyList()
        persistCurrentSession()
    }

    companion object {
        private const val TAG = "AiControlViewModel"
    }
}
