package com.aliothmoon.maameow.presentation.view.settings

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Stop
import com.aliothmoon.maameow.ai.usage.AiMessageUsage
import com.aliothmoon.maameow.ai.usage.AiUsageCalculator
import com.aliothmoon.maameow.ai.usage.AiUsageStats
import com.aliothmoon.maameow.ai.usage.OpenRouterPriceService
import com.aliothmoon.maameow.ai.usage.UsageCategoryBreakdown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.aliothmoon.maameow.maa.callback.TaskRunInfo
import com.aliothmoon.maameow.presentation.components.AiTaskProgressCard
import timber.log.Timber
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.aliothmoon.maameow.R
import com.aliothmoon.maameow.ai.AiChatMessage
import com.aliothmoon.maameow.ai.AiToolCallCard
import com.aliothmoon.maameow.constant.Routes
import com.aliothmoon.maameow.presentation.components.MaaMarkdownText
import com.aliothmoon.maameow.presentation.components.TopAppBar
import com.aliothmoon.maameow.presentation.viewmodel.AiControlViewModel
import com.aliothmoon.maameow.theme.MaaDesignTokens
import org.koin.androidx.compose.koinViewModel
/**
 * §5 Thin In-App Chat Client:
 * - Message list + streaming output (`MaaMarkdownText` reusing `compose-markdown`)
 * - Tool execution summary cards (`AiToolCallCard`)
 * - Stop generation button
 * - Zero session management backend, zero knowledge base UI, zero hardcoded business system prompts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatView(
    navController: NavController,
    /** 作为底栏 Tab 时没有返回箭头；从卡片跳进来的独立页保留 */
    isTab: Boolean = false,
    viewModel: AiControlViewModel = koinViewModel(),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val isStreaming by viewModel.isStreaming.collectAsStateWithLifecycle()
    val chatTarget by viewModel.aiChatTargetLabel.collectAsStateWithLifecycle()
    val runningTasks by viewModel.runningTasks.collectAsStateWithLifecycle()
    val isTaskRunning by viewModel.isTaskRunning.collectAsStateWithLifecycle()
    val sessionStats by viewModel.sessionUsageStats.collectAsStateWithLifecycle()
    var showHistorySheet by remember { mutableStateOf(false) }
    var inputText by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val query = intent?.getStringExtra("text") ?: intent?.getStringExtra("query")
                if (!query.isNullOrBlank()) {
                    viewModel.sendMessage(query)
                }
            }
        }
        val filter = IntentFilter("com.aliothmoon.maameow.AI_CHAT_SEND").apply {
            addAction("com.aliothmoon.maameow.TEST_SEND_MESSAGE")
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }
    // 未配置密钥时不展示示例问句，改为引导卡片
    val configured = chatTarget != null
    val subtitle = chatTarget ?: stringResource(R.string.ai_chat_setup_card_title)

    // 反向列表以最新消息的底边为锚点：正文、思考与工具卡片增长不需要逐块抢滚动。
    val isScrolledToBottom by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds(),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            // 主界面 Scaffold 与顶栏已避让系统栏；这里不再吃 systemBars，只让 Column 的 imePadding 接管键盘
            contentWindowInsets = WindowInsets(0),
            topBar = {
                TopAppBar(
                    title = stringResource(R.string.ai_chat_title),
                    subtitle = subtitle,
                    navigationIcon = if (isTab) null else Icons.AutoMirrored.Filled.ArrowBack,
                    onNavigationClick = { navController.navigateUp() },
                    actions = {
                        IconButton(onClick = { showHistorySheet = true }) {
                            Icon(
                                imageVector = Icons.Rounded.History,
                                contentDescription = "历史与工作区",
                            )
                        }
                        IconButton(onClick = { viewModel.clearChat() }) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = stringResource(R.string.ai_chat_clear),
                            )
                        }
                    },
                )
            },
        ) { paddingValues ->
            Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
                // 键盘弹出时整块上移一个 IME 高度：输入框贴着键盘，窗口不再被系统整体顶出屏幕
                .imePadding(),
        ) {
            if (messages.isEmpty()) {
                EmptyChatState(
                    configured = configured,
                    onExampleClick = { inputText = it },
                    onSetupClick = { navController.navigate(Routes.AI_CONTROL) },
                    modifier = Modifier.weight(1f),
                )
            } else {
                SessionUsageBar(
                    stats = sessionStats,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = MaaDesignTokens.Spacing.listHorizontal,
                            vertical = 4.dp,
                        ),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            horizontal = MaaDesignTokens.Spacing.listHorizontal,
                            vertical = MaaDesignTokens.Spacing.sm,
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(count = messages.size, key = { messages[messages.lastIndex - it].id }) { index ->
                            val msg = messages[messages.lastIndex - index]
                            ChatMessageBubble(
                                msg = msg,
                                priceService = viewModel.effectivePriceService,
                                onOpenSettings = { navController.navigate(Routes.AI_CONTROL) },
                                runningTasks = runningTasks,
                                isTaskRunning = isTaskRunning,
                                onStopTask = { viewModel.stopTaskExecution() },
                            )
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !isScrolledToBottom && messages.isNotEmpty(),
                        enter = fadeIn() + slideInVertically { it / 2 },
                        exit = fadeOut() + slideOutVertically { it / 2 },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp),
                    ) {
                        FilledTonalButton(
                            onClick = {
                                coroutineScope.launch {
                                    listState.animateScrollToItem(0)
                                }
                            },
                            shape = RoundedCornerShape(20.dp),
                            elevation = ButtonDefaults.filledTonalButtonElevation(defaultElevation = 4.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.ai_chat_scroll_to_bottom),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }

            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val sendInput = {
                    if (inputText.isNotBlank() && !isStreaming) {
                        val text = inputText
                        inputText = ""
                        viewModel.sendMessage(text)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text(stringResource(R.string.ai_chat_input_hint)) },
                        modifier = Modifier.weight(1f),
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { sendInput() }),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    if (isStreaming || isTaskRunning) {
                        IconButton(onClick = {
                            if (isTaskRunning) viewModel.stopTaskExecution()
                            if (isStreaming) viewModel.stopGeneration()
                        }) {
                            Icon(
                                imageVector = Icons.Rounded.Stop,
                                contentDescription = stringResource(R.string.common_cancel),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (!isStreaming) {
                        IconButton(
                            enabled = inputText.isNotBlank(),
                            onClick = sendInput,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Send,
                                contentDescription = stringResource(R.string.ai_chat_send),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showHistorySheet) {
        ModalBottomSheet(
            onDismissRequest = { showHistorySheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            AiChatHistoryDrawer(
                viewModel = viewModel,
                onCloseDrawer = { showHistorySheet = false },
            )
        }
    }
    }
}
@Composable
private fun EmptyChatState(
    configured: Boolean,
    onExampleClick: (String) -> Unit,
    onSetupClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val examples = listOf(
        stringResource(R.string.ai_chat_example_1),
        stringResource(R.string.ai_chat_example_2),
        stringResource(R.string.ai_chat_example_3),
        stringResource(R.string.ai_chat_example_4),
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.Chat,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(44.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.ai_chat_empty_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(20.dp))

        if (configured) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                examples.forEach { prompt ->
                    OutlinedButton(
                        onClick = { onExampleClick(prompt) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = prompt,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.ai_chat_setup_card_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.ai_chat_setup_card_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onSetupClick,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.ai_chat_setup_action))
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatMessageBubble(
    msg: AiChatMessage,
    priceService: OpenRouterPriceService,
    onOpenSettings: () -> Unit,
    runningTasks: List<TaskRunInfo> = emptyList(),
    isTaskRunning: Boolean = false,
    onStopTask: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (msg.isUser) Alignment.End else Alignment.Start,
    ) {
        if (msg.toolCalls.isNotEmpty()) {
            ToolCallsSection(
                toolCalls = msg.toolCalls,
                runningTasks = runningTasks,
                isTaskRunning = isTaskRunning,
                onStopTask = onStopTask,
                messageId = msg.id,
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .padding(bottom = 6.dp),
            )
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = when {
                msg.isError -> MaterialTheme.colorScheme.errorContainer
                msg.isUser -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.fillMaxWidth(if (msg.isUser) 0.85f else 0.95f),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (msg.isUser) {
                    Text(
                        text = msg.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else {
                    if (msg.thinking.isNotBlank()) {
                        ThinkingHeader(
                            messageId = msg.id,
                            thinking = msg.thinking,
                            hasAnswer = msg.content.isNotBlank() || !msg.isStreaming,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    val displayMd = when {
                        msg.isStreaming && msg.content.isBlank() -> "..."
                        msg.isStreaming -> msg.content + "▍"
                        else -> msg.content
                    }
                    MaaMarkdownText(
                        markdown = displayMd,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // 原始错误文本单独小字附在下方，方便复制反馈
                    if (!msg.errorDetail.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        SelectionContainer {
                            Text(
                                text = msg.errorDetail,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.75f),
                            )
                        }
                    }
                    if (msg.errorNeedsSetup) {
                        TextButton(
                            onClick = onOpenSettings,
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.ai_chat_setup_action),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }
        }
        if (!msg.isUser) {
            Spacer(modifier = Modifier.height(4.dp))
            TurnUsageSection(
                usage = msg.usage,
                priceService = priceService,
                modifier = Modifier.fillMaxWidth(0.95f),
            )
        }
    }
}

private val EXECUTION_TOOL_NAMES = setOf(
    "maa_execute_adhoc",
    "maa_run_profile",
    "maa_run_workspace_script",
    "maa_run_script",
)

/**
 * 工具调用折叠容器：
 * - 默认收起为紧凑条目（显示工具调用数量与进行状态），避免铺满整屏；
 * - 明确提供「展开 / 收起」切换按钮与点击区域；
 * - 任务执行中时，无论折叠与否，停止按钮始终在卡片条右侧显式展示，绝不隐藏唯一停止控制；
 * - 展开后展示各工具卡片；各个卡片本身也支持折叠，参数、日志与截图按需渲染。
 */
@Composable
private fun ToolCallsSection(
    toolCalls: List<AiToolCallCard>,
    runningTasks: List<TaskRunInfo>,
    isTaskRunning: Boolean,
    onStopTask: () -> Unit,
    messageId: String,
    modifier: Modifier = Modifier,
) {
    // 默认折叠，避免多轮工具铺满屏幕
    var isExpanded by rememberSaveable(messageId) { mutableStateOf(false) }

    val hasRunningTool = toolCalls.any { !it.isFinished } ||
        (isTaskRunning && toolCalls.any { it.toolName in EXECUTION_TOOL_NAMES })
    val finishedCount = toolCalls.count { it.isFinished }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // 汇总顶栏（始终可见，支持点击展开/折叠，包含停止控制）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { isExpanded = !isExpanded }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                if (hasRunningTool) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Build,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (hasRunningTool) {
                        stringResource(R.string.ai_chat_tools_summary_running, finishedCount, toolCalls.size)
                    } else {
                        stringResource(R.string.ai_chat_tools_summary_finished, toolCalls.size)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )

                // 保持执行任务停止按钮始终可达，不能因折叠隐藏唯一停止控制
                if (isTaskRunning && toolCalls.any { it.toolName in EXECUTION_TOOL_NAMES }) {
                    FilledTonalButton(
                        onClick = onStopTask,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Stop,
                            contentDescription = stringResource(R.string.ai_chat_stop_task),
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.ai_chat_stop_task),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }

                // 明确的展开/收起按钮
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(if (isExpanded) R.string.common_collapse else R.string.common_expand),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(
                        imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                    )
                }
            }

            // 展开内容
            if (isExpanded) {
                Spacer(modifier = Modifier.height(6.dp))
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    toolCalls.forEach { card ->
                        val isExecutionTool = card.toolName in EXECUTION_TOOL_NAMES
                        if (isExecutionTool && (isTaskRunning || runningTasks.isNotEmpty())) {
                            AiTaskProgressCard(
                                toolName = card.toolName,
                                toolInput = card.toolInput,
                                tasks = runningTasks,
                                isRunning = isTaskRunning,
                                onStop = onStopTask,
                            )
                        } else {
                            ToolSummaryCard(card)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 推理模型的思维链头部：默认收起，保留紧凑状态提示（思考中 / 已思考及字数），
 * 用户点击后可展开查阅完整思考过程，避免流式输出时侵占全屏视觉。
 */
@Composable
private fun ThinkingHeader(
    messageId: String,
    thinking: String,
    hasAnswer: Boolean,
) {
    // 默认收起
    var expanded by rememberSaveable(messageId) { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (!hasAnswer) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.8.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (hasAnswer) {
                        stringResource(R.string.ai_chat_thinking_finished, thinking.length)
                    } else {
                        stringResource(R.string.ai_chat_thinking_header, thinking.length)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(if (expanded) R.string.common_collapse else R.string.common_expand),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
                    thickness = 0.5.dp,
                )
                Spacer(modifier = Modifier.height(6.dp))
                SelectionContainer {
                    Text(
                        text = thinking,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    )
                }
            }
        }
    }
}

/**
 * 单个工具执行卡片：
 * - 默认收起为紧凑行（工具名、状态指示、是否有截图标签）；
 * - 展开后才渲染传入参数、返回观测结果、解码大图截图与执行日志；
 * - 仅在展开时执行 Base64 截图采样解码，折叠时不占用内存和计算资源；
 * - 提供明确复制日志能力与收起/展开控制。
 */
@Composable
private fun ToolSummaryCard(card: AiToolCallCard) {
    val context = LocalContext.current
    var isCardExpanded by rememberSaveable(card.id) { mutableStateOf(false) }

    // 仅在卡片展开时才解码 Base64 截图，避免默认铺满整屏与内存/解码开销
    val decodedBitmap = if (isCardExpanded && !card.screenshotBase64.isNullOrBlank()) {
        remember(card.screenshotBase64) {
            val base64 = card.screenshotBase64
            try {
                val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@remember null

                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 720 && bounds.outHeight / (sample * 2) >= 720) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
            } catch (t: Throwable) {
                Timber.w(t, "Failed to decode screenshot base64 in ToolSummaryCard")
                null
            }
        }
    } else {
        null
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        ),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // 单工具头部行
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { isCardExpanded = !isCardExpanded }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                if (!card.isFinished) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.8.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = card.toolName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // 紧凑状态提示（未展开时展示关键状态）
                if (!card.isFinished) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.ai_chat_tool_running),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else if (!card.screenshotBase64.isNullOrBlank()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    ) {
                        Text(
                            text = stringResource(R.string.ai_chat_tool_has_screenshot),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Icon(
                    imageVector = if (isCardExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }

            // 展开详情（参数、结果、截图、日志）
            if (isCardExpanded) {
                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f),
                    thickness = 0.5.dp,
                )
                Spacer(modifier = Modifier.height(6.dp))

                if (card.toolInput.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.ai_chat_card_details),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    SelectionContainer {
                        Text(
                            text = card.toolInput,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        )
                    }
                }

                if (card.observation.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    SelectionContainer {
                        Text(
                            text = card.observation,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (decodedBitmap != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.ai_chat_card_screenshot_subtitle),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Image(
                        bitmap = decodedBitmap,
                        contentDescription = stringResource(R.string.ai_settings_screenshot_title),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp)),
                        contentScale = ContentScale.FillWidth,
                    )
                }

                if (!card.detailLogs.isNullOrBlank() && card.detailLogs != card.observation) {
                    var showDetailLogs by rememberSaveable(card.id) { mutableStateOf(false) }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDetailLogs = !showDetailLogs },
                    ) {
                        Icon(
                            imageVector = if (showDetailLogs) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(
                                if (showDetailLogs) R.string.ai_chat_tool_details_collapse
                                else R.string.ai_chat_tool_details_expand,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("AI Tool Log", card.detailLogs))
                                Toast.makeText(context, R.string.ai_chat_card_logs_copied, Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.height(24.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.ai_chat_card_copy_logs),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    if (showDetailLogs) {
                        Spacer(modifier = Modifier.height(4.dp))
                        SelectionContainer {
                            Text(
                                text = card.detailLogs,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionUsageBar(
    stats: AiUsageStats,
    modifier: Modifier = Modifier,
) {
    var isExpanded by rememberSaveable { mutableStateOf(false) }

    UsageBreakdownCard(
        title = stringResource(R.string.ai_chat_usage_session_title),
        stats = stats,
        isExpanded = isExpanded,
        onToggleExpand = { isExpanded = !isExpanded },
        isLegacyUnknown = stats.requests.isEmpty(),
        modifier = modifier,
    )
}

@Composable
private fun TurnUsageSection(
    usage: AiMessageUsage?,
    priceService: OpenRouterPriceService,
    modifier: Modifier = Modifier,
) {
    var isExpanded by rememberSaveable { mutableStateOf(false) }

    if (usage == null) {
        UsageBreakdownCard(
            title = stringResource(R.string.ai_chat_usage_turn_title),
            stats = AiUsageStats(),
            isExpanded = isExpanded,
            onToggleExpand = { isExpanded = !isExpanded },
            isLegacyUnknown = true,
            modifier = modifier,
        )
    } else {
        val stats = remember(usage) {
            AiUsageCalculator.computeStats(usage.requests, usage.isIncomplete, priceService)
        }
        UsageBreakdownCard(
            title = stringResource(R.string.ai_chat_usage_turn_title),
            stats = stats,
            isExpanded = isExpanded,
            onToggleExpand = { isExpanded = !isExpanded },
            isLegacyUnknown = false,
            modifier = modifier,
        )
    }
}

@Composable
private fun UsageBreakdownCard(
    title: String,
    stats: AiUsageStats,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
    isLegacyUnknown: Boolean = false,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ReceiptLong,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(6.dp))

                    val summaryText = if (isLegacyUnknown) {
                        stringResource(R.string.ai_chat_usage_compact_unknown)
                    } else {
                        val costStr = AiUsageCalculator.formatCostUsd(stats.totalCostUsd)
                            ?: stringResource(R.string.ai_chat_usage_unknown)
                        val tokenStr = AiUsageCalculator.formatTokens(stats.totalTokens)
                            ?: stringResource(R.string.ai_chat_usage_unknown)
                        val costPrefix = when {
                            stats.hasUnknownCost && stats.totalCostUsd != null -> "≥"
                            stats.hasEstimatedCost -> "≈"
                            else -> ""
                        }
                        "$title: $costPrefix$costStr · $tokenStr Token"
                    }

                    Text(
                        text = summaryText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (stats.isIncomplete) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.ai_chat_usage_incomplete_tag),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                IconButton(
                    onClick = onToggleExpand,
                    modifier = Modifier.size(20.dp),
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (isExpanded) {
                            stringResource(R.string.ai_chat_usage_details_collapse)
                        } else {
                            stringResource(R.string.ai_chat_usage_details_expand)
                        },
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(6.dp))

                if (isLegacyUnknown) {
                    Text(
                        text = stringResource(R.string.ai_chat_usage_legacy_history),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    )
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        CategoryUsageRow(
                            label = stringResource(R.string.ai_chat_usage_llm_title),
                            breakdown = stats.llm,
                        )
                        CategoryUsageRow(
                            label = stringResource(R.string.ai_chat_usage_jev_title),
                            breakdown = stats.jev,
                        )

                        val sourceTag = when {
                            stats.isAllReported -> stringResource(R.string.ai_chat_usage_reported_tag)
                            stats.hasEstimatedCost -> stringResource(R.string.ai_chat_usage_estimated_tag)
                            else -> null
                        }
                        if (sourceTag != null) {
                            Text(
                                text = sourceTag,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryUsageRow(
    label: String,
    breakdown: UsageCategoryBreakdown,
) {
    if (breakdown.requestCount == 0 && breakdown.totalTokens == null) return

    val tokensStr = AiUsageCalculator.formatTokens(breakdown.totalTokens)
        ?: stringResource(R.string.ai_chat_usage_unknown)
    val costStr = AiUsageCalculator.formatCostUsd(breakdown.costUsd)
        ?: stringResource(R.string.ai_chat_usage_unknown)

    val parts = mutableListOf<String>()
    breakdown.inputTokens?.let {
        parts += stringResource(R.string.ai_chat_usage_input_tokens) + " " + AiUsageCalculator.formatTokens(it)
    }
    breakdown.outputTokens?.let {
        parts += stringResource(R.string.ai_chat_usage_output_tokens) + " " + AiUsageCalculator.formatTokens(it)
    }
    breakdown.cachedInputTokens?.takeIf { it > 0 }?.let {
        parts += stringResource(R.string.ai_chat_usage_cached_tokens) + " " + AiUsageCalculator.formatTokens(it)
    }
    val detailsStr = if (parts.isNotEmpty()) " (" + parts.joinToString(" · ") + ")" else ""

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$label: $tokensStr Token$detailsStr",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = costStr,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
