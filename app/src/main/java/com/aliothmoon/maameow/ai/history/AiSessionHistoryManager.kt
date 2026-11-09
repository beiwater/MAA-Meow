package com.aliothmoon.maameow.ai.history

import android.content.Context
import com.aliothmoon.maameow.ai.AiChatMessage
import com.aliothmoon.maameow.utils.JsonUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Persisted chat session representation for multi-session AI conversations.
 */
@Serializable
data class AiChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "新对话",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<AiChatMessage> = emptyList(),
    val lastTaskSummary: String? = null,
)

@Serializable
data class AiSessionStore(
    val activeSessionId: String? = null,
    val sessions: List<AiChatSession> = emptyList(),
)

/**
 * Thread-safe manager for AI conversation session history with atomic file persistence.
 *
 * Persists to `Context.filesDir -> ai_history/sessions.json`.
 */
class AiSessionHistoryManager(
    private val baseDir: File,
) {
    constructor(context: Context) : this(File(context.filesDir, "ai_history"))

    companion object {
        const val DEFAULT_TITLE = "新对话"
        const val MAX_AUTO_TITLE_LENGTH = 16
        const val SESSIONS_FILE_NAME = "sessions.json"

        /**
         * Extracts up to first 16 characters of the user's first non-blank message.
         */
        fun extractAutoTitle(messages: List<AiChatMessage>): String? {
            val userMsg = messages.firstOrNull { it.isUser && it.content.isNotBlank() } ?: return null
            val trimmed = userMsg.content.trim()
            return if (trimmed.length > MAX_AUTO_TITLE_LENGTH) {
                trimmed.substring(0, MAX_AUTO_TITLE_LENGTH)
            } else {
                trimmed
            }
        }
    }

    private val json = JsonUtils.common
    private val lock = Any()
    val sessionFile: File get() = File(baseDir, SESSIONS_FILE_NAME)

    fun extractAutoTitle(messages: List<AiChatMessage>): String? =
        Companion.extractAutoTitle(messages)

    fun loadSessions(): List<AiChatSession> = synchronized(lock) {
        readStore().sessions
    }

    fun getActiveSessionId(): String? = synchronized(lock) {
        val store = readStore()
        store.activeSessionId ?: store.sessions.firstOrNull()?.id
    }

    fun saveSession(session: AiChatSession): Unit = synchronized(lock) {
        val store = readStore()
        val shouldAutoTitle = session.title.isBlank() || session.title == DEFAULT_TITLE
        val effectiveTitle = if (shouldAutoTitle) {
            extractAutoTitle(session.messages) ?: session.title
        } else {
            session.title
        }

        val sessionToSave = session.copy(
            title = effectiveTitle,
            updatedAt = System.currentTimeMillis(),
        )

        val existingIndex = store.sessions.indexOfFirst { it.id == sessionToSave.id }
        val updatedSessions = if (existingIndex >= 0) {
            store.sessions.toMutableList().apply {
                set(existingIndex, sessionToSave)
            }
        } else {
            listOf(sessionToSave) + store.sessions
        }

        val activeId = store.activeSessionId ?: sessionToSave.id
        writeStore(store.copy(activeSessionId = activeId, sessions = updatedSessions))
    }

    fun createNewSession(initialTitle: String = DEFAULT_TITLE): AiChatSession = synchronized(lock) {
        val now = System.currentTimeMillis()
        val newSession = AiChatSession(
            id = UUID.randomUUID().toString(),
            title = initialTitle,
            createdAt = now,
            updatedAt = now,
            messages = emptyList(),
            lastTaskSummary = null,
        )
        val store = readStore()
        val updatedSessions = listOf(newSession) + store.sessions
        writeStore(store.copy(activeSessionId = newSession.id, sessions = updatedSessions))
        newSession
    }

    fun switchSession(sessionId: String): AiChatSession? = synchronized(lock) {
        val store = readStore()
        val target = store.sessions.find { it.id == sessionId } ?: return null
        writeStore(store.copy(activeSessionId = sessionId))
        target
    }

    fun deleteSession(sessionId: String): Unit = synchronized(lock) {
        val store = readStore()
        val updatedSessions = store.sessions.filterNot { it.id == sessionId }
        val updatedActiveId = if (store.activeSessionId == sessionId) {
            updatedSessions.firstOrNull()?.id
        } else {
            store.activeSessionId
        }
        writeStore(store.copy(activeSessionId = updatedActiveId, sessions = updatedSessions))
    }

    fun clearAllSessions(): Unit = synchronized(lock) {
        writeStore(AiSessionStore(activeSessionId = null, sessions = emptyList()))
    }

    private fun readStore(): AiSessionStore {
        val file = sessionFile
        if (!file.exists()) return AiSessionStore()
        return runCatching {
            val text = file.readText()
            if (text.isBlank()) return AiSessionStore()
            val element = json.parseToJsonElement(text)
            when (element) {
                is JsonObject -> json.decodeFromJsonElement(AiSessionStore.serializer(), element)
                is JsonArray -> {
                    val list = json.decodeFromJsonElement<List<AiChatSession>>(element)
                    AiSessionStore(activeSessionId = list.firstOrNull()?.id, sessions = list)
                }
                else -> AiSessionStore()
            }
        }.getOrElse {
            Timber.e(it, "Failed to load AI sessions from $file")
            AiSessionStore()
        }
    }

    private fun writeStore(store: AiSessionStore) {
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        val targetFile = sessionFile
        val tempFile = File(baseDir, "${SESSIONS_FILE_NAME}.tmp.${UUID.randomUUID().toString().take(8)}")
        try {
            val text = json.encodeToString(AiSessionStore.serializer(), store)
            tempFile.writeText(text)
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
    }
}
