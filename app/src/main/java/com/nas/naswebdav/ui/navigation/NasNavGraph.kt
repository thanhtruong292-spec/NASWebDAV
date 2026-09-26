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
            startDestination = NasRoutes.LOGIN,
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
                androidx.activity.compose.BackHandler { /* Block back on dashboard */ }
                MainMenuScreen(
                    onOpenFiles = {
                        domainProvider.fileBrowser.resetToDefaultMode()
                        navController.navigate(NasRoutes.FILES)
                    },
                    onOpenFolder = { webdavPath ->
                        domainProvider.fileBrowser.openSpecificUrl(webdavPath, "Downloads")
                        navController.navigate(NasRoutes.FILES)
                    },
                    onGlobalSearch = { keyword ->
                        domainProvider.fileBrowser.searchGlobal(keyword)
                        navController.navigate(NasRoutes.FILES)
                    },
                    onOpenLatestPhotos = {
                        domainProvider.fileBrowser.showLatestPhotos()
                        navController.navigate(NasRoutes.FILES)
                    },
                    onOpenRecentVideos = {
                        domainProvider.fileBrowser.showRecentVideos()
                        navController.navigate(NasRoutes.FILES)
                    },
                    onOpenTrash = {
                        val trashUrl = buildWebDavTrashTargetUrl(
                            WebDavManager.currentBaseUrl,
                            domainProvider.fileBrowser.currentUrl.ifBlank { WebDavManager.currentBaseUrl },
                            "",
                            false
                        )
                        domainProvider.fileBrowser.openSpecificUrl(trashUrl, "Thùng rác")
                        navController.navigate(NasRoutes.FILES)
                    },
                    onOpenPerformance = { navController.navigate(NasRoutes.PERFORMANCE) },
                    onLogout = {
                        domainProvider.livestream.viewModelScope.launch {
                            domainProvider.repository.addSystemLog("INFO", "Network",
                                "Người dùng '${WebDavManager.currentUser}' đã chủ động Đăng xuất.")
                        }
                        navController.navigate(NasRoutes.LOGIN) { popUpTo(0) }
                    },
                    onOpenOrganizer = { navController.navigate(NasRoutes.SMART_ORGANIZER) },
                    onOpenGuestPass = { navController.navigate(NasRoutes.GUEST_PASS) },
                    onOpenSocialExtractor = { navController.navigate(NasRoutes.SOCIAL_EXTRACTOR) },
                    onStartScreenRecord = onStartScreenRecord,
                )
            }

            // ── APPS (Ứng dụng) ────────────────────────────────────────
            composable(NasRoutes.APPS) {
                AppsSectionScreen(
                    onOpenOrganizer = { navController.navigate(NasRoutes.SMART_ORGANIZER) },
                    onOpenGuestPass = { navController.navigate(NasRoutes.GUEST_PASS) },
                    onOpenSocialExtractor = { navController.navigate(NasRoutes.SOCIAL_EXTRACTOR) },
                    onStartScreenRecord = onStartScreenRecord,
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
                SystemStatusScreen(
                    onBack = { navController.popBackStack() },
                )
            }

            // ── MORE (Thêm) ────────────────────────────────────────────
            composable(NasRoutes.MORE) {
                val moreContext = androidx.compose.ui.platform.LocalContext.current
                var showAboutDialog by remember { mutableStateOf(false) }
                MoreSectionScreen(
                    onOpenPerformance = { navController.navigate(NasRoutes.PERFORMANCE) },
                    onLogout = {
                        domainProvider.livestream.viewModelScope.launch {
                            domainProvider.repository.addSystemLog("INFO", "Network",
                                "Người dùng '${WebDavManager.currentUser}' đã chủ động Đăng xuất.")
                        }
                        navController.navigate(NasRoutes.LOGIN) { popUpTo(0) }
                    },
                    // FIX-AUDIT-#6: het 2 TODO — Cai dat mo app settings he thong,
                    // Gioi thieu mo dialog version inline.
                    onOpenSettings = {
                        runCatching {
                            val intent = android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                            ).apply {
                                data = android.net.Uri.fromParts(
                                    "package", moreContext.packageName, null)
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            moreContext.startActivity(intent)
                        }
                    },
                    onOpenAbout = { showAboutDialog = true },
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
        }
    }
}
