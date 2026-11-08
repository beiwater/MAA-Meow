package com.aliothmoon.maameow.presentation.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aliothmoon.maameow.R
import com.aliothmoon.maameow.constant.Routes
import com.aliothmoon.maameow.presentation.components.MaaWindowInsets
import com.aliothmoon.maameow.presentation.onboarding.OnboardingTarget
import com.aliothmoon.maameow.presentation.onboarding.onboardingTarget
import com.aliothmoon.maameow.theme.MaaDesignTokens

sealed class BottomNavTab(
    val route: String, @param:StringRes val labelRes: Int, val icon: ImageVector
) {
    data object HOME : BottomNavTab(
        route = Routes.HOME, labelRes = R.string.bottom_nav_home, icon = Icons.Default.Home
    )

    data object AI : BottomNavTab(
        route = Routes.AI_CHAT,
        labelRes = R.string.bottom_nav_ai,
        icon = Icons.AutoMirrored.Rounded.Chat
    )

    data object BACKGROUND : BottomNavTab(
        route = Routes.BACKGROUND_TASK,
        labelRes = R.string.bottom_nav_background_task,
        icon = Icons.Default.PlayArrow
    )

    data object SCHEDULE : BottomNavTab(
        route = Routes.SCHEDULE,
        labelRes = R.string.bottom_nav_schedule,
        icon = Icons.Default.DateRange
    )

    data object SETTINGS : BottomNavTab(
        route = Routes.SETTINGS,
        labelRes = R.string.bottom_nav_settings,
        icon = Icons.Default.Settings
    )

    companion object {
        /** 静态全序，底栏与主分页的唯一顺序来源；AI 用 [visibleTabs] 按开关过滤，别直接遍历它 */
        val all = listOf(HOME, AI, BACKGROUND, SCHEDULE, SETTINGS)

        /** AI 控制关闭时底栏不出现 AI Tab，其余顺序不变 */
        fun visibleTabs(aiEnabled: Boolean): List<BottomNavTab> =
            if (aiEnabled) all else all.filter { it != AI }
    }
}

/**
 * tabs 列表变化（AI 开关）后，把选中页按路由映射回新下标。
 *
 * 只夹下标不够：AI 插在首页之后，插入/移除会让它后面的所有页错位一格。
 * 选中页已消失（关掉 AI 时正停在 AI 页）则把 [fallbackPage] 夹到合法范围。
 */
internal fun resolvePageForTabs(
    tabs: List<BottomNavTab>,
    selectedRoute: String?,
    fallbackPage: Int,
): Int {
    if (tabs.isEmpty()) return 0
    val mapped = tabs.indexOfFirst { it.route == selectedRoute }
    return if (mapped >= 0) mapped else fallbackPage.coerceIn(tabs.indices)
}

@Composable
fun AppBottomNavigation(
    currentRoute: String,
    onTabSelected: (BottomNavTab) -> Unit,
    modifier: Modifier = Modifier,
    tabs: List<BottomNavTab> = BottomNavTab.all,
) {
    Surface(
        modifier = modifier, color = MaterialTheme.colorScheme.surface, shadowElevation = 0.dp
    ) {
        Column {
            HorizontalDivider(
                thickness = MaaDesignTokens.Separator.thickness,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(MaaWindowInsets.bottomBar)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEach { tab ->
                    val label = stringResource(tab.labelRes)
                    val selected = currentRoute == tab.route
                    val contentColor = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    // 首启引导：底栏只指一下设置
                    val targetModifier = if (tab == BottomNavTab.SETTINGS) {
                        Modifier.onboardingTarget(OnboardingTarget.TAB_SETTINGS)
                    } else {
                        Modifier
                    }

                    Column(
                        modifier = targetModifier
                            // 5 个 Tab 时等分宽度，长语种标签不挤爆整行
                            .weight(1f)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onTabSelected(tab) }
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 2.dp, vertical = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = label,
                            modifier = Modifier.size(20.dp),
                            tint = contentColor
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = contentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
