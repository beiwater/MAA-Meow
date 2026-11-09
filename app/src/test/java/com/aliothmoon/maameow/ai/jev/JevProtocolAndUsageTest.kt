package com.aliothmoon.maameow.ai.jev

import com.aliothmoon.maameow.ai.usage.AiRequestUsage
import com.aliothmoon.maameow.ai.usage.AiUsageContext
import com.aliothmoon.maameow.utils.JsonUtils
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress

class JevProtocolAndUsageTest {
    private val response = """{
        "id":"generation-1","model":"typesafe/jev-1.14",
        "answers":{
            "popup_box":{"box":{"top":0.34,"left":0.16,"bottom":0.60,"right":1.0},"confidence":0.94},
            "target_present":{"noul":0.96},"condition":{"noul":0.01}
        },
        "usage":{"input_tokens":741,"output_tokens":38,"cost":3.1122e-5}
    }""".trimIndent()

    @Test
    fun `visible target does not imply postcondition success`() {
        val result = JevJsonHelper.parseDecisionResult(JsonUtils.common.parseToJsonElement(response).jsonObject, 5)
        assertEquals(0.96f, result.noulAnswers["target_present"]!!, 0.001f)
        assertEquals(0.01f, result.noulAnswers["condition"]!!, 0.001f)
        assertEquals(0.34f, result.popupBox!!.box.top, 0.001f)
    }

    @Test
    fun `missing box edges and invalid confidence cannot authorize a click`() {
        val body = """{"answers":{
            "popup_box":{"box":{"left":0.2,"bottom":0.6,"right":0.8},"confidence":1.5},
            "condition":{"noul":2.0}
        }}"""
        val result = JevJsonHelper.parseDecisionResult(JsonUtils.common.parseToJsonElement(body).jsonObject, 5)
        assertNull(result.popupBox)
        assertNull(result.noulAnswers["condition"])
    }

    @Test
    fun `request starts unknown then updates the same usage entry with actual billing`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/systemone") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val client = OkHttpClient()
        try {
            val usage = mutableListOf<AiRequestUsage>()
            withContext(AiUsageContext { usage += it }) {
                JevSystemOneClient(client).evaluate(
                    baseUrl = "http://127.0.0.1:${server.address.port}", apiKey = "", model = "typesafe/jev-1.14",
                    stateText = "Upgrade Kroos", boxInstruction = "Level Up",
                ).getOrThrow()
            }
            assertEquals(2, usage.size)
            assertEquals(usage.first().requestId, usage.last().requestId)
            assertNull(usage.first().inputTokens)
            assertNull(usage.first().reportedCostUsd)
            assertEquals(741L, usage.last().inputTokens)
            assertEquals(38L, usage.last().outputTokens)
            assertEquals("0.000031122", usage.last().reportedCostUsd)
            assertEquals("generation-1", usage.last().providerRequestId)
        } finally {
            server.stop(0)
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
