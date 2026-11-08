package com.aliothmoon.maameow.ai.mcp

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class TaskTypeSpec(
    val typeId: String,
    val configClassName: String,
    val description: String,
    val serializer: KSerializer<*>,
    val defaultInstance: Any,
    val inDefaultChain: Boolean = true,
    val enabledByDefault: Boolean = false,
)

/**
 * Automatically generates JSON Schema from kotlinx.serialization [SerialDescriptor]
 * and default instances — zero hand-copied field definitions.
 */
@OptIn(ExperimentalSerializationApi::class)
object TaskSchemaGenerator {

    private val schemaJson = Json {
        encodeDefaults = true
        prettyPrint = false
    }

    fun generateCatalogJson(specs: List<TaskTypeSpec>): JsonObject = buildJsonObject {
        put("task_types", buildJsonArray {
            for (spec in specs) {
                add(generateTaskTypeSchema(spec))
            }
        })
    }

    @Suppress("UNCHECKED_CAST")
    fun generateTaskTypeSchema(spec: TaskTypeSpec): JsonObject {
        val serializer = spec.serializer as KSerializer<Any>
        val defaultJson = runCatching {
            schemaJson.encodeToJsonElement(serializer, spec.defaultInstance) as? JsonObject
        }.getOrNull()

        return buildJsonObject {
            put("type_id", spec.typeId)
            put("config_type", spec.serializer.descriptor.serialName)
            put("short_name", spec.configClassName)
            put("description", spec.description)
            put("in_default_chain", spec.inDefaultChain)
            put("enabled_by_default", spec.enabledByDefault)
            put("schema", descriptorToJsonSchema(spec.serializer.descriptor, defaultJson, mutableSetOf()))
            if (defaultJson != null) {
                put("default_config", defaultJson)
            }
        }
    }

    fun descriptorToJsonSchema(
        descriptor: SerialDescriptor,
        defaults: JsonObject? = null,
        visited: MutableSet<String> = mutableSetOf(),
    ): JsonObject = buildJsonObject {
        when (val kind = descriptor.kind) {
            PrimitiveKind.BOOLEAN -> put("type", "boolean")
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG ->
                put("type", "integer")
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> put("type", "number")
            PrimitiveKind.CHAR, PrimitiveKind.STRING -> put("type", "string")
            SerialKind.ENUM -> {
                put("type", "string")
                put("enum", buildJsonArray {
                    for (i in 0 until descriptor.elementsCount) {
                        add(JsonPrimitive(descriptor.getElementName(i)))
                    }
                })
            }
            StructureKind.LIST -> {
                put("type", "array")
                if (descriptor.elementsCount > 0) {
                    put("items", descriptorToJsonSchema(descriptor.getElementDescriptor(0), null, visited))
                }
            }
            StructureKind.MAP -> {
                put("type", "object")
                if (descriptor.elementsCount >= 2) {
                    put(
                        "additionalProperties",
                        descriptorToJsonSchema(descriptor.getElementDescriptor(1), null, visited),
                    )
                }
            }
            StructureKind.CLASS, StructureKind.OBJECT -> {
                put("type", "object")
                put("serialName", descriptor.serialName)
                if (visited.add(descriptor.serialName)) {
                    val props = buildJsonObject {
                        for (i in 0 until descriptor.elementsCount) {
                            val name = descriptor.getElementName(i)
                            val childDesc = descriptor.getElementDescriptor(i)
                            val childDefault = defaults?.get(name)
                            val baseSchema = descriptorToJsonSchema(
                                childDesc,
                                childDefault as? JsonObject,
                                visited.toMutableSet(),
                            )
                            val merged = buildJsonObject {
                                baseSchema.forEach { (k, v) -> put(k, v) }
                                if (childDesc.isNullable) {
                                    put("nullable", true)
                                }
                                if (childDefault != null) {
                                    put("default", childDefault)
                                }
                            }
                            put(name, merged)
                        }
                    }
                    put("properties", props)
                    val requiredFields = buildJsonArray {
                        for (i in 0 until descriptor.elementsCount) {
                            if (!descriptor.isElementOptional(i)) {
                                add(JsonPrimitive(descriptor.getElementName(i)))
                            }
                        }
                    }
                    put("required", requiredFields)
                    put("additionalProperties", false)
                }
            }
            is PolymorphicKind -> {
                put("type", "object")
                put("polymorphic", descriptor.serialName)
            }
            SerialKind.CONTEXTUAL -> {
                put("type", "object")
            }
        }
    }

    /**
     * Collects allowed field names for a given [SerialDescriptor].
     */
    fun allowedFields(descriptor: SerialDescriptor): Set<String> {
        val fields = mutableSetOf("type")
        for (i in 0 until descriptor.elementsCount) {
            fields += descriptor.getElementName(i)
        }
        return fields
    }
}
