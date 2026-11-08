package com.aliothmoon.maameow.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaaToolSpecTest {

    /** 变更工具集时必须同步改这里，避免系统提示词/客户端 tools[] 与 MCP 服务端漂移。 */
    private val expectedToolNames = listOf(
        "maa_status",
        "maa_list_task_types",
        "maa_list_profiles",
        "maa_get_profile",
        "maa_create_profile",
        "maa_update_profile",
        "maa_delete_profile",
        "maa_run_profile",
        "maa_stop",
        "maa_save_script",
        "maa_list_scripts",
        "maa_run_script",
        "maa_get_logs",
        "maa_screenshot",
        "maa_load_copilot",
        "maa_search_wiki",
        "maa_get_user_data",
        "maa_get_wiki_article",
        "maa_click",
        "maa_back_to_home",
    )

    @Test
    fun `toolSpecs exposes exactly the twenty known MCP tools`() {
        val specs = MaaMcpServerFactory.toolSpecs

        assertEquals(20, specs.size)
        assertEquals(expectedToolNames, specs.map { it.name })
        assertEquals("tool names must be unique", specs.size, specs.map { it.name }.toSet().size)
        assertTrue("every tool needs a description", specs.all { it.description.isNotBlank() })
    }

    @Test
    fun `every required parameter is declared in the property schema`() {
        for (spec in MaaMcpServerFactory.toolSpecs) {
            for (required in spec.required) {
                assertTrue(
                    "${spec.name} requires '$required' but the schema does not declare it",
                    spec.properties[required] != null,
                )
            }
        }
    }

    @Test
    fun `createServer registers exactly the tools from toolSpecs`() = runBlocking {
        val validator = ArknightsEntityValidator(
            validStages = emptySet(),
            stageAliases = emptyMap(),
            validOperators = emptySet(),
            validItemIds = emptyMap(),
            taskSpecsByType = emptyMap(),
        )
        val server = MaaMcpServerFactory.createServer(FakeMaaToolBridge(emptyList(), validator))

        val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
        server.createSession(serverTransport)
        val client = Client(clientInfo = Implementation(name = "spec-test-client", version = "1.0.0"))
        client.connect(clientTransport)

        val registered = client.listTools().tools
        assertEquals(
            MaaMcpServerFactory.toolSpecs.map { it.name },
            registered.map { it.name },
        )

        // 元数据必须逐字来自 spec，而不是 handler 里另写一份
        val byName = registered.associateBy { it.name }
        for (spec in MaaMcpServerFactory.toolSpecs) {
            val tool = byName.getValue(spec.name)
            assertEquals(spec.description, tool.description)
            assertEquals(
                spec.required.sorted(),
                tool.inputSchema.required.orEmpty().sorted(),
            )
            assertEquals(
                spec.properties["profile_id"]?.jsonObject?.get("type")?.jsonPrimitive?.content,
                tool.inputSchema.properties?.get("profile_id")?.jsonObject?.get("type")?.jsonPrimitive?.content,
            )
        }

        client.close()
    }
}
