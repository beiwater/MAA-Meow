package com.aliothmoon.maameow.presentation.view.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
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
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 未配置密钥时不展示示例问句，改为引导卡片
    val configured = chatTarget != null
    val subtitle = chatTarget ?: stringResource(R.string.ai_chat_setup_card_title)

    // 新消息到达才滚到底；流式增量单独处理，避免每次 chunk 都打断用户滚动
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }
    val streamingTailLength = messages.lastOrNull()?.content?.length ?: 0
    LaunchedEffect(streamingTailLength) {
        if (messages.isEmpty() || streamingTailLength == 0) return@LaunchedEffect
        // 用户往上翻时不要抢滚动；贴底才跟随
        val visibleLast = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
        if (visibleLast >= messages.lastIndex - 1) {
            listState.scrollToItem(messages.lastIndex)
        }
    }

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
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(
                        horizontal = MaaDesignTokens.Spacing.listHorizontal,
                        vertical = MaaDesignTokens.Spacing.sm,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(messages, key = { it.id }) { msg ->
                        ChatMessageBubble(
                            msg = msg,
                            onOpenSettings = { navController.navigate(Routes.AI_CONTROL) },
                        )
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
                    if (isStreaming) {
                        IconButton(onClick = { viewModel.stopGeneration() }) {
                            Icon(
                                imageVector = Icons.Rounded.Stop,
                                contentDescription = stringResource(R.string.common_cancel),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    } else {
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
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (msg.isUser) Alignment.End else Alignment.Start,
    ) {
        if (msg.toolCalls.isNotEmpty()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .padding(bottom = 6.dp),
            ) {
                msg.toolCalls.forEach { card ->
                    ToolSummaryCard(card)
                }
            }
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
                            hasAnswer = msg.content.isNotBlank(),
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
    }
}

/**
 * 推理模型的思维链头部：回答开始前展开（配合 `...` 占位，避免「看起来卡死」），
 * 一旦出现正文就自动折叠，用户仍可点开查看完整思考过程。
 */
@Composable
private fun ThinkingHeader(
    messageId: String,
    thinking: String,
    hasAnswer: Boolean,
) {
    // null = 跟随自动策略；非 null = 用户手动指定
    var manualExpand by remember(messageId) { mutableStateOf<Boolean?>(null) }
    val expanded = manualExpand ?: !hasAnswer

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { manualExpand = !expanded },
        ) {
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.ai_chat_thinking_header, thinking.length),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Spacer(modifier = Modifier.height(4.dp))
            SelectionContainer {
                Text(
                    text = thinking,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun ToolSummaryCard(card: AiToolCallCard) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
        ),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Build,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = card.toolName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                if (!card.isFinished) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.ai_chat_tool_running),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    )
                }
            }
            if (card.toolInput.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = card.toolInput.take(240),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                )
            }
            if (card.observation.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = card.observation.take(280),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}
