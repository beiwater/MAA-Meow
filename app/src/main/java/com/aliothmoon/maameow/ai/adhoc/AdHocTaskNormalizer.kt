package com.aliothmoon.maameow.ai.adhoc

import com.aliothmoon.maameow.ai.mcp.ArknightsEntityValidator
import com.aliothmoon.maameow.data.model.AwardConfig
import com.aliothmoon.maameow.data.model.FightConfig
import com.aliothmoon.maameow.data.model.InfrastConfig
import com.aliothmoon.maameow.domain.enums.InfrastMode
import com.aliothmoon.maameow.data.model.MallConfig
import com.aliothmoon.maameow.data.model.ReclamationConfig
import com.aliothmoon.maameow.data.model.RecruitConfig
import com.aliothmoon.maameow.data.model.RoguelikeConfig
import com.aliothmoon.maameow.data.model.TaskChainNode
import com.aliothmoon.maameow.data.model.TaskProfile
import com.aliothmoon.maameow.data.model.WakeUpConfig
import com.aliothmoon.maameow.domain.enums.InfrastRoomType
import com.aliothmoon.maameow.utils.JsonUtils
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import java.util.UUID

/**
 * Normalizes simplified ad-hoc task specifications received from LLM tool calls
 * (`maa_execute_adhoc`) into executable [TaskChainNode] structures.
 *
 * Applies safety bounds:
 * - Capping sanity medicine at [MAX_SAFE_MEDICINE] (5).
 * - Prohibiting originite prime (碎石) spending unless explicitly allowed.
 * - Validating stage names against [ArknightsEntityValidator].
 */
class AdHocTaskNormalizer(
    private val validator: ArknightsEntityValidator? = null,
) {
    companion object {
        const val MAX_SAFE_MEDICINE = 5

        fun normalize(tasks: JsonArray, validator: ArknightsEntityValidator? = null): List<TaskChainNode> =
            AdHocTaskNormalizer(validator).normalize(tasks)

        fun normalize(tasksJson: String, validator: ArknightsEntityValidator? = null): List<TaskChainNode> =
            AdHocTaskNormalizer(validator).normalize(tasksJson)

        fun normalizeToProfile(
            tasks: JsonArray,
            name: String = "Ad-Hoc Task",
            validator: ArknightsEntityValidator? = null,
        ): TaskProfile = AdHocTaskNormalizer(validator).normalizeToProfile(tasks, name)

        fun normalizeToProfile(
            tasksJson: String,
            name: String = "Ad-Hoc Task",
            validator: ArknightsEntityValidator? = null,
        ): TaskProfile = AdHocTaskNormalizer(validator).normalizeToProfile(tasksJson, name)
    }

    fun normalize(tasksJson: String): List<TaskChainNode> {
        val element = JsonUtils.common.parseToJsonElement(tasksJson)
        val array = element as? JsonArray
            ?: throw IllegalArgumentException("Expected JSON array for tasks, got: ${element::class.simpleName}")
        return normalize(array)
    }

    fun normalizeToProfile(tasks: JsonArray, name: String = "Ad-Hoc Task"): TaskProfile {
        val nodes = normalize(tasks)
        return TaskProfile(
            id = "adhoc-${UUID.randomUUID().toString().take(8)}",
            name = name,
            chain = nodes,
        )
    }

    fun normalizeToProfile(tasksJson: String, name: String = "Ad-Hoc Task"): TaskProfile {
        val nodes = normalize(tasksJson)
        return TaskProfile(
            id = "adhoc-${UUID.randomUUID().toString().take(8)}",
            name = name,
            chain = nodes,
        )
    }

    fun normalize(tasks: JsonArray): List<TaskChainNode> {
        if (tasks.isEmpty()) {
            throw IllegalArgumentException("Task list cannot be empty")
        }

        return tasks.mapIndexed { index, element ->
            val obj = element as? JsonObject
                ?: throw IllegalArgumentException("Task at index $index must be a JSON object")
            val rawType = obj.stringOrNull("type")?.trim()
                ?: throw IllegalArgumentException("Task at index $index is missing 'type'")

            parseTaskNode(obj, rawType, index)
        }
    }

    private fun parseTaskNode(obj: JsonObject, rawType: String, index: Int): TaskChainNode {
        val normalizedType = rawType.uppercase()
        return when (normalizedType) {
            "FIGHT", "COMBAT", "BATTLE" -> normalizeFight(obj, index)
            "INFRAST", "BASE" -> normalizeInfrast(obj, index)
            "RECRUIT", "RECRUITING" -> normalizeRecruit(obj, index)
            "MALL", "SHOP" -> normalizeMall(obj, index)
            "AWARD", "MISSION" -> normalizeAward(obj, index)
            "ROGUELIKE", "AUTO_ROGUELIKE" -> normalizeRoguelike(obj, index)
            "RECLAMATION" -> normalizeReclamation(obj, index)
            "WAKEUP", "WAKE_UP", "STARTUP", "START_UP" -> normalizeWakeUp(obj, index)
            else -> throw IllegalArgumentException("Unsupported task type '$rawType' at index $index. Supported types: Fight, Infrast, Recruit, Mall, Award, Roguelike, Reclamation")
        }
    }

    private fun normalizeFight(obj: JsonObject, index: Int): TaskChainNode {
        val rawStage = obj.stringOrNull("stage") ?: obj.stringOrNull("stage1")
        val resolvedStage = if (!rawStage.isNullOrBlank()) {
            val trimmed = rawStage.trim()
            if (validator != null) {
                val err = validator.validateStage(trimmed, path = "tasks[$index].stage")
                if (err != null) {
                    throw IllegalArgumentException("Invalid stage '$trimmed' at index $index: ${err.reason}")
                }
                validator.resolveStageCode(trimmed) ?: trimmed
            } else {
                trimmed
            }
        } else {
            ""
        }

        val rawTimes = obj.intOrNull("times") ?: obj.intOrNull("max_times") ?: 1
        val times = rawTimes.coerceAtLeast(1)

        val rawMedicine = obj.intOrNull("medicine") ?: obj.intOrNull("use_medicine") ?: 0
        // Cap sanity medicine usage at MAX_SAFE_MEDICINE (5) for safety
        val cappedMedicine = rawMedicine.coerceIn(0, MAX_SAFE_MEDICINE)

        // Prohibit originite prime (originite stone / 碎石) spending unless explicitly specified/safe
        val allowStone = obj.booleanOrNull("allow_stone") == true
        val stoneNumber = if (allowStone) {
            (obj.intOrNull("stone") ?: obj.intOrNull("stone_number") ?: 0).coerceAtLeast(0)
        } else {
            0
        }
        val useStone = allowStone && stoneNumber > 0

        val config = FightConfig(
            stage1 = resolvedStage,
            hasTimesLimited = true,
            maxTimes = times,
            useMedicine = cappedMedicine > 0,
            medicineNumber = cappedMedicine,
            useStone = useStone,
            stoneNumber = stoneNumber,
        )

        val title = if (resolvedStage.isNotBlank()) "理智作战 - $resolvedStage" else "理智作战"
        return TaskChainNode(
            id = UUID.randomUUID().toString(),
            name = title,
            enabled = true,
            order = index,
            config = config,
        )
    }

    private fun normalizeInfrast(obj: JsonObject, index: Int): TaskChainNode {
        val mode = obj.stringOrNull("mode")?.trim()?.lowercase()
        val facilities = if (mode == "harvest") {
            // Harvest mode: only manufacturing & trade stations
            InfrastRoomType.values.map { room ->
                room to (room == InfrastRoomType.Mfg || room == InfrastRoomType.Trade)
            }
        } else {
            // Default shift_and_harvest: all facilities enabled
            InfrastRoomType.values.map { room -> room to true }
        }

        val config = InfrastConfig(
            facilities = facilities,
            mode = InfrastMode.Normal,
        )

        return TaskChainNode(
            id = UUID.randomUUID().toString(),
            name = "基建换班",
            enabled = true,
            order = index,
            config = config,
        )
    }

    private fun normalizeRecruit(obj: JsonObject, index: Int): TaskChainNode {
        val maxStar = obj.intOrNull("max_star") ?: 4
        val autoConfirm = obj.booleanOrNull("auto_confirm") ?: true
        val times = (obj.intOrNull("times") ?: obj.intOrNull("max_times") ?: 4).coerceAtLeast(1)

        val config = RecruitConfig(
            maxRecruitTimes = times,
            chooseLevel3 = maxStar >= 3,
            chooseLevel4 = maxStar >= 4,
            chooseLevel5 = maxStar >= 5,
            chooseLevel6 = maxStar >= 6,
            autoConfirm = autoConfirm,
        )

        return TaskChainNode(
            id = UUID.randomUUID().toString(),
            name = "自动公招",
            enabled = true,
            order = index,
            config = config,
        )
    }

    private fun normalizeMall(obj: JsonObject, index: Int): TaskChainNode {
        val buyFree = obj.booleanOrNull("buy_free")
        val config = when (buyFree) {
            true -> MallConfig(shopping = true, onlyBuyDiscount = true)
            false -> MallConfig(shopping = false)
            null -> MallConfig()
        }

        return TaskChainNode(
            id = UUID.randomUUID().toString(),
            name = "获取信用及购物",
            enabled = true,
            order = index,
            config = config,
        )
    }

    private fun normalizeAward(obj: JsonObject, index: Int): TaskChainNode {
        val config = AwardConfig(award = true, mail = true, freeGacha = true)
        return TaskChainNode(
            id = UUID.randomUUID().toString(),
            name = "领取奖励",
            enabled = true,
            order = index,
            config = config,
        )
    }

    private fun normalizeRoguelike(obj: JsonObject, index: Int): TaskChainNode {
        val config = RoguelikeConfig()
        return TaskChainNode(
            id = UUID.randomUUID().toString(),
            name = "自动肉鸽",
            enabled = true,
            order = index,
            config = config,
        )
    }

    private fun normalizeReclamation(obj: JsonObject, index: Int): TaskChainNode {
        val config = ReclamationConfig()
        return TaskChainNode(
            id = UUID.randomUUID().toString(),
            name = "生息演算",
            enabled = true,
            order = index,
            config = config,
        )
    }

    private fun normalizeWakeUp(obj: JsonObject, index: Int): TaskChainNode {
        val config = WakeUpConfig()
        return TaskChainNode(
            id = UUID.randomUUID().toString(),
            name = "开始唤醒",
            enabled = true,
            order = index,
            config = config,
        )
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.intOrNull(key: String): Int? =
        (get(key) as? JsonPrimitive)?.intOrNull

    private fun JsonObject.booleanOrNull(key: String): Boolean? =
        (get(key) as? JsonPrimitive)?.booleanOrNull
}
