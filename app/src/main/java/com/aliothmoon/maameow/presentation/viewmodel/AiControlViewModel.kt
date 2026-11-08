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
import com.aliothmoon.maameow.data.model.TaskChainNode
import com.aliothmoon.maameow.data.preferences.AppSettingsManager
import com.aliothmoon.maameow.data.preferences.TaskChainState
import com.aliothmoon.maameow.domain.models.AiChatProvider
import kotlinx.coroutines.Job
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
    toolBridge: MaaToolBridge,
) : ViewModel() {

    /** 与外部 MCP 客户端共用同一个 bridge 单例：状态、校验、副作用完全一致。 */
    private val toolInvoker = MaaToolBridgeInvoker(
        bridge = toolBridge,
        screenshotEnabled = { appSettings.aiScreenshotEnabled.value },
    )

    /** 「请求 → 执行工具 → 回灌结果」循环；上限 6 轮 / 8 次，取消即停。 */
    private val toolLoop = ChatToolLoop(
        client = openAiClient,
        invoker = { name, argumentsJson -> toolInvoker.invoke(name, argumentsJson) },
        limitNote = app.getString(R.string.ai_chat_tool_limit_reached),
    )

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
        if (text.isEmpty() || _isStreaming.value) return

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
        _isStreaming.value = true
        currentTaskId = ""

        streamJob = viewModelScope.launch {
            when (appSettings.aiChatProvider.value) {
                AiChatProvider.OPENAI -> streamOpenAi(assistantMsgId)
                AiChatProvider.DIFY -> streamDify(text, assistantMsgId)
            }
            _isStreaming.value = false
            _messages.update { list ->
                list.map { msg ->
                    if (msg.id == assistantMsgId && msg.isStreaming) msg.copy(isStreaming = false) else msg
                }
            }
        }
    }

    /** Dify 应用 API：会话状态由 conversation_id 维护，工具调用渲染为卡片。 */
    private suspend fun streamDify(text: String, assistantMsgId: String) {
        val baseUrl = appSettings.aiDifyBaseUrl.value
        val apiKey = appSettings.aiDifyApiKey.value
        val user = appSettings.aiDifyUser.value

        if (apiKey.isBlank() || baseUrl.isBlank()) {
            // 未配置就绝不发请求：避免把 Failed to connect 之类的原始 socket 文本丢给用户
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
                    currentTaskId = event.taskId
                    if (event.conversationId.isNotBlank()) {
                        conversationId = event.conversationId
                    }
                }
                is DifyStreamEvent.TextDelta -> appendAssistant(assistantMsgId, event.delta)
                is DifyStreamEvent.ToolCallStarted -> {
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
                is DifyStreamEvent.Completed -> {
                    if (event.conversationId.isNotBlank()) {
                        conversationId = event.conversationId
                    }
                    finishAssistant(assistantMsgId)
                }
                is DifyStreamEvent.Error -> failAssistant(
                    assistantMsgId,
                    chatFailure(event.kind, event.host, event.port, event.httpCode, event.message),
                )
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
     *    上限沿用 [ChatToolLoop] 的 6 轮 / 8 次调用。
     *
     * AI 控制关闭时只做纯文本对话：不发送 tools，也不执行任何工具。
     */
    private suspend fun streamOpenAi(assistantMsgId: String) {
        val apiKey = appSettings.aiOpenAiApiKey.value
        val baseUrl = appSettings.aiOpenAiBaseUrl.value
        if (apiKey.isBlank() || baseUrl.isBlank()) {
            // 未配置密钥时不发请求，直接在气泡里给出可操作的提示
            failAssistant(assistantMsgId, notConfiguredFailure(), needsSetup = true)
            return
        }

        val aiEnabled = appSettings.aiControlEnabled.value
        val systemPrompt = MaaChatSystemPrompt.build(
            toolSpecs = MaaMcpServerFactory.toolSpecs,
            aiControlEnabled = aiEnabled,
            profileSummary = currentProfileSummary(),
        )

        val request = ChatRequest(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = appSettings.aiOpenAiModel.value,
            systemPrompt = systemPrompt,
            messages = _messages.value
                .filter { it.id != assistantMsgId && !it.isError && it.content.isNotBlank() }
                .map { msg ->
                    OpenAiChatMessage(
                        role = if (msg.isUser) "user" else "assistant",
                        content = msg.content,
                    )
                },
            // 关闭时不带 tools：模型没有任何可调用的能力，只能如实说明限制
            tools = if (aiEnabled) MaaMcpServerFactory.toolSpecs else emptyList(),
        )

        when (
            val result = toolLoop.run(
                request = request,
                onText = { appendAssistant(assistantMsgId, it) },
                onThinking = { appendThinking(assistantMsgId, it) },
                onToolCard = { card, _ -> upsertToolCard(assistantMsgId, card) },
            )
        ) {
            // 上限提示已由 ChatToolLoop 通过 onText 写进气泡
            ChatLoopResult.Completed, ChatLoopResult.LimitReached -> Unit
            is ChatLoopResult.Failure -> failAssistant(
                assistantMsgId,
                chatFailure(result.kind, result.host, result.port, result.httpCode, result.detail),
            )
        }
    }

    /**
     * 用户当前配置的真实名单（名字 + profile_id + 已启用任务），
     * 作为最便宜的「RAG」grounding 放进系统提示词，
     * 避免「把日常跑完」被反问「哪个游戏 / 哪个配置」。
     */
    private suspend fun currentProfileSummary(): String? {
        // 配置还没从 DataStore 载入完时稍等一下，不要给模型一份空名单
        withTimeoutOrNull(1_500) { taskChainState.isLoaded.first { it } }
        val profiles = taskChainState.profiles.value
        if (profiles.isEmpty()) return null
        val activeId = taskChainState.profileId.value
        val activeChain = taskChainState.chain.value
        return profiles.joinToString("\n") { profile ->
            val chain = if (profile.id == activeId) activeChain else profile.chain
            val taskNames = chain.filter { it.enabled }.map { it.name }
            val mark = if (profile.id == activeId) "（当前激活）" else ""
            val nodes = if (taskNames.isEmpty()) "无已启用任务" else taskNames.joinToString(" → ")
            "- ${profile.name}$mark  profile_id=${profile.id}  任务: $nodes"
        }
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
                    msg.copy(
                        content = if (msg.content.isBlank()) message else "${msg.content}\n\n[Error: $message]",
                        isStreaming = false,
                        isError = true,
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
        val taskId = currentTaskId
        val provider = appSettings.aiChatProvider.value
        streamJob?.cancel()
        streamJob = null
        _isStreaming.value = false
        _messages.update { list ->
            list.map { if (it.isStreaming) it.copy(isStreaming = false) else it }
        }
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
        stopGeneration()
        conversationId = ""
        _messages.value = emptyList()
    }
}
