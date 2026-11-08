package com.aliothmoon.maameow.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * `maameow://ai-import?…` 深链的一次性搬运通道。
 *
 * MainActivity 只负责校验并把 URI 放进来，不碰 DataStore、不建 ViewModel；
 * AppNavigation 看到后跳 AI 设置页，AiControlSettingsView 取出并交给
 * [com.aliothmoon.maameow.presentation.viewmodel.AiControlViewModel.importChatConfig]。
 * 这样深链不依赖 AI 控制服务是否已启用（服务只在开关打开时启动）。
 */
class AiChatImportRelay {

    private val _request = MutableStateFlow<String?>(null)

    /** [consume] 置空后，同一条链接的下次导入仍会重新发射 */
    val request: StateFlow<String?> = _request.asStateFlow()

    fun offer(uri: String) {
        _request.value = uri
    }

    /** 仅主线程调用（Activity / Compose 副作用） */
    fun consume(): String? {
        val pending = _request.value
        _request.value = null
        return pending
    }
}
