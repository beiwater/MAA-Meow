package com.aliothmoon.maameow.data.preferences

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import timber.log.Timber

/**
 * 修正 `ai_control_enabled` 的历史存储类型。
 *
 * 早期构建把该开关按 Boolean 写进 app_settings，而 [com.aliothmoon.maameow.domain.models.AppSettingsSchema]
 * 现在用 `stringPreferencesKey` 读取；`Preferences.get` 是 unchecked cast，读到 Boolean 会在
 * 启动收集设置时抛 `ClassCastException: java.lang.Boolean cannot be cast to java.lang.String`。
 *
 * 迁移只改写存储类型（"true" / "false"），不改语义；[shouldMigrate] 每次都检查现存值，
 * 因此外部工具再次写坏同一条记录也能自愈。
 */
internal object AiControlEnabledTypeMigration : DataMigration<Preferences> {

    private val key = stringPreferencesKey("ai_control_enabled")

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        legacyText(currentData.asMap()[key]) != null

    override suspend fun migrate(currentData: Preferences): Preferences {
        val legacy = legacyText(currentData.asMap()[key]) ?: return currentData
        Timber.i("app_settings: 修正 %s 的历史非字符串存储值 -> %s", key.name, legacy)
        return currentData.toMutablePreferences().apply { this[key] = legacy }.toPreferences()
    }

    override suspend fun cleanUp() = Unit

    /** 只有按 String 读取会崩溃的类型才需要修复 */
    private fun legacyText(value: Any?): String? = when (value) {
        null, is String -> null
        is Boolean, is Number -> value.toString()
        else -> null
    }
}
