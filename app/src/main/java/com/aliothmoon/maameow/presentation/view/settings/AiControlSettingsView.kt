package com.aliothmoon.maameow.presentation.view.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.aliothmoon.maameow.R
import com.aliothmoon.maameow.constant.Routes
import com.aliothmoon.maameow.presentation.components.ListItemDivider
import com.aliothmoon.maameow.presentation.components.SettingsGroupCard
import com.aliothmoon.maameow.presentation.components.TopAppBar
import com.aliothmoon.maameow.presentation.viewmodel.AiControlViewModel
import com.aliothmoon.maameow.theme.MaaDesignTokens
import org.koin.androidx.compose.koinViewModel

@Composable
fun AiControlSettingsView(
    navController: NavController,
    viewModel: AiControlViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val enabled by viewModel.aiControlEnabled.collectAsStateWithLifecycle()
    val port by viewModel.aiMcpPort.collectAsStateWithLifecycle()
    val lanEnabled by viewModel.aiMcpLanEnabled.collectAsStateWithLifecycle()
    val token by viewModel.aiMcpBearerToken.collectAsStateWithLifecycle()
    val screenshotEnabled by viewModel.aiScreenshotEnabled.collectAsStateWithLifecycle()
    val difyBaseUrl by viewModel.aiDifyBaseUrl.collectAsStateWithLifecycle()
    val difyApiKey by viewModel.aiDifyApiKey.collectAsStateWithLifecycle()
    val runningEndpoint by viewModel.runningEndpoint.collectAsStateWithLifecycle()
    val isRecording by viewModel.isRecordingScript.collectAsStateWithLifecycle()
    val draftNodes by viewModel.recordedDraftNodes.collectAsStateWithLifecycle()

    var portText by remember(port) { mutableStateOf(port.toString()) }
    var urlText by remember(difyBaseUrl) { mutableStateOf(difyBaseUrl) }
    var apiKeyText by remember(difyApiKey) { mutableStateOf(difyApiKey) }
    var scriptNameText by remember { mutableStateOf("AI-录制脚本") }
    var showLanConfirmDialog by remember { mutableStateOf(false) }

    if (showLanConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showLanConfirmDialog = false },
            title = { Text(stringResource(R.string.ai_settings_lan_confirm_title)) },
            text = { Text(stringResource(R.string.ai_settings_lan_confirm_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLanConfirmDialog = false
                        viewModel.setAiMcpLanEnabled(true)
                    },
                ) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLanConfirmDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.ai_settings_title),
                navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
                onNavigationClick = { navController.navigateUp() },
                actions = {
                    IconButton(onClick = { navController.navigate(Routes.AI_CHAT) }) {
                        Icon(
                            imageVector = Icons.Rounded.Chat,
                            contentDescription = stringResource(R.string.ai_chat_title),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding()),
            contentPadding = PaddingValues(
                horizontal = MaaDesignTokens.Spacing.listHorizontal,
                vertical = MaaDesignTokens.Spacing.sm,
            ),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
        ) {
            item {
                SettingsGroupCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = MaaDesignTokens.Spacing.sm),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.ai_settings_enable_title),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = runningEndpoint
                                    ?: stringResource(R.string.ai_settings_enable_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { viewModel.setAiControlEnabled(it) },
                        )
                    }

                    ListItemDivider()

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = MaaDesignTokens.Spacing.sm),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.ai_settings_lan_title),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = stringResource(R.string.ai_settings_lan_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = lanEnabled,
                            onCheckedChange = { wantLan ->
                                if (wantLan) {
                                    showLanConfirmDialog = true
                                } else {
                                    viewModel.setAiMcpLanEnabled(false)
                                }
                            },
                        )
                    }

                    ListItemDivider()

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = MaaDesignTokens.Spacing.sm),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.ai_settings_screenshot_title),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = stringResource(R.string.ai_settings_screenshot_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = screenshotEnabled,
                            onCheckedChange = { viewModel.setAiScreenshotEnabled(it) },
                        )
                    }

                    ListItemDivider()

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = MaaDesignTokens.Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { value ->
                                portText = value
                                value.toIntOrNull()?.takeIf { it in 1024..65535 }?.let {
                                    viewModel.setAiMcpPort(it)
                                }
                            },
                            label = { Text(stringResource(R.string.ai_settings_port_label)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        OutlinedTextField(
                            value = token,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.ai_settings_token_label)) },
                            singleLine = true,
                            trailingIcon = {
                                Row {
                                    IconButton(
                                        onClick = {
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            cm.setPrimaryClip(ClipData.newPlainText("MCP Token", token))
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.ai_settings_token_copied),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        },
                                    ) {
                                        Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                                    }
                                    IconButton(onClick = { viewModel.regenerateToken() }) {
                                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // §4 Script Recording section
            item {
                SettingsGroupCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = MaaDesignTokens.Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.ai_settings_record_title),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.ai_settings_record_desc,
                                        draftNodes.size,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = isRecording,
                                onCheckedChange = { viewModel.toggleScriptRecording(it) },
                            )
                        }

                        if (draftNodes.isNotEmpty()) {
                            OutlinedTextField(
                                value = scriptNameText,
                                onValueChange = { scriptNameText = it },
                                label = { Text(stringResource(R.string.ai_settings_script_name_label)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedButton(
                                onClick = {
                                    viewModel.saveRecordedScript(scriptNameText) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.ai_settings_script_saved),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.ai_settings_save_script_btn))
                            }
                        }
                    }
                }
            }

            // §5 Dify Chat API settings
            item {
                SettingsGroupCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = MaaDesignTokens.Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.ai_settings_dify_section_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        OutlinedTextField(
                            value = urlText,
                            onValueChange = {
                                urlText = it
                                viewModel.setAiDifyBaseUrl(it)
                            },
                            label = { Text(stringResource(R.string.ai_settings_dify_url_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = apiKeyText,
                            onValueChange = {
                                apiKeyText = it
                                viewModel.setAiDifyApiKey(it)
                            },
                            label = { Text(stringResource(R.string.ai_settings_dify_key_label)) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = { navController.navigate(Routes.AI_CHAT) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.ai_settings_open_chat_btn))
                        }
                    }
                }
            }
        }
    }
}
