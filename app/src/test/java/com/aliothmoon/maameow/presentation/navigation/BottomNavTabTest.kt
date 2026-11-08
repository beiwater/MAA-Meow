package com.aliothmoon.maameow.presentation.navigation

import com.aliothmoon.maameow.constant.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 底栏 AI Tab 的插入位置、开关过滤，以及 tabs 增减后的分页下标映射 */
class BottomNavTabTest {

    @Test
    fun `all keeps ai right after home`() {
        assertEquals(
            listOf(
                Routes.HOME,
                Routes.AI_CHAT,
                Routes.BACKGROUND_TASK,
                Routes.SCHEDULE,
                Routes.SETTINGS,
            ),
            BottomNavTab.all.map { it.route },
        )
    }

    @Test
    fun `visibleTabs exposes ai when ai control is on`() {
        val tabs = BottomNavTab.visibleTabs(aiEnabled = true)

        assertEquals(BottomNavTab.all, tabs)
        assertTrue(tabs.contains(BottomNavTab.AI))
    }

    @Test
    fun `visibleTabs hides ai but keeps the rest in order`() {
        val tabs = BottomNavTab.visibleTabs(aiEnabled = false)

        assertFalse(tabs.contains(BottomNavTab.AI))
        assertEquals(
            listOf(
                Routes.HOME,
                Routes.BACKGROUND_TASK,
                Routes.SCHEDULE,
                Routes.SETTINGS,
            ),
            tabs.map { it.route },
        )
    }

    @Test
    fun `page follows its route when ai appears`() {
        val withoutAi = BottomNavTab.visibleTabs(aiEnabled = false)
        val withAi = BottomNavTab.visibleTabs(aiEnabled = true)

        // 关着 AI 时「定时」在第 2 页；开启后 AI 插在首页之后，同一路由应落到第 3 页
        assertEquals(2, withoutAi.indexOf(BottomNavTab.SCHEDULE))
        assertEquals(
            withAi.indexOf(BottomNavTab.SCHEDULE),
            resolvePageForTabs(withAi, Routes.SCHEDULE, fallbackPage = 2),
        )
        assertEquals(
            withAi.indexOf(BottomNavTab.SETTINGS),
            resolvePageForTabs(withAi, Routes.SETTINGS, fallbackPage = 3),
        )
    }

    @Test
    fun `page follows its route when ai disappears`() {
        val withoutAi = BottomNavTab.visibleTabs(aiEnabled = false)

        // 关掉 AI 时停在「设置」：新列表里是 3，不能被 pager 夹取后的 3（旧列表的「定时」）带偏
        assertEquals(
            3,
            resolvePageForTabs(withoutAi, Routes.SETTINGS, fallbackPage = 3),
        )
        assertEquals(
            2,
            resolvePageForTabs(withoutAi, Routes.SCHEDULE, fallbackPage = 3),
        )
    }

    @Test
    fun `page falls back into range when the selected ai page disappears`() {
        val withoutAi = BottomNavTab.visibleTabs(aiEnabled = false)
        val withAi = BottomNavTab.visibleTabs(aiEnabled = true)

        val page = resolvePageForTabs(withoutAi, Routes.AI_CHAT, fallbackPage = withAi.indexOf(BottomNavTab.AI))

        assertTrue(page in withoutAi.indices)
        assertEquals(BottomNavTab.BACKGROUND, withoutAi[page])
    }

    @Test
    fun `fallback page outside the new tabs is clamped`() {
        val withoutAi = BottomNavTab.visibleTabs(aiEnabled = false)

        assertEquals(
            withoutAi.lastIndex,
            resolvePageForTabs(withoutAi, selectedRoute = null, fallbackPage = 99),
        )
        assertEquals(
            0,
            resolvePageForTabs(withoutAi, selectedRoute = null, fallbackPage = -1),
        )
    }

    @Test
    fun `unchanged tabs keep the selected page`() {
        val tabs = BottomNavTab.visibleTabs(aiEnabled = true)

        for (page in tabs.indices) {
            val route = tabs[page].route
            assertEquals(page, resolvePageForTabs(tabs, route, fallbackPage = page))
        }
    }
}
