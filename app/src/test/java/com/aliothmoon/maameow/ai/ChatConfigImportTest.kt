package com.aliothmoon.maameow.ai

import com.aliothmoon.maameow.domain.models.AiChatProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖三种粘贴形态：URL + Key、`maameow://ai-import` 链接、pi `models.json`。
 * 夹具里的 Key 全部是形状正确的假值，仓库里不出现任何真实凭据。
 */
class ChatConfigImportTest {

    /** OpenRouter 风格的假 Key：前缀 `sk-` 正确即可，刻意不写成真实密钥形状 */
    private val fakeOpenRouterKey = "sk-fake-openrouter-test-only"

    private fun success(input: String): ChatConfigImportResult.Success =
        ChatConfigImport.parse(input) as? ChatConfigImportResult.Success
            ?: error("expected Success, got ${ChatConfigImport.parse(input)}")

    private fun failure(input: String): ChatConfigImportResult.Failure =
        ChatConfigImport.parse(input) as? ChatConfigImportResult.Failure
            ?: error("expected Failure, got ${ChatConfigImport.parse(input)}")

    @Test
    fun `URL with key fragment imports base url and key`() {
        val result = success("https://api.example.com/v1#sk-1234567890abcdef")

        assertEquals(AiChatProvider.OPENAI, result.provider)
        assertEquals("https://api.example.com/v1", result.baseUrl)
        assertEquals("sk-1234567890abcdef", result.apiKey)
        assertNull(result.model)
        assertEquals(ChatConfigImport.SOURCE_URL, result.source)
    }

    @Test
    fun `URL fragment with key and model params imports both`() {
        val result = success("https://api.example.com/v1#key=sk-1234567890abcdef&model=deepseek-chat")

        assertEquals("https://api.example.com/v1", result.baseUrl)
        assertEquals("sk-1234567890abcdef", result.apiKey)
        assertEquals("deepseek-chat", result.model)
    }

    @Test
    fun `URL and key separated by a space imports the pair`() {
        val result = success("https://api.example.com/v1  sk-1234567890abcdef")

        assertEquals("https://api.example.com/v1", result.baseUrl)
        assertEquals("sk-1234567890abcdef", result.apiKey)
    }

    @Test
    fun `URL and key separated by a pipe imports the pair`() {
        val result = success("https://api.example.com/v1 | sk-1234567890abcdef")

        assertEquals("https://api.example.com/v1", result.baseUrl)
        assertEquals("sk-1234567890abcdef", result.apiKey)
    }

    @Test
    fun `openrouter URL with key fragment imports generically without vendor branching`() {
        val result = success("https://openrouter.ai/api/v1#$fakeOpenRouterKey")

        assertEquals(AiChatProvider.OPENAI, result.provider)
        assertEquals("https://openrouter.ai/api/v1", result.baseUrl)
        assertEquals(fakeOpenRouterKey, result.apiKey)
        assertNull(result.model)
    }

    @Test
    fun `trailing slash on base url is normalized away`() {
        val result = success("https://openrouter.ai/api/v1/#$fakeOpenRouterKey")

        assertEquals("https://openrouter.ai/api/v1", result.baseUrl)
    }

    @Test
    fun `maameow link with encoded params imports provider base url key and model`() {
        val result = success(
            "maameow://ai-import?baseUrl=https%3A%2F%2Fopenrouter.ai%2Fapi%2Fv1" +
                "&apiKey=sk-or-v1-0000&model=meta%2Fmuse-spark-1.3-contributor",
        )

        assertEquals(AiChatProvider.OPENAI, result.provider)
        assertEquals("https://openrouter.ai/api/v1", result.baseUrl)
        assertEquals("sk-or-v1-0000", result.apiKey)
        assertEquals("meta/muse-spark-1.3-contributor", result.model)
        assertEquals(ChatConfigImport.SOURCE_LINK, result.source)
    }

    @Test
    fun `maameow link aliases base_url and key are accepted and provider selects dify`() {
        val result = success(
            "maameow://ai-import?base_url=http%3A%2F%2F127.0.0.1%2Fv1" +
                "&key=app-abcdefghijklmnop&provider=DIFY",
        )

        assertEquals(AiChatProvider.DIFY, result.provider)
        assertEquals("http://127.0.0.1/v1", result.baseUrl)
        assertEquals("app-abcdefghijklmnop", result.apiKey)
    }

    @Test
    fun `maameow link without apiKey fails with MISSING_KEY`() {
        val result = failure("maameow://ai-import?baseUrl=https%3A%2F%2Fapi.example.com%2Fv1")

        assertEquals(ImportFailure.MISSING_KEY, result.reason)
    }

    @Test
    fun `pi models json with openai-completions imports first compatible provider`() {
        val result = success(
            """
            {
              "providers": {
                "openrouter": {
                  "baseUrl": "https://openrouter.ai/api/v1",
                  "api": "openai-completions",
                  "apiKey": "$fakeOpenRouterKey",
                  "models": [{"id": "meta/muse-spark-1.3-contributor"}, {"id": "other-model"}]
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals(AiChatProvider.OPENAI, result.provider)
        assertEquals("https://openrouter.ai/api/v1", result.baseUrl)
        assertEquals(fakeOpenRouterKey, result.apiKey)
        assertEquals("meta/muse-spark-1.3-contributor", result.model)
        assertEquals(ChatConfigImport.SOURCE_JSON, result.source)
    }

    @Test
    fun `pi models json trims trailing slash on base url and keeps dots in model id`() {
        val result = success(
            """
            {"providers": {"gateway": {
              "baseUrl": "https://openrouter.ai/api/v1/",
              "api": "openai-responses",
              "apiKey": "sk-1234567890abcdef",
              "models": [{"id": "meta/muse-spark-1.3-contributor"}]
            }}}
            """.trimIndent(),
        )

        assertEquals("https://openrouter.ai/api/v1", result.baseUrl)
        assertEquals("meta/muse-spark-1.3-contributor", result.model)
    }

    @Test
    fun `pi models json with only an anthropic entry fails with NO_PROVIDER_ENTRY`() {
        val result = failure(
            """
            {"providers": {"anthropic": {
              "baseUrl": "https://api.anthropic.com/v1",
              "api": "anthropic-messages",
              "apiKey": "sk-ant-fake-key-0000",
              "models": [{"id": "claude-sonnet"}]
            }}}
            """.trimIndent(),
        )

        assertEquals(ImportFailure.NO_PROVIDER_ENTRY, result.reason)
        assertFalse(result.message.contains("sk-ant-fake-key-0000"))
    }

    @Test
    fun `pi auth json oauth blob fails with OAUTH_CREDENTIAL and never echoes tokens`() {
        val accessToken = "SECRET-ACCESS-TOKEN-VALUE"
        val refreshToken = "SECRET-REFRESH-TOKEN-VALUE"
        val input =
            """
            {"anthropic": {
              "type": "oauth",
              "access_token": "$accessToken",
              "refresh_token": "$refreshToken",
              "expires_at": 1893456000
            }}
            """.trimIndent()

        val result = failure(input)

        assertEquals(ImportFailure.OAUTH_CREDENTIAL, result.reason)
        assertFalse(result.message.contains(accessToken))
        assertFalse(result.message.contains(refreshToken))
        assertTrue(result.message.contains("API Key"))
    }

    @Test
    fun `env interpolated apiKey fails with MISSING_KEY`() {
        val result = failure(
            """
            {"providers": {"openrouter": {
              "baseUrl": "https://openrouter.ai/api/v1",
              "api": "openai-completions",
              "apiKey": "${'$'}OPENROUTER_API_KEY",
              "models": [{"id": "meta/muse-spark-1.3-contributor"}]
            }}}
            """.trimIndent(),
        )

        assertEquals(ImportFailure.MISSING_KEY, result.reason)
        assertFalse(result.message.contains("OPENROUTER_API_KEY"))
    }

    @Test
    fun `command interpolated apiKey fails with MISSING_KEY`() {
        val result = failure(
            """
            {"providers": {"openrouter": {
              "baseUrl": "https://openrouter.ai/api/v1",
              "api": "openai-completions",
              "apiKey": "!op read op://private/openrouter/key",
              "models": [{"id": "meta/muse-spark-1.3-contributor"}]
            }}}
            """.trimIndent(),
        )

        assertEquals(ImportFailure.MISSING_KEY, result.reason)
        assertFalse(result.message.contains("op://private/openrouter/key"))
    }

    @Test
    fun `blank input fails with EMPTY`() {
        assertEquals(ImportFailure.EMPTY, failure("").reason)
        assertEquals(ImportFailure.EMPTY, failure("   \n\t ").reason)
        assertEquals(ImportFailure.EMPTY, failure("\"\"").reason)
    }

    @Test
    fun `random text fails with UNSUPPORTED_LINK`() {
        assertEquals(ImportFailure.UNSUPPORTED_LINK, failure("hello world, no key here").reason)
        assertEquals(ImportFailure.UNSUPPORTED_LINK, failure("ftp://example.com/v1#sk-1234567890abcdef").reason)
    }

    @Test
    fun `malformed json fails with INVALID_JSON`() {
        assertEquals(ImportFailure.INVALID_JSON, failure("{\"providers\": ").reason)
    }

    @Test
    fun `URL without key fails with MISSING_KEY`() {
        assertEquals(ImportFailure.MISSING_KEY, failure("https://api.example.com/v1").reason)
    }

    @Test
    fun `URL with undersized non-sk key fails with MISSING_KEY and does not echo it`() {
        val result = failure("https://api.example.com/v1#short-key")

        assertEquals(ImportFailure.MISSING_KEY, result.reason)
        assertFalse(result.message.contains("short-key"))
    }
}
