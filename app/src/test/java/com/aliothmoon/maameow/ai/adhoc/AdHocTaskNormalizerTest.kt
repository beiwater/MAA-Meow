package com.aliothmoon.maameow.ai.adhoc

import com.aliothmoon.maameow.ai.mcp.ArknightsEntityValidator
import com.aliothmoon.maameow.data.model.AwardConfig
import com.aliothmoon.maameow.data.model.FightConfig
import com.aliothmoon.maameow.data.model.InfrastConfig
import com.aliothmoon.maameow.domain.enums.InfrastMode
import com.aliothmoon.maameow.data.model.MallConfig
import com.aliothmoon.maameow.data.model.ReclamationConfig
import com.aliothmoon.maameow.data.model.RecruitConfig
import com.aliothmoon.maameow.data.model.RoguelikeConfig
import com.aliothmoon.maameow.domain.enums.InfrastRoomType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdHocTaskNormalizerTest {

    private val testValidator = ArknightsEntityValidator(
        validStages = setOf("1-7", "LS-6", "CE-6", "AP-5"),
        stageAliases = mapOf("土豆" to "1-7", "钱本" to "CE-6"),
        validOperators = setOf("silverash", "thorns"),
        validItemIds = mapOf("30012" to "固源岩"),
        taskSpecsByType = emptyMap(),
    )

    @Test
    fun `normalize Fight task maps fields correctly`() {
        val normalizer = AdHocTaskNormalizer(testValidator)
        val json = """
            [
                {
                    "type": "Fight",
                    "stage": "1-7",
                    "times": 3,
                    "medicine": 2
                }
            ]
        """.trimIndent()

        val nodes = normalizer.normalize(json)
        assertEquals(1, nodes.size)

        val node = nodes[0]
        assertEquals("理智作战 - 1-7", node.name)
        assertTrue(node.enabled)
        assertEquals(0, node.order)

        val config = node.config as FightConfig
        assertEquals("1-7", config.stage1)
        assertTrue(config.hasTimesLimited)
        assertEquals(3, config.maxTimes)
        assertTrue(config.useMedicine)
        assertEquals(2, config.medicineNumber)
        assertFalse("Originite prime must not be used by default", config.useStone)
        assertEquals(0, config.stoneNumber)
    }

    @Test
    fun `normalize Fight task supports aliases stage1 and max_times`() {
        val normalizer = AdHocTaskNormalizer(testValidator)
        val json = """
            [
                {
                    "type": "Fight",
                    "stage1": "LS-6",
                    "max_times": 5,
                    "use_medicine": 1
                }
            ]
        """.trimIndent()

        val nodes = normalizer.normalize(json)
        val config = nodes[0].config as FightConfig
        assertEquals("LS-6", config.stage1)
        assertEquals(5, config.maxTimes)
        assertTrue(config.useMedicine)
        assertEquals(1, config.medicineNumber)
    }

    @Test
    fun `sanity medicine is capped at 5 for safety`() {
        val normalizer = AdHocTaskNormalizer(testValidator)
        val json = """
            [
                {
                    "type": "Fight",
                    "stage": "1-7",
                    "times": 10,
                    "medicine": 20
                }
            ]
        """.trimIndent()

        val nodes = normalizer.normalize(json)
        val config = nodes[0].config as FightConfig
        assertEquals(5, config.medicineNumber)
        assertTrue(config.useMedicine)
    }

    @Test
    fun `originite stone usage is prohibited unless explicitly allowed`() {
        val normalizer = AdHocTaskNormalizer(testValidator)
        val json = """
            [
                {
                    "type": "Fight",
                    "stage": "1-7",
                    "stone": 10
                }
            ]
        """.trimIndent()

        val nodes = normalizer.normalize(json)
        val config = nodes[0].config as FightConfig
        assertFalse(config.useStone)
        assertEquals(0, config.stoneNumber)
    }

    @Test
    fun `stage code alias is resolved by validator`() {
        val normalizer = AdHocTaskNormalizer(testValidator)
        val json = """
            [
                {
                    "type": "Fight",
                    "stage": "土豆",
                    "times": 1
                }
            ]
        """.trimIndent()

        val nodes = normalizer.normalize(json)
        val config = nodes[0].config as FightConfig
        assertEquals("1-7", config.stage1)
    }

    @Test
    fun `invalid stage detection throws IllegalArgumentException`() {
        val normalizer = AdHocTaskNormalizer(testValidator)
        val json = """
            [
                {
                    "type": "Fight",
                    "stage": "NONEXISTENT_STAGE_999",
                    "times": 1
                }
            ]
        """.trimIndent()

        val ex = assertThrows(IllegalArgumentException::class.java) {
            normalizer.normalize(json)
        }
        assertTrue(ex.message!!.contains("NONEXISTENT_STAGE_999"))
    }

    @Test
    fun `normalize Infrast shift_and_harvest enables all facilities`() {
        val normalizer = AdHocTaskNormalizer()
        val json = """
            [
                {
                    "type": "Infrast",
                    "mode": "shift_and_harvest"
                }
            ]
        """.trimIndent()

        val nodes = normalizer.normalize(json)
        val config = nodes[0].config as InfrastConfig
        assertEquals(InfrastMode.Normal, config.mode)
        assertTrue(config.facilities.all { it.second })
    }

    @Test
    fun `normalize Infrast harvest mode enables only Mfg and Trade`() {
        val normalizer = AdHocTaskNormalizer()
        val json = """
            [
                {
                    "type": "Infrast",
                    "mode": "harvest"
                }
            ]
        """.trimIndent()

        val nodes = normalizer.normalize(json)
        val config = nodes[0].config as InfrastConfig
        assertEquals(InfrastMode.Normal, config.mode)

        val enabledRooms = config.facilities.filter { it.second }.map { it.first }.toSet()
        assertEquals(setOf(InfrastRoomType.Mfg, InfrastRoomType.Trade), enabledRooms)
    }

    @Test
    fun `normalize Recruit sets star choices and auto_confirm correctly`() {
        val normalizer = AdHocTaskNormalizer()
        val json = """
            [
                {
                    "type": "Recruit",
                    "max_star": 4,
                    "auto_confirm": false,
                    "times": 3
                }
            ]
        """.trimIndent()

        val nodes = normalizer.normalize(json)
        val config = nodes[0].config as RecruitConfig
        assertEquals(3, config.maxRecruitTimes)
        assertTrue(config.chooseLevel3)
        assertTrue(config.chooseLevel4)
        assertFalse(config.chooseLevel5)
        assertFalse(config.chooseLevel6)
        assertFalse(config.autoConfirm)
    }

    @Test
    fun `normalize Recruit with 5 stars and default auto_confirm`() {
        val normalizer = AdHocTaskNormalizer()
        val json = """
            [
                {
                    "type": "Recruit",
                    "max_star": 5
                }
            ]
        """.trimIndent()

        val nodes = normalizer.normalize(json)
        val config = nodes[0].config as RecruitConfig
        assertTrue(config.chooseLevel3)
        assertTrue(config.chooseLevel4)
        assertTrue(config.chooseLevel5)
        assertFalse(config.chooseLevel6)
        assertTrue(config.autoConfirm)
    }

    @Test
    fun `normalize Mall with buy_free true sets discount only`() {
        val normalizer = AdHocTaskNormalizer()
        val json = """
            [
                {
                    "type": "Mall",
                    "buy_free": true
                }
            ]
        """.trimIndent()

        val nodes = normalizer.normalize(json)
        val config = nodes[0].config as MallConfig
        assertTrue(config.shopping)
        assertTrue(config.onlyBuyDiscount)
    }

    @Test
    fun `normalize multi-step ad-hoc task chain into profile`() {
        val normalizer = AdHocTaskNormalizer(testValidator)
        val json = """
            [
                { "type": "Fight", "stage": "1-7", "times": 5, "medicine": 1 },
                { "type": "Infrast", "mode": "shift_and_harvest" },
                { "type": "Recruit", "max_star": 4 },
                { "type": "Mall", "buy_free": true },
                { "type": "Award" },
                { "type": "Roguelike" },
                { "type": "Reclamation" }
            ]
        """.trimIndent()

        val profile = normalizer.normalizeToProfile(json, "My Ad-Hoc Run")
        assertEquals("My Ad-Hoc Run", profile.name)
        assertEquals(7, profile.chain.size)

        assertTrue(profile.chain[0].config is FightConfig)
        assertTrue(profile.chain[1].config is InfrastConfig)
        assertTrue(profile.chain[2].config is RecruitConfig)
        assertTrue(profile.chain[3].config is MallConfig)
        assertTrue(profile.chain[4].config is AwardConfig)
        assertTrue(profile.chain[5].config is RoguelikeConfig)
        assertTrue(profile.chain[6].config is ReclamationConfig)

        profile.chain.forEachIndexed { i, node ->
            assertEquals(i, node.order)
            assertTrue(node.enabled)
            assertNotNull(node.id)
        }
    }

    @Test
    fun `empty tasks list throws IllegalArgumentException`() {
        val normalizer = AdHocTaskNormalizer()
        assertThrows(IllegalArgumentException::class.java) {
            normalizer.normalize("[]")
        }
    }

    @Test
    fun `unknown task type throws IllegalArgumentException`() {
        val normalizer = AdHocTaskNormalizer()
        val json = """[ { "type": "UnknownType123" } ]"""
        val ex = assertThrows(IllegalArgumentException::class.java) {
            normalizer.normalize(json)
        }
        assertTrue(ex.message!!.contains("UnknownType123"))
    }
}
