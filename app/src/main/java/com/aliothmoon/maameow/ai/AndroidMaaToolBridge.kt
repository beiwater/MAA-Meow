package com.aliothmoon.maameow.ai

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
import com.aliothmoon.maameow.utils.JsonUtils
import com.aliothmoon.maameow.manager.RemoteServiceManager
import kotlinx.coroutines.delay
import com.aliothmoon.maameow.utils.i18n.resolve
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
) : MaaToolBridge {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = JsonUtils.common
    private val activeRunId = AtomicReference<String?>(null)

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
    override suspend fun getUserData(category: String, filter: String?): Result<JsonObject> = withContext(Dispatchers.IO) {
        runCatching {
            val cat = category.trim().lowercase()
            val queryFilter = filter?.trim()?.takeIf { it.isNotEmpty() }

            buildJsonObject {
                if (cat == "operators" || cat == "all") {
                    val operSnapshot = operBoxRepository.snapshot.value
                    val filteredOwned = operSnapshot.owned.filter { op ->
                        if (queryFilter == null) true
                        else op.name.contains(queryFilter, ignoreCase = true) || op.id.contains(queryFilter, ignoreCase = true)
                    }

                    put("operators", buildJsonObject {
                        put("hasSynced", operSnapshot.hasSynced)
                        put("syncTimeMillis", operSnapshot.syncTimeMillis)
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
            }
        }
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
            val clampedX = x.coerceIn(0, 1280)
            val clampedY = y.coerceIn(0, 720)
            val service = RemoteServiceManager.getInstanceOrNull()
                ?: throw IllegalStateException("Remote elevated service not connected")
            val maa = service.maaCoreService
            if (maa != null && maa.hasInstance()) {
                val res = maa.AsyncClick(clampedX, clampedY, true)
                res >= 0
            } else {
                // Fallback: inject touch via RemoteService if no core instance
                service.touchDown(clampedX, clampedY, 0)
                delay(50)
                service.touchUp(clampedX, clampedY, 0)
                true
            }
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
            maa.BackToHome()
        }
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
}
