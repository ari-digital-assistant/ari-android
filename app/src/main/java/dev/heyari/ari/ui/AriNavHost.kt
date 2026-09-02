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
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.heyari.ari.data.SettingsRepository
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.navigation.compose.currentBackStackEntryAsState
import dev.heyari.ari.BuildConfig
import dev.heyari.ari.ui.about.AboutScreen
import dev.heyari.ari.ui.bugreport.BugReportFab
import dev.heyari.ari.ui.bugreport.BugReportScreen
import dev.heyari.ari.ui.conversation.ConversationScreen
import dev.heyari.ari.ui.menu.MenuScreen
import dev.heyari.ari.ui.onboarding.AssistantScreen
import dev.heyari.ari.ui.onboarding.CompleteScreen
import dev.heyari.ari.ui.onboarding.GeneralScreen
import dev.heyari.ari.ui.onboarding.ListeningScreen
import dev.heyari.ari.ui.onboarding.OnboardingViewModel
import dev.heyari.ari.ui.onboarding.PermissionsScreen
import dev.heyari.ari.ui.onboarding.LanguageScreen
import dev.heyari.ari.ui.onboarding.SttScreen
import dev.heyari.ari.ui.onboarding.WakeWordScreen
import dev.heyari.ari.ui.onboarding.WelcomeScreen
import dev.heyari.ari.ui.settings.SettingsScreen
import dev.heyari.ari.ui.settings.SettingsViewModel
import dev.heyari.ari.ui.settings.pages.AssistantSettingsPage
import dev.heyari.ari.ui.settings.pages.AutoUpdateSettingsPage
import dev.heyari.ari.ui.settings.pages.ConversationSettingsPage
import dev.heyari.ari.ui.settings.pages.GeneralSettingsPage
import dev.heyari.ari.ui.settings.pages.ListeningPlacesPage
import dev.heyari.ari.ui.settings.pages.ListeningSchedulesPage
import dev.heyari.ari.ui.settings.pages.PlaceEditorScreen
import dev.heyari.ari.ui.settings.pages.ScheduleEditorScreen
import dev.heyari.ari.ui.settings.pages.ListeningSettingsPage
import dev.heyari.ari.ui.settings.pages.PermissionsSettingsPage
import dev.heyari.ari.ui.settings.pages.SttSettingsPage
import dev.heyari.ari.ui.settings.pages.TtsSettingsPage
import dev.heyari.ari.ui.settings.pages.DebugSettingsPage
import dev.heyari.ari.ui.settings.pages.WakeWordSettingsPage
import dev.heyari.ari.ui.settings.skills.SKILLS_SHOW_INSTALLED_TAB_KEY
import dev.heyari.ari.ui.settings.skills.SkillDetailScreen
import dev.heyari.ari.ui.settings.skills.SkillsScreen
import dev.heyari.ari.wakeword.WakeWordService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

object Routes {
    const val CONVERSATION = "conversation"
    const val MENU = "menu"
    const val SETTINGS = "settings"
    const val SETTINGS_GENERAL = "settings/general"
    const val SETTINGS_PERMISSIONS = "settings/permissions"
    const val SETTINGS_WAKEWORD = "settings/wakeword"
    const val SETTINGS_LISTENING = "settings/listening"
    const val SETTINGS_LISTENING_SCHEDULES = "settings/listening/schedules"
    const val SETTINGS_LISTENING_PLACES = "settings/listening/places"
    const val SETTINGS_LISTENING_SCHEDULE_EDIT = "settings/listening/schedules/edit?id={id}"
    const val SETTINGS_LISTENING_PLACE_EDIT = "settings/listening/places/edit?id={id}"
    const val SETTINGS_STT = "settings/stt"
    const val SETTINGS_TTS = "settings/tts"
    const val SETTINGS_CONVERSATION = "settings/conversation"
    const val SETTINGS_LLM = "settings/llm"
    const val SETTINGS_AUTO_UPDATE = "settings/auto-update"
    const val SETTINGS_DEBUG = "settings/debug"
    const val BUG_REPORT = "bug-report"
    const val SKILLS = "skills?type={type}"
    const val SKILL_DETAIL = "skills/detail/{skillId}?source={source}"
    const val ABOUT = "about"

    // Onboarding wizard
    const val ONBOARDING_LANGUAGE = "onboarding/language"
    const val ONBOARDING_WELCOME = "onboarding/welcome"
    const val ONBOARDING_PERMISSIONS = "onboarding/permissions"
    const val ONBOARDING_WAKE_WORD = "onboarding/wakeword"
    const val ONBOARDING_LISTENING = "onboarding/listening"
    const val ONBOARDING_STT = "onboarding/stt"
    const val ONBOARDING_ASSISTANT = "onboarding/assistant"
    const val ONBOARDING_GENERAL = "onboarding/general"
    const val ONBOARDING_COMPLETE = "onboarding/complete"

    fun skillDetail(id: String, source: String) = "skills/detail/$id?source=$source"
    fun skills(type: String? = null) = if (type != null) "skills?type=$type" else "skills"
    fun scheduleEdit(id: String? = null) =
        if (id != null) "settings/listening/schedules/edit?id=$id" else "settings/listening/schedules/edit"
    fun placeEdit(id: String? = null) =
        if (id != null) "settings/listening/places/edit?id=$id" else "settings/listening/places/edit"
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

    // Navigation-compose defaults to a crossfade, which leaves both screens
    // semi-transparent mid-animation and lets the window background flash
    // through. Slide is what Android does everywhere else anyway: forward
    // pushes in from the right, back returns from the left.
    // The graph sits in a Box so the bug-report button can float over every
    // destination rather than being re-added to each one.
    var container by remember { mutableStateOf(IntSize.Zero) }
    val fabPosition by settingsRepository.bugReportFabPosition.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { container = it },
    ) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left)
        },
        exitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left)
        },
        popEnterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right)
        },
        popExitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right)
        },
    ) {
        composable(Routes.CONVERSATION) {
            ConversationScreen(
                onOpenMenu = { navController.navigate(Routes.MENU) { launchSingleTop = true } },
                onOpenAutoUpdate = {
                    navController.navigate(Routes.SETTINGS_AUTO_UPDATE) { launchSingleTop = true }
                },
                onOpenSkills = {
                    navController.navigate(Routes.skills()) { launchSingleTop = true }
                },
                onOpenAssistantSkills = {
                    navController.navigate(Routes.skills(type = "assistant")) {
                        launchSingleTop = true
                    }
                },
                onOpenListeningSettings = {
                    navController.navigate(Routes.SETTINGS_LISTENING) { launchSingleTop = true }
                },
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
                onOpenListening = { navController.navigate(Routes.SETTINGS_LISTENING) },
                onOpenStt = { navController.navigate(Routes.SETTINGS_STT) },
                onOpenTts = { navController.navigate(Routes.SETTINGS_TTS) },
                onOpenConversation = { navController.navigate(Routes.SETTINGS_CONVERSATION) },
                onOpenLlm = { navController.navigate(Routes.SETTINGS_LLM) },
                onOpenAutoUpdate = { navController.navigate(Routes.SETTINGS_AUTO_UPDATE) },
                onOpenDebug = { navController.navigate(Routes.SETTINGS_DEBUG) },
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
        composable(Routes.SETTINGS_LISTENING) {
            ListeningSettingsPage(
                onBack = { navController.popBackStack() },
                onOpenSchedules = { navController.navigate(Routes.SETTINGS_LISTENING_SCHEDULES) },
                onOpenPlaces = { navController.navigate(Routes.SETTINGS_LISTENING_PLACES) },
            )
        }
        composable(Routes.SETTINGS_LISTENING_SCHEDULES) {
            ListeningSchedulesPage(
                onBack = { navController.popBackStack() },
                onOpenEditor = { id -> navController.navigate(Routes.scheduleEdit(id)) },
            )
        }
        composable(Routes.SETTINGS_LISTENING_PLACES) {
            ListeningPlacesPage(
                onBack = { navController.popBackStack() },
                onOpenEditor = { id -> navController.navigate(Routes.placeEdit(id)) },
            )
        }
        composable(
            Routes.SETTINGS_LISTENING_SCHEDULE_EDIT,
            arguments = listOf(
                navArgument("id") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            ScheduleEditorScreen(
                scheduleId = entry.arguments?.getString("id"),
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            Routes.SETTINGS_LISTENING_PLACE_EDIT,
            arguments = listOf(
                navArgument("id") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            PlaceEditorScreen(
                placeId = entry.arguments?.getString("id"),
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS_STT) {
            SttSettingsPage(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_TTS) {
            TtsSettingsPage(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_CONVERSATION) {
            ConversationSettingsPage(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_LLM) {
            AssistantSettingsPage(
                onBack = { navController.popBackStack() },
                onOpenSkills = {
                    navController.navigate(Routes.skills(type = "assistant")) { launchSingleTop = true }
                },
            )
        }
        composable(Routes.SETTINGS_AUTO_UPDATE) {
            AutoUpdateSettingsPage(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_DEBUG) {
            DebugSettingsPage(onBack = { navController.popBackStack() })
        }
        composable(Routes.BUG_REPORT) {
            val context = LocalContext.current
            BugReportScreen(
                onClose = { navController.popBackStack() },
                // The filed issue opens in a browser rather than in-app: it is
                // a public page on somebody else's site, and pretending
                // otherwise is how a webview ends up owning a login flow.
                onOpenIssue = { url ->
                    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
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
                consumeShowInstalledTabFlag = {
                    val flag = entry.savedStateHandle
                        .remove<Boolean>(SKILLS_SHOW_INSTALLED_TAB_KEY)
                    flag == true
                },
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
                onJustInstalledFromBrowse = {
                    // Hand a one-shot flag back to whichever screen
                    // pushed us here. SkillsScreen reads this on
                    // resume and switches to the Installed tab so the
                    // user lands on the row for the skill they just
                    // installed instead of being thrown back to the
                    // Browse list they came from.
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(SKILLS_SHOW_INSTALLED_TAB_KEY, true)
                },
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }

        // ── Onboarding wizard (nested graph for shared ViewModel) ──
        //
        // Both view models are scoped to the nested "onboarding" graph so
        // they're shared across all wizard screens. We resolve the entry
        // via remember { getBackStackEntry("onboarding") } so the lookup
        // happens once during initial composition, not on recomposition
        // during exit transitions (which would crash because the graph
        // entry has already been popped).
        //
        // The SettingsViewModel scoping matters for more than tidiness:
        // per-destination instances meant every step of the wizard stood up
        // a fresh one, and its constructor opens two dozen preference flows,
        // stats the model directories and enumerates the TTS voices.
        navigation(
            startDestination = Routes.ONBOARDING_LANGUAGE,
            route = "onboarding",
        ) {
            composable(Routes.ONBOARDING_LANGUAGE) {
                val graphEntry = remember(it) { navController.getBackStackEntry("onboarding") }
                val onboardingViewModel: OnboardingViewModel = hiltViewModel(graphEntry)
                LanguageScreen(
                    onboardingViewModel = onboardingViewModel,
                    onNext = { navController.navigate(Routes.ONBOARDING_WELCOME) },
                    onBack = {
                        // Revisit-only back: returns to conversation
                        // without committing onboarding (the user
                        // already completed it once).
                        navController.navigate(Routes.CONVERSATION) {
                            popUpTo("onboarding") { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }

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
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.ONBOARDING_PERMISSIONS) {
                val graphEntry = remember(it) { navController.getBackStackEntry("onboarding") }
                val settingsViewModel: SettingsViewModel = hiltViewModel(graphEntry)
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
                    onOpenOverlaySettings = settingsViewModel::openOverlaySettings,
                    onOpenAppSettings = settingsViewModel::openAppSettings,
                )
            }

            composable(Routes.ONBOARDING_WAKE_WORD) {
                val graphEntry = remember(it) { navController.getBackStackEntry("onboarding") }
                val settingsViewModel: SettingsViewModel = hiltViewModel(graphEntry)
                val onboardingViewModel: OnboardingViewModel = hiltViewModel(graphEntry)
                val wizardState by onboardingViewModel.state.collectAsStateWithLifecycle()

                WakeWordScreen(
                    settingsViewModel = settingsViewModel,
                    onboardingViewModel = onboardingViewModel,
                    onNext = {
                        if (wizardState.startListeningNow) {
                            WakeWordService.start(context)
                        }
                        navController.navigate(Routes.ONBOARDING_LISTENING)
                    },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.ONBOARDING_LISTENING) {
                val graphEntry = remember(it) { navController.getBackStackEntry("onboarding") }
                val settingsViewModel: SettingsViewModel = hiltViewModel(graphEntry)
                ListeningScreen(
                    settingsViewModel = settingsViewModel,
                    // Everyone sees the STT step now. It used to be skipped
                    // for non-English locales because Whisper-turbo was
                    // their only choice, but the step is no longer a model
                    // picker — it is on-device vs cloud, which is a real
                    // choice in every language. Which local model serves
                    // on-device is still decided for them.
                    onNext = { navController.navigate(Routes.ONBOARDING_STT) },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.ONBOARDING_STT) {
                val graphEntry = remember(it) { navController.getBackStackEntry("onboarding") }
                val settingsViewModel: SettingsViewModel = hiltViewModel(graphEntry)
                SttScreen(
                    settingsViewModel = settingsViewModel,
                    onNext = { navController.navigate(Routes.ONBOARDING_ASSISTANT) },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.ONBOARDING_ASSISTANT) {
                val graphEntry = remember(it) { navController.getBackStackEntry("onboarding") }
                val settingsViewModel: SettingsViewModel = hiltViewModel(graphEntry)
                val onboardingViewModel: OnboardingViewModel = hiltViewModel(graphEntry)
                AssistantScreen(
                    settingsViewModel = settingsViewModel,
                    onboardingViewModel = onboardingViewModel,
                    // AssistantScreen.onPrimary commits the router decision
                    // from the chosen assistant + locale before this fires.
                    onNext = { navController.navigate(Routes.ONBOARDING_GENERAL) },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.ONBOARDING_GENERAL) {
                val graphEntry = remember(it) { navController.getBackStackEntry("onboarding") }
                val settingsViewModel: SettingsViewModel = hiltViewModel(graphEntry)
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
                        // Land on the conversation screen as the back-stack
                        // root first (popping the onboarding graph), THEN push
                        // the assistant-skills browser on top. Navigating
                        // straight to skills with popUpTo("onboarding")
                        // inclusive made skills the root, so backing out of the
                        // skills list dead-ended instead of returning to chat.
                        navController.navigate(Routes.CONVERSATION) {
                            popUpTo("onboarding") { inclusive = true }
                            launchSingleTop = true
                        }
                        navController.navigate(Routes.skills(type = "assistant")) {
                            launchSingleTop = true
                        }
                    },
                )
            }
        }
    }

        // Testing builds only. Hidden while the reporter itself is open —
        // offering to report a bug from inside the bug reporter is noise.
        val route = navController.currentBackStackEntryAsState().value?.destination?.route
        if (BuildConfig.ARI_TESTING && route != Routes.BUG_REPORT) {
            BugReportFab(
                container = container,
                position = fabPosition,
                onMoved = { x, y ->
                    scope.launch { settingsRepository.setBugReportFabPosition(x, y) }
                },
                onClick = {
                    navController.navigate(Routes.BUG_REPORT) { launchSingleTop = true }
                },
            )
        }
    }
}
