package com.aliothmoon.maameow.ai.history

import com.aliothmoon.maameow.ai.AiChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AiSessionHistoryManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var manager: AiSessionHistoryManager

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("ai_history")
        manager = AiSessionHistoryManager(storageDir)
    }

    @Test
    fun `createNewSession sets default fields and persists to disk`() {
        val session = manager.createNewSession()

        assertNotNull(session.id)
        assertEquals("新对话", session.title)
        assertTrue(session.messages.isEmpty())
        assertNull(session.lastTaskSummary)
        assertTrue(session.createdAt > 0)
        assertEquals(session.createdAt, session.updatedAt)

        assertEquals(session.id, manager.getActiveSessionId())

        val sessions = manager.loadSessions()
        assertEquals(1, sessions.size)
        assertEquals(session.id, sessions[0].id)

        // Verify file was written to disk
        val sessionFile = File(storageDir, "sessions.json")
        assertTrue(sessionFile.exists())
        assertTrue(sessionFile.length() > 0)
    }

    @Test
    fun `createNewSession with custom title`() {
        val session = manager.createNewSession("日常刷图助手")
        assertEquals("日常刷图助手", session.title)
        assertEquals(session.id, manager.getActiveSessionId())
    }

    @Test
    fun `persistence reloads sessions across manager instances`() {
        val session1 = manager.createNewSession("会话1")
        val msg = AiChatMessage(id = "m1", isUser = true, content = "今天体力刷什么？")
        manager.saveSession(session1.copy(messages = listOf(msg)))

        val session2 = manager.createNewSession("会话2")

        // New manager instance pointing to the same directory
        val reloadedManager = AiSessionHistoryManager(storageDir)
        val loadedSessions = reloadedManager.loadSessions()

        assertEquals(2, loadedSessions.size)
        assertEquals(session2.id, reloadedManager.getActiveSessionId())

        val reloadedSession1 = loadedSessions.first { it.id == session1.id }
        assertEquals(1, reloadedSession1.messages.size)
        assertEquals("今天体力刷什么？", reloadedSession1.messages[0].content)
    }

    @Test
    fun `switchSession updates activeSessionId`() {
        val s1 = manager.createNewSession("S1")
        val s2 = manager.createNewSession("S2")

        assertEquals(s2.id, manager.getActiveSessionId())

        val switched = manager.switchSession(s1.id)
        assertNotNull(switched)
        assertEquals(s1.id, switched?.id)
        assertEquals(s1.id, manager.getActiveSessionId())

        // Non-existent session
        val nonExistent = manager.switchSession("not-found-id")
        assertNull(nonExistent)
        assertEquals(s1.id, manager.getActiveSessionId())
    }

    @Test
    fun `deleteSession removes session and adjusts activeSessionId`() {
        val s1 = manager.createNewSession("S1")
        val s2 = manager.createNewSession("S2")

        assertEquals(s2.id, manager.getActiveSessionId())

        manager.deleteSession(s2.id)

        val sessions = manager.loadSessions()
        assertEquals(1, sessions.size)
        assertEquals(s1.id, sessions[0].id)
        // Fallback to the remaining session
        assertEquals(s1.id, manager.getActiveSessionId())

        manager.deleteSession(s1.id)
        assertTrue(manager.loadSessions().isEmpty())
        assertNull(manager.getActiveSessionId())
    }

    @Test
    fun `clearAllSessions wipes everything`() {
        manager.createNewSession("S1")
        manager.createNewSession("S2")
        assertEquals(2, manager.loadSessions().size)

        manager.clearAllSessions()

        assertTrue(manager.loadSessions().isEmpty())
        assertNull(manager.getActiveSessionId())
    }

    @Test
    fun `auto-title helper extracts up to 16 characters from first user message`() {
        val session = manager.createNewSession("新对话")

        val longMessage = "帮我刷5次1-7关卡，然后去基建收菜"
        assertTrue(longMessage.length > 16)
        val expectedTitle = longMessage.substring(0, 16)

        val updated = session.copy(
            messages = listOf(
                AiChatMessage(id = "m1", isUser = true, content = longMessage),
            )
        )
        manager.saveSession(updated)

        val reloaded = manager.loadSessions().first { it.id == session.id }
        assertEquals(expectedTitle, reloaded.title)
    }

    @Test
    fun `auto-title does not truncate if message is 16 chars or shorter`() {
        val session = manager.createNewSession("新对话")
        val shortMessage = "刷一下钱本"

        val updated = session.copy(
            messages = listOf(
                AiChatMessage(id = "m1", isUser = true, content = shortMessage),
            )
        )
        manager.saveSession(updated)

        val reloaded = manager.loadSessions().first { it.id == session.id }
        assertEquals("刷一下钱本", reloaded.title)
    }

    @Test
    fun `auto-title does not overwrite user custom title`() {
        val customTitle = "我的专属配置"
        val session = manager.createNewSession(customTitle)

        val updated = session.copy(
            messages = listOf(
                AiChatMessage(id = "m1", isUser = true, content = "这是一条长消息这是一条长消息这是一条长消息"),
            )
        )
        manager.saveSession(updated)

        val reloaded = manager.loadSessions().first { it.id == session.id }
        assertEquals(customTitle, reloaded.title)
    }

    @Test
    fun `concurrent writes maintain data integrity and leave no temp files`() {
        val threadCount = 10
        val operationsPerThread = 15
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (op in 0 until operationsPerThread) {
                        val session = manager.createNewSession("Thread-$t-Op-$op")
                        manager.saveSession(
                            session.copy(
                                messages = listOf(
                                    AiChatMessage(id = "m-$t-$op", isUser = true, content = "Msg-$t-$op")
                                )
                            )
                        )
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        val sessions = manager.loadSessions()
        assertEquals(threadCount * operationsPerThread, sessions.size)

        // Ensure no leftover temp files in directory
        val tempFiles = storageDir.listFiles { _, name -> name.contains(".tmp") }
        assertTrue(tempFiles.isNullOrEmpty())
    }
}
