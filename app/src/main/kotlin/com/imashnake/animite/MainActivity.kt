package com.imashnake.animite

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.RoundedCorner
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.DefaultNavTransitions
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import com.anigooli.library.ConflictResolutionScreen
import com.anigooli.library.GenreManagerScreen
import com.anigooli.library.LibraryScreen
import com.anigooli.notifications.ReleaseNotificationHelper
import com.imashnake.animite.anime.AnimeScreen
import com.imashnake.animite.core.ui.Density
import com.imashnake.animite.core.ui.LocalPaddings
import com.imashnake.animite.core.ui.rememberDefaultPaddings
import com.imashnake.animite.explore.ExploreScreen
import com.imashnake.animite.explore.searchbar.SearchFrontDrop
import com.imashnake.animite.theme.AnimiteTheme
import com.imashnake.animite.theme.manropeFontFamily
import com.imashnake.animite.manga.MangaScreen
import com.imashnake.animite.media.MediaPage
import com.imashnake.animite.navigation.AnimeRoute
import com.imashnake.animite.navigation.ConflictResolutionRoute
import com.imashnake.animite.navigation.ExploreRoute
import com.imashnake.animite.navigation.GenreManagerRoute
import com.imashnake.animite.navigation.LibraryRoute
import com.imashnake.animite.navigation.MangaRoute
import com.imashnake.animite.navigation.NavigationBar
import com.imashnake.animite.navigation.NavigationBarPaths
import com.imashnake.animite.navigation.NavigationRail
import com.imashnake.animite.navigation.ProfileRoute
import com.imashnake.animite.navigation.SocialRoute
import com.imashnake.animite.profile.ProfileScreen
import com.imashnake.animite.profile.ProfileViewModel
import com.imashnake.animite.profile.dev.ANILIST_AUTH_DEEPLINK
import com.imashnake.animite.settings.SettingsPage
import com.imashnake.animite.settings.SettingsViewModel
import com.imashnake.animite.settings.Theme
import com.imashnake.animite.social.SocialScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private const val TAG = "MainActivity"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val profileViewModel: ProfileViewModel by viewModels()
    private val pendingNotificationMedia = MutableStateFlow<Pair<Int, String>?>(null)
    var showSplashScreen = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        extractNotificationTarget(intent)

        installSplashScreen().setKeepOnScreenCondition { showSplashScreen }
        enableEdgeToEdge()
        setContent {
            val fontFamilyResolver = LocalFontFamilyResolver.current
            LaunchedEffect(Unit) {
                try {
                    fontFamilyResolver.preload(manropeFontFamily)
                } catch (e: Exception) {
                    Log.e(TAG, "onCreate: Failed to load font: ${e.cause}: ${e.message}")
                }
                showSplashScreen = false
            }

            val hasRequestedNotif by settingsViewModel.hasRequestedNotificationPermission
                .collectAsStateWithLifecycle(initialValue = true)
            val notifPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { granted ->
                settingsViewModel.markNotificationPermissionRequested()
                settingsViewModel.setNotificationsEnabled(granted)
            }
            LaunchedEffect(hasRequestedNotif) {
                if (!hasRequestedNotif && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val alreadyGranted = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (alreadyGranted) {
                        settingsViewModel.markNotificationPermissionRequested()
                        settingsViewModel.setNotificationsEnabled(true)
                    } else {
                        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }

            val notificationTarget by pendingNotificationMedia.collectAsStateWithLifecycle()

            val theme by settingsViewModel.theme.collectAsState(initial = Theme.DEVICE_THEME.name)
            val useSystemColorScheme by settingsViewModel.useSystemColorScheme.collectAsState(initial = false)
            val isAmoled by settingsViewModel.isAmoled.collectAsState(initial = false)
            val density by settingsViewModel.density.collectAsState(initial = Density.COMFY.name)
            val showUserDescription by settingsViewModel.showUserDescription.collectAsStateWithLifecycle(initialValue = true)

            val useDarkTheme = when(Theme.valueOf(theme)) {
                Theme.DARK -> true
                Theme.LIGHT -> false
                Theme.DEVICE_THEME -> isSystemInDarkTheme()
            }

            val dayHour by settingsViewModel.dayHour.collectAsState(initial = null)

            val avatar by profileViewModel.viewerAvatar.collectAsState(initial = null)

            AnimiteTheme(
                paddings = rememberDefaultPaddings(Density.valueOf(density)),
                useDarkTheme = useDarkTheme,
                isAmoled = isAmoled,
                useSystemColorScheme = useSystemColorScheme,
                dayHour = dayHour
            ) {
                MainScreen(
                    deviceScreenCornerRadius = getDeviceScreenCornerRadius(),
                    useDarkTheme = useDarkTheme,
                    isAmoled = isAmoled,
                    avatar = avatar,
                    showUserDescription = showUserDescription,
                    pendingNotificationTarget = notificationTarget,
                    onConsumeNotificationTarget = { pendingNotificationMedia.value = null },
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractNotificationTarget(intent)
    }

    private fun extractNotificationTarget(intent: Intent?) {
        val mediaId = intent?.getIntExtra(ReleaseNotificationHelper.EXTRA_MEDIA_ID, -1) ?: -1
        if (mediaId > 0) {
            val mediaType = intent?.getStringExtra(ReleaseNotificationHelper.EXTRA_MEDIA_TYPE) ?: "ANIME"
            pendingNotificationMedia.value = mediaId to mediaType
        }
    }

    private fun getDeviceScreenCornerRadius(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return windowManager
                .currentWindowMetrics
                .windowInsets
                .getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)
                ?.radius
                ?: 0
        }
        return 0
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    deviceScreenCornerRadius: Int,
    useDarkTheme: Boolean,
    isAmoled: Boolean,
    avatar: String?,
    showUserDescription: Boolean,
    pendingNotificationTarget: Pair<Int, String>? = null,
    onConsumeNotificationTarget: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()

    LaunchedEffect(pendingNotificationTarget) {
        if (pendingNotificationTarget != null) {
            navController.navigate(
                MediaPage(
                    id = pendingNotificationTarget.first,
                    source = "Notification",
                    mediaType = pendingNotificationTarget.second,
                )
            )
            onConsumeNotificationTarget()
        }
    }

    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val isNavBarVisible = rememberSaveable(currentBackStackEntry) {
        if (currentBackStackEntry != null) {
            NavigationBarPaths.entries.any {
                it.matchesDestination(currentBackStackEntry!!)
            }
        } else false
    }

    var zoomableMediaImage by rememberSaveable { mutableStateOf<String?>(null) }

    // TODO: Refactor to use Scaffold once AnimatedVisibility issues are fixed;
    //  see https://issuetracker.google.com/issues/258270139.
    Box(modifier) {
        val animeScrollState = rememberScrollState()
        val mangaScrollState = rememberScrollState()
        val exploreScrollState = rememberLazyListState()

        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onBackground
        ) {
            SharedTransitionLayout {
                NavHost(
                    navController = navController,
                    startDestination = AnimeRoute,
                    predictivePopEnterTransition = {
                        DefaultNavTransitions.enterTransition.invoke(this)
                    },
                    predictivePopExitTransition = {
                        DefaultNavTransitions.exitTransition.invoke(this)
                    }
                ) {
                    composable<AnimeRoute> {
                        AnimeScreen(
                            onNavigateToMediaItem = navController::navigate,
                            onNavigateToExplore = navController::navigate,
                            scrollState = animeScrollState,
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedVisibilityScope = this,
                        )
                    }
                    composable<MangaRoute> {
                        MangaScreen(
                            onNavigateToMediaItem = navController::navigate,
                            onNavigateToExplore = navController::navigate,
                            scrollState = mangaScrollState,
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedVisibilityScope = this,
                        )
                    }
                    composable<MediaPage> {
                        MediaPage(
                            onBack = navController::navigateUp,
                            onNavigateToMediaItem = navController::navigate,
                            onNavigateToExplore = navController::navigate,
                            zoomableMediaImage = zoomableMediaImage,
                            setZoomableMediaImage = { zoomableMediaImage = it },
                            useDarkTheme = useDarkTheme,
                            isAmoled = isAmoled,
                            deviceScreenCornerRadius = deviceScreenCornerRadius,
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedVisibilityScope = this,
                        )
                    }
                    composable<ProfileRoute>(
                        deepLinks = listOf(
                            navDeepLink {
                                uriPattern = ANILIST_AUTH_DEEPLINK
                                action = Intent.ACTION_VIEW
                            }
                        )
                    ) {
                        ProfileScreen(
                            onNavigateToMediaItem = navController::navigate,
                            onNavigateToSettings = navController::navigate,
                            showUserDescription = showUserDescription,
                            deviceScreenCornerRadius = deviceScreenCornerRadius,
                            useDarkTheme = useDarkTheme,
                            isAmoled = isAmoled,
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedVisibilityScope = this,
                        )
                    }
                    composable<SettingsPage> {
                        SettingsPage(
                            versionName = BuildConfig.VERSION_NAME,
                            onNavigateToSocial = { navController.navigate(SocialRoute) },
                        )
                    }
                    composable<SocialRoute> {
                        SocialScreen()
                    }
                    composable<LibraryRoute> {
                        LibraryScreen(
                            onNavigateToMedia = { mediaId ->
                                navController.navigate(
                                    MediaPage(
                                        id = mediaId,
                                        source = "Library",
                                        mediaType = "ANIME",
                                    )
                                )
                            },
                            onNavigateToGenres = { navController.navigate(GenreManagerRoute) },
                            onNavigateToConflicts = { navController.navigate(ConflictResolutionRoute) },
                        )
                    }
                    composable<GenreManagerRoute> {
                        GenreManagerScreen(onBack = navController::navigateUp)
                    }
                    composable<ConflictResolutionRoute> {
                        ConflictResolutionScreen(onFinished = navController::navigateUp)
                    }
                    composable<ExploreRoute> {
                        ExploreScreen(
                            listState = exploreScrollState,
                            deviceScreenCornerRadius = deviceScreenCornerRadius,
                            onItemClick = { id, mediaType ->
                                navController.navigate(
                                    MediaPage(
                                        id = id,
                                        source = "Search",
                                        mediaType = mediaType.rawValue,
                                    )
                                )
                            }
                        )
                    }
                }
            }
        }

        when(LocalConfiguration.current.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> {
                AnimatedVisibility(
                    visible = isNavBarVisible,
                    modifier = Modifier.align(Alignment.CenterStart),
                    enter = slideInHorizontally { -it },
                    exit = slideOutHorizontally { -it }
                ) { NavigationRail(navController, avatar) }
            }
            else -> {
                val scope = rememberCoroutineScope()
                AnimatedVisibility(
                    visible = isNavBarVisible,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = slideInVertically { it },
                    exit = slideOutVertically { it }
                ) {
                    NavigationBar(
                        navController = navController,
                        avatar = avatar,
                        scrollToTop = {
                            scope.launch {
                                when (it) {
                                    NavigationBarPaths.Explore -> { exploreScrollState.animateScrollToItem(0) }
                                    // TODO: Fix ClassCastException.
//                                    NavigationBarPaths.Anime -> { animeScrollState.animateScrollTo(0) }
//                                    NavigationBarPaths.Manga -> { mangaScrollState.animateScrollTo(0) }
                                    else -> {}
                                }
                            }
                        }
                    )
                }
            }
        }

        SearchFrontDrop(
            hasExtraPadding = isNavBarVisible &&
                    LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT,
            onItemClick = { id, mediaType ->
                navController.navigate(
                    MediaPage(
                        id = id,
                        source = "Quick Search",
                        mediaType = mediaType.rawValue,
                    )
                )
            },
            isFabVisible = currentBackStackEntry?.destination?.hasRoute<SettingsPage>() == false
                    && currentBackStackEntry?.destination?.hasRoute<ExploreRoute>() == false
                    && currentBackStackEntry?.destination?.hasRoute<GenreManagerRoute>() == false
                    && currentBackStackEntry?.destination?.hasRoute<ConflictResolutionRoute>() == false
                    && zoomableMediaImage == null,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    start = LocalPaddings.current.large,
                    end = LocalPaddings.current.large,
                    bottom = LocalPaddings.current.large,
                )
        )
    }
}
