package com.aliothmoon.maameow.ai.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import kotlin.math.min

@Serializable
data class ValidationError(
    val path: String,
    val invalidValue: String,
    val reason: String,
    val candidates: List<String> = emptyList(),
)

@Serializable
data class ValidationResult(
    val valid: Boolean,
    val errors: List<ValidationError> = emptyList(),
)

/**
 * Anti-hallucination gate: validates stage codes, operator names, item IDs,
 * task types, and config schema fields against stable IDs from MAA resource/ and TaskSchemaGenerator.
 * Rejects unknown entities/fields and returns closest valid candidates.
 */
class ArknightsEntityValidator(
    val validStages: Set<String>,
    val stageAliases: Map<String, String> = DEFAULT_STAGE_ALIASES,
    val validOperators: Set<String>,
    val validItemIds: Map<String, String>, // itemId -> itemName
    val taskSpecsByType: Map<String, TaskTypeSpec>, // keyed by typeId, shortName, and serialName
) {
    private val normalizedStages: Map<String, String> = buildMap {
        for (stage in validStages) {
            put(stage.uppercase(), stage)
        }
        for ((alias, target) in stageAliases) {
            put(alias.uppercase(), target)
        }
    }

    private val normalizedOperators: Map<String, String> = buildMap {
        for (op in validOperators) {
            put(op.lowercase(), op)
        }
    }

    private val itemIdsByName: Map<String, String> = buildMap {
        for ((id, name) in validItemIds) {
            put(name.lowercase(), id)
        }
    }

    fun validateStage(stageInput: String, path: String = "stage1"): ValidationError? {
        val trimmed = stageInput.trim()
        if (trimmed.isEmpty()) return null // Empty stage means default/current/last stage in MAA FightConfig
        val matched = normalizedStages[trimmed.uppercase()]
        if (matched != null) return null

        val candidates = suggestCandidates(trimmed, validStages + stageAliases.keys, maxSuggestions = 5)
        return ValidationError(
            path = path,
            invalidValue = stageInput,
            reason = "Unknown stage ID/code '$stageInput' (not found in PRTS/Arknights-Tile-Pos or permanent stages)",
            candidates = candidates,
        )
    }

    fun validateOperator(operatorInput: String, path: String = "operator"): ValidationError? {
        val trimmed = operatorInput.trim()
        if (trimmed.isEmpty()) return null
        if (normalizedOperators.containsKey(trimmed.lowercase())) return null

        val candidates = suggestCandidates(trimmed, validOperators, maxSuggestions = 5)
        return ValidationError(
            path = path,
            invalidValue = operatorInput,
            reason = "Unknown operator '$operatorInput' (not found in battle_data.json)",
            candidates = candidates,
        )
    }

    fun validateItemId(itemInput: String, path: String = "dropsItemId"): ValidationError? {
        val trimmed = itemInput.trim()
        if (trimmed.isEmpty()) return null
        if (validItemIds.containsKey(trimmed) || itemIdsByName.containsKey(trimmed.lowercase())) return null

        val candidates = suggestCandidates(
            trimmed,
            validItemIds.entries.map { "${it.key} (${it.value})" },
            maxSuggestions = 5,
        )
        return ValidationError(
            path = path,
            invalidValue = itemInput,
            reason = "Unknown item ID '$itemInput' (not found in item_index.json)",
            candidates = candidates,
        )
    }

    /**
     * Validates a full profile's `chain` array of nodes before deserializing or persisting.
     * Checks:
     * 1. Node fields (`id`, `name`, `enabled`, `order`, `config` / `type`)
     * 2. Task type / `config.type` hits a registered `TaskTypeSpec`
     * 3. Unknown fields inside `config` are rejected with candidate field suggestions
     * 4. Entity fields (`stage1`, `alternateStages`, `annihilationStage`, `dropsItemId`,
     *    `startingOpers`, `fiammettaTargets`, `plans`, `clientType`) hit stable IDs.
     */
    fun validateChainJson(chainArray: JsonArray): ValidationResult {
        val errors = mutableListOf<ValidationError>()
        val allowedNodeKeys = setOf("id", "name", "enabled", "order", "config", "task_type")

        chainArray.forEachIndexed { index, element ->
            val nodePath = "chain[$index]"
            val obj = element as? JsonObject
            if (obj == null) {
                errors += ValidationError(nodePath, element.toString(), "Chain node must be a JSON object")
                return@forEachIndexed
            }

            for (key in obj.keys) {
                if (key !in allowedNodeKeys) {
                    errors += ValidationError(
                        path = "$nodePath.$key",
                        invalidValue = key,
                        reason = "Unknown node field '$key'",
                        candidates = suggestCandidates(key, allowedNodeKeys),
                    )
                }
            }

            val configObj = obj["config"] as? JsonObject
            val explicitType = (obj["task_type"] as? JsonPrimitive)?.contentOrNull
                ?: (configObj?.get("type") as? JsonPrimitive)?.contentOrNull

            if (explicitType.isNullOrBlank()) {
                errors += ValidationError(
                    path = "$nodePath.config.type",
                    invalidValue = "",
                    reason = "Missing task config 'type' or 'task_type'",
                    candidates = taskSpecsByType.values.map { it.typeId }.distinct(),
                )
                return@forEachIndexed
            }

            val spec = taskSpecsByType[explicitType]
                ?: taskSpecsByType[explicitType.uppercase()]
            if (spec == null) {
                errors += ValidationError(
                    path = "$nodePath.config.type",
                    invalidValue = explicitType,
                    reason = "Unknown task type '$explicitType'",
                    candidates = suggestCandidates(
                        explicitType,
                        taskSpecsByType.values.flatMap { listOf(it.typeId, it.configClassName, it.serializer.descriptor.serialName) }.toSet(),
                    ),
                )
                return@forEachIndexed
            }

            if (configObj != null) {
                validateConfigObject(spec, configObj, "$nodePath.config", errors)
            }
        }

        return ValidationResult(valid = errors.isEmpty(), errors = errors)
    }

    fun validateConfigObject(
        spec: TaskTypeSpec,
        configObj: JsonObject,
        basePath: String,
        errors: MutableList<ValidationError>,
    ) {
        val allowed = TaskSchemaGenerator.allowedFields(spec.serializer.descriptor)
        for (key in configObj.keys) {
            if (key !in allowed) {
                errors += ValidationError(
                    path = "$basePath.$key",
                    invalidValue = key,
                    reason = "Unknown parameter field '$key' for ${spec.configClassName}",
                    candidates = suggestCandidates(key, allowed - "type"),
                )
            }
        }

        // Entity checks by config type
        when (spec.typeId) {
            "WAKE_UP" -> {
                val clientType = (configObj["clientType"] as? JsonPrimitive)?.contentOrNull
                if (!clientType.isNullOrBlank() && clientType !in VALID_CLIENT_TYPES) {
                    errors += ValidationError(
                        path = "$basePath.clientType",
                        invalidValue = clientType,
                        reason = "Unknown clientType '$clientType'",
                        candidates = VALID_CLIENT_TYPES.toList(),
                    )
                }
            }
            "COMBAT" -> {
                val stage1 = (configObj["stage1"] as? JsonPrimitive)?.contentOrNull
                if (!stage1.isNullOrBlank()) {
                    validateStage(stage1, "$basePath.stage1")?.let { errors += it }
                }
                val alternate = configObj["alternateStages"] as? JsonArray
                alternate?.forEachIndexed { idx, elem ->
                    val s = (elem as? JsonPrimitive)?.contentOrNull.orEmpty()
                    validateStage(s, "$basePath.alternateStages[$idx]")?.let { errors += it }
                }
                val dropsItemId = (configObj["dropsItemId"] as? JsonPrimitive)?.contentOrNull
                if (!dropsItemId.isNullOrBlank()) {
                    validateItemId(dropsItemId, "$basePath.dropsItemId")?.let { errors += it }
                }
            }
            "BASE" -> {
                val targets = configObj["fiammettaTargets"] as? JsonArray
                targets?.forEachIndexed { idx, elem ->
                    val op = (elem as? JsonPrimitive)?.contentOrNull.orEmpty()
                    validateOperator(op, "$basePath.fiammettaTargets[$idx]")?.let { errors += it }
                }
            }
            "AUTO_ROGUELIKE" -> {
                val theme = (configObj["theme"] as? JsonPrimitive)?.contentOrNull
                if (!theme.isNullOrBlank() && theme !in VALID_ROGUELIKE_THEMES) {
                    errors += ValidationError(
                        path = "$basePath.theme",
                        invalidValue = theme,
                        reason = "Unknown roguelike theme '$theme'",
                        candidates = VALID_ROGUELIKE_THEMES.toList(),
                    )
                }
                val startingOpers = configObj["startingOpers"] as? JsonArray
                startingOpers?.forEachIndexed { idx, elem ->
                    val opObj = elem as? JsonObject
                    val name = (opObj?.get("name") as? JsonPrimitive)?.contentOrNull.orEmpty()
                    validateOperator(name, "$basePath.startingOpers[$idx].name")?.let { errors += it }
                }
            }
            "OPER_PROGRESS" -> {
                val plans = configObj["plans"] as? JsonArray
                plans?.forEachIndexed { idx, elem ->
                    val planObj = elem as? JsonObject
                    val name = (planObj?.get("operatorName") as? JsonPrimitive)?.contentOrNull
                        ?: (planObj?.get("name") as? JsonPrimitive)?.contentOrNull
                    if (!name.isNullOrBlank()) {
                        validateOperator(name, "$basePath.plans[$idx].operatorName")?.let { errors += it }
                    }
                }
            }
            "DEPOT_MAINTAIN" -> {
                val plans = configObj["plans"] as? JsonArray
                plans?.forEachIndexed { idx, elem ->
                    val planObj = elem as? JsonObject
                    val itemId = (planObj?.get("itemId") as? JsonPrimitive)?.contentOrNull
                    if (!itemId.isNullOrBlank()) {
                        validateItemId(itemId, "$basePath.plans[$idx].itemId")?.let { errors += it }
                    }
                }
            }
        }
    }

    companion object {
        val VALID_CLIENT_TYPES = setOf("Official", "Bilibili", "YoStarEN", "YoStarJP", "YoStarKR", "txwy")
        val VALID_ROGUELIKE_THEMES = setOf("Phantom", "Mizuki", "Sami", "Sarkaz", "JieGarden", "BlackFlow")

        val PERMANENT_STAGE_CODES = setOf(
            "1-7", "R8-11", "12-17-HARD",
            "CE-1", "CE-2", "CE-3", "CE-4", "CE-5", "CE-6",
            "AP-1", "AP-2", "AP-3", "AP-4", "AP-5",
            "CA-1", "CA-2", "CA-3", "CA-4", "CA-5",
            "LS-1", "LS-2", "LS-3", "LS-4", "LS-5", "LS-6",
            "SK-1", "SK-2", "SK-3", "SK-4", "SK-5",
            "PR-A-1", "PR-A-2", "PR-B-1", "PR-B-2", "PR-C-1", "PR-C-2", "PR-D-1", "PR-D-2",
            "Annihilation",
        )

        val DEFAULT_STAGE_ALIASES = mapOf(
            "AN" to "Annihilation",
            "剿灭" to "Annihilation",
            "CE" to "CE-6",
            "龙门币" to "CE-6",
            "钱" to "CE-6",
            "LS" to "LS-6",
            "经验" to "LS-6",
            "狗粮" to "LS-6",
            "作战记录" to "LS-6",
            "CA" to "CA-5",
            "技能" to "CA-5",
            "技能书" to "CA-5",
            "AP" to "AP-5",
            "红票" to "AP-5",
            "采购凭证" to "AP-5",
            "SK" to "SK-5",
            "碳" to "SK-5",
            "炭" to "SK-5",
            "碳素" to "SK-5",
        )

        /**
         * Loads stable IDs directly from a deployed `MaaResource` directory
         * (`Arknights-Tile-Pos/overview.json`, `battle_data.json`, `item_index.json`).
         */
        fun fromResourceDir(
            resourceDir: File,
            taskSpecs: List<TaskTypeSpec>,
        ): ArknightsEntityValidator {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val stages = mutableSetOf<String>()
            stages += PERMANENT_STAGE_CODES

            val overviewFile = File(resourceDir, "Arknights-Tile-Pos/overview.json")
            if (overviewFile.isFile) {
                runCatching {
                    val root = json.parseToJsonElement(overviewFile.readText()) as? JsonObject
                    root?.values?.forEach { elem ->
                        val obj = elem as? JsonObject ?: return@forEach
                        (obj["code"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                            stages += it
                            stages += "$it-HARD"
                        }
                        (obj["stageId"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                            stages += it
                        }
                        (obj["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                            stages += it
                        }
                    }
                }
            }

            val operators = mutableSetOf<String>()
            val battleDataFile = File(resourceDir, "battle_data.json")
            if (battleDataFile.isFile) {
                runCatching {
                    val root = json.parseToJsonElement(battleDataFile.readText()) as? JsonObject
                    val chars = root?.get("chars") as? JsonObject
                    chars?.forEach { (id, elem) ->
                        if (!id.startsWith("char_")) return@forEach
                        val obj = elem as? JsonObject ?: return@forEach
                        for (field in listOf("name", "name_en", "name_jp", "name_kr", "name_tw")) {
                            (obj[field] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                                operators += it
                            }
                        }
                    }
                }
            }

            val items = mutableMapOf<String, String>()
            val itemIndexFile = File(resourceDir, "item_index.json")
            if (itemIndexFile.isFile) {
                runCatching {
                    val root = json.parseToJsonElement(itemIndexFile.readText()) as? JsonObject
                    root?.forEach { (id, elem) ->
                        val obj = elem as? JsonObject ?: return@forEach
                        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: id
                        items[id] = name
                    }
                }
            }

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
                validOperators = operators,
                validItemIds = items,
                taskSpecsByType = specMap,
            )
        }

        fun suggestCandidates(
            input: String,
            pool: Collection<String>,
            maxSuggestions: Int = 5,
        ): List<String> {
            if (pool.isEmpty()) return emptyList()
            val q = input.trim().lowercase()
            val substringMatches = pool.filter {
                val cand = it.lowercase()
                cand.contains(q) || q.contains(cand)
            }
            if (substringMatches.isNotEmpty()) {
                return substringMatches
                    .sortedBy { kotlin.math.abs(it.length - q.length) }
                    .take(maxSuggestions)
            }
            return pool.asSequence()
                .map { it to levenshtein(q, it.lowercase()) }
                .sortedBy { it.second }
                .take(maxSuggestions)
                .map { it.first }
                .toList()
        }

        private fun levenshtein(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            val prev = IntArray(b.length + 1) { it }
            val curr = IntArray(b.length + 1)
            for (i in a.indices) {
                curr[0] = i + 1
                for (j in b.indices) {
                    val cost = if (a[i] == b[j]) 0 else 1
                    curr[j + 1] = min(
                        min(curr[j] + 1, prev[j + 1] + 1),
                        prev[j] + cost,
                    )
                }
                System.arraycopy(curr, 0, prev, 0, prev.size)
            }
            return prev[b.length]
        }
    }
}
