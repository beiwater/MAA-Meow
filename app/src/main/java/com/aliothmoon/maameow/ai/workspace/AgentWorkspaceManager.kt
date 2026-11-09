package com.aliothmoon.maameow.ai.workspace

import android.content.Context
import com.aliothmoon.maameow.utils.JsonUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

@Serializable
data class WorkspaceFileInfo(
    val name: String,
    val size: Long,
    val updatedAt: Long,
    val description: String? = null,
)

/**
 * Dedicated file and script workspace manager for the AI Agent.
 *
 * Persists custom automation workflows, ad-hoc JSON scripts, notes,
 * and visual macro sequences to `Context.filesDir/ai_workspace/`.
 * Supports atomic upsert (updating existing files without duplicate configuration creation).
 */
class AgentWorkspaceManager(
    private val baseDir: File,
) {
    constructor(context: Context) : this(File(context.filesDir, WORKSPACE_DIR_NAME))

    companion object {
        const val WORKSPACE_DIR_NAME = "ai_workspace"
        private const val META_FILE_NAME = "workspace_meta.json"
    }

    private val json = JsonUtils.common
    private val lock = Any()
    private val metaFile: File get() = File(baseDir, META_FILE_NAME)

    init {
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
    }

    /**
     * Atomically saves or updates a workspace file (Upsert).
     *
     * @param name File name (e.g. "daily_farm.json", "custom_macro.txt")
     * @param content File content text
     * @param description Optional description of the script/workflow
     */
    fun saveFile(name: String, content: String, description: String? = null): File = synchronized(lock) {
        val sanitizedName = sanitizeFileName(name)
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }

        val targetFile = File(baseDir, sanitizedName)
        val tempFile = File(baseDir, "$sanitizedName.tmp.${UUID.randomUUID().toString().take(8)}")

        try {
            tempFile.writeText(content)
            FileOutputStream(tempFile, true).use { fos ->
                fos.fd.sync()
            }
            try {
                Files.move(
                    tempFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (e: Exception) {
                try {
                    Files.move(
                        tempFile.toPath(),
                        targetFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (e2: Exception) {
                    if (!tempFile.renameTo(targetFile)) {
                        Files.copy(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        tempFile.delete()
                    }
                }
            }
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }

        if (description != null) {
            updateMeta(sanitizedName, description)
        }

        targetFile
    }

    /**
     * Reads the content of a workspace file by name.
     */
    fun readFile(name: String): String? = synchronized(lock) {
        val sanitizedName = sanitizeFileName(name)
        val file = File(baseDir, sanitizedName)
        if (!file.exists() || !file.isFile) return null
        runCatching { file.readText() }.getOrNull()
    }

    /**
     * Lists all user/agent files stored in the workspace with metadata.
     */
    fun listFiles(): List<WorkspaceFileInfo> = synchronized(lock) {
        if (!baseDir.exists()) return emptyList()
        val meta = readMeta()
        val files = baseDir.listFiles { f ->
            f.isFile && f.name != META_FILE_NAME && !f.name.contains(".tmp")
        } ?: return emptyList()

        files.map { file ->
            WorkspaceFileInfo(
                name = file.name,
                size = file.length(),
                updatedAt = file.lastModified(),
                description = meta[file.name],
            )
        }.sortedByDescending { it.updatedAt }
    }

    /**
     * Deletes a workspace file and removes its metadata.
     */
    fun deleteFile(name: String): Boolean = synchronized(lock) {
        val sanitizedName = sanitizeFileName(name)
        val file = File(baseDir, sanitizedName)
        val deleted = if (file.exists()) file.delete() else false
        removeMeta(sanitizedName)
        deleted
    }

    private fun sanitizeFileName(rawName: String): String {
        val trimmed = rawName.trim()
        require(trimmed.isNotBlank()) { "Workspace file name cannot be blank" }
        return File(trimmed).name
    }

    private fun readMeta(): Map<String, String> {
        if (!metaFile.exists()) return emptyMap()
        return runCatching {
            val text = metaFile.readText()
            if (text.isBlank()) return emptyMap()
            val obj = json.parseToJsonElement(text) as? JsonObject ?: return emptyMap()
            obj.entries.associate { (k, v) ->
                k to ((v as? JsonPrimitive)?.contentOrNull ?: "")
            }
        }.getOrElse {
            Timber.w(it, "Failed to read workspace metadata")
            emptyMap()
        }
    }

    private fun updateMeta(name: String, description: String) {
        val current = readMeta().toMutableMap()
        current[name] = description
        writeMeta(current)
    }

    private fun removeMeta(name: String) {
        val current = readMeta().toMutableMap()
        if (current.remove(name) != null) {
            writeMeta(current)
        }
    }

    private fun writeMeta(meta: Map<String, String>) {
        runCatching {
            val obj = buildJsonObject {
                meta.forEach { (k, v) -> put(k, v) }
            }
            val tempFile = File(baseDir, "$META_FILE_NAME.tmp")
            tempFile.writeText(obj.toString())
            try {
                Files.move(
                    tempFile.toPath(),
                    metaFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (e: Exception) {
                if (!tempFile.renameTo(metaFile)) {
                    Files.copy(tempFile.toPath(), metaFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    tempFile.delete()
                }
            }
        }.onFailure {
            Timber.w(it, "Failed to write workspace metadata")
        }
    }
}
