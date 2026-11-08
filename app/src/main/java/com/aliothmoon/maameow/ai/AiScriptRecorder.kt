package com.aliothmoon.maameow.ai

import com.aliothmoon.maameow.data.model.AwardConfig
import com.aliothmoon.maameow.data.model.FightConfig
import com.aliothmoon.maameow.data.model.InfrastConfig
import com.aliothmoon.maameow.data.model.MallConfig
import com.aliothmoon.maameow.data.model.RecruitConfig
import com.aliothmoon.maameow.data.model.RoguelikeConfig
import com.aliothmoon.maameow.data.model.RoguelikeStartingOper
import com.aliothmoon.maameow.data.model.SwitchThemeConfig
import com.aliothmoon.maameow.data.model.TaskChainNode
import com.aliothmoon.maameow.data.model.TaskParamProvider
import com.aliothmoon.maameow.data.model.TaskProfile
import com.aliothmoon.maameow.data.model.UserDataUpdateConfig
import com.aliothmoon.maameow.data.model.WakeUpConfig
import com.aliothmoon.maameow.domain.enums.InfrastMode
import com.aliothmoon.maameow.domain.enums.InfrastRoomType
import com.aliothmoon.maameow.domain.enums.RoguelikeMode
import com.aliothmoon.maameow.maa.task.MaaTaskParams
import com.aliothmoon.maameow.maa.task.MaaTaskType
import com.aliothmoon.maameow.utils.JsonUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.util.UUID

/**
 * §4 Scripting:
 * - Captures effective `List<MaaTaskParams>` from runs (and recorded manual task chains) into a draft.
 * - Converts `List<MaaTaskParams>` -> `TaskProfile` using existing `TaskParamProvider` models & serializers.
 * - Preserves exact node configs when `TaskSlot.nodeId` matches the source `TaskChainNode` or reconstructs
 *   `TaskParamProvider` directly from `MaaTaskParams` JSON so `TaskProfile` round-trips cleanly with `ConfigBackupManager`.
 */
class AiScriptRecorder {

    private val json = JsonUtils.common

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordedNodes = MutableStateFlow<List<TaskChainNode>>(emptyList())
    val recordedNodes: StateFlow<List<TaskChainNode>> = _recordedNodes.asStateFlow()

    private val _lastEffectiveParams = MutableStateFlow<List<MaaTaskParams>>(emptyList())
    val lastEffectiveParams: StateFlow<List<MaaTaskParams>> = _lastEffectiveParams.asStateFlow()

    private val _lastEffectiveNodes = MutableStateFlow<List<TaskChainNode>>(emptyList())
    val lastEffectiveNodes: StateFlow<List<TaskChainNode>> = _lastEffectiveNodes.asStateFlow()

    fun startRecording() {
        _recordedNodes.value = emptyList()
        _isRecording.value = true
    }

    fun stopRecording(): List<TaskChainNode> {
        _isRecording.value = false
        return _recordedNodes.value
    }

    fun clearDraft() {
        _recordedNodes.value = emptyList()
    }

    /**
     * Called when a task chain or single task run starts with effective `List<MaaTaskParams>`.
     */
    fun onRunStarted(
        effectiveParams: List<MaaTaskParams>,
        sourceChain: List<TaskChainNode> = emptyList(),
    ) {
        _lastEffectiveParams.value = effectiveParams
        val converted = paramsToNodes(effectiveParams, sourceChain)
        _lastEffectiveNodes.value = converted
        if (_isRecording.value && converted.isNotEmpty()) {
            val merged = (_recordedNodes.value + converted).mapIndexed { index, node ->
                node.copy(order = index)
            }
            _recordedNodes.value = merged
        }
    }

    /**
     * Builds a `TaskProfile` from either the active recording draft or the last run's effective `List<MaaTaskParams>`.
     */
    fun buildProfileFromSnapshot(
        name: String,
        profileId: String = UUID.randomUUID().toString(),
    ): TaskProfile? {
        val nodes = _recordedNodes.value.ifEmpty { _lastEffectiveNodes.value }
        if (nodes.isEmpty()) return null
        return TaskProfile(
            id = profileId,
            name = name.trim().take(20).ifEmpty { "AI-录制脚本" },
            chain = nodes.mapIndexed { idx, n ->
                n.copy(id = UUID.randomUUID().toString(), enabled = true, order = idx)
            },
        )
    }

    /**
     * Converts a `List<MaaTaskParams>` (the actual parameters sent to `AsstAppendTask`)
     * into `List<TaskChainNode>` backed by `@Serializable` `TaskParamProvider` subtypes.
     */
    fun paramsToNodes(
        paramsList: List<MaaTaskParams>,
        sourceChain: List<TaskChainNode> = emptyList(),
    ): List<TaskChainNode> {
        if (paramsList.isEmpty()) return emptyList()
        val sourceById = sourceChain.associateBy { it.id }
        val result = mutableListOf<TaskChainNode>()
        val seenNodeIds = mutableSetOf<String>()

        for ((idx, param) in paramsList.withIndex()) {
            val nodeId = param.slot?.nodeId
            if (nodeId != null && sourceById.containsKey(nodeId)) {
                if (seenNodeIds.add(nodeId)) {
                    val srcNode = sourceById.getValue(nodeId)
                    // Overlay effective runtime params onto the source config so any dynamic overrides are captured
                    val updatedConfig = overlayEffectiveParams(srcNode.config, param)
                    result += srcNode.copy(
                        id = UUID.randomUUID().toString(),
                        enabled = true,
                        order = result.size,
                        config = updatedConfig,
                    )
                }
                continue
            }

            val config = paramToProvider(param) ?: continue
            result += TaskChainNode(
                id = UUID.randomUUID().toString(),
                name = defaultTitleForType(param.type),
                enabled = true,
                order = idx,
                config = config,
            )
        }
        return result.mapIndexed { i, node -> node.copy(order = i) }
    }

    private fun overlayEffectiveParams(base: TaskParamProvider, param: MaaTaskParams): TaskParamProvider {
        val obj = runCatching { json.parseToJsonElement(param.params) as? JsonObject }.getOrNull()
            ?: return base
        return when (base) {
            is WakeUpConfig -> base.copy(
                clientType = obj.str("client_type") ?: base.clientType,
                startGameEnabled = obj.bool("start_game_enabled") ?: base.startGameEnabled,
                accountName = obj.str("account_name") ?: base.accountName,
            )
            is FightConfig -> {
                val stage = obj.str("stage") ?: base.stage1
                val medicine = obj.int("medicine")
                val stone = obj.int("stone")
                val times = obj.int("times")
                base.copy(
                    stage1 = if (stage == "Annihilation") base.stage1 else stage,
                    useCustomAnnihilation = stage == "Annihilation" || base.useCustomAnnihilation,
                    useMedicine = if (medicine != null) medicine > 0 else base.useMedicine,
                    medicineNumber = medicine ?: base.medicineNumber,
                    useStone = if (stone != null) stone > 0 else base.useStone,
                    stoneNumber = stone ?: base.stoneNumber,
                    hasTimesLimited = if (times != null) times > 0 else base.hasTimesLimited,
                    maxTimes = times ?: base.maxTimes,
                )
            }
            else -> base
        }
    }

    fun paramToProvider(param: MaaTaskParams): TaskParamProvider? {
        val obj = runCatching { json.parseToJsonElement(param.params) as? JsonObject }.getOrNull()
            ?: JsonObject(emptyMap())
        return when (param.type) {
            MaaTaskType.START_UP -> WakeUpConfig(
                clientType = obj.str("client_type") ?: "Official",
                startGameEnabled = obj.bool("start_game_enabled") ?: true,
                accountName = obj.str("account_name") ?: "",
            )
            MaaTaskType.FIGHT -> {
                val stage = obj.str("stage").orEmpty()
                val medicine = obj.int("medicine")
                val stone = obj.int("stone")
                val times = obj.int("times")
                val series = obj.int("series") ?: 0
                val drGrandet = obj.bool("DrGrandet") ?: false
                val expiringMed = obj.int("expiring_medicine")
                val dropsObj = obj["drops"] as? JsonObject
                val firstDrop = dropsObj?.entries?.firstOrNull()
                FightConfig(
                    useMedicine = medicine != null && medicine > 0,
                    medicineNumber = medicine ?: 999,
                    useStone = stone != null && stone > 0,
                    stoneNumber = stone ?: 0,
                    hasTimesLimited = times != null && times > 0,
                    maxTimes = times ?: 5,
                    isSpecifiedDrops = firstDrop != null,
                    dropsItemId = firstDrop?.key.orEmpty(),
                    dropsQuantity = (firstDrop?.value as? JsonPrimitive)?.intOrNull ?: 5,
                    series = series,
                    stage1 = if (stage == "Annihilation") "" else stage,
                    useCustomAnnihilation = stage == "Annihilation",
                    annihilationStage = if (stage == "Annihilation") "Annihilation" else "Annihilation",
                    isDrGrandet = drGrandet,
                    useExpiringMedicine = expiringMed != null && expiringMed > 0,
                )
            }
            MaaTaskType.RECRUIT -> {
                val maxTimes = obj.int("max_times") ?: 4
                val refresh = obj.bool("refresh") ?: true
                val expedited = obj.bool("expedite") ?: false
                val selectList = (obj["select"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                    .orEmpty()
                RecruitConfig(
                    maxRecruitTimes = maxTimes,
                    useExpedited = expedited,
                    refreshLevel3 = refresh,
                    chooseLevel3 = 3 in selectList || selectList.isEmpty(),
                    chooseLevel4 = 4 in selectList || selectList.isEmpty(),
                    chooseLevel5 = 5 in selectList,
                )
            }
            MaaTaskType.INFRAST -> {
                val modeInt = obj.int("mode") ?: 0
                val mode = when (modeInt) {
                    10000 -> InfrastMode.Custom
                    20000 -> InfrastMode.Rotation
                    else -> InfrastMode.Normal
                }
                val facilityNames = (obj["facility"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?.toSet()
                val facilities = if (facilityNames != null) {
                    InfrastRoomType.values.map { it to (it.name in facilityNames) }
                } else {
                    InfrastRoomType.values.map { it to true }
                }
                val threshold = obj.double("threshold")?.let { (it * 100).toInt() } ?: 30
                InfrastConfig(
                    mode = mode,
                    facilities = facilities,
                    usesOfDrones = obj.str("drones") ?: "Money",
                    dormThreshold = threshold,
                    dormTrustEnabled = obj.bool("dorm_trust_enabled") ?: true,
                    dormFilterNotStationedEnabled = obj.bool("dorm_notstationed_enabled") ?: true,
                    originiumShardAutoReplenishment = obj.bool("replenish") ?: true,
                    continueTraining = obj.bool("continue_training") ?: false,
                )
            }
            MaaTaskType.MALL -> {
                val buyFirst = (obj["buy_first"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?: listOf("招聘许可", "龙门币")
                val blacklist = (obj["blacklist"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?: listOf("碳", "家具", "加急许可")
                MallConfig(
                    shopping = obj.bool("shopping") ?: true,
                    visitFriends = obj.bool("visit_friends") ?: false,
                    creditFight = obj.bool("credit_fight") ?: false,
                    creditFightFormation = obj.int("formation_index") ?: 0,
                    buyFirst = buyFirst,
                    blacklist = blacklist,
                    forceShoppingIfCreditFull = obj.bool("force_shopping_if_credit_full") ?: false,
                    onlyBuyDiscount = obj.bool("only_buy_discount") ?: false,
                    reserveMaxCredit = obj.bool("reserve_max_credit") ?: false,
                )
            }
            MaaTaskType.AWARD -> AwardConfig(
                award = obj.bool("award") ?: true,
                mail = obj.bool("mail") ?: false,
                freeGacha = obj.bool("recruit") ?: false,
                orundum = obj.bool("orundum") ?: false,
                mining = obj.bool("mining") ?: false,
                specialAccess = obj.bool("specialaccess") ?: false,
                signInEvent = obj.bool("signinevent") ?: false,
            )
            MaaTaskType.ROGUELIKE -> {
                val theme = obj.str("theme") ?: "Phantom"
                val modeInt = obj.int("mode") ?: 0
                val mode = RoguelikeMode.entries.firstOrNull { it.value == modeInt } ?: RoguelikeMode.Exp
                val coreChar = obj.str("core_char").orEmpty()
                val opers = if (coreChar.isNotBlank()) {
                    listOf(
                        RoguelikeStartingOper(
                            name = coreChar,
                            useSupport = obj.bool("use_support") ?: false,
                        ),
                    )
                } else {
                    emptyList()
                }
                RoguelikeConfig(
                    theme = theme,
                    mode = mode,
                    startsCount = obj.int("starts_count") ?: 999999,
                    investmentEnabled = obj.bool("investment_enabled") ?: true,
                    investCount = obj.int("investments_count") ?: 999,
                    stopWhenInvestmentFull = obj.bool("stop_when_investment_full") ?: false,
                    squad = obj.str("squad") ?: "",
                    roles = obj.str("roles") ?: "稳扎稳打",
                    startingOpers = opers,
                )
            }
            MaaTaskType.SWITCH_THEME -> {
                val themeId = obj.str("theme")
                SwitchThemeConfig(themes = listOfNotNull(themeId))
            }
            MaaTaskType.DEPOT -> UserDataUpdateConfig(updateOperBox = false, updateDepot = true)
            MaaTaskType.OPER_BOX -> UserDataUpdateConfig(updateOperBox = true, updateDepot = false)
            else -> null
        }
    }

    private fun defaultTitleForType(type: MaaTaskType): String = when (type) {
        MaaTaskType.START_UP -> "开始唤醒"
        MaaTaskType.FIGHT -> "刷理智"
        MaaTaskType.RECRUIT -> "自动公招"
        MaaTaskType.INFRAST -> "基建换班"
        MaaTaskType.MALL -> "获取信用及购物"
        MaaTaskType.AWARD -> "领取奖励"
        MaaTaskType.ROGUELIKE -> "自动肉鸽"
        MaaTaskType.SWITCH_THEME -> "切换主题"
        MaaTaskType.DEPOT, MaaTaskType.OPER_BOX -> "更新干员与仓库数据"
        else -> type.value
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
}
