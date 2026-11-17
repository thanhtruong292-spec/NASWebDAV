package com.nas.naswebdav.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import com.nas.naswebdav.DomainViewModelProvider
import com.nas.naswebdav.WebDavManager
import com.nas.naswebdav.buildWebDavTrashTargetUrl
import com.nas.naswebdav.ui.components.BOTTOM_NAV_ROUTES
import com.nas.naswebdav.ui.components.NasNavigationBar
import com.nas.naswebdav.ui.components.NasTab
import com.nas.naswebdav.ui.screens.*
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/**
 * Centralized navigation graph — single source of truth for all routes.
 *
 * Routes:
 *   login              → LoginScreen (no bottom nav)
 *   dashboard          → MainMenuScreen (bottom nav)
 *   apps               → AppsSectionScreen (bottom nav)
 *   files              → BrowserScreen (bottom nav)
 *   system             → SystemStatusScreen (bottom nav)
 *   more               → MoreSectionScreen (bottom nav)
 *   performance        → PerformanceScreen (full-screen, no bottom nav)
 *   video              → ExoPlayerScreen (full-screen, no bottom nav)
 *   image              → ImageViewerScreen (full-screen, no bottom nav)
 *   guest_pass         → GuestPassScreen (full-screen, no bottom nav)
 *   social_extractor   → SocialExtractorScreen (full-screen, no bottom nav)
 *   smart_organizer    → SmartOrganizerScreen (full-screen, no bottom nav)
 *
 * Bottom nav is only shown when currentRoute ∈ BOTTOM_NAV_ROUTES.
 */
object NasRoutes {
    const val LOGIN = "login"
    const val DASHBOARD = "dashboard"
    const val APPS = "apps"
    const val FILES = "files"
    const val SYSTEM = "system"
    const val MORE = "more"
    const val PERFORMANCE = "performance"
    const val VIDEO = "video"
    const val IMAGE = "image"
    const val GUEST_PASS = "guest_pass"
    const val SOCIAL_EXTRACTOR = "social_extractor"
    const val SMART_ORGANIZER = "smart_organizer"
}

/**
 * CC-005 P1: reset phiên tập trung khi logout — dùng chung cho mọi onLogout.
 * Giữ disconnect/reset hiện có + thêm clear ViewModel (cancel login, reset browser mode).
 */
private fun performLogout(domainProvider: DomainViewModelProvider) {
    try { domainProvider.systemMonitor.stopDashboardMonitoring() } catch (_: Exception) { }
    try { domainProvider.authSession.cancelLogin() } catch (_: Exception) { }
    try { com.nas.naswebdav.WebDavManager.disconnect() } catch (_: Exception) { }
    try { com.nas.naswebdav.shared.SharedStateHolder.reset() } catch (_: Exception) { }
    try { domainProvider.fileBrowser.resetToDefaultMode() } catch (_: Exception) { }
}

/**
 * Main navigation host with bottom nav visibility logic.
 *
 * @param domainProvider  DI container for all 8 domain VMs
 * @param navController   NavController from rememberNavController()
 * @param mediaUrl        Shared state for video/image playback
 * @param onSetMediaUrl   Callback to update mediaUrl
 * @param onStartScreenRecord Callback for screen record permission
 */
@Composable
fun NasNavHost(
    domainProvider: DomainViewModelProvider,
    navController: NavHostController,
    mediaUrl: String,
    onSetMediaUrl: (String) -> Unit,
    onStartScreenRecord: () -> Unit = {},
    // CC-005 P1: splash conditional start — đã login -> dashboard, chưa -> login.
    startDestination: String = NasRoutes.LOGIN,
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showBottomBar = currentRoute in BOTTOM_NAV_ROUTES

    Scaffold(
        containerColor = DarkSurface,
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
            ) {
                NasNavigationBar(
                    currentRoute = currentRoute,
                    onTabSelected = { tab ->
                        // CC-005 P1: reset ExclusivePanelState khi chuyển màn +
                        // dismiss dialog host (tránh dialog/sheet màn cũ đè màn mới).
                        runCatching {
                            com.nas.naswebdav.ui.screens.ExclusivePanelState.current.value = null
                        }
                        runCatching {
                            domainProvider.globalUi.dismiss()
                            domainProvider.deviceManagement.dismissIpApproval()
                        }
                        if (tab.route == NasRoutes.FILES && domainProvider.fileBrowser.currentUrl.isBlank()) {
                            domainProvider.fileBrowser.resetToDefaultMode()
                        }
                        navController.navigate(tab.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        val mContext = androidx.compose.ui.platform.LocalContext.current

        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            enterTransition = {
                fadeIn(animationSpec = tween(300)) + slideInHorizontally(
                    initialOffsetX = { 60 },
                    animationSpec = tween(300)
                )
            },
            exitTransition = {
                fadeOut(animationSpec = tween(200))
            },
            popEnterTransition = {
                fadeIn(animationSpec = tween(300)) + slideInHorizontally(
                    initialOffsetX = { -60 },
                    animationSpec = tween(300)
                )
            },
            popExitTransition = {
                fadeOut(animationSpec = tween(200))
            },
        ) {
            // ── LOGIN ──────────────────────────────────────────────────
            composable(NasRoutes.LOGIN) {
                LoginScreen {
                    navController.navigate(NasRoutes.DASHBOARD) {
                        popUpTo(NasRoutes.LOGIN) { inclusive = true }
                    }
                }
            }

            // ── DASHBOARD (Trang chủ) ──────────────────────────────────
            composable(NasRoutes.DASHBOARD) {
                // CC-005 P1: double-tap back để thoát (lần 1 toast, lần 2 finish).
                val dashContext = androidx.compose.ui.platform.LocalContext.current
                var dashLastBack by remember { mutableLongStateOf(0L) }
                androidx.activity.compose.BackHandler {
                    val now = System.currentTimeMillis()
                    if (now - dashLastBack < 2000L) {
                        (dashContext as? android.app.Activity)?.finish()
                    } else {
                        dashLastBack = now
                        android.widget.Toast.makeText(
                            dashContext, "Nhấn Back lần nữa để thoát", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                val htmlActionHandler: (String, String?) -> Unit = { act, arg ->
                    when (act) {
                        "files" -> {
                            domainProvider.fileBrowser.resetToDefaultMode()
                            navController.navigate(NasRoutes.FILES)
                        }
                        "search" -> if (!arg.isNullOrBlank()) {
                            domainProvider.fileBrowser.searchGlobal(arg)
                            navController.navigate(NasRoutes.FILES)
                        }
                        "photos" -> {
                            domainProvider.fileBrowser.showLatestPhotos()
                            navController.navigate(NasRoutes.FILES)
                        }
                        "videos" -> {
                            domainProvider.fileBrowser.showRecentVideos()
                            navController.navigate(NasRoutes.FILES)
                        }
                        "trash" -> {
                            val trashUrl = buildWebDavTrashTargetUrl(
                                WebDavManager.currentBaseUrl,
                                domainProvider.fileBrowser.currentUrl.ifBlank { WebDavManager.currentBaseUrl },
                                "",
                                false
                            )
                            domainProvider.fileBrowser.openSpecificUrl(trashUrl, "Thùng rác")
                            navController.navigate(NasRoutes.FILES)
                        }
                        "performance" -> navController.navigate(NasRoutes.PERFORMANCE)
                        "organizer" -> navController.navigate(NasRoutes.SMART_ORGANIZER)
                        "guest" -> navController.navigate(NasRoutes.GUEST_PASS)
                        "social" -> navController.navigate(NasRoutes.SOCIAL_EXTRACTOR)
                        "screenRecord" -> onStartScreenRecord()
                        "toolbox" -> navController.navigate(NasRoutes.SYSTEM)
                        "diskProfile", "smartDetails", "procCpu", "procMem" -> navController.navigate(NasRoutes.PERFORMANCE)
                    }
                }
                com.nas.naswebdav.ui.html5.NasHtmlDashboardScreen(
                    viewName = "dashboard",
                    onAction = htmlActionHandler,
                )
            }

            // ── APPS (Ứng dụng) ────────────────────────────────────────
            composable(NasRoutes.APPS) {
                com.nas.naswebdav.ui.html5.NasHtmlDashboardScreen(
                    viewName = "apps",
                    onAction = { act, _ ->
                        when (act) {
                            "organizer" -> navController.navigate(NasRoutes.SMART_ORGANIZER)
                            "guest" -> navController.navigate(NasRoutes.GUEST_PASS)
                            "social" -> navController.navigate(NasRoutes.SOCIAL_EXTRACTOR)
                            "screenRecord" -> onStartScreenRecord()
                            "files" -> {
                                domainProvider.fileBrowser.resetToDefaultMode()
                                navController.navigate(NasRoutes.FILES)
                            }
                            "performance" -> navController.navigate(NasRoutes.PERFORMANCE)
                        }
                    }
                )
            }

            // ── FILES (File browser) ───────────────────────────────────
            composable(NasRoutes.FILES) {
                BrowserScreen(
                    onVideo = { url ->
                        val auth = WebDavManager.currentAuthState()
                        openExternalVideoPlayer(
                            context = mContext,
                            url = url,
                            user = auth.user,
                            pass = auth.pass,
                            onError = {
                                onSetMediaUrl(url)
                                navController.navigate(NasRoutes.VIDEO)
                            }
                        )
                    },
                    onImage = { url ->
                        onSetMediaUrl(url)
                        navController.navigate(NasRoutes.IMAGE)
                    },
                    onLogout = {
                        domainProvider.livestream.viewModelScope.launch {
                            domainProvider.repository.addSystemLog("INFO", "Network",
                                "Người dùng '${WebDavManager.currentUser}' đã chủ động Đăng xuất.")
                        }
                        performLogout(domainProvider)
                        navController.navigate(NasRoutes.LOGIN) { popUpTo(0) }
                    },
                    onBackToMenu = {
                        domainProvider.fileBrowser.resetToDefaultMode()
                        navController.navigate(NasRoutes.DASHBOARD) {
                            popUpTo(NasRoutes.DASHBOARD) { inclusive = true }
                        }
                    },
                )
            }

            // ── SYSTEM (Hệ thống) ──────────────────────────────────────
            composable(NasRoutes.SYSTEM) {
                com.nas.naswebdav.ui.html5.NasHtmlDashboardScreen(
                    viewName = "system",
                    onAction = { act, _ ->
                        when (act) {
                            "performance", "diskProfile", "smartDetails" -> navController.navigate(NasRoutes.PERFORMANCE)
                            "toolbox" -> navController.navigate(NasRoutes.APPS)
                        }
                    }
                )
            }

            // ── MORE (Thêm) ────────────────────────────────────────────
            composable(NasRoutes.MORE) {
                val moreContext = androidx.compose.ui.platform.LocalContext.current
                var showAboutDialog by remember { mutableStateOf(false) }
                com.nas.naswebdav.ui.html5.NasHtmlDashboardScreen(
                    viewName = "more",
                    onAction = { act, _ ->
                        when (act) {
                            "performance" -> navController.navigate(NasRoutes.PERFORMANCE)
                            "appSettings" -> runCatching {
                                val intent = android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                ).apply {
                                    data = android.net.Uri.fromParts("package", moreContext.packageName, null)
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                moreContext.startActivity(intent)
                            }
                            "about" -> showAboutDialog = true
                            "logout" -> {
                                domainProvider.livestream.viewModelScope.launch {
                                    domainProvider.repository.addSystemLog("INFO", "Network",
                                        "Người dùng '${WebDavManager.currentUser}' đã chủ động Đăng xuất.")
                                }
                                performLogout(domainProvider)
                                navController.navigate(NasRoutes.LOGIN) { popUpTo(0) }
                            }
                        }
                    }
                )
                if (showAboutDialog) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showAboutDialog = false },
                        title = { androidx.compose.material3.Text("NAS WebDAV") },
                        text = {
                            androidx.compose.material3.Text(
                                "Ứng dụng quản lý NAS qua WebDAV.\n" +
                                "Phiên bản: " + runCatching {
                                    moreContext.packageManager
                                        .getPackageInfo(moreContext.packageName, 0).versionName
                                }.getOrDefault("?")
                            )
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(
                                onClick = { showAboutDialog = false }) {
                                androidx.compose.material3.Text("Đóng")
                            }
                        }
                    )
                }
            }

            // ── FULL-SCREEN DESTINATIONS (no bottom nav) ────────────────
            composable(NasRoutes.PERFORMANCE) {
                PerformanceScreen(onBack = { navController.popBackStack() })
            }

            composable(NasRoutes.VIDEO) {
                val auth = remember { WebDavManager.currentAuthState() }
                ExoPlayerScreen(
                    url = mediaUrl,
                    user = auth.user,
                    pass = auth.pass,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(NasRoutes.IMAGE) {
                ImageViewerScreen(
                    initialUrl = mediaUrl,
                    user = WebDavManager.currentUser,
                    pass = WebDavManager.currentPass,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(NasRoutes.GUEST_PASS) {
                GuestPassScreen(onBack = { navController.popBackStack() })
            }

            composable(NasRoutes.SOCIAL_EXTRACTOR) {
                SocialExtractorScreen(onBack = { navController.popBackStack() })
            }

            composable(NasRoutes.SMART_ORGANIZER) {
                SmartOrganizerScreen(onBack = { navController.popBackStack() })
            }

            // ── CC-005 P1: alias route cũ (migrate dần) ────────────────────
            // main_menu -> DASHBOARD, browser -> FILES (launchSingleTop + save/restore + popUpTo start).
            composable("main_menu") {
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    navController.navigate(NasRoutes.DASHBOARD) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
            composable("browser") {
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    navController.navigate(NasRoutes.FILES) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        }
    }
}
