package com.aliothmoon.maameow.ai

import com.aliothmoon.maameow.ai.jev.JevSystemOneClient
import com.aliothmoon.maameow.ai.plan.UiPlanExecutor
import com.aliothmoon.maameow.ai.plan.JevSettings
import com.aliothmoon.maameow.ai.adhoc.AdHocTaskNormalizer
import android.content.Context
import android.util.Base64
import com.aliothmoon.maameow.ai.mcp.ArknightsEntityValidator
import com.aliothmoon.maameow.ai.mcp.LogRedactor
import com.aliothmoon.maameow.ai.mcp.MaaStatusDto
import com.aliothmoon.maameow.ai.mcp.MaaToolBridge
import com.aliothmoon.maameow.ai.mcp.RunLaunchResultDto
import com.aliothmoon.maameow.ai.mcp.ScriptSummaryDto
import com.aliothmoon.maameow.ai.mcp.TaskItemStatusDto
import com.aliothmoon.maameow.ai.mcp.TaskTypeSpec
import com.aliothmoon.maameow.ai.mcp.WorkspaceFileDto
import com.aliothmoon.maameow.ai.workspace.AgentWorkspaceManager
import com.aliothmoon.maameow.data.config.MaaPathConfig
import com.aliothmoon.maameow.data.log.LogEntry
import com.aliothmoon.maameow.data.model.LogLevel
import com.aliothmoon.maameow.domain.state.MaaExecutionState
import com.aliothmoon.maameow.maa.callback.TaskRunStatus
import com.aliothmoon.maameow.data.model.AwardConfig
import com.aliothmoon.maameow.data.model.CopilotConfig
import com.aliothmoon.maameow.data.model.DepotMaintainConfig
import com.aliothmoon.maameow.data.model.FightConfig
import com.aliothmoon.maameow.data.model.InfrastConfig
import com.aliothmoon.maameow.data.model.MallConfig
import com.aliothmoon.maameow.data.model.OperProgressConfig
import com.aliothmoon.maameow.data.model.RecruitConfig
import com.aliothmoon.maameow.data.model.RoguelikeConfig
import com.aliothmoon.maameow.data.model.SwitchThemeConfig
import com.aliothmoon.maameow.data.model.TaskChainNode
import com.aliothmoon.maameow.data.model.TaskParamProvider
import com.aliothmoon.maameow.data.model.TaskProfile
import com.aliothmoon.maameow.data.model.TaskTypeInfo
import com.aliothmoon.maameow.data.model.UserDataUpdateConfig
import com.aliothmoon.maameow.data.model.WakeUpConfig
import com.aliothmoon.maameow.data.preferences.AppSettingsManager
import com.aliothmoon.maameow.data.preferences.TaskChainState
import com.aliothmoon.maameow.data.repository.CopilotRepository
import com.aliothmoon.maameow.data.repository.DepotRepository
import com.aliothmoon.maameow.data.repository.OperBoxRepository
import com.aliothmoon.maameow.data.resource.ItemHelper
import com.aliothmoon.maameow.data.resource.PermanentStages
import com.aliothmoon.maameow.data.resource.ResourceDataManager
import com.aliothmoon.maameow.data.resource.StageAliasMapper
import com.aliothmoon.maameow.domain.launch.StartTaskChainUseCase
import com.aliothmoon.maameow.domain.service.CopilotManager
import com.aliothmoon.maameow.domain.service.FrameSnapshotter
import com.aliothmoon.maameow.domain.service.MaaCompositionService
import com.aliothmoon.maameow.domain.service.MaaSessionLogger
import com.aliothmoon.maameow.domain.state.isIdle
import com.aliothmoon.maameow.domain.usecase.TaskStartContext
import com.aliothmoon.maameow.domain.usecase.TaskStartMode
import com.aliothmoon.maameow.maa.callback.TaskChainStatusTracker
import com.aliothmoon.maameow.maa.task.MaaTaskType
import com.aliothmoon.maameow.maa.task.MaaTaskParams
import com.aliothmoon.maameow.domain.service.OperBoxYituliuSync
import com.aliothmoon.maameow.utils.JsonUtils
import com.aliothmoon.maameow.manager.RemoteServiceManager
import com.aliothmoon.maameow.utils.i18n.resolve
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class AndroidMaaToolBridge(
    private val context: Context,
    private val compositionService: MaaCompositionService,
    private val statusTracker: TaskChainStatusTracker,
    private val taskChainState: TaskChainState,
    private val startTaskChainUseCase: StartTaskChainUseCase,
    private val sessionLogger: MaaSessionLogger,
    private val frameSnapshotter: FrameSnapshotter,
    private val copilotManager: CopilotManager,
    private val copilotRepository: CopilotRepository,
    private val resourceDataManager: ResourceDataManager,
    private val itemHelper: ItemHelper,
    private val pathConfig: MaaPathConfig,
    private val appSettings: AppSettingsManager,
    private val scriptRecorder: AiScriptRecorder,
    private val httpClient: OkHttpClient,
    private val operBoxRepository: OperBoxRepository,
    private val depotRepository: DepotRepository,
    private val operBoxYituliuSync: OperBoxYituliuSync,
    private val workspaceManager: AgentWorkspaceManager = AgentWorkspaceManager(context),
    private val jevClient: JevSystemOneClient? = null,
) : MaaToolBridge {
    private val resolvedJevClient: JevSystemOneClient by lazy {
        jevClient ?: JevSystemOneClient(httpClient)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = JsonUtils.common
    private val activeRunId = AtomicReference<String?>(null)
    private val dataRefreshMutex = Mutex()

    override val taskSpecs: List<TaskTypeSpec> by lazy {
        TaskTypeInfo.entries.map { info ->
            val defaultCfg = info.defaultConfig()
            val (className, serializer) = when (defaultCfg) {
                is WakeUpConfig -> "WakeUpConfig" to WakeUpConfig.serializer()
                is RecruitConfig -> "RecruitConfig" to RecruitConfig.serializer()
                is InfrastConfig -> "InfrastConfig" to InfrastConfig.serializer()
                is FightConfig -> "FightConfig" to FightConfig.serializer()
                is MallConfig -> "MallConfig" to MallConfig.serializer()
                is AwardConfig -> "AwardConfig" to AwardConfig.serializer()
                is RoguelikeConfig -> "RoguelikeConfig" to RoguelikeConfig.serializer()
                is UserDataUpdateConfig -> "UserDataUpdateConfig" to UserDataUpdateConfig.serializer()
                is DepotMaintainConfig -> "DepotMaintainConfig" to DepotMaintainConfig.serializer()
                is SwitchThemeConfig -> "SwitchThemeConfig" to SwitchThemeConfig.serializer()
                is OperProgressConfig -> "OperProgressConfig" to OperProgressConfig.serializer()
                else -> defaultCfg::class.simpleName.orEmpty() to TaskParamProvider.serializer()
            }
            TaskTypeSpec(
                typeId = info.name,
                configClassName = className,
                description = info.defaultName(context),
                serializer = serializer,
                defaultInstance = defaultCfg,
                inDefaultChain = info.inDefaultChain,
                enabledByDefault = info.enabledByDefault,
            )
        }
    }

    override val validator: ArknightsEntityValidator
        get() = buildValidatorSnapshot()

    private fun buildValidatorSnapshot(): ArknightsEntityValidator {
        val resourceDir = File(pathConfig.resourceDir)
        val snapshot = resourceDataManager.snapshot.value
        val items = itemHelper.items.value

        if (snapshot.characters.isEmpty() && resourceDir.isDirectory) {
            return ArknightsEntityValidator.fromResourceDir(resourceDir, taskSpecs)
        }

        val stages = mutableSetOf<String>()
        stages += ArknightsEntityValidator.PERMANENT_STAGE_CODES
        PermanentStages.STAGES.forEach { stages += it.code }
        snapshot.mapData.forEach { m ->
            m.code?.takeIf { it.isNotBlank() }?.let {
                stages += it
                stages += "$it-HARD"
            }
            m.stageId?.takeIf { it.isNotBlank() }?.let { stages += it }
            m.name?.takeIf { it.isNotBlank() }?.let { stages += it }
        }

        val operators = mutableSetOf<String>()
        operators += snapshot.characterNames
        snapshot.characters.values.forEach { c ->
            listOfNotNull(c.name, c.nameEn, c.nameJp, c.nameKr, c.nameTw)
                .filter { it.isNotBlank() }
                .forEach { operators += it }
        }

        val itemMap = if (items.isNotEmpty()) {
            items.mapValues { it.value.name }
        } else if (resourceDir.isDirectory) {
            return ArknightsEntityValidator.fromResourceDir(resourceDir, taskSpecs)
        } else {
            emptyMap()
        }

        val aliases = StageAliasMapper.getKnownAliases().toMap()
        val specMap = buildMap {
            for (spec in taskSpecs) {
                put(spec.typeId, spec)
                put(spec.typeId.uppercase(), spec)
                put(spec.configClassName, spec)
                put(spec.serializer.descriptor.serialName, spec)
            }
        }
        return ArknightsEntityValidator(
            validStages = stages,
            stageAliases = aliases,
            validOperators = operators,
            validItemIds = itemMap,
            taskSpecsByType = specMap,
        )
    }

    override suspend fun getStatus(): MaaStatusDto {
        val execState = compositionService.state.value
        val taskRuns = statusTracker.tasks.value
        val tasks = taskRuns.map { info ->
            TaskItemStatusDto(
                taskId = info.taskId,
                taskChain = info.taskChain,
                status = info.status.name,
                nodeId = info.slot?.nodeId,
                logName = info.logName?.resolve(context),
            )
        }

        val hasError = execState == MaaExecutionState.ERROR || taskRuns.any { it.status == TaskRunStatus.ERROR }
        val lastError = if (hasError) {
            sessionLogger.logs.value.lastOrNull { it.level == LogLevel.ERROR }?.content
                ?: sessionLogger.getLogFiles().firstOrNull()?.let { fileInfo ->
                    sessionLogger.readLogFile(fileInfo.fileName)
                        .filterIsInstance<LogEntry.Log>()
                        .lastOrNull { it.level == LogLevel.ERROR.name }
                        ?.content
                }
                ?: "Unknown execution error"
        } else null

        val handoverRequired = hasError
        val handoverReason = if (hasError) {
            "Task execution encountered an error: $lastError. Agent decision required (diagnose via maa_get_logs/maa_screenshot, modify profile or restart)."
        } else null

        return MaaStatusDto(
            state = execState.name,
            activeProfileId = taskChainState.profileId.value,
            clientType = taskChainState.clientType,
            currentRunId = activeRunId.get(),
            runDeadlineMs = compositionService.runDeadline.value,
            recordingActive = scriptRecorder.isRecording.value,
            tasks = tasks,
            lastError = lastError,
            handoverRequired = handoverRequired,
            handoverReason = handoverReason,
        )
    }

    override suspend fun listProfiles(): JsonArray {
        taskChainState.isLoaded.first { it }
        val activeId = taskChainState.profileId.value
        val activeChain = taskChainState.chain.value
        val profiles = taskChainState.profiles.value.map { p ->
            if (p.id == activeId) p.copy(chain = activeChain) else p
        }
        return json.encodeToJsonElement(profiles).jsonArray
    }

    override suspend fun getProfile(profileId: String): JsonObject? {
        taskChainState.isLoaded.first { it }
        val activeId = taskChainState.profileId.value
        val profile = taskChainState.profiles.value.find { it.id == profileId } ?: return null
        val upToDate = if (profile.id == activeId) profile.copy(chain = taskChainState.chain.value) else profile
        return json.encodeToJsonElement(upToDate).jsonObject
    }

    override suspend fun createProfile(name: String, chain: JsonArray): Result<JsonObject> = runCatching {
        val nodes = decodeChainNodes(chain)
        val created = taskChainState.saveOrUpdateCustomProfile(
            profileId = null,
            name = name,
            chain = nodes,
            switchToNew = false,
        )
        json.encodeToJsonElement(created).jsonObject
    }

    override suspend fun updateProfile(profileId: String, name: String?, chain: JsonArray?): Result<JsonObject> =
        runCatching {
            val existing = getProfile(profileId)
                ?: throw IllegalArgumentException("Profile not found: $profileId")
            val existingProfile = json.decodeFromJsonElement<TaskProfile>(existing)
            val newNodes = if (chain != null) decodeChainNodes(chain) else existingProfile.chain
            val updated = taskChainState.saveOrUpdateCustomProfile(
                profileId = profileId,
                name = name ?: existingProfile.name,
                chain = newNodes,
                switchToNew = false,
            )
            json.encodeToJsonElement(updated).jsonObject
        }

    override suspend fun deleteProfile(profileId: String): Result<Boolean> = runCatching {
        taskChainState.isLoaded.first { it }
        val countBefore = taskChainState.profiles.value.size
        if (countBefore <= 1) {
            throw IllegalStateException("Cannot delete the last remaining profile")
        }
        if (taskChainState.profiles.value.none { it.id == profileId }) {
            throw IllegalArgumentException("Profile not found: $profileId")
        }
        taskChainState.removeProfile(profileId)
        taskChainState.flush()
        true
    }

    override suspend fun runProfile(profileId: String?, force: Boolean): Result<RunLaunchResultDto> = runCatching {
        taskChainState.isLoaded.first { it }
        val targetId = profileId?.takeIf { it.isNotBlank() } ?: taskChainState.profileId.value
        val profileJson = getProfile(targetId)
            ?: throw IllegalArgumentException("Profile not found: $targetId")
        val profile = json.decodeFromJsonElement<TaskProfile>(profileJson)

        if (!compositionService.state.value.isIdle) {
            if (!force) {
                throw IllegalStateException("Task is currently ${compositionService.state.value}; pass force=true to preempt")
            }
            compositionService.stop(MaaCompositionService.StopOrigin.USER)
        }

        if (taskChainState.profileId.value != targetId) {
            taskChainState.switchProfile(targetId)
        }

        val runId = "run-${UUID.randomUUID().toString().take(8)}"
        activeRunId.set(runId)

        // Launch asynchronously so HTTP returns run_id immediately; status polled via maa_status
        scope.launch {
            startTaskChainUseCase(
                chain = profile.chain,
                context = TaskStartContext(mode = TaskStartMode.SCHEDULED),
                scheduleLabel = "AI MCP ($runId)",
            )
        }

        RunLaunchResultDto(
            started = true,
            runId = runId,
            profileId = targetId,
            message = "Profile '${profile.name}' launched asynchronously",
        )
    }

    override suspend fun executeAdHoc(tasks: JsonArray, force: Boolean): Result<RunLaunchResultDto> = runCatching {
        if (tasks.isEmpty()) {
            throw IllegalArgumentException("Task list cannot be empty")
        }

        val normalizer = AdHocTaskNormalizer(validator = validator)
        val nodes = normalizer.normalize(tasks)

        if (!compositionService.state.value.isIdle) {
            if (!force) {
                throw IllegalStateException("Task is currently ${compositionService.state.value}; pass force=true to preempt")
            }
            compositionService.stop(MaaCompositionService.StopOrigin.USER)
        }

        val runId = "adhoc-${UUID.randomUUID().toString().take(8)}"
        activeRunId.set(runId)

        scope.launch {
            startTaskChainUseCase(
                chain = nodes,
                context = TaskStartContext(mode = TaskStartMode.SCHEDULED),
                scheduleLabel = "AI Ad-Hoc ($runId)",
            )
        }

        RunLaunchResultDto(
            started = true,
            runId = runId,
            profileId = "adhoc",
            message = "Ad-hoc task chain with ${nodes.size} tasks launched asynchronously",
        )
    }

    override suspend fun stop(): Result<Boolean> = runCatching {
        val res = compositionService.stop(MaaCompositionService.StopOrigin.USER)
        res == MaaCompositionService.StopResult.Success
    }

    override suspend fun saveScript(
        name: String,
        fromRecordedOrLastRun: Boolean,
        chainOverride: JsonArray?,
    ): Result<ScriptSummaryDto> = runCatching {
        val nodes = if (chainOverride != null) {
            decodeChainNodes(chainOverride)
        } else {
            val snapshotProfile = scriptRecorder.buildProfileFromSnapshot(name)
                ?: throw IllegalStateException("No recorded session or recent run parameters available to save as script")
            snapshotProfile.chain
        }

        val saved = taskChainState.saveOrUpdateCustomProfile(
            profileId = null,
            name = name,
            chain = nodes,
            switchToNew = false,
        )
        ScriptSummaryDto(
            profileId = saved.id,
            name = saved.name,
            nodeCount = saved.chain.size,
            isRecordedDraft = false,
            chain = json.encodeToJsonElement(saved.chain).jsonArray,
        )
    }

    override suspend fun listScripts(): List<ScriptSummaryDto> {
        taskChainState.isLoaded.first { it }
        val activeId = taskChainState.profileId.value
        val activeChain = taskChainState.chain.value
        val list = mutableListOf<ScriptSummaryDto>()

        val draftNodes = scriptRecorder.recordedNodes.value
        if (draftNodes.isNotEmpty()) {
            list += ScriptSummaryDto(
                profileId = "draft-recording",
                name = "[录制草稿]",
                nodeCount = draftNodes.size,
                isRecordedDraft = true,
                chain = json.encodeToJsonElement(draftNodes).jsonArray,
            )
        }

        taskChainState.profiles.value.forEach { p ->
            val chain = if (p.id == activeId) activeChain else p.chain
            list += ScriptSummaryDto(
                profileId = p.id,
                name = p.name,
                nodeCount = chain.size,
                isRecordedDraft = false,
                chain = json.encodeToJsonElement(chain).jsonArray,
            )
        }
        return list
    }

    override suspend fun runScript(profileId: String, force: Boolean): Result<RunLaunchResultDto> =
        runProfile(profileId, force)

    override suspend fun getLogs(limit: Int, redactSensitive: Boolean): List<String> {
        val runtimeLogs = sessionLogger.logs.value
        val lines = if (runtimeLogs.isNotEmpty()) {
            runtimeLogs.takeLast(limit).map { item ->
                "[${item.formattedTime}] [${item.level.name}] ${item.content}"
            }
        } else {
            val latestFile = sessionLogger.getLogFiles().firstOrNull()
            if (latestFile != null) {
                sessionLogger.readLogFile(latestFile.fileName).takeLast(limit).map { entry ->
                    json.encodeToString(entry)
                }
            } else {
                emptyList()
            }
        }
        return if (redactSensitive) lines.map(LogRedactor::redact) else lines
    }

    override suspend fun captureScreenshotBase64(): Result<String> = runCatching {
        val jpegBytes = frameSnapshotter.captureJpeg()
            ?: throw IllegalStateException("No frame available (remote service not connected or game not running)")
        Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
    }

    override suspend fun loadAndRunCopilot(
        copilotInput: String,
        configJson: JsonObject?,
    ): Result<RunLaunchResultDto> = runCatching {
        val config = if (configJson != null) {
            json.decodeFromJsonElement<CopilotConfig>(configJson)
        } else {
            copilotRepository.loadConfig() ?: CopilotConfig()
        }

        val trimmed = copilotInput.trim()
        val filePath = if (trimmed.startsWith("{")) {
            copilotManager.parseJson(trimmed).getOrThrow()
            copilotRepository.saveCopilotJsonByName("ai_copilot.json", trimmed)
        } else {
            val (id, _, rawContent) = copilotManager.parseFromId(trimmed).getOrThrow()
            copilotRepository.saveCopilotJson(id, rawContent)
        }

        val corePath = copilotRepository.toCorePath(filePath)
        val taskParam = copilotManager.buildSingleTask(
            taskType = MaaTaskType.COPILOT,
            filePath = corePath,
            config = config,
        )

        val runId = "copilot-${UUID.randomUUID().toString().take(8)}"
        activeRunId.set(runId)

        scope.launch {
            compositionService.prepareResources(taskChainState.clientType)
            compositionService.startCopilot(listOf(taskParam), taskChainState.clientType)
        }

        RunLaunchResultDto(
            started = true,
            runId = runId,
            profileId = taskChainState.profileId.value,
            message = "Copilot task started asynchronously ($runId)",
        )
    }

    override suspend fun searchWiki(query: String, limit: Int, wiki: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) {
                return@runCatching "No search query provided."
            }
            val clampedLimit = limit.coerceIn(1, 10)
            val hasChinese = trimmed.any { it.code in 0x4E00..0x9FFF }
            val target = wiki.lowercase().trim()

            when (target) {
                "wiki_gg" -> executeWikiSearch(trimmed, clampedLimit, isPrts = false)
                "prts" -> executeWikiSearch(trimmed, clampedLimit, isPrts = true)
                else -> { // "auto"
                    if (!hasChinese) {
                        // Pure English / Latin -> query wiki.gg directly
                        executeWikiSearch(trimmed, clampedLimit, isPrts = false)
                    } else {
                        // Chinese -> try PRTS first, fallback to wiki.gg on timeout/failure
                        try {
                            executeWikiSearch(trimmed, clampedLimit, isPrts = true)
                        } catch (e: Exception) {
                            try {
                                val fallbackRes = executeWikiSearch(trimmed, clampedLimit, isPrts = false)
                                "[PRTS connection failed: ${e.message}; falling back to wiki.gg]\n\n$fallbackRes"
                            } catch (e2: Exception) {
                                throw RuntimeException("Failed to query PRTS Wiki (${e.message}) and fallback Arknights Terra Wiki (${e2.message})", e)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun executeWikiSearch(query: String, limit: Int, isPrts: Boolean): String {
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val apiEndpoint = if (isPrts) "https://prts.wiki/api.php" else "https://arknights.wiki.gg/api.php"
        val articlePrefix = if (isPrts) "https://prts.wiki/w/" else "https://arknights.wiki.gg/wiki/"
        val sourceHeader = if (isPrts) "[PRTS Wiki (中文)]" else "[Arknights Terra Wiki (wiki.gg)]"

        val url = "$apiEndpoint?action=query&list=search&srsearch=$encodedQuery&format=json&srlimit=$limit&utf8=1"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "MaaMeow-AI/1.0 (https://github.com/Aliothmoon/MAA-Meow)")
            .build()

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("HTTP ${response.code} ${response.message} from $apiEndpoint")
            }
            val bodyString = response.body.string()
            val jsonRoot = json.parseToJsonElement(bodyString).jsonObject
            val queryObj = jsonRoot["query"]?.jsonObject
            val searchHits = queryObj?.get("search")?.jsonArray.orEmpty()

            if (searchHits.isEmpty()) {
                return@use "$sourceHeader\nNo results found for \"$query\"."
            }

            val tagRegex = Regex("<[^>]+>")
            val sb = StringBuilder()
            sb.append(sourceHeader).append("\n\n")

            searchHits.forEachIndexed { index, hitElement ->
                val hitObj = hitElement.jsonObject
                val title = (hitObj["title"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val rawSnippet = (hitObj["snippet"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val cleanSnippet = rawSnippet.replace(tagRegex, "").replace("\n", " ").trim()
                val encodedTitle = URLEncoder.encode(title, StandardCharsets.UTF_8.name()).replace("+", "%20")
                val link = "$articlePrefix$encodedTitle"

                sb.append("[${index + 1}] **[$title]($link)**\n")
                if (cleanSnippet.isNotEmpty()) {
                    sb.append(cleanSnippet).append("\n")
                }
                sb.append("\n")
            }
            sb.toString().trimEnd()
        }
    }
    private suspend fun refreshUserData(category: String): JsonObject {
        require(category == "operators" || category == "depot" || category == "all") { "Unknown refresh category: $category" }
        check(dataRefreshMutex.tryLock()) { "A user-data update is already running" }
        // Admitted toolbox work survives cancellation of the chat wait, like other native tasks.
        // Do not issue a global stop here: it could stop an unrelated task.
        return scope.async {
            try {
                withTimeout(300_000L) {
                    taskChainState.isLoaded.first { it }
                    operBoxRepository.isLoaded.first { it }
                    depotRepository.isLoaded.first { it }
                    check(compositionService.state.value.isIdle) { "Another native task is active; finish it before updating data" }
                    val profileId = taskChainState.profileId.value
                    val wantsOperators = category != "depot"
                    val wantsDepot = category != "operators"
                    val beforeOperators = operBoxRepository.snapshot.value.syncTimeMillis
                    val beforeDepot = depotRepository.snapshot.value.syncTimeMillis
                    val useImport = wantsOperators && appSettings.operBoxUseYituliuApi.value
                    var skipped = 0
                    val warnings = mutableListOf<String>()
                    if (useImport) {
                        when (val result = operBoxYituliuSync.sync()) {
                            is OperBoxYituliuSync.Result.Success -> skipped = result.skipped
                            is OperBoxYituliuSync.Result.Failed -> warnings += result.message.resolve(context)
                        }
                    }
                    check(taskChainState.profileId.value == profileId) { "Profile changed during data update; result cannot be attributed safely" }
                    val nativeTasks = buildList {
                        if (wantsOperators && !useImport) add(MaaTaskParams(MaaTaskType.OPER_BOX, "{}"))
                        if (wantsDepot) add(MaaTaskParams(MaaTaskType.DEPOT, "{}"))
                    }
                    if (nativeTasks.isNotEmpty()) {
                        val started = compositionService.startCopilot(nativeTasks)
                        if (started is MaaCompositionService.StartResult.Success) {
                            activeRunId.set("refresh-${UUID.randomUUID().toString().take(8)}")
                            val terminal = compositionService.state.first { it.isIdle }
                            if (terminal == MaaExecutionState.ERROR) warnings += "Native recognition ended with an error"
                        } else {
                            warnings += "Native recognition did not start: ${started.javaClass.simpleName}"
                        }
                    }
                    check(taskChainState.profileId.value == profileId) { "Profile changed during data update; do not use the result for the previous profile" }
                    val operators = operBoxRepository.snapshot.value
                    val depot = depotRepository.snapshot.value
                    val operatorsUpdated = wantsOperators && operators.syncTimeMillis > beforeOperators && operators.owned.isNotEmpty()
                    val depotUpdated = wantsDepot && depot.syncTimeMillis > beforeDepot && depot.items.isNotEmpty()
                    val allUpdated = (!wantsOperators || operatorsUpdated) && (!wantsDepot || depotUpdated)
                    buildJsonObject {
                        put("requested", true)
                        put("profileId", profileId)
                        put("status", when {
                            allUpdated && skipped == 0 -> "snapshot_updated"
                            operatorsUpdated || depotUpdated -> "partial"
                            else -> "not_updated"
                        })
                        put("operatorsUpdated", operatorsUpdated)
                        put("depotUpdated", depotUpdated)
                        put("operatorsSource", if (useImport) "yituliu_download" else "native_recognition")
                        put("upstreamObservationTimeKnown", false)
                        put("upstreamOperatorsSkipped", skipped)
                        put("operatorSnapshotTimeMillis", operators.syncTimeMillis)
                        put("depotSnapshotTimeMillis", depot.syncTimeMillis)
                        put("warnings", buildJsonArray { warnings.forEach { add(it) } })
                    }
                }
            } catch (e: TimeoutCancellationException) {
                throw IllegalStateException("Timed out awaiting data update; native recognition may still be running. No refresh success is claimed.", e)
            } finally {
                dataRefreshMutex.unlock()
            }
        }.await()
    }

    override suspend fun getUserData(category: String, filter: String?, refresh: Boolean): Result<JsonObject> = withContext(Dispatchers.IO) {
        runCatching {
            val cat = category.trim().lowercase()
            val queryFilter = filter?.trim()?.takeIf { it.isNotEmpty() }
            taskChainState.isLoaded.first { it }
            operBoxRepository.isLoaded.first { it }
            depotRepository.isLoaded.first { it }
            val refreshReport = if (refresh) refreshUserData(cat) else null
            val profileId = taskChainState.profileId.value
            check(refreshReport == null || refreshReport["profileId"]?.jsonPrimitive?.content == profileId) {
                "Profile changed after update; read the selected profile again"
            }
            val readAtMillis = System.currentTimeMillis()

            buildJsonObject {
                put("source", "cached_snapshot")
                put("isLive", false)
                put("updateInProgress", dataRefreshMutex.isLocked)
                put("profileId", profileId)
                put("readAtMillis", readAtMillis)
                put("refresh", refreshReport ?: buildJsonObject {
                    put("requested", false)
                    put("status", "not_requested")
                })
                if (refresh && appSettings.aiScreenshotEnabled.value && compositionService.state.value.isIdle) {
                    val currentFrame = runCatching { frameSnapshotter.captureJpeg() }.getOrNull()
                    if (currentFrame != null) put("screenshot_base64", Base64.encodeToString(currentFrame, Base64.NO_WRAP))
                }
                if (cat == "operators" || cat == "all") {
                    val operSnapshot = operBoxRepository.snapshot.value
                    val filteredOwned = operSnapshot.owned.filter { op ->
                        if (queryFilter == null) true
                        else op.name.contains(queryFilter, ignoreCase = true) || op.id.contains(queryFilter, ignoreCase = true)
                    }

                    put("operators", buildJsonObject {
                        put("hasSynced", operSnapshot.hasSynced)
                        put("syncTimeMillis", operSnapshot.syncTimeMillis)
                        put("ageMillis", (readAtMillis - operSnapshot.syncTimeMillis).takeIf { operSnapshot.hasSynced && it >= 0L })
                        put("owned", buildJsonArray {
                            filteredOwned.forEach { op ->
                                add(buildJsonObject {
                                    put("name", op.name)
                                    put("id", op.id)
                                    put("rarity", op.rarity)
                                    put("elite", op.elite)
                                    put("level", op.level)
                                    put("potential", op.potential)
                                    put("skills", buildJsonArray {
                                        op.skills.forEach { sk ->
                                            add(buildJsonObject {
                                                put("id", sk.id)
                                                put("level", sk.level)
                                            })
                                        }
                                    })
                                    put("equips", buildJsonArray {
                                        op.equips.forEach { eq ->
                                            add(buildJsonObject {
                                                put("type", eq.type)
                                                put("level", eq.level)
                                            })
                                        }
                                    })
                                })
                            }
                        })
                    })
                }

                if (cat == "depot" || cat == "all") {
                    val depotSnapshot = depotRepository.snapshot.value
                    put("depot", buildJsonObject {
                        put("hasSynced", depotSnapshot.hasSynced)
                        put("syncTimeMillis", depotSnapshot.syncTimeMillis)
                        put("ageMillis", (readAtMillis - depotSnapshot.syncTimeMillis).takeIf { depotSnapshot.hasSynced && it >= 0L })
                        put("items", buildJsonObject {
                            depotSnapshot.items.forEach { (itemId, count) ->
                                val itemName = itemHelper.getItemInfo(itemId)?.name ?: itemId
                                if (queryFilter == null ||
                                    itemId.contains(queryFilter, ignoreCase = true) ||
                                    itemName.contains(queryFilter, ignoreCase = true)
                                ) {
                                    put(itemName, count)
                                }
                            }
                        })
                    })
                }
            }.also {
                check(taskChainState.profileId.value == profileId) { "Profile changed while reading cached data" }
            }
        }.onFailure { if (it is CancellationException) throw it }
    }

    override suspend fun getWikiArticle(title: String, wiki: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmedTitle = title.trim()
            if (trimmedTitle.isEmpty()) {
                throw IllegalArgumentException("Article title cannot be empty")
            }
            val hasChinese = trimmedTitle.any { it.code in 0x4E00..0x9FFF }
            val target = wiki.lowercase().trim()

            when (target) {
                "wiki_gg" -> executeGetWikiArticle(trimmedTitle, isPrts = false)
                "prts" -> executeGetWikiArticle(trimmedTitle, isPrts = true)
                else -> { // "auto"
                    if (!hasChinese) {
                        executeGetWikiArticle(trimmedTitle, isPrts = false)
                    } else {
                        try {
                            executeGetWikiArticle(trimmedTitle, isPrts = true)
                        } catch (e: Exception) {
                            try {
                                executeGetWikiArticle(trimmedTitle, isPrts = false)
                            } catch (e2: Exception) {
                                throw RuntimeException("Failed to fetch article from PRTS Wiki (${e.message}) and fallback Arknights Terra Wiki (${e2.message})", e)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun executeGetWikiArticle(title: String, isPrts: Boolean): String {
        val encodedTitle = URLEncoder.encode(title, StandardCharsets.UTF_8.name())
        val apiEndpoint = if (isPrts) "https://prts.wiki/api.php" else "https://arknights.wiki.gg/api.php"

        // Step 1: Try prop=extracts&explaintext=1
        val extractUrl = "$apiEndpoint?action=query&prop=extracts&explaintext=1&titles=$encodedTitle&format=json&utf8=1"
        val extractRequest = Request.Builder()
            .url(extractUrl)
            .header("User-Agent", "MaaMeow-AI/1.0 (https://github.com/Aliothmoon/MAA-Meow)")
            .build()

        var articleText: String? = null
        var resolvedTitle: String = title

        try {
            httpClient.newCall(extractRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val root = json.parseToJsonElement(body).jsonObject
                    val pages = root["query"]?.jsonObject?.get("pages")?.jsonObject
                    if (pages != null) {
                        for ((_, pageElem) in pages) {
                            val pageObj = pageElem.jsonObject
                            val pageTitle = (pageObj["title"] as? JsonPrimitive)?.contentOrNull
                            if (!pageTitle.isNullOrEmpty()) {
                                resolvedTitle = pageTitle
                            }
                            val extract = (pageObj["extract"] as? JsonPrimitive)?.contentOrNull?.trim()
                            if (!extract.isNullOrEmpty()) {
                                articleText = extract
                                break
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // Step 2: Fallback to action=parse&prop=wikitext
        if (articleText.isNullOrEmpty()) {
            val parseUrl = "$apiEndpoint?action=parse&page=$encodedTitle&prop=wikitext&format=json&utf8=1"
            val parseRequest = Request.Builder()
                .url(parseUrl)
                .header("User-Agent", "MaaMeow-AI/1.0 (https://github.com/Aliothmoon/MAA-Meow)")
                .build()

            httpClient.newCall(parseRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    throw RuntimeException("Wiki HTTP error: code ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                val root = json.parseToJsonElement(body).jsonObject
                val parseObj = root["parse"]?.jsonObject
                    ?: throw RuntimeException("Wiki article not found: '$title'")

                val pageTitle = (parseObj["title"] as? JsonPrimitive)?.contentOrNull
                if (!pageTitle.isNullOrEmpty()) {
                    resolvedTitle = pageTitle
                }
                val wikitextObj = parseObj["wikitext"]?.jsonObject
                val rawWikitext = (wikitextObj?.get("*") as? JsonPrimitive)?.contentOrNull?.trim()
                if (!rawWikitext.isNullOrEmpty()) {
                    // Strip basic templates/tags for clean text
                    articleText = rawWikitext
                } else {
                    throw RuntimeException("Empty article content for '$title'")
                }
            }
        }

        return "# $resolvedTitle\n\n${articleText.orEmpty().trim()}"
    }

    override suspend fun click(x: Int, y: Int): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            require(x in 0..1279) { "Click X coordinate ($x) is outside standard game bounds (0..1279)" }
            require(y in 0..719) { "Click Y coordinate ($y) is outside standard game bounds (0..719)" }
            val service = RemoteServiceManager.getInstanceOrNull()
                ?: throw IllegalStateException("Remote elevated service not connected")
            val maa = service.maaCoreService
                ?: throw IllegalStateException("MaaCoreService not available")
            check(maa.hasInstance()) { "MaaCore has no active instance; connect the game before clicking" }
            check(maa.AsyncClick(x, y, true) > 0) {
                "MaaCore rejected the click at ($x, $y)"
            }
            true
        }
    }

    suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int = 300): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            require(startX in 0..1279 && endX in 0..1279) { "Swipe X ($startX -> $endX) out of bounds (0..1279)" }
            require(startY in 0..719 && endY in 0..719) { "Swipe Y ($startY -> $endY) out of bounds (0..719)" }
            val service = RemoteServiceManager.getInstanceOrNull()
                ?: throw IllegalStateException("Remote elevated service not connected")
            check(service.swipeGame(startX, startY, endX, endY, durationMs)) {
                "Swipe gesture failed from ($startX, $startY) to ($endX, $endY)"
            }
            true
        }
    }

    override suspend fun backToHome(): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val service = RemoteServiceManager.getInstanceOrNull()
                ?: throw IllegalStateException("Remote elevated service not connected")
            val maa = service.maaCoreService
                ?: throw IllegalStateException("MaaCoreService not available")
            if (!maa.hasInstance()) {
                throw IllegalStateException("MaaCore has no active instance")
            }
            check(maa.BackToHome()) { "MaaCore could not return to the home screen" }
            true
        }
    }

    override suspend fun visualClick(instruction: String): Result<JsonObject> = withContext(Dispatchers.IO) {
        runCatching {
            check(appSettings.aiScreenshotEnabled.value) { "Enable 'Allow AI Screenshot' before visual actions" }
            check(!dataRefreshMutex.isLocked && compositionService.state.value.isIdle) { "Finish native tasks/data update before visual UI actions" }
            val startTime = System.currentTimeMillis()
            val timeStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date(startTime))
            val jpegBytes = frameSnapshotter.captureJpeg()
                ?: return@runCatching buildJsonObject {
                    put("success", false)
                    put("confidence", 0f)
                    put("message", "Cannot capture frame")
                    put("detail_logs", "[$timeStr] visualClick failed: Cannot capture frame from screen")
                }
            val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            val evalResult = resolvedJevClient.evaluate(
                baseUrl = appSettings.aiJevBaseUrl.value,
                apiKey = appSettings.aiJevApiKey.value,
                model = appSettings.aiJevModel.value.ifBlank { "jev-1.14" },
                stateText = instruction,
                stateImageBase64 = base64,
                boxInstruction = instruction,
            ).getOrThrow()

            val popupBox = evalResult.popupBox
            if (popupBox != null && popupBox.confidence >= 0.60f) {
                val (x, y) = popupBox.box.toScreenCoordinates()
                check(click(x, y).getOrThrow()) { "Click was not dispatched" }
                delay(600)
                val postActionFrame = runCatching { frameSnapshotter.captureJpeg() }.getOrNull()
                val log = "[$timeStr] visualClick success\n" +
                    "Target: $instruction\n" +
                    "Bounding Box: [${popupBox.box.ymin}, ${popupBox.box.xmin}, ${popupBox.box.ymax}, ${popupBox.box.xmax}]\n" +
                    "Screen Coordinates: ($x, $y)\n" +
                    "Confidence: ${popupBox.confidence}\n" +
                    "Latency: ${evalResult.latencyMs}ms"
                buildJsonObject {
                    put("success", true)
                    put("dispatched", true)
                    put("x", x)
                    put("y", y)
                    put("confidence", popupBox.confidence)
                    put("latency_ms", evalResult.latencyMs)
                    put("message", "Click dispatched for '$instruction' at ($x, $y); inspect the returned current frame before claiming the goal is complete.")
                    put("screenshot_phase", if (postActionFrame != null) "after_dispatch" else "unavailable")
                    if (postActionFrame != null) {
                        put("screenshot_base64", Base64.encodeToString(postActionFrame, Base64.NO_WRAP))
                    }
                    put("detail_logs", log)
                }
            } else {
                val conf = popupBox?.confidence ?: 0f
                val boxStr = popupBox?.box?.let { "[${it.ymin}, ${it.xmin}, ${it.ymax}, ${it.xmax}]" } ?: "null"
                val log = "[$timeStr] visualClick target not found\n" +
                    "Target: $instruction\n" +
                    "Bounding Box: $boxStr\n" +
                    "Confidence: $conf (threshold: 0.60)\n" +
                    "Latency: ${evalResult.latencyMs}ms"
                buildJsonObject {
                    put("success", false)
                    put("dispatched", false)
                    put("screenshot_phase", "no_action")
                    put("confidence", conf)
                    put("message", "Target '$instruction' not found with sufficient confidence; request LLM spot-check and replan")
                    put("screenshot_base64", base64)
                    put("detail_logs", log)
                }
            }
        }
    }

    override suspend fun executeUiPlan(plan: JsonObject): Result<JsonObject> = withContext(Dispatchers.IO) {
        if (dataRefreshMutex.isLocked || !compositionService.state.value.isIdle) {
            return@withContext Result.failure(IllegalStateException("Finish native tasks/data update before executing a UI plan"))
        }
        if (!appSettings.aiScreenshotEnabled.value) {
            return@withContext Result.failure(IllegalStateException("Enable 'Allow AI Screenshot' before UI plans"))
        }
        val executor = UiPlanExecutor(
            jevClient = resolvedJevClient,
            frameSnapshotter = frameSnapshotter,
            clickDispatcher = { x, y -> click(x, y) },
            swipeDispatcher = { sx, sy, ex, ey, dur -> swipe(sx, sy, ex, ey, dur) },
            cancelGestureDispatcher = {
                runCatching { RemoteServiceManager.getInstanceOrNull()?.touchCancel() }
            },
            jevSettings = {
                JevSettings(
                    baseUrl = appSettings.aiJevBaseUrl.value,
                    apiKey = appSettings.aiJevApiKey.value,
                    model = appSettings.aiJevModel.value.ifBlank { "jev-1.14" },
                )
            },
        )
        executor.execute(plan)
    }

    /**
     * Normalizes shorthand config `type` (e.g. `"COMBAT"` or `"FightConfig"`) into full
     * kotlinx.serialization polymorphic discriminator (`com.aliothmoon.maameow.data.model.FightConfig`)
     * before decoding into `List<TaskChainNode>`.
     */
    fun decodeChainNodes(chainArray: JsonArray): List<TaskChainNode> {
        val specMap = validator.taskSpecsByType
        return chainArray.mapIndexed { index, element ->
            val obj = element.jsonObject
            val configObj = obj["config"]?.jsonObject
                ?: throw IllegalArgumentException("Missing 'config' in chain[$index]")
            val rawType = (obj["task_type"] as? JsonPrimitive)?.contentOrNull
                ?: (configObj["type"] as? JsonPrimitive)?.contentOrNull
                ?: throw IllegalArgumentException("Missing 'config.type' in chain[$index]")

            val spec = specMap[rawType] ?: specMap[rawType.uppercase()]
                ?: throw IllegalArgumentException("Unknown task type '$rawType' at chain[$index]")

            val fullSerialName = spec.serializer.descriptor.serialName
            val normalizedConfigObj = buildJsonObject {
                configObj.forEach { (k, v) ->
                    if (k != "type") put(k, v)
                }
                put("type", fullSerialName)
            }

            val config = json.decodeFromJsonElement(TaskParamProvider.serializer(), normalizedConfigObj)
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString()
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: spec.description
            val enabled = (obj["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true
            val order = (obj["order"] as? JsonPrimitive)?.intOrNull ?: index

            TaskChainNode(
                id = id,
                name = name,
                enabled = enabled,
                order = order,
                config = config,
            )
        }
    }

    override suspend fun saveWorkspaceFile(
        name: String,
        content: String,
        description: String?,
    ): Result<WorkspaceFileDto> = runCatching {
        val file = workspaceManager.saveFile(name, content, description)
        WorkspaceFileDto(
            name = file.name,
            size = file.length(),
            updatedAt = file.lastModified(),
            description = description,
        )
    }

    override suspend fun readWorkspaceFile(name: String): Result<String> = runCatching {
        workspaceManager.readFile(name)
            ?: throw IllegalArgumentException("Workspace file '$name' not found")
    }

    override suspend fun listWorkspaceFiles(): Result<List<WorkspaceFileDto>> = runCatching {
        workspaceManager.listFiles().map {
            WorkspaceFileDto(
                name = it.name,
                size = it.size,
                updatedAt = it.updatedAt,
                description = it.description,
            )
        }
    }

    override suspend fun runWorkspaceScript(name: String, force: Boolean): Result<RunLaunchResultDto> = runCatching {
        val content = workspaceManager.readFile(name)
            ?: throw IllegalArgumentException("Workspace file '$name' not found")

        val normalizer = AdHocTaskNormalizer(validator = validator)
        val nodes = try {
            val element = json.parseToJsonElement(content)
            when (element) {
                is JsonArray -> normalizer.normalize(element)
                is JsonObject -> {
                    val tasks = element["tasks"] as? JsonArray
                        ?: throw IllegalArgumentException("Workspace script JSON object must contain 'tasks' array")
                    normalizer.normalize(tasks)
                }
                else -> throw IllegalArgumentException("Workspace script must be a JSON array or object with 'tasks'")
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to parse workspace script '$name': ${e.message}", e)
        }

        if (!compositionService.state.value.isIdle) {
            if (!force) {
                throw IllegalStateException("Task is currently ${compositionService.state.value}; pass force=true to preempt")
            }
            compositionService.stop(MaaCompositionService.StopOrigin.USER)
        }

        val runId = "ws-${UUID.randomUUID().toString().take(8)}"
        activeRunId.set(runId)

        scope.launch {
            startTaskChainUseCase(
                chain = nodes,
                context = TaskStartContext(mode = TaskStartMode.SCHEDULED),
                scheduleLabel = "AI Workspace Script ($name)",
            )
        }

        RunLaunchResultDto(
            started = true,
            runId = runId,
            profileId = name,
            message = "Workspace script '$name' launched asynchronously",
        )
    }

    override suspend fun deleteWorkspaceFile(name: String): Result<Boolean> = runCatching {
        workspaceManager.deleteFile(name)
    }
}
