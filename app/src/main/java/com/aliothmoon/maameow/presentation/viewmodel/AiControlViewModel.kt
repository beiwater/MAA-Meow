package com.aliothmoon.maameow.presentation.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliothmoon.maameow.ai.AiChatMessage
import com.aliothmoon.maameow.ai.AiControlService
import com.aliothmoon.maameow.ai.AiScriptRecorder
import com.aliothmoon.maameow.ai.AiToolCallCard
import com.aliothmoon.maameow.ai.DifyChatClient
import com.aliothmoon.maameow.ai.DifyStreamEvent
import com.aliothmoon.maameow.data.model.TaskChainNode
import com.aliothmoon.maameow.data.preferences.AppSettingsManager
import com.aliothmoon.maameow.data.preferences.TaskChainState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class AiControlViewModel(
    private val app: Application,
    private val appSettings: AppSettingsManager,
    private val difyClient: DifyChatClient,
    private val scriptRecorder: AiScriptRecorder,
    private val taskChainState: TaskChainState,
) : ViewModel() {

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

    val runningEndpoint: StateFlow<String?> = AiControlService.runningEndpoint

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
            val baseUrl = appSettings.aiDifyBaseUrl.value
            val apiKey = appSettings.aiDifyApiKey.value
            val user = appSettings.aiDifyUser.value

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
                    is DifyStreamEvent.TextDelta -> {
                        _messages.update { list ->
                            list.map { msg ->
                                if (msg.id == assistantMsgId) {
                                    msg.copy(content = msg.content + event.delta)
                                } else {
                                    msg
                                }
                            }
                        }
                    }
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
                        _messages.update { list ->
                            list.map { msg ->
                                if (msg.id == assistantMsgId) msg.copy(isStreaming = false) else msg
                            }
                        }
                    }
                    is DifyStreamEvent.Error -> {
                        _messages.update { list ->
                            list.map { msg ->
                                if (msg.id == assistantMsgId) {
                                    msg.copy(
                                        content = if (msg.content.isBlank()) event.message else "${msg.content}\n\n[Error: ${event.message}]",
                                        isStreaming = false,
                                        isError = true,
                                    )
                                } else {
                                    msg
                                }
                            }
                        }
                    }
                }
            }
            _isStreaming.value = false
            _messages.update { list ->
                list.map { msg ->
                    if (msg.id == assistantMsgId && msg.isStreaming) msg.copy(isStreaming = false) else msg
                }
            }
        }
    }

    fun stopGeneration() {
        val taskId = currentTaskId
        streamJob?.cancel()
        streamJob = null
        _isStreaming.value = false
        _messages.update { list ->
            list.map { if (it.isStreaming) it.copy(isStreaming = false) else it }
        }
        if (taskId.isNotBlank()) {
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
