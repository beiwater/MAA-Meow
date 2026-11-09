package com.aliothmoon.maameow.data.preferences

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aliothmoon.maameow.domain.models.AppSettingsSchema
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 真机启动崩溃回归：`app_settings` 里 `ai_control_enabled` 是 Boolean，
 * schema 按 String 读，启动收集设置时抛 ClassCastException。
 * 这里直接跑 schema 的 `toAppSettings()` 复现并验证迁移可修复。
 */
class AiControlEnabledTypeMigrationTest {

    private val stringKey = stringPreferencesKey("ai_control_enabled")
    private val booleanKey = booleanPreferencesKey("ai_control_enabled")

    @Test
    fun `schema read of legacy boolean throws before migration`() = runBlocking {
        val legacy = mutablePreferencesOf(booleanKey to true)

        try {
            with(AppSettingsSchema) { legacy.toAppSettings() }
            fail("legacy Boolean 应当复现 ClassCastException")
        } catch (expected: ClassCastException) {
            // 与真机日志同一失败类型（JVM 与 ART 的 message 措辞不同）
            assertTrue(expected.message.orEmpty().contains("Boolean"))
        }
    }

    @Test
    fun `legacy boolean is migrated to string and schema read succeeds`() = runBlocking {
        val legacy = mutablePreferencesOf(booleanKey to true)

        assertTrue(AiControlEnabledTypeMigration.shouldMigrate(legacy))
        val migrated = AiControlEnabledTypeMigration.migrate(legacy)

        assertEquals("true", migrated[stringKey])
        assertEquals("true", with(AppSettingsSchema) { migrated.toAppSettings() }.aiControlEnabled)
        assertFalse(AiControlEnabledTypeMigration.shouldMigrate(migrated))
    }

    @Test
    fun `legacy false keeps its meaning`() = runBlocking {
        val migrated = AiControlEnabledTypeMigration.migrate(mutablePreferencesOf(booleanKey to false))

        assertEquals("false", migrated[stringKey])
        assertEquals("false", with(AppSettingsSchema) { migrated.toAppSettings() }.aiControlEnabled)
    }

    @Test
    fun `already string entry is untouched`() = runBlocking {
        val current: Preferences = mutablePreferencesOf(stringKey to "false")

        assertFalse(AiControlEnabledTypeMigration.shouldMigrate(current))
        assertSame(current, AiControlEnabledTypeMigration.migrate(current))
    }

    @Test
    fun `missing entry needs no migration`() = runBlocking {
        val empty: Preferences = mutablePreferencesOf()

        assertFalse(AiControlEnabledTypeMigration.shouldMigrate(empty))
        assertSame(empty, AiControlEnabledTypeMigration.migrate(empty))
    }
}
