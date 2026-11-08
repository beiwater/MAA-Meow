package com.aliothmoon.maameow.presentation.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.aliothmoon.maameow.constant.Routes
import com.aliothmoon.maameow.presentation.LocalInputFocusManager
import com.aliothmoon.maameow.presentation.components.LocalPageVisible
import com.aliothmoon.maameow.presentation.components.ShizukuReadinessGate
import com.aliothmoon.maameow.presentation.components.consumeAllPointerEvents
import com.aliothmoon.maameow.presentation.components.rememberPageVisible
import com.aliothmoon.maameow.presentation.components.rememberStagedBeyondViewportCount
import com.aliothmoon.maameow.presentation.pip.LocalIsInPip
import com.aliothmoon.maameow.presentation.onboarding.onboardingBlocksStartupDialogs
import com.aliothmoon.maameow.presentation.view.background.BackgroundTaskView
import com.aliothmoon.maameow.presentation.view.home.HomeView
import com.aliothmoon.maameow.presentation.view.settings.AiChatView
import com.aliothmoon.maameow.presentation.view.settings.SettingsView
import com.aliothmoon.maameow.presentation.viewmodel.BackgroundTaskViewModel
import com.aliothmoon.maameow.schedule.ui.ScheduleListView
import com.aliothmoon.maameow.theme.LocalReduceMotion
import com.aliothmoon.maameow.theme.MaaMotion
import com.aliothmoon.maameow.theme.PagerPageSpacing
import com.aliothmoon.maameow.theme.rememberMaaPagerFling
import com.aliothmoon.maameow.theme.rememberOuterPagerNestedScroll
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.abs


@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    navController: NavController,
    backgroundTaskViewModel: BackgroundTaskViewModel,
    aiControlEnabled: Boolean,
    onViewAnnouncement: () -> Unit = {},
    onViewOnboarding: () -> Unit = {},
    visible: Boolean = true,
    fullscreen: Boolean = false,
    parallax: MutableFloatState,
) {
    // AI 控制开关决定底栏是否有 AI Tab；全部分页下标都以 tabs 为准，不要碰 BottomNavTab.all
    val tabs = remember(aiControlEnabled) { BottomNavTab.visibleTabs(aiControlEnabled) }
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalReduceMotion.current
    val chromeHidden = fullscreen || LocalIsInPip.current
    val inputFocus = LocalInputFocusManager.current
    val mainVisible = rememberUpdatedState(visible)
    // 键盘弹出时收起底栏：聊天页要贴着键盘，底栏留着会多顶出一条缝
    val imeVisible = WindowInsets.isImeVisible

    // tabs 增减时按路由把当前页挪到新下标；错位会让人莫名停到别的 Tab。
    // 选中路由在组合期同步（只在 tabs 未变的帧里读 pager）：tabs 一变，pager 会被自动夹取，
    // 那一帧再读 currentPage 会把「设置」读成「定时」。
    var selectedRoute by remember { mutableStateOf(tabs.first().route) }
    var previousTabs by remember { mutableStateOf(tabs) }
    if (previousTabs == tabs) {
        selectedRoute = tabs.getOrNull(pagerState.currentPage)?.route ?: selectedRoute
    }
    LaunchedEffect(tabs) {
        val target = resolvePageForTabs(tabs, selectedRoute, pagerState.currentPage)
        if (target != pagerState.currentPage) pagerState.scrollToPage(target)
        previousTabs = tabs
    }

    // 把分页位置实时上报给背景层做视差，归一化到 -1~1。
    // 不能只用 currentPageOffsetFraction：它在滑过中点时随 currentPage 进位翻符号，背景会瞬移。
    // 减弱动效由背景层统一忽略，这里照常上报。
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage + pagerState.currentPageOffsetFraction }
            .collect { position ->
                val lastIndex = (pagerState.pageCount - 1).coerceAtLeast(1)
                parallax.floatValue = (position / lastIndex * 2f - 1f).coerceIn(-1f, 1f)
            }
    }

    // targetPage：点击/滑动一旦确定目标即生效，停稳后等于 currentPage。
    // animateScrollToPage 内部走 MutatorMutex，连续调用时后者自动接管，无需手动取消。
    fun goToPage(index: Int, animate: Boolean = true) {
        if (index !in tabs.indices || index == pagerState.targetPage) return
        inputFocus.clear()
        scope.launch {
            if (!animate) {
                pagerState.scrollToPage(index)
                return@launch
            }
            val distance = abs(index - pagerState.currentPage).coerceAtLeast(1)
            pagerState.animateScrollToPage(
                page = index,
                animationSpec = tween(
                    durationMillis = MaaMotion.pagerDuration(distance, reduceMotion),
                    easing = MaaMotion.Emphasized,
                ),
            )
        }
    }

    // 非首页 Tab 按返回键先回到首页；全屏由 BackgroundTaskView 自行处理。
    BackHandler(enabled = visible && !chromeHidden && pagerState.targetPage != 0) {
        goToPage(0)
    }

    // 主 Tab 路由是空占位不能 navigate，只能先 pop 回主界面再滑 pager
    fun surfaceToTab(tab: BottomNavTab, animate: Boolean = true) {
        navController.popBackStack(Routes.HOME, false)
        // Tab 被关掉（AI 控制关闭）时退回首页，别留在越界的下标上
        goToPage(tabs.indexOf(tab).takeIf { it >= 0 } ?: 0, animate)
    }

    // 定时任务触发时自动浮出后台任务页
    // （恢复旧导航 navigate(BACKGROUND){popUpTo(HOME)} 的“自动浮出后台页”语义）。
    val pendingNavigateRequestId by backgroundTaskViewModel.pendingNavigateRequestId.collectAsStateWithLifecycle()
    LaunchedEffect(pendingNavigateRequestId) {
        if (pendingNavigateRequestId != null) {
            surfaceToTab(BottomNavTab.BACKGROUND)
            backgroundTaskViewModel.onNavigateForScheduledLaunch()
        }
    }

    // 子页面的「回到主界面并切 Tab」请求
    val mainTabNavigator: MainTabNavigator = koinInject()
    val mainTabRequest by mainTabNavigator.request.collectAsStateWithLifecycle()
    LaunchedEffect(mainTabRequest) {
        mainTabRequest?.let { request ->
            surfaceToTab(request.tab, request.animate)
            mainTabNavigator.consume()
        }
    }

    val scaffoldContent: @Composable () -> Unit = {
        Scaffold(
            modifier = modifier,
            // 键盘抬起时内容要伸到窗口底边，聊天页自己的 imePadding 才不会和底栏/导航栏 inset 叠成两层
            contentWindowInsets = if (imeVisible) WindowInsets(0) else ScaffoldDefaults.contentWindowInsets,
            bottomBar = {
                // IME 弹出时让位：底栏盖在键盘后面只会把输入框顶出一条缝
                if (visible && !chromeHidden && !imeVisible) {
                    AppBottomNavigation(
                        currentRoute = (tabs.getOrNull(pagerState.targetPage) ?: tabs.first()).route,
                        tabs = tabs,
                        onTabSelected = { tab -> goToPage(tabs.indexOf(tab)) },
                    )
                }
            },
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = paddingValues.calculateBottomPadding())
                    .graphicsLayer {
                        // 隐藏时跳过绘制但保留组合树，避免 HorizontalPager 状态丢失
                        alpha = if (visible) 1f else 0f
                    },
            ) {
                val pagerFling = rememberMaaPagerFling(pagerState)
                CompositionLocalProvider(LocalPageVisible provides mainVisible) {
                    HorizontalPager(
                        state = pagerState,
                        beyondViewportPageCount = rememberStagedBeyondViewportCount(
                            pagerState, tabs.lastIndex.coerceAtLeast(0)
                        ),
                        modifier = Modifier.fillMaxSize(),
                        // 下标可能比 tabs 长一帧（tabs 刚减员的过渡帧），回退到占位 key 免得越界
                        key = { page -> tabs.getOrNull(page)?.route ?: "tab_$page" },
                        userScrollEnabled = visible && !chromeHidden,
                        pageSpacing = PagerPageSpacing,
                        flingBehavior = pagerFling,
                        pageNestedScrollConnection = rememberOuterPagerNestedScroll(pagerState, pagerFling),
                    ) { page ->
                        CompositionLocalProvider(LocalPageVisible provides rememberPageVisible(pagerState, page)) {
                            when (tabs.getOrNull(page)) {
                                BottomNavTab.HOME -> HomeView(navController = navController)
                                BottomNavTab.AI -> AiChatView(
                                    navController = navController,
                                    isTab = true,
                                )
                                BottomNavTab.BACKGROUND -> BackgroundTaskView(
                                    viewModel = backgroundTaskViewModel,
                                    isActivePage = { mainVisible.value && pagerState.targetPage == page },
                                )

                                BottomNavTab.SCHEDULE -> ScheduleListView(
                                    navController = navController,
                                    isActivePage = {
                                        mainVisible.value && !pagerState.isScrollInProgress &&
                                                pagerState.settledPage == page
                                    },
                                )
                                BottomNavTab.SETTINGS -> SettingsView(
                                    navController = navController,
                                    onViewAnnouncement = onViewAnnouncement,
                                    onViewOnboarding = onViewOnboarding,
                                )
                                null -> Unit
                            }
                        }
                    }
                }

                // 隐藏（子页面叠加其上）时吞掉所有指针，防止横滑切走主 Tab / 点击穿透到底层页。
                if (!visible) {
                    Box(Modifier
                        .matchParentSize()
                        .consumeAllPointerEvents())
                }
            }
        }
    }

    scaffoldContent()
    if (visible && !LocalIsInPip.current && !onboardingBlocksStartupDialogs()) {
        ShizukuReadinessGate()
    }
}
