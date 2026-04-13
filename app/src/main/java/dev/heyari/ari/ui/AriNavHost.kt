package dev.heyari.ari.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.heyari.ari.data.SettingsRepository
import dev.heyari.ari.ui.about.AboutScreen
import dev.heyari.ari.ui.conversation.ConversationScreen
import dev.heyari.ari.ui.menu.MenuScreen
import dev.heyari.ari.ui.onboarding.AssistantScreen
import dev.heyari.ari.ui.onboarding.CompleteScreen
import dev.heyari.ari.ui.onboarding.GeneralScreen
import dev.heyari.ari.ui.onboarding.OnboardingViewModel
import dev.heyari.ari.ui.onboarding.PermissionsScreen
import dev.heyari.ari.ui.onboarding.SttScreen
import dev.heyari.ari.ui.onboarding.WakeWordScreen
import dev.heyari.ari.ui.onboarding.WelcomeScreen
import dev.heyari.ari.ui.settings.SettingsScreen
import dev.heyari.ari.ui.settings.SettingsViewModel
import dev.heyari.ari.ui.settings.pages.AssistantSettingsPage
import dev.heyari.ari.ui.settings.pages.GeneralSettingsPage
import dev.heyari.ari.ui.settings.pages.PermissionsSettingsPage
import dev.heyari.ari.ui.settings.pages.SttSettingsPage
import dev.heyari.ari.ui.settings.pages.TtsSettingsPage
import dev.heyari.ari.ui.settings.pages.WakeWordSettingsPage
import dev.heyari.ari.ui.settings.skills.SkillDetailScreen
import dev.heyari.ari.ui.settings.skills.SkillsScreen
import dev.heyari.ari.wakeword.WakeWordService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

object Routes {
    const val CONVERSATION = "conversation"
    const val MENU = "menu"
    const val SETTINGS = "settings"
    const val SETTINGS_GENERAL = "settings/general"
    const val SETTINGS_PERMISSIONS = "settings/permissions"
    const val SETTINGS_WAKEWORD = "settings/wakeword"
    const val SETTINGS_STT = "settings/stt"
    const val SETTINGS_TTS = "settings/tts"
    const val SETTINGS_LLM = "settings/llm"
    const val SKILLS = "skills?type={type}"
    const val SKILL_DETAIL = "skills/detail/{skillId}?source={source}"
    const val ABOUT = "about"

    // Onboarding wizard
    const val ONBOARDING_WELCOME = "onboarding/welcome"
    const val ONBOARDING_PERMISSIONS = "onboarding/permissions"
    const val ONBOARDING_WAKE_WORD = "onboarding/wakeword"
    const val ONBOARDING_STT = "onboarding/stt"
    const val ONBOARDING_ASSISTANT = "onboarding/assistant"
    const val ONBOARDING_GENERAL = "onboarding/general"
    const val ONBOARDING_COMPLETE = "onboarding/complete"

    fun skillDetail(id: String, source: String) = "skills/detail/$id?source=$source"
    fun skills(type: String? = null) = if (type != null) "skills?type=$type" else "skills"
}

/**
 * Top-level navigation host. The hamburger icon on the conversation screen
 * navigates to a full-screen [MenuScreen] (replacing the old modal drawer).
 * Subpages use a back-arrow top bar.
 *
 * [deepLinkCommands] is an optional flow of navigation commands emitted
 * from outside the NavHost (e.g. MainActivity translating a skill-update
 * notification tap into a [Routes.SKILLS] destination).
 */
@Composable
fun AriNavHost(
    deepLinkCommands: Flow<String>? = null,
    settingsRepository: SettingsRepository,
) {
    val navController = rememberNavController()
    val context = LocalContext.current

    // First-run gating: synchronous DataStore read (sub-ms cache hit after first access).
    val startDestination = if (runBlocking { settingsRepository.onboardingCompleted.first() }) {
        Routes.CONVERSATION
    } else {
        "onboarding"
    }

    if (deepLinkCommands != null) {
        LaunchedEffect(deepLinkCommands) {
            deepLinkCommands.collect { route ->
                navController.navigate(route) { launchSingleTop = true }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(Routes.CONVERSATION) {
            ConversationScreen(
                onOpenMenu = { navController.navigate(Routes.MENU) { launchSingleTop = true } },
            )
        }
        composable(
            route = Routes.MENU,
            enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right) },
            exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right) },
            popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right) },
            popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right) },
        ) {
            MenuScreen(
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                onOpenSkills = { navController.navigate(Routes.skills()) { launchSingleTop = true } },
                onOpenAbout = { navController.navigate(Routes.ABOUT) { launchSingleTop = true } },
                onOpenSetupWizard = { navController.navigate("onboarding") { launchSingleTop = true } },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenGeneral = { navController.navigate(Routes.SETTINGS_GENERAL) },
                onOpenPermissions = { navController.navigate(Routes.SETTINGS_PERMISSIONS) },
                onOpenWakeWord = { navController.navigate(Routes.SETTINGS_WAKEWORD) },
                onOpenStt = { navController.navigate(Routes.SETTINGS_STT) },
                onOpenTts = { navController.navigate(Routes.SETTINGS_TTS) },
                onOpenLlm = { navController.navigate(Routes.SETTINGS_LLM) },
            )
        }
        composable(Routes.SETTINGS_GENERAL) {
            GeneralSettingsPage(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_PERMISSIONS) {
            PermissionsSettingsPage(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_WAKEWORD) {
            WakeWordSettingsPage(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_STT) {
            SttSettingsPage(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_TTS) {
            TtsSettingsPage(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_LLM) {
            AssistantSettingsPage(
                onBack = { navController.popBackStack() },
                onOpenSkills = {
                    navController.navigate(Routes.skills(type = "assistant")) { launchSingleTop = true }
                },
            )
        }
        composable(
            route = Routes.SKILLS,
            arguments = listOf(
                navArgument("type") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val typeFilter = entry.arguments?.getString("type")
            SkillsScreen(
                onBack = { navController.popBackStack() },
                onOpenDetail = { id, source ->
                    navController.navigate(Routes.skillDetail(id, source))
                },
                initialTypeFilter = typeFilter,
            )
        }
        composable(
            route = Routes.SKILL_DETAIL,
            arguments = listOf(
                navArgument("skillId") { type = NavType.StringType },
                navArgument("source") {
                    type = NavType.StringType
                    defaultValue = "browse"
                },
            ),
        ) { entry ->
            val skillId = entry.arguments?.getString("skillId").orEmpty()
            val source = entry.arguments?.getString("source") ?: "browse"
            SkillDetailScreen(
                skillId = skillId,
                source = source,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }

        // ── Onboarding wizard (nested graph for shared ViewModel) ──
        //
        // The OnboardingViewModel is scoped to the nested "onboarding"
        // graph so it's shared across all wizard screens. We resolve it
        // via remember { getBackStackEntry("onboarding") } so the lookup
        // happens once during initial composition, not on recomposition
        // during exit transitions (which would crash because the graph
        // entry has already been popped).
        navigation(
            startDestination = Routes.ONBOARDING_WELCOME,
            route = "onboarding",
        ) {
            composable(Routes.ONBOARDING_WELCOME) {
                val graphEntry = remember(it) { navController.getBackStackEntry("onboarding") }
                val onboardingViewModel: OnboardingViewModel = hiltViewModel(graphEntry)
                WelcomeScreen(
                    onboardingViewModel = onboardingViewModel,
                    onGetStarted = { navController.navigate(Routes.ONBOARDING_PERMISSIONS) },
                    onSkip = {
                        onboardingViewModel.completeOnboarding()
                        navController.navigate(Routes.CONVERSATION) {
                            popUpTo("onboarding") { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onBack = {
                        navController.navigate(Routes.CONVERSATION) {
                            popUpTo("onboarding") { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }

            composable(Routes.ONBOARDING_PERMISSIONS) {
                val graphEntry = remember(it) { navController.getBackStackEntry("onboarding") }
                val settingsViewModel: SettingsViewModel = hiltViewModel()
                val onboardingViewModel: OnboardingViewModel = hiltViewModel(graphEntry)

                val recordAudioLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { settingsViewModel.refreshPermissions() }

                val notificationsLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { settingsViewModel.refreshPermissions() }

                PermissionsScreen(
                    settingsViewModel = settingsViewModel,
                    onboardingViewModel = onboardingViewModel,
                    onNext = { navController.navigate(Routes.ONBOARDING_WAKE_WORD) },
                    onNextMicDenied = { navController.navigate(Routes.ONBOARDING_ASSISTANT) },
                    onBack = { navController.popBackStack() },
                    onRequestRecordAudio = {
                        recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onRequestNotifications = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onOpenFsnSettings = settingsViewModel::openFsnSettings,
                    onOpenOverlaySettings = settingsViewModel::openOverlaySettings,
                    onOpenAppSettings = settingsViewModel::openAppSettings,
                )
            }

            composable(Routes.ONBOARDING_WAKE_WORD) {
                val graphEntry = remember(it) { navController.getBackStackEntry("onboarding") }
                val settingsViewModel: SettingsViewModel = hiltViewModel()
                val onboardingViewModel: OnboardingViewModel = hiltViewModel(graphEntry)
                val wizardState by onboardingViewModel.state.collectAsStateWithLifecycle()

                WakeWordScreen(
                    settingsViewModel = settingsViewModel,
                    onboardingViewModel = onboardingViewModel,
                    onNext = {
                        if (wizardState.startListeningNow) {
                            val intent = Intent(context, WakeWordService::class.java)
                            ContextCompat.startForegroundService(context, intent)
                        }
                        navController.navigate(Routes.ONBOARDING_STT)
                    },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.ONBOARDING_STT) {
                val settingsViewModel: SettingsViewModel = hiltViewModel()
                SttScreen(
                    settingsViewModel = settingsViewModel,
                    onNext = { navController.navigate(Routes.ONBOARDING_ASSISTANT) },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.ONBOARDING_ASSISTANT) {
                val graphEntry = remember(it) { navController.getBackStackEntry("onboarding") }
                val settingsViewModel: SettingsViewModel = hiltViewModel()
                val onboardingViewModel: OnboardingViewModel = hiltViewModel(graphEntry)
                AssistantScreen(
                    settingsViewModel = settingsViewModel,
                    onboardingViewModel = onboardingViewModel,
                    onNext = { navController.navigate(Routes.ONBOARDING_GENERAL) },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.ONBOARDING_GENERAL) {
                val settingsViewModel: SettingsViewModel = hiltViewModel()
                GeneralScreen(
                    settingsViewModel = settingsViewModel,
                    onNext = { navController.navigate(Routes.ONBOARDING_COMPLETE) },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.ONBOARDING_COMPLETE) {
                val graphEntry = remember(it) { navController.getBackStackEntry("onboarding") }
                val onboardingViewModel: OnboardingViewModel = hiltViewModel(graphEntry)
                CompleteScreen(
                    onboardingViewModel = onboardingViewModel,
                    onDone = {
                        navController.navigate(Routes.CONVERSATION) {
                            popUpTo("onboarding") { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onBrowseCloudSkills = {
                        onboardingViewModel.completeOnboarding()
                        navController.navigate(Routes.skills(type = "assistant")) {
                            popUpTo("onboarding") { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
        }
    }
}
