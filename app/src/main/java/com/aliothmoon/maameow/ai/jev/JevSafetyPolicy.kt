package com.aliothmoon.maameow.ai.jev

/**
 * Jev System 1 工具安全执行策略。
 *
 * 只有在白名单 [SAFE_TOOLS] 中且不在黑名单 [IRREVERSIBLE_TOOLS] 中的工具
 * 才允许被 System 1 拦截并直接执行；其余工具必须回退给 System 2 (LLM)。
 */
object JevSafetyPolicy {
    val SAFE_TOOLS: Set<String> = setOf(
        "maa_status",
        "maa_list_profiles",
        "maa_get_profile",
        "maa_get_logs",
        "maa_search_wiki",
        "maa_get_user_data",
        "maa_get_wiki_article",
        "maa_run_profile",
        "maa_stop",
        "maa_back_to_home",
        "maa_execute_adhoc",
    )

    val IRREVERSIBLE_TOOLS: Set<String> = setOf(
        "maa_delete_profile",
    )

    fun isSafeToExecute(toolName: String): Boolean {
        return toolName in SAFE_TOOLS && toolName !in IRREVERSIBLE_TOOLS
    }
}
