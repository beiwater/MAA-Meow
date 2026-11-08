package com.aliothmoon.maameow.ai

import com.aliothmoon.maameow.ai.mcp.MaaMcpServerFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaaChatSystemPromptTest {

    private val specs = MaaMcpServerFactory.toolSpecs

    @Test
    fun `enabled prompt lists all twenty tools and forbids fabrication`() {
        val prompt = MaaChatSystemPrompt.build(
            toolSpecs = specs,
            aiControlEnabled = true,
            profileSummary = null,
        )

        for (spec in specs) {
            assertTrue("prompt must list ${spec.name}", prompt.contains(spec.name))
        }
        // 身份与「工具真的存在」的明确表述，防止模型自称看不到 tools
        assertTrue(prompt.contains("MaaMeow"))
        assertTrue(prompt.contains("已经"))
        assertTrue(prompt.contains("我看不到任何 tools"))
        // 反幻觉规则
        assertTrue(prompt.contains("不许编造"))
        // 每个工具都带一行可读签名
        assertTrue(prompt.contains("maa_run_profile()"))
        assertTrue(prompt.contains("maa_delete_profile(profile_id, confirm)"))
        // 长任务轮询 / 禁止手打
        assertTrue(prompt.contains("轮询"))
        assertTrue(prompt.contains("禁止用它手工打关"))
    }

    @Test
    fun `disabled prompt omits the tool list and states the limitation`() {
        val prompt = MaaChatSystemPrompt.build(
            toolSpecs = specs,
            aiControlEnabled = false,
            profileSummary = null,
        )

        assertTrue(prompt.contains("AI 控制当前已关闭"))
        for (spec in specs) {
            assertFalse("disabled prompt must not list ${spec.name}", prompt.contains(spec.name))
        }
    }

    @Test
    fun `profile summary is injected as grounding`() {
        val summary = "- 刷日常（当前激活）  profile_id=p1  任务: 开始唤醒 → 刷材料"

        val prompt = MaaChatSystemPrompt.build(
            toolSpecs = specs,
            aiControlEnabled = true,
            profileSummary = summary,
        )

        assertTrue(prompt.contains(summary))
        assertTrue(prompt.contains("配置"))
    }
}
