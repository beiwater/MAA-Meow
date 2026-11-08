package com.aliothmoon.maameow.ai

import com.aliothmoon.maameow.domain.models.AiChatProvider
import com.aliothmoon.maameow.utils.JsonUtils
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLDecoder

/**
 * 一次「导入链接 / 导入 JSON」的解析结果。
 *
 * 只做纯文本 → 配置字段的搬运，不写 DataStore、不带 Android 依赖，便于单测。
 * [Success.apiKey] 是明文凭据：任何日志、提示、界面都不得回显。
 */
sealed interface ChatConfigImportResult {

    data class Success(
        val provider: AiChatProvider,
        val baseUrl: String?,
        val apiKey: String,
        val model: String?,
        /** 识别到的来源（url / maameow / models.json），仅用于提示与排查 */
        val source: String,
    ) : ChatConfigImportResult

    /** [message] 是可直接展示给用户的说明，必定不含粘贴的密钥 */
    data class Failure(val reason: ImportFailure, val message: String) : ChatConfigImportResult
}

enum class ImportFailure {
    EMPTY,
    UNSUPPORTED_LINK,
    INVALID_JSON,
    NO_PROVIDER_ENTRY,
    OAUTH_CREDENTIAL,
    MISSING_KEY,
}

/**
 * 支持三种粘贴形态：
 *
 * 1. `https://host/v1#sk-xxx`、`https://host/v1#key=sk-xxx&model=m`、`https://host/v1 sk-xxx`、`https://host/v1|sk-xxx`
 * 2. `maameow://ai-import?baseUrl=<enc>&apiKey=<enc>&model=<enc>&provider=OPENAI|DIFY`
 * 3. pi 的 `models.json` 片段：`{"providers": {"<id>": {"baseUrl": …, "api": "openai-completions", "apiKey": …, "models": [{"id": …}]}}}`
 *
 * pi 的 `auth.json`（OAuth 订阅凭据）不接受：令牌需要定时刷新，APK 内无法完成。
 */
object ChatConfigImport {

    const val SCHEME = "maameow"
    const val HOST = "ai-import"

    /** `maameow://ai-import` 前缀，供深链与界面提示复用 */
    const val LINK_PREFIX = "$SCHEME://$HOST"

    const val SOURCE_URL = "url"
    const val SOURCE_LINK = "maameow"
    const val SOURCE_JSON = "models.json"

    /** 非 `sk-` 前缀的 Key 至少这么长才认作可用凭据 */
    private const val KEY_MIN_LENGTH = 16

    private const val OAUTH_MESSAGE =
        "订阅类 OAuth 凭据需要访问令牌定时刷新，APK 内无法完成，" +
            "请改用该服务的 OpenAI 兼容端点 + API Key（或 maameow://ai-import 链接）。"

    fun parse(input: String): ChatConfigImportResult {
        val text = unwrap(input)
        if (text.isEmpty()) {
            return failure(
                ImportFailure.EMPTY,
                "没有可导入的内容：请粘贴 maameow://ai-import 链接、Base URL + Key，或 pi 的 models.json 片段。",
            )
        }
        return when {
            text.startsWith("{") || text.startsWith("[") -> parseJson(text)
            text.startsWith("$SCHEME://", ignoreCase = true) -> parseMaameowLink(text)
            isHttpUrl(text) -> parseUrlPair(text)
            else -> failure(
                ImportFailure.UNSUPPORTED_LINK,
                "无法识别的导入内容：仅支持 maameow://ai-import 链接、" +
                    "https://host/v1#sk-… 形式的 Base URL + Key，或 pi models.json 片段。",
            )
        }
    }

    // ───────────────────────── 1. Base URL + Key ─────────────────────────

    private fun parseUrlPair(text: String): ChatConfigImportResult {
        val separator = text.indexOfFirst { it.isWhitespace() || it == '|' }
        val head = if (separator >= 0) text.substring(0, separator) else text
        // `url | key` 这种带空格的写法会把分隔符也带进来，先剥掉
        val tail = if (separator >= 0) text.substring(separator + 1).trim().trimStart('|').trim() else ""

        val rawUrl = head.trim()
        val fragment = rawUrl.substringAfter('#', "")
        val baseUrl = rawUrl.substringBefore('#').trim().trimEnd('/')

        if (!isHttpUrl(baseUrl) || !hasHost(baseUrl)) {
            return failure(
                ImportFailure.UNSUPPORTED_LINK,
                "Base URL 无法识别：需要形如 https://api.example.com/v1 的完整地址。",
            )
        }

        // `#key=…&model=…` 与 ` key=… ` 两种都兼容
        val params = LinkedHashMap<String, String>()
        params += parseKeyValuePairs(tail)
        params += parseKeyValuePairs(fragment)

        val apiKey = params["apikey"] ?: params["key"] ?: fragment.takeIf { it.isNotBlank() && '=' !in it }
            ?: tail.takeIf { it.isNotBlank() && '=' !in it }
        if (apiKey.isNullOrBlank()) {
            return failure(
                ImportFailure.MISSING_KEY,
                "链接里没有 API Key：请使用 https://host/v1#sk-xxx 或 https://host/v1 sk-xxx 的写法。",
            )
        }
        if (!looksLikeApiKey(apiKey)) {
            return failure(
                ImportFailure.MISSING_KEY,
                "识别到的 Key 不像 API Key：应以 sk- 开头，或长度不少于 $KEY_MIN_LENGTH 个字符。",
            )
        }

        return ChatConfigImportResult.Success(
            provider = AiChatProvider.OPENAI,
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = params["model"]?.takeIf { it.isNotBlank() },
            source = SOURCE_URL,
        )
    }

    // ───────────────────────── 2. maameow:// 导入链接 ─────────────────────────

    private fun parseMaameowLink(text: String): ChatConfigImportResult {
        if (!text.startsWith(LINK_PREFIX, ignoreCase = true)) {
            return failure(
                ImportFailure.UNSUPPORTED_LINK,
                "不支持的链接：目前只接受 maameow://ai-import?… 形式的导入链接。",
            )
        }
        val params = parseKeyValuePairs(text.substringAfter('?', ""), decode = true)

        val provider = AiChatProvider.parse(params["provider"] ?: AiChatProvider.OPENAI.name)
        val baseUrl = (params["baseurl"] ?: params["url"])
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { it.isNotBlank() }
        if (baseUrl != null && (!isHttpUrl(baseUrl) || !hasHost(baseUrl))) {
            return failure(
                ImportFailure.UNSUPPORTED_LINK,
                "导入链接里的 baseUrl 无法识别：需要形如 https://api.example.com/v1 的完整地址。",
            )
        }

        val apiKey = (params["apikey"] ?: params["key"])?.trim()
        if (apiKey.isNullOrEmpty()) {
            return failure(ImportFailure.MISSING_KEY, "导入链接缺少 apiKey 参数。")
        }

        return ChatConfigImportResult.Success(
            provider = provider,
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = params["model"]?.trim()?.takeIf { it.isNotBlank() },
            source = SOURCE_LINK,
        )
    }

    // ───────────────────────── 3. pi models.json ─────────────────────────

    private fun parseJson(text: String): ChatConfigImportResult {
        val root = runCatching { JsonUtils.common.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return failure(
                ImportFailure.INVALID_JSON,
                "JSON 解析失败：请确认粘贴的是完整的 pi models.json 片段。",
            )

        if (containsOAuthCredential(root)) {
            return failure(ImportFailure.OAUTH_CREDENTIAL, OAUTH_MESSAGE)
        }

        val providers = root["providers"] as? JsonObject
            ?: return failure(
                ImportFailure.NO_PROVIDER_ENTRY,
                "JSON 里没有 providers 字段，不是 pi 的 models.json。",
            )
        val entry = providers.entries.firstOrNull { (_, value) ->
            val obj = value as? JsonObject ?: return@firstOrNull false
            isOpenAiCompatibleApi(obj.string("api"))
        }?.value as? JsonObject
            ?: return failure(
                ImportFailure.NO_PROVIDER_ENTRY,
                "providers 里没有 OpenAI 兼容条目（api 需为 openai-completions 或 openai-responses）。",
            )

        val rawKey = entry.string("apiKey").orEmpty()
        if (rawKey.isBlank()) {
            return failure(ImportFailure.MISSING_KEY, "该 provider 没有可用的 apiKey。")
        }
        if (rawKey.startsWith("!") || '$' in rawKey) {
            return failure(
                ImportFailure.MISSING_KEY,
                "apiKey 使用了 \$ENV 或 !command 插值，APK 内无法展开，请改成真实 Key。",
            )
        }

        val baseUrl = (entry.string("baseUrl") ?: entry.string("base_url"))
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { it.isNotBlank() && isHttpUrl(it) }
        val model = (entry["models"] as? JsonArray)
            ?.firstOrNull()
            ?.let { it as? JsonObject }
            ?.string("id")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        return ChatConfigImportResult.Success(
            provider = AiChatProvider.OPENAI,
            baseUrl = baseUrl,
            apiKey = rawKey.trim(),
            model = model,
            source = SOURCE_JSON,
        )
    }

    private fun isOpenAiCompatibleApi(api: String?): Boolean {
        val value = api?.trim()?.lowercase().orEmpty()
        return value == "openai" || value.startsWith("openai-")
    }

    /** pi auth.json 或任意含访问令牌的对象：递归找 OAuth 字段，避免漏判嵌套结构 */
    private fun containsOAuthCredential(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.entries.any { (name, value) ->
            name in OAUTH_KEYS ||
                (name == "type" && (value as? JsonPrimitive)?.contentOrNull.equals("oauth", true)) ||
                containsOAuthCredential(value)
        }
        is JsonArray -> element.any { containsOAuthCredential(it) }
        else -> false
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    // ───────────────────────── 公共小工具 ─────────────────────────

    private val OAUTH_KEYS = setOf("access_token", "refresh_token", "expires_at")

    /** 去掉首尾空白与整体包裹的引号 / 反引号 */
    private fun unwrap(raw: String): String {
        var text = raw.trim()
        if (text.length >= 2) {
            val first = text.first()
            val last = text.last()
            val quoted = first == last && (first == '"' || first == '\'' || first == '`')
            if (quoted) text = text.substring(1, text.length - 1).trim()
        }
        return text
    }

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)

    private fun hasHost(url: String): Boolean {
        val rest = when {
            url.startsWith("https://", ignoreCase = true) -> url.substring(8)
            url.startsWith("http://", ignoreCase = true) -> url.substring(7)
            else -> return false
        }
        return rest.substringBefore('/').substringBefore('?').substringBefore('#').isNotBlank()
    }

    /** `sk-` 前缀直接认；否则长度够也算（部分兼容服务用自己的 Key 格式） */
    private fun looksLikeApiKey(key: String): Boolean =
        key.startsWith("sk-", ignoreCase = true) || key.length >= KEY_MIN_LENGTH

    /**
     * 解析 `a=1&b=2` 片段；键统一成小写去下划线（`api_key` / `apiKey` / `APIKEY` 同名）。
     * URL 里没有转义的 `+` 很常见，只有明确来自链接查询串时才做百分号解码，避免把 `+` 变成空格。
     */
    private fun parseKeyValuePairs(raw: String, decode: Boolean = false): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        raw.split('&').forEach { pair ->
            if (pair.isBlank()) return@forEach
            val index = pair.indexOf('=')
            val rawName = if (index >= 0) pair.substring(0, index) else pair
            val rawValue = if (index >= 0) pair.substring(index + 1) else ""
            val name = normalizeKey(if (decode) decodeValue(rawName) else rawName)
            val value = if (decode) decodeValue(rawValue) else rawValue
            if (name.isNotEmpty() && value.isNotEmpty()) result[name] = value
        }
        return result
    }

    private fun normalizeKey(raw: String): String =
        raw.trim().lowercase().replace("_", "").replace("-", "")

    private fun decodeValue(raw: String): String =
        runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)

    private fun failure(reason: ImportFailure, message: String) =
        ChatConfigImportResult.Failure(reason, message)
}
