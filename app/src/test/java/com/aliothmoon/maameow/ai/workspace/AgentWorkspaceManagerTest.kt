package com.aliothmoon.maameow.ai.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentWorkspaceManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var workspaceDir: File
    private lateinit var manager: AgentWorkspaceManager

    @Before
    fun setUp() {
        workspaceDir = tempFolder.newFolder("ai_workspace")
        manager = AgentWorkspaceManager(workspaceDir)
    }

    @Test
    fun `saveFile creates file and writes content atomically`() {
        val file = manager.saveFile(
            name = "daily_farm.json",
            content = """[{"type":"Fight","stage":"1-7","times":5}]""",
            description = "刷1-7日常脚本",
        )

        assertTrue(file.exists())
        assertEquals("daily_farm.json", file.name)
        val readBack = manager.readFile("daily_farm.json")
        assertEquals("""[{"type":"Fight","stage":"1-7","times":5}]""", readBack)

        // Ensure no leftover temp files
        val tempFiles = workspaceDir.listFiles { _, name -> name.contains(".tmp") }
        assertTrue(tempFiles.isNullOrEmpty())
    }

    @Test
    fun `saveFile upserts existing file without creating duplicates`() {
        manager.saveFile("workflow.txt", "v1 content", "版本1")
        assertEquals("v1 content", manager.readFile("workflow.txt"))

        manager.saveFile("workflow.txt", "v2 updated content", "版本2更新")
        assertEquals("v2 updated content", manager.readFile("workflow.txt"))

        val files = manager.listFiles()
        assertEquals(1, files.size)
        assertEquals("workflow.txt", files[0].name)
        assertEquals("版本2更新", files[0].description)
    }

    @Test
    fun `readFile returns null for non-existent file`() {
        assertNull(manager.readFile("does_not_exist.json"))
    }

    @Test
    fun `listFiles returns all files with metadata sorted by updatedAt descending`() {
        manager.saveFile("script_a.json", "content A", "Script A")
        Thread.sleep(20)
        manager.saveFile("script_b.json", "content B", "Script B")

        val list = manager.listFiles()
        assertEquals(2, list.size)
        assertEquals("script_b.json", list[0].name)
        assertEquals("Script B", list[0].description)
        assertEquals("script_a.json", list[1].name)
        assertEquals("Script A", list[1].description)
    }

    @Test
    fun `deleteFile removes file and metadata`() {
        manager.saveFile("to_delete.json", "delete me", "待删除")
        assertEquals(1, manager.listFiles().size)

        val deleted = manager.deleteFile("to_delete.json")
        assertTrue(deleted)
        assertNull(manager.readFile("to_delete.json"))
        assertTrue(manager.listFiles().isEmpty())

        // Deleting non-existent file returns false
        assertFalse(manager.deleteFile("to_delete.json"))
    }

    @Test
    fun `saveFile rejects blank file names`() {
        assertThrows(IllegalArgumentException::class.java) {
            manager.saveFile("   ", "content")
        }
    }
}
