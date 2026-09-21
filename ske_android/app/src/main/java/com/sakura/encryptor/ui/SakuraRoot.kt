package com.sakura.encryptor.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sakura.encryptor.AppContainer
import com.sakura.encryptor.core.player.PlaybackExtras
import com.sakura.encryptor.core.security.AppLockState
import com.sakura.encryptor.core.security.BiometricUnlock
import com.sakura.encryptor.data.prefs.ThemeMode
import com.sakura.encryptor.ui.components.LocalMusicController
import com.sakura.encryptor.ui.components.MiniPlayerBar
import com.sakura.encryptor.ui.components.rememberMusicController
import com.sakura.encryptor.ui.navigation.PlaybackSource
import com.sakura.encryptor.ui.navigation.Routes
import com.sakura.encryptor.ui.screens.browse.BrowseScreen
import com.sakura.encryptor.ui.screens.downloads.DownloadsScreen
import com.sakura.encryptor.ui.screens.encrypt.EncryptScreen
import com.sakura.encryptor.ui.screens.local.LocalScreen
import com.sakura.encryptor.ui.screens.lock.AppLockScreen
import com.sakura.encryptor.ui.screens.player.PlayerScreen
import com.sakura.encryptor.ui.screens.profiles.ProfileEditScreen
import com.sakura.encryptor.ui.screens.profiles.ProfilesScreen
import com.sakura.encryptor.ui.screens.settings.SettingsScreen
import com.sakura.encryptor.ui.theme.AccentTheme
import com.sakura.encryptor.ui.theme.SakuraTheme
import kotlinx.coroutines.launch

@Composable
fun SakuraRoot(container: AppContainer) {
    val accent by container.settings.accent
        .collectAsStateWithLifecycle(initialValue = AccentTheme.Indigo)
    val themeMode by container.settings.themeMode
        .collectAsStateWithLifecycle(initialValue = ThemeMode.System)
    val lockState by container.appLockManager.state.collectAsStateWithLifecycle()
    val biometricEnabled by container.settings.appLockBiometric
        .collectAsStateWithLifecycle(initialValue = false)

    val context = LocalContext.current
    // Asked once: the answer only changes when the user edits their device lock
    // screen, which tears the process down anyway.
    val biometricAvailable = remember { BiometricUnlock.isAvailable(context) }

    val darkTheme = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    // One connection for the whole app: the player screen and the mini player
    // both drive this session rather than opening competing ones.
    val musicController = rememberMusicController()
    val navController = rememberNavController()
    var currentRoute by remember { mutableStateOf<String?>(null) }

    CompositionLocalProvider(
        LocalAppContainer provides container,
        LocalMusicController provides musicController,
    ) {
        SakuraTheme(accent = accent, darkTheme = darkTheme) {
            when (lockState) {
                // The credential is still being read from disk. Showing the app
                // now would flash its content before the gate appears, and a
                // bare colour reads as a hang — so show the brand instead.
                AppLockState.Loading -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(52.dp),
                        )
                        Spacer(Modifier.height(18.dp))
                        Text(
                            text = "Sakura Encryptor",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "媒体保险箱",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                AppLockState.Locked -> AppLockScreen(
                    verify = container.appLockManager::verify,
                    onUnlocked = container.appLockManager::unlock,
                    biometricEnabled = biometricEnabled,
                    biometricAvailable = biometricAvailable,
                )

                // The bar floats over the content, so screens stay full-bleed and
                // only the very bottom of a list ends up behind it.
                AppLockState.Unlocked -> Box(modifier = Modifier.fillMaxSize()) {
                    SakuraNavigation(
                        navController = navController,
                        onRouteChanged = { currentRoute = it },
                    )

                    MiniPlayerBar(
                        controller = musicController,
                        // Inside a player the bar would only repeat what is
                        // already on screen.
                        enabled = currentRoute?.startsWith(PLAYER_ROUTE_PREFIX) != true,
                        onOpen = { item ->
                            // Step back into the full player for whatever is on.
                            val extras = item.mediaMetadata.extras ?: return@MiniPlayerBar
                            val name = extras.getString(PlaybackExtras.DISPLAY_NAME)
                                ?: return@MiniPlayerBar
                            val source = PlaybackSource.fromId(
                                extras.getString(PlaybackExtras.SOURCE)
                            )
                            val dir = extras.getString(PlaybackExtras.DIRECTORY).orEmpty()
                            val uri = item.localConfiguration?.uri?.toString().orEmpty()
                            navController.navigate(
                                Routes.player(uri, name, source.name, dir)
                            ) {
                                launchSingleTop = true
                            }
                        },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }
}

private data class DrawerDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val drawerDestinations = listOf(
    DrawerDestination(Routes.ENCRYPT, "加密", Icons.Rounded.Shield),
    DrawerDestination(Routes.BROWSE, "云端", Icons.Rounded.Cloud),
    DrawerDestination(Routes.LOCAL, "本地", Icons.Rounded.FolderOpen),
    DrawerDestination(Routes.DOWNLOADS, "下载", Icons.Rounded.Download),
    DrawerDestination(Routes.SETTINGS, "设置", Icons.Rounded.Settings),
)

/**
 * How far a screen shifts while switching from one drawer destination to the
 * next, as a fraction of the width. Small on purpose: enough to convey
 * direction without a full page slide.
 */
private const val TAB_SLIDE_FRACTION = 8

/**
 * How much of the screen width the open drawer covers.
 *
 * Expressed as a fraction rather than a fixed dp so the proportions hold on
 * every device: wide enough for the labels, narrow enough that the dimmed
 * content behind still reads as "a drawer slid out", not "the page changed".
 */
private const val DRAWER_WIDTH_FRACTION = 0.61f

/** -1 = moving left through the destinations, 1 = moving right, 0 = not a switch. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabDirection(): Int {
    val from = Routes.mainDestinations.indexOf(initialState.destination.route)
    val to = Routes.mainDestinations.indexOf(targetState.destination.route)
    if (from < 0 || to < 0) return 0
    return (to - from).coerceIn(-1, 1)
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isPlayerTarget(): Boolean =
    targetState.destination.route?.startsWith(PLAYER_ROUTE_PREFIX) == true

private val PLAYER_ROUTE_PREFIX = Routes.PLAYER.substringBefore('?')

@Composable
private fun SakuraNavigation(
    navController: NavHostController,
    onRouteChanged: (String?) -> Unit,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // The mini player sits above this screen, so it has to know when a player
    // screen comes and goes.
    LaunchedEffect(currentRoute) { onRouteChanged(currentRoute) }

    // The full-screen player owns every gesture while it is open, and the
    // sub-screens have a back arrow instead.
    val drawerEnabled = currentRoute in Routes.mainDestinations
    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }

    val openPlayer: (String, String, PlaybackSource, String) -> Unit = { uri, name, source, dir ->
        navController.navigate(Routes.player(uri, name, source.name, dir))
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerEnabled,
        drawerContent = {
            SakuraDrawer(
                currentRoute = currentRoute,
                onSelect = { route ->
                    scope.launch { drawerState.close() }
                    if (currentRoute != route) {
                        navController.navigate(route) {
                            popUpTo(Routes.ENCRYPT) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
            )
        },
    ) {
        NavHost(
            navController = navController,
            // The app opens straight into the encryption workspace: no account
            // and no password are required just to launch it.
            startDestination = Routes.ENCRYPT,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            enterTransition = {
                when {
                    isPlayerTarget() -> slideInVertically(
                        initialOffsetY = { it / 4 },
                        animationSpec = tween(280, easing = FastOutSlowInEasing),
                    ) + fadeIn(tween(220))

                    tabDirection() != 0 -> slideInHorizontally(
                        initialOffsetX = { tabDirection() * it / TAB_SLIDE_FRACTION },
                        animationSpec = tween(240, easing = FastOutSlowInEasing),
                    ) + fadeIn(tween(240))

                    else -> slideInHorizontally(
                        initialOffsetX = { it / 5 },
                        animationSpec = tween(260, easing = FastOutSlowInEasing),
                    ) + fadeIn(tween(200))
                }
            },
            exitTransition = {
                when {
                    isPlayerTarget() -> fadeOut(tween(180))

                    tabDirection() != 0 -> slideOutHorizontally(
                        targetOffsetX = { -tabDirection() * it / TAB_SLIDE_FRACTION },
                        animationSpec = tween(240, easing = FastOutSlowInEasing),
                    ) + fadeOut(tween(180))

                    else -> fadeOut(tween(160))
                }
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it / 5 },
                    animationSpec = tween(260, easing = FastOutSlowInEasing),
                ) + fadeIn(tween(200))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it / 5 },
                    animationSpec = tween(260, easing = FastOutSlowInEasing),
                ) + fadeOut(tween(180))
            },
        ) {
            composable(Routes.BROWSE) {
                BrowseScreen(
                    onOpenPlayer = openPlayer,
                    onOpenDrawer = openDrawer,
                    onManageProfiles = { navController.navigate(Routes.PROFILES) },
                    onGoLocal = {
                        navController.navigate(Routes.LOCAL) { launchSingleTop = true }
                    },
                )
            }

            composable(Routes.LOCAL) {
                LocalScreen(
                    onOpenPlayer = openPlayer,
                    onOpenDrawer = openDrawer,
                )
            }

            composable(Routes.ENCRYPT) {
                EncryptScreen(onOpenDrawer = openDrawer)
            }

            composable(Routes.DOWNLOADS) {
                DownloadsScreen(onOpenDrawer = openDrawer)
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onManageProfiles = { navController.navigate(Routes.PROFILES) },
                    onOpenDrawer = openDrawer,
                )
            }

            composable(Routes.PROFILES) {
                ProfilesScreen(
                    onEditProfile = { id -> navController.navigate(Routes.profileEdit(id)) },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.PROFILE_EDIT,
                arguments = listOf(
                    navArgument(Routes.PROFILE_ARG_ID) {
                        type = NavType.LongType
                        defaultValue = Routes.NEW_PROFILE_ID
                    }
                ),
            ) { entry ->
                val rawId = entry.arguments?.getLong(Routes.PROFILE_ARG_ID) ?: Routes.NEW_PROFILE_ID
                ProfileEditScreen(
                    profileId = rawId.takeIf { it != Routes.NEW_PROFILE_ID },
                    onDone = { navController.popBackStack(Routes.ENCRYPT, inclusive = false) },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.PLAYER,
                arguments = listOf(
                    navArgument(Routes.PLAYER_ARG_URI) { type = NavType.StringType },
                    navArgument(Routes.PLAYER_ARG_NAME) { type = NavType.StringType },
                    navArgument(Routes.PLAYER_ARG_SOURCE) { type = NavType.StringType },
                    navArgument(Routes.PLAYER_ARG_DIR) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                val uri = entry.arguments?.getString(Routes.PLAYER_ARG_URI).orEmpty()
                val name = entry.arguments?.getString(Routes.PLAYER_ARG_NAME).orEmpty()
                val source = PlaybackSource.fromId(entry.arguments?.getString(Routes.PLAYER_ARG_SOURCE))
                val dir = entry.arguments?.getString(Routes.PLAYER_ARG_DIR).orEmpty()

                PlayerScreen(
                    uri = uri,
                    displayName = name,
                    source = source,
                    directory = dir,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

/** The slide-in drawer listing every top-level destination. */
@Composable
private fun SakuraDrawer(
    currentRoute: String?,
    onSelect: (String) -> Unit,
) {
    val drawerWidth = (LocalConfiguration.current.screenWidthDp * DRAWER_WIDTH_FRACTION).dp

    ModalDrawerSheet(
        // requiredWidth rather than width: Material clamps drawer sheets to
        // 360dp, and on a wide screen that clamp would quietly win over 61%.
        modifier = Modifier.requiredWidth(drawerWidth),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerShape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier.padding(start = 26.dp, end = 26.dp, top = 26.dp, bottom = 22.dp)
        ) {
            Text(text = "Sakura Encryptor", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "媒体保险箱",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(horizontal = 18.dp),
        )

        Spacer(Modifier.height(10.dp))

        drawerDestinations.forEach { destination ->
            NavigationDrawerItem(
                label = { Text(destination.label) },
                icon = { Icon(destination.icon, contentDescription = null) },
                selected = currentRoute == destination.route,
                onClick = { onSelect(destination.route) },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}
