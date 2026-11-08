package com.aliothmoon.maameow.ai

import com.aliothmoon.maameow.data.model.AwardConfig
import com.aliothmoon.maameow.data.model.FightConfig
import com.aliothmoon.maameow.data.model.InfrastConfig
import com.aliothmoon.maameow.data.model.MallConfig
import com.aliothmoon.maameow.data.model.RecruitConfig
import com.aliothmoon.maameow.data.model.RoguelikeConfig
import com.aliothmoon.maameow.data.model.RoguelikeStartingOper
import com.aliothmoon.maameow.data.model.TaskChainNode
import com.aliothmoon.maameow.data.model.TaskProfile
import com.aliothmoon.maameow.data.model.WakeUpConfig
import com.aliothmoon.maameow.data.preferences.ConfigBackup
import com.aliothmoon.maameow.domain.models.AppSettings
import com.aliothmoon.maameow.data.notification.NotificationSettings
import com.aliothmoon.maameow.maa.task.MaaTaskParams
import com.aliothmoon.maameow.maa.task.MaaTaskType
import com.aliothmoon.maameow.maa.task.TaskSlot
import com.aliothmoon.maameow.utils.JsonUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiScriptRecorderAndClientTest {

    private val json = JsonUtils.common

    @Test
    fun `record A to save script to clear UI to replay produces empty parameter diff and round-trips with ConfigBackup`() {
        val recorder = AiScriptRecorder()

        // Original UI chain A
        val originalNodes = listOf(
            TaskChainNode(
                id = "node-wakeup",
                name = "开始唤醒",
                enabled = true,
                order = 0,
                config = WakeUpConfig(clientType = "Official", startGameEnabled = true),
            ),
            TaskChainNode(
                id = "node-fight",
                name = "刷理智",
                enabled = true,
                order = 1,
                config = FightConfig(
                    stage1 = "1-7",
                    useMedicine = true,
                    medicineNumber = 3,
                    hasTimesLimited = true,
                    maxTimes = 5,
                ),
            ),
            TaskChainNode(
                id = "node-recruit",
                name = "自动公招",
                enabled = true,
                order = 2,
                config = RecruitConfig(maxRecruitTimes = 4, refreshLevel3 = true),
            ),
            TaskChainNode(
                id = "node-infrast",
                name = "基建换班",
                enabled = true,
                order = 3,
                config = InfrastConfig(usesOfDrones = "Money", dormThreshold = 30),
            ),
            TaskChainNode(
                id = "node-mall",
                name = "获取信用及购物",
                enabled = true,
                order = 4,
                config = MallConfig(shopping = true, visitFriends = true),
            ),
            TaskChainNode(
                id = "node-award",
                name = "领取奖励",
                enabled = true,
                order = 5,
                config = AwardConfig(award = true, mail = true),
            ),
        )

        // Effective MaaTaskParams emitted when running A
        val effectiveParamsA = listOf(
            MaaTaskParams(
                type = MaaTaskType.START_UP,
                params = """{"client_type":"Official","start_game_enabled":true}""",
                slot = TaskSlot("node-wakeup", 0),
            ),
            MaaTaskParams(
                type = MaaTaskType.FIGHT,
                params = """{"stage":"1-7","medicine":3,"times":5,"series":0}""",
                slot = TaskSlot("node-fight", 0),
            ),
            MaaTaskParams(
                type = MaaTaskType.RECRUIT,
                params = """{"max_times":4,"refresh":true,"expedite":false,"select":[3,4]}""",
                slot = TaskSlot("node-recruit", 0),
            ),
            MaaTaskParams(
                type = MaaTaskType.INFRAST,
                params = """{"mode":0,"drones":"Money","threshold":0.3,"replenish":true,"dorm_notstationed_enabled":true,"dorm_trust_enabled":true}""",
                slot = TaskSlot("node-infrast", 0),
            ),
            MaaTaskParams(
                type = MaaTaskType.MALL,
                params = """{"shopping":true,"visit_friends":true,"credit_fight":false,"formation_index":0,"buy_first":["招聘许可","龙门币"],"blacklist":["碳","家具","加急许可"]}""",
                slot = TaskSlot("node-mall", 0),
            ),
            MaaTaskParams(
                type = MaaTaskType.AWARD,
                params = """{"award":true,"mail":true,"recruit":false,"orundum":false,"mining":false,"specialaccess":false,"signinevent":false}""",
                slot = TaskSlot("node-award", 0),
            ),
        )

        // 1. Start recording & run A
        recorder.startRecording()
        assertTrue(recorder.isRecording.value)
        recorder.onRunStarted(effectiveParamsA, originalNodes)
        val draft = recorder.stopRecording()
        assertFalse(recorder.isRecording.value)
        assertEquals(6, draft.size)

        // 2. Save script -> TaskProfile
        val savedProfile: TaskProfile? = recorder.buildProfileFromSnapshot("日常脚本-A", profileId = "script-a")
        assertNotNull(savedProfile)
        savedProfile!!

        // 3. Clear UI / draft
        recorder.clearDraft()
        assertTrue(recorder.recordedNodes.value.isEmpty())

        // 4. Verify parameter diff between original chain A configs and saved script configs is empty
        val originalConfigs = originalNodes.map { it.config }
        val scriptConfigs = savedProfile.chain.map { it.config }
        assertEquals("Parameter diff must be empty", originalConfigs, scriptConfigs)

        // 5. Export via ConfigBackup format and re-import -> still executable and identical
        val backup = ConfigBackup(
            version = 1,
            exportedAt = "2026-11-08T10:00:00Z",
            appSettings = AppSettings(),
            notificationSettings = NotificationSettings(),
            taskProfiles = listOf(savedProfile),
            activeProfileId = savedProfile.id,
            scheduleStrategies = emptyList(),
        )
        val backupJson = json.encodeToString(ConfigBackup.serializer(), backup)
        val reimportedBackup = json.decodeFromString(ConfigBackup.serializer(), backupJson)
        val reimportedProfile = reimportedBackup.taskProfiles.single()

        assertEquals(savedProfile.id, reimportedProfile.id)
        assertEquals(savedProfile.name, reimportedProfile.name)
        assertEquals(
            "Re-imported backup script configs must match original configs with zero diff",
            originalConfigs,
            reimportedProfile.chain.map { it.config },
        )
    }

    @Test
    fun `reconstructs TaskParamProvider directly from raw MaaTaskParams without UI chain`() {
        val recorder = AiScriptRecorder()
        val rawParams = listOf(
            MaaTaskParams(
                type = MaaTaskType.FIGHT,
                params = """{"stage":"CE-6","medicine":2,"times":4}""",
            ),
            MaaTaskParams(
                type = MaaTaskType.ROGUELIKE,
                params = """{"theme":"Sami","mode":0,"core_char":"维什戴尔","use_support":true}""",
            ),
        )
        recorder.onRunStarted(rawParams, sourceChain = emptyList())
        val profile = recorder.buildProfileFromSnapshot("纯参数恢复")
        assertNotNull(profile)
        val fight = profile!!.chain[0].config as FightConfig
        assertEquals("CE-6", fight.stage1)
        assertTrue(fight.useMedicine)
        assertEquals(2, fight.medicineNumber)
        assertEquals(4, fight.maxTimes)

        val rogue = profile.chain[1].config as RoguelikeConfig
        assertEquals("Sami", rogue.theme)
        assertEquals(listOf(RoguelikeStartingOper(name = "维什戴尔", useSupport = true)), rogue.startingOpers)
    }

    @Test
    fun `DifyChatClient parses streaming text and agent tool call events`() {
        val client = DifyChatClient(okhttp3.OkHttpClient())

        val toolStart = client.parseSseData(
            """{"event":"agent_thought","id":"thought-1","tool":"maa_run_profile","tool_input":"{\"profile_id\":\"default\"}","observation":""}""",
        )
        assertEquals(
            DifyStreamEvent.ToolCallStarted("thought-1", "maa_run_profile", """{"profile_id":"default"}"""),
            toolStart,
        )

        val toolEnd = client.parseSseData(
            """{"event":"agent_thought","id":"thought-1","tool":"maa_run_profile","tool_input":"{\"profile_id\":\"default\"}","observation":"{\"started\":true,\"runId\":\"run-1\"}"}""",
        )
        assertEquals(
            DifyStreamEvent.ToolCallFinished(
                "thought-1",
                "maa_run_profile",
                """{"profile_id":"default"}""",
                """{"started":true,"runId":"run-1"}""",
            ),
            toolEnd,
        )

        val delta = client.parseSseData(
            """{"event":"agent_message","task_id":"task-123","conversation_id":"conv-456","answer":"已为您启动日常配置。"}""",
        )
        assertEquals(DifyStreamEvent.TextDelta("已为您启动日常配置。"), delta)
    }
}
