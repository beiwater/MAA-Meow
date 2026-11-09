package com.aliothmoon.maameow.ai

import android.app.Application
import com.aliothmoon.maameow.ai.history.AiChatSession
import com.aliothmoon.maameow.ai.history.AiSessionHistoryManager
import com.aliothmoon.maameow.ai.mcp.MaaToolBridge
import com.aliothmoon.maameow.ai.workspace.AgentWorkspaceManager
import com.aliothmoon.maameow.data.model.TaskProfile
import com.aliothmoon.maameow.data.preferences.AppSettingsManager
import com.aliothmoon.maameow.data.preferences.TaskChainState
import com.aliothmoon.maameow.domain.models.AiChatProvider
import com.aliothmoon.maameow.domain.service.MaaCompositionService
import com.aliothmoon.maameow.domain.state.MaaExecutionState
import com.aliothmoon.maameow.maa.callback.TaskChainStatusTracker
import com.aliothmoon.maameow.maa.callback.TaskRunStatus
import com.aliothmoon.maameow.presentation.viewmodel.AiControlViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiControlViewModelForkingTest {

    private val testDispatcher = StandardTestDispatcher()
    private val app = mockk<Application>(relaxed = true)
    private val appSettings = mockk<AppSettingsManager>(relaxed = true)
    private val difyClient = mockk<DifyChatClient>(relaxed = true)
    private val openAiClient = mockk<OpenAiCompatibleChatClient>(relaxed = true)
    private val scriptRecorder = mockk<AiScriptRecorder>(relaxed = true)
    private val taskChainState = mockk<TaskChainState>(relaxed = true)
    private val toolBridge = mockk<MaaToolBridge>(relaxed = true)
    private val sessionHistoryManager = mockk<AiSessionHistoryManager>(relaxed = true)
    private val workspaceManager = mockk<AgentWorkspaceManager>(relaxed = true)
    private val statusTracker = TaskChainStatusTracker()
    private val compositionService = mockk<MaaCompositionService>(relaxed = true)

    private val executionState = MutableStateFlow(MaaExecutionState.IDLE)
    private val providerFlow = MutableStateFlow(AiChatProvider.OPENAI)
    private val openAiApiKeyFlow = MutableStateFlow("sk-test")
    private val openAiBaseUrlFlow = MutableStateFlow("https://openrouter.ai/api/v1")
    private val openAiModelFlow = MutableStateFlow("gpt-4o-mini")
    private val aiControlEnabledFlow = MutableStateFlow(true)
    private val isLoadedFlow = MutableStateFlow(true)
    private val profilesFlow = MutableStateFlow(listOf(TaskProfile(id = "p1", name = "日常", chain = emptyList())))
    private val profileIdFlow = MutableStateFlow("p1")
    private val chainFlow = MutableStateFlow(emptyList<com.aliothmoon.maameow.data.model.TaskChainNode>())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        every { appSettings.aiChatProvider } returns providerFlow
        every { appSettings.aiOpenAiApiKey } returns openAiApiKeyFlow
        every { appSettings.aiOpenAiBaseUrl } returns openAiBaseUrlFlow
        every { appSettings.aiOpenAiModel } returns openAiModelFlow
        every { appSettings.aiControlEnabled } returns aiControlEnabledFlow
        every { appSettings.aiMcpPort } returns MutableStateFlow(18080)
        every { appSettings.aiMcpLanEnabled } returns MutableStateFlow(false)
        every { appSettings.aiMcpBearerToken } returns MutableStateFlow("")
        every { appSettings.aiScreenshotEnabled } returns MutableStateFlow(false)
        every { appSettings.aiDifyBaseUrl } returns MutableStateFlow("http://127.0.0.1/v1")
        every { appSettings.aiDifyApiKey } returns MutableStateFlow("")
        every { appSettings.aiDifyUser } returns MutableStateFlow("user")
        every { appSettings.aiJevEnabled } returns MutableStateFlow(false)
        every { appSettings.aiChatTargetLabel } returns MutableStateFlow(null)
        every { appSettings.aiOpenAiTemperature } returns MutableStateFlow(0.7f)

        every { taskChainState.isLoaded } returns isLoadedFlow
        every { taskChainState.profiles } returns profilesFlow
        every { taskChainState.profileId } returns profileIdFlow
        every { taskChainState.chain } returns chainFlow

        every { compositionService.state } returns executionState
        every { sessionHistoryManager.loadSessions() } returns listOf(AiChatSession(id = "s1", messages = emptyList()))
        every { sessionHistoryManager.getActiveSessionId() } returns "s1"
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `isTaskRunning reflects background execution state`() = runTest(testDispatcher) {
        val vm = AiControlViewModel(
            app = app,
            appSettings = appSettings,
            difyClient = difyClient,
            openAiClient = openAiClient,
            scriptRecorder = scriptRecorder,
            taskChainState = taskChainState,
            toolBridge = toolBridge,
            sessionHistoryManager = sessionHistoryManager,
            workspaceManager = workspaceManager,
            statusTracker = statusTracker,
            compositionService = compositionService,
        )

        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.isTaskRunning.value)

        executionState.value = MaaExecutionState.RUNNING
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.isTaskRunning.value)

        executionState.value = MaaExecutionState.IDLE
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.isTaskRunning.value)
    }

    @Test
    fun `user can send message even when background task is running`() = runTest(testDispatcher) {
        // Mock streamChat returning an empty completed stream
        every {
            openAiClient.streamChat(any(), any(), any(), any(), any(), any(), any())
        } returns flowOf(ChatStreamEvent.Completed(finishReason = "stop"))

        val vm = AiControlViewModel(
            app = app,
            appSettings = appSettings,
            difyClient = difyClient,
            openAiClient = openAiClient,
            scriptRecorder = scriptRecorder,
            taskChainState = taskChainState,
            toolBridge = toolBridge,
            sessionHistoryManager = sessionHistoryManager,
            workspaceManager = workspaceManager,
            statusTracker = statusTracker,
            compositionService = compositionService,
        )

        testDispatcher.scheduler.advanceUntilIdle()

        // Background task is running!
        executionState.value = MaaExecutionState.RUNNING
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.isTaskRunning.value)

        // User sends message while background task is running
        vm.sendMessage("你觉得哪个干员最好用？")

        // User message should be added, isStreaming should briefly be true, then reset to false
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.isStreaming.value)
        assertEquals(2, vm.messages.value.size)
        assertEquals("你觉得哪个干员最好用？", vm.messages.value[0].content)
        assertTrue(vm.messages.value[0].isUser)
        assertFalse(vm.messages.value[1].isUser)
        assertFalse(vm.messages.value[1].isStreaming)
    }

    @Test
    fun `status tracker updates running tasks summary`() = runTest(testDispatcher) {
        val vm = AiControlViewModel(
            app = app,
            appSettings = appSettings,
            difyClient = difyClient,
            openAiClient = openAiClient,
            scriptRecorder = scriptRecorder,
            taskChainState = taskChainState,
            toolBridge = toolBridge,
            sessionHistoryManager = sessionHistoryManager,
            workspaceManager = workspaceManager,
            statusTracker = statusTracker,
            compositionService = compositionService,
        )

        testDispatcher.scheduler.advanceUntilIdle()
        statusTracker.register(1, "FIGHT")
        statusTracker.updateStatus(1, TaskRunStatus.IN_PROGRESS)

        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, vm.runningTasks.value.size)
        assertEquals("FIGHT", vm.runningTasks.value[0].taskChain)
        assertEquals(TaskRunStatus.IN_PROGRESS, vm.runningTasks.value[0].status)
    }

    @Test
    fun `background task grounding is injected into system prompt for subsequent user messages`() = runTest(testDispatcher) {
        var capturedSystemPrompt = ""
        every {
            openAiClient.streamChat(any(), any(), any(), any(), any(), any(), any())
        } answers {
            capturedSystemPrompt = arg<String>(4)
            flowOf(ChatStreamEvent.Completed(finishReason = "stop"))
        }

        val vm = AiControlViewModel(
            app = app,
            appSettings = appSettings,
            difyClient = difyClient,
            openAiClient = openAiClient,
            scriptRecorder = scriptRecorder,
            taskChainState = taskChainState,
            toolBridge = toolBridge,
            sessionHistoryManager = sessionHistoryManager,
            workspaceManager = workspaceManager,
            statusTracker = statusTracker,
            compositionService = compositionService,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // Background task is running with statusTracker tasks!
        executionState.value = MaaExecutionState.RUNNING
        statusTracker.register(1, "FIGHT")
        statusTracker.updateStatus(1, TaskRunStatus.IN_PROGRESS)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.sendMessage("进度怎么样了？")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(capturedSystemPrompt.contains("[当前后台任务正在运行:"))
        assertTrue(capturedSystemPrompt.contains("FIGHT[进行中]"))
    }

    @Test
    fun `background task tools provide default acknowledgment when assistant text is blank`() = runTest(testDispatcher) {
        var round = 0
        every {
            openAiClient.streamChat(any(), any(), any(), any(), any(), any(), any())
        } answers {
            round++
            if (round == 1) {
                flowOf(
                    ChatStreamEvent.ToolCallsReady(
                        listOf(OpenAiToolCall(id = "c1", name = "maa_run_profile", arguments = """{"profile_id":"p1"}"""))
                    ),
                    ChatStreamEvent.Completed(finishReason = "tool_calls")
                )
            } else {
                flowOf(
                    ChatStreamEvent.Completed(finishReason = "stop")
                )
            }
        }
        coEvery { toolBridge.runProfile(any(), any()) } returns Result.success(
            com.aliothmoon.maameow.ai.mcp.RunLaunchResultDto(
                started = true,
                runId = "run-123",
                profileId = "p1",
                message = "Profile launched asynchronously",
            )
        )

        val vm = AiControlViewModel(
            app = app,
            appSettings = appSettings,
            difyClient = difyClient,
            openAiClient = openAiClient,
            scriptRecorder = scriptRecorder,
            taskChainState = taskChainState,
            toolBridge = toolBridge,
            sessionHistoryManager = sessionHistoryManager,
            workspaceManager = workspaceManager,
            statusTracker = statusTracker,
            compositionService = compositionService,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        vm.sendMessage("帮我跑日常")
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.isStreaming.value)
        val assistantMsg = vm.messages.value.find { !it.isUser }
        assertEquals("已为您转入后台执行，您可以继续跟我聊其他事情或安排新任务～", assistantMsg?.content)
    }

    @Test
    fun `stopGeneration cancels streamJob and does not flag assistant message as error`() = runTest(testDispatcher) {
        every {
            openAiClient.streamChat(any(), any(), any(), any(), any(), any(), any())
        } answers {
            kotlinx.coroutines.flow.flow {
                emit(ChatStreamEvent.TextDelta("正在思考"))
                kotlinx.coroutines.delay(10_000)
                emit(ChatStreamEvent.Completed(finishReason = "stop"))
            }
        }

        val vm = AiControlViewModel(
            app = app,
            appSettings = appSettings,
            difyClient = difyClient,
            openAiClient = openAiClient,
            scriptRecorder = scriptRecorder,
            taskChainState = taskChainState,
            toolBridge = toolBridge,
            sessionHistoryManager = sessionHistoryManager,
            workspaceManager = workspaceManager,
            statusTracker = statusTracker,
            compositionService = compositionService,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        vm.sendMessage("请写一篇长文")
        testDispatcher.scheduler.advanceTimeBy(100)

        assertTrue(vm.isStreaming.value)
        val assistantDuring = vm.messages.value.find { !it.isUser }
        assertTrue(assistantDuring?.isStreaming == true)

        vm.stopGeneration()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.isStreaming.value)
        val assistantAfter = vm.messages.value.find { !it.isUser }
        assertFalse(assistantAfter?.isStreaming ?: true)
        assertFalse("Message must not be marked as error when cancelled by user", assistantAfter?.isError ?: true)
        assertEquals("正在思考", assistantAfter?.content)
    }

    @Test
    fun `streamJob handles network timeout and resets isStreaming`() = runTest(testDispatcher) {
        every {
            openAiClient.streamChat(any(), any(), any(), any(), any(), any(), any())
        } answers {
            flowOf(ChatStreamEvent.Error(kind = ChatErrorKind.NETWORK, host = "api.openrouter.ai", port = 443, httpCode = 0, detail = "connect timed out"))
        }

        val vm = AiControlViewModel(
            app = app,
            appSettings = appSettings,
            difyClient = difyClient,
            openAiClient = openAiClient,
            scriptRecorder = scriptRecorder,
            taskChainState = taskChainState,
            toolBridge = toolBridge,
            sessionHistoryManager = sessionHistoryManager,
            workspaceManager = workspaceManager,
            statusTracker = statusTracker,
            compositionService = compositionService,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        vm.sendMessage("联网请求")
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.isStreaming.value)
        val assistantMsg = vm.messages.value.find { !it.isUser }
        assertFalse(assistantMsg?.isStreaming ?: true)
        assertTrue(assistantMsg?.isError ?: false)
    }

    @Test
    fun `streamJob handles HTTP 429 rate limit and resets isStreaming`() = runTest(testDispatcher) {
        every {
            openAiClient.streamChat(any(), any(), any(), any(), any(), any(), any())
        } answers {
            flowOf(ChatStreamEvent.Error(kind = ChatErrorKind.RATE_LIMITED, host = "api.openrouter.ai", port = 443, httpCode = 429, detail = "Rate limit exceeded"))
        }

        val vm = AiControlViewModel(
            app = app,
            appSettings = appSettings,
            difyClient = difyClient,
            openAiClient = openAiClient,
            scriptRecorder = scriptRecorder,
            taskChainState = taskChainState,
            toolBridge = toolBridge,
            sessionHistoryManager = sessionHistoryManager,
            workspaceManager = workspaceManager,
            statusTracker = statusTracker,
            compositionService = compositionService,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        vm.sendMessage("并发请求")
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.isStreaming.value)
        val assistantMsg = vm.messages.value.find { !it.isUser }
        assertFalse(assistantMsg?.isStreaming ?: true)
        assertTrue(assistantMsg?.isError ?: false)
    }
}
