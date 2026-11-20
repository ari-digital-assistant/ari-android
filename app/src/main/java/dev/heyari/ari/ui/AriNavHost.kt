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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import android.app.Activity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import dev.heyari.ari.bugreport.BugReportHandoff
import dev.heyari.ari.bugreport.CrashRecorder
import dev.heyari.ari.bugreport.captureWindow
import dev.heyari.ari.R
import dev.heyari.ari.data.SettingsRepository
import dev.heyari.ari.data.restartAri
import dev.heyari.ari.ui.settings.SettingsTransferViewModel
import dev.heyari.ari.ui.settings.pages.IMPORT_MIME_TYPES
import dev.heyari.ari.ui.settings.pages.ImportDialog
import dev.heyari.ari.ui.settings.AdviceMode
import dev.heyari.ari.ui.settings.pages.FalseWakeAdvicePage
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
import dev.heyari.ari.ui.bugreport.MyReportsPage
import dev.heyari.ari.ui.bugreport.BugReportScreen
import dev.heyari.ari.ui.conversation.ConversationScreen
import dev.heyari.ari.ui.menu.MenuScreen
import dev.heyari.ari.ui.onboarding.AssistantScreen
import dev.heyari.ari.ui.onboarding.CompleteScreen
import dev.heyari.ari.ui.onboarding.GeneralScreen
import dev.heyari.ari.ui.onboarding.ListeningScreen
import dev.heyari.ari.ui.onboarding.OnboardingViewModel
import dev.heyari.ari.ui.onboarding.PermissionsScreen
import dev.heyari.ari.ui.onboarding.RecordingsScreen
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
import dev.heyari.ari.ui.settings.pages.DeveloperSettingsPage
import dev.heyari.ari.ui.settings.pages.SensitivityTuningPage
import dev.heyari.ari.ui.settings.pages.WakeSamplesPage
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
    const val SETTINGS_WAKEWORD_TUNE = "settings/wakeword/tune"
    const val SETTINGS_WAKEWORD_ADVICE = "settings/wakeword/advice?mode={mode}"
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
    const val SETTINGS_DEVELOPER = "settings/developer"
    const val SETTINGS_DEBUG = "settings/debug"
    const val BUG_REPORT = "bug-report"
    const val MY_REPORTS = "settings/debug/my-reports"
    const val WAKE_SAMPLES = "settings/debug/wake-samples"
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
    const val ONBOARDING_RECORDINGS = "onboarding/recordings"
    const val ONBOARDING_COMPLETE = "onboarding/complete"

    fun wakeWordAdvice(mode: AdviceMode) = "settings/wakeword/advice?mode=${mode.name}"

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
    handoff: BugReportHandoff,
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
    // False until loaded, so the hint can't flash up for a tester who has seen it.
    val fabHintDue by settingsRepository.bugReportFabHintDue.collectAsStateWithLifecycle(false)
    val scope = rememberCoroutineScope()

    // Offered once, on the first launch after a crash. Reading the record
    // consumes it: a prompt that returns every launch until it is accepted is
    // nagging rather than helpful.
    var pendingCrash by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (BuildConfig.ARI_TESTING) pendingCrash = CrashRecorder.consume(context)
    }
    pendingCrash?.let { trace ->
        AlertDialog(
            onDismissRequest = { pendingCrash = null },
            title = { Text(stringResource(R.string.crash_prompt_title)) },
            text = { Text(stringResource(R.string.crash_prompt_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingCrash = null
                    handoff.offer(crashTrace = trace)
                    navController.navigate(Routes.BUG_REPORT) { launchSingleTop = true }
                }) { Text(stringResource(R.string.crash_prompt_report)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingCrash = null }) {
                    Text(stringResource(R.string.crash_prompt_dismiss))
                }
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { container = it },
    ) {
    // Tap-driven navigation goes through dropUnlessResumed, or the same
    // RESUMED check where the callback takes arguments. An entry only
    // reaches RESUMED once its slide has settled, and a navigate or pop
    // landing mid-slide can leave NavHost's transition frozen partway, the
    // screen stuck half off the edge (ari-android#39).
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
        composable(Routes.CONVERSATION) { entry ->
            ConversationScreen(
                onOpenMenu = dropUnlessResumed {
                    navController.navigate(Routes.MENU) { launchSingleTop = true }
                },
                onOpenAutoUpdate = dropUnlessResumed {
                    navController.navigate(Routes.SETTINGS_AUTO_UPDATE) { launchSingleTop = true }
                },
                onOpenSkills = dropUnlessResumed {
                    navController.navigate(Routes.skills()) { launchSingleTop = true }
                },
                onOpenAssistantSkills = dropUnlessResumed {
                    navController.navigate(Routes.skills(type = "assistant")) {
                        launchSingleTop = true
                    }
                },
                onOpenListeningSettings = dropUnlessResumed {
                    navController.navigate(Routes.SETTINGS_LISTENING) { launchSingleTop = true }
                },
                onOpenListeningPlaces = dropUnlessResumed {
                    navController.navigate(Routes.SETTINGS_LISTENING_PLACES) { launchSingleTop = true }
                },
                onOpenSkillSettings = { id ->
                    if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        navController.navigate(Routes.skillDetail(id, "installed")) {
                            launchSingleTop = true
                        }
                    }
                },
                onOpenStoreSkill = { id ->
                    if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        navController.navigate(Routes.skillDetail(id, "browse")) {
                            launchSingleTop = true
                        }
                    }
                },
                onOpenSpeechSettings = dropUnlessResumed {
                    navController.navigate(Routes.SETTINGS_STT) { launchSingleTop = true }
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
                onBack = dropUnlessResumed { navController.popBackStack() },
                onOpenSettings = dropUnlessResumed {
                    navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                },
                onOpenSkills = dropUnlessResumed {
                    navController.navigate(Routes.skills()) { launchSingleTop = true }
                },
                onOpenAbout = dropUnlessResumed {
                    navController.navigate(Routes.ABOUT) { launchSingleTop = true }
                },
                onOpenSetupWizard = dropUnlessResumed {
                    navController.navigate("onboarding") { launchSingleTop = true }
                },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = dropUnlessResumed { navController.popBackStack() },
                onOpenGeneral = dropUnlessResumed {
                    navController.navigate(Routes.SETTINGS_GENERAL)
                },
                onOpenPermissions = dropUnlessResumed {
                    navController.navigate(Routes.SETTINGS_PERMISSIONS)
                },
                onOpenWakeWord = dropUnlessResumed {
                    navController.navigate(Routes.SETTINGS_WAKEWORD)
                },
                onOpenListening = dropUnlessResumed {
                    navController.navigate(Routes.SETTINGS_LISTENING)
                },
                onOpenStt = dropUnlessResumed { navController.navigate(Routes.SETTINGS_STT) },
                onOpenTts = dropUnlessResumed { navController.navigate(Routes.SETTINGS_TTS) },
                onOpenConversation = dropUnlessResumed {
                    navController.navigate(Routes.SETTINGS_CONVERSATION)
                },
                onOpenLlm = dropUnlessResumed { navController.navigate(Routes.SETTINGS_LLM) },
                onOpenAutoUpdate = dropUnlessResumed {
                    navController.navigate(Routes.SETTINGS_AUTO_UPDATE)
                },
                onOpenDeveloper = dropUnlessResumed {
                    navController.navigate(Routes.SETTINGS_DEVELOPER)
                },
                onOpenDebug = dropUnlessResumed { navController.navigate(Routes.SETTINGS_DEBUG) },
            )
        }
        composable(Routes.SETTINGS_GENERAL) {
            GeneralSettingsPage(onBack = dropUnlessResumed { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_PERMISSIONS) {
            PermissionsSettingsPage(onBack = dropUnlessResumed { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_WAKEWORD) {
            WakeWordSettingsPage(
                onBack = dropUnlessResumed { navController.popBackStack() },
                onTune = dropUnlessResumed {
                    navController.navigate(Routes.SETTINGS_WAKEWORD_TUNE)
                },
            )
        }
        composable(
            route = Routes.SETTINGS_WAKEWORD_ADVICE,
            arguments = listOf(
                navArgument("mode") {
                    type = NavType.StringType
                    defaultValue = AdviceMode.BURST.name
                },
            ),
        ) { entry ->
            // An unrecognised mode falls back to BURST rather than crashing:
            // this route is reached from a notification, which can outlive the
            // build that posted it.
            val mode = runCatching {
                AdviceMode.valueOf(entry.arguments?.getString("mode").orEmpty())
            }.getOrDefault(AdviceMode.BURST)
            FalseWakeAdvicePage(
                mode = mode,
                // Unguarded: the page closes itself from a LaunchedEffect,
                // which can fire before its own slide has settled.
                onDone = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS_WAKEWORD_TUNE) {
            SensitivityTuningPage(onBack = dropUnlessResumed { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_LISTENING) {
            ListeningSettingsPage(
                onBack = dropUnlessResumed { navController.popBackStack() },
                onOpenSchedules = dropUnlessResumed {
                    navController.navigate(Routes.SETTINGS_LISTENING_SCHEDULES)
                },
                onOpenPlaces = dropUnlessResumed {
                    navController.navigate(Routes.SETTINGS_LISTENING_PLACES)
                },
            )
        }
        composable(Routes.SETTINGS_LISTENING_SCHEDULES) { entry ->
            ListeningSchedulesPage(
                onBack = dropUnlessResumed { navController.popBackStack() },
                onOpenEditor = { id ->
                    if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        navController.navigate(Routes.scheduleEdit(id))
                    }
                },
            )
        }
        composable(Routes.SETTINGS_LISTENING_PLACES) { entry ->
            ListeningPlacesPage(
                onBack = dropUnlessResumed { navController.popBackStack() },
                onOpenEditor = { id ->
                    if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        navController.navigate(Routes.placeEdit(id))
                    }
                },
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
                onBack = dropUnlessResumed { navController.popBackStack() },
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
                onBack = dropUnlessResumed { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS_STT) {
            SttSettingsPage(onBack = dropUnlessResumed { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_TTS) {
            TtsSettingsPage(onBack = dropUnlessResumed { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_CONVERSATION) {
            ConversationSettingsPage(onBack = dropUnlessResumed { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_LLM) {
            AssistantSettingsPage(
                onBack = dropUnlessResumed { navController.popBackStack() },
                onOpenSkills = dropUnlessResumed {
                    navController.navigate(Routes.skills(type = "assistant")) { launchSingleTop = true }
                },
            )
        }
        composable(Routes.SETTINGS_AUTO_UPDATE) {
            AutoUpdateSettingsPage(onBack = dropUnlessResumed { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_DEVELOPER) {
            DeveloperSettingsPage(onBack = dropUnlessResumed { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_DEBUG) {
            DebugSettingsPage(
                onBack = dropUnlessResumed { navController.popBackStack() },
                onOpenMyReports = dropUnlessResumed { navController.navigate(Routes.MY_REPORTS) },
                onOpenWakeSamples = dropUnlessResumed {
                    navController.navigate(Routes.WAKE_SAMPLES)
                },
            )
        }
        composable(Routes.WAKE_SAMPLES) {
            WakeSamplesPage(onBack = dropUnlessResumed { navController.popBackStack() })
        }
        composable(Routes.MY_REPORTS) {
            val context = LocalContext.current
            MyReportsPage(
                onBack = dropUnlessResumed { navController.popBackStack() },
                onOpenIssue = { url ->
                    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                },
            )
        }
        composable(Routes.BUG_REPORT) {
            val context = LocalContext.current
            BugReportScreen(
                onClose = dropUnlessResumed { navController.popBackStack() },
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
                onBack = dropUnlessResumed { navController.popBackStack() },
                onOpenDetail = { id, source ->
                    if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        navController.navigate(Routes.skillDetail(id, source))
                    }
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
                onBack = dropUnlessResumed { navController.popBackStack() },
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
            AboutScreen(onBack = dropUnlessResumed { navController.popBackStack() })
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
                    onNext = dropUnlessResumed {
                        navController.navigate(Routes.ONBOARDING_WELCOME)
                    },
                    onBack = dropUnlessResumed {
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
                val transferViewModel: SettingsTransferViewModel = hiltViewModel(graphEntry)
                val importState by transferViewModel.importState.collectAsStateWithLifecycle()
                val importLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri -> uri?.let(transferViewModel::import) }
                // The file made the wizard's choices; permissions are the one
                // thing it can't carry, so that is where the wizard picks up.
                ImportDialog(
                    state = importState,
                    doneLabel = stringResource(R.string.action_continue),
                    onDone = {
                        onboardingViewModel.setImported()
                        transferViewModel.dismissImport()
                        navController.navigate(Routes.ONBOARDING_PERMISSIONS)
                    },
                    onDismiss = transferViewModel::dismissImport,
                )
                WelcomeScreen(
                    onboardingViewModel = onboardingViewModel,
                    onGetStarted = dropUnlessResumed {
                        navController.navigate(Routes.ONBOARDING_PERMISSIONS)
                    },
                    onImport = { importLauncher.launch(IMPORT_MIME_TYPES) },
                    onSkip = dropUnlessResumed {
                        onboardingViewModel.completeOnboarding()
                        navController.navigate(Routes.CONVERSATION) {
                            popUpTo("onboarding") { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onBack = dropUnlessResumed { navController.popBackStack() },
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

                val imported = onboardingViewModel.state.collectAsStateWithLifecycle().value.imported

                PermissionsScreen(
                    settingsViewModel = settingsViewModel,
                    onboardingViewModel = onboardingViewModel,
                    onNext = dropUnlessResumed {
                        navController.navigate(if (imported) Routes.ONBOARDING_COMPLETE else Routes.ONBOARDING_WAKE_WORD)
                    },
                    onNextMicDenied = dropUnlessResumed {
                        navController.navigate(if (imported) Routes.ONBOARDING_COMPLETE else Routes.ONBOARDING_ASSISTANT)
                    },
                    onBack = dropUnlessResumed { navController.popBackStack() },
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
                    onOpenBatterySettings = settingsViewModel::openBatterySettings,
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
                    onTune = dropUnlessResumed {
                        navController.navigate(Routes.SETTINGS_WAKEWORD_TUNE)
                    },
                    onNext = dropUnlessResumed {
                        if (wizardState.startListeningNow) {
                            WakeWordService.start(context)
                        }
                        navController.navigate(Routes.ONBOARDING_LISTENING)
                    },
                    onBack = dropUnlessResumed { navController.popBackStack() },
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
                    onNext = dropUnlessResumed { navController.navigate(Routes.ONBOARDING_STT) },
                    onBack = dropUnlessResumed { navController.popBackStack() },
                )
            }

            composable(Routes.ONBOARDING_STT) {
                val graphEntry = remember(it) { navController.getBackStackEntry("onboarding") }
                val settingsViewModel: SettingsViewModel = hiltViewModel(graphEntry)
                SttScreen(
                    settingsViewModel = settingsViewModel,
                    onNext = dropUnlessResumed {
                        navController.navigate(Routes.ONBOARDING_ASSISTANT)
                    },
                    onBack = dropUnlessResumed { navController.popBackStack() },
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
                    onNext = dropUnlessResumed {
                        navController.navigate(Routes.ONBOARDING_GENERAL)
                    },
                    onBack = dropUnlessResumed { navController.popBackStack() },
                )
            }

            composable(Routes.ONBOARDING_GENERAL) {
                val graphEntry = remember(it) { navController.getBackStackEntry("onboarding") }
                val settingsViewModel: SettingsViewModel = hiltViewModel(graphEntry)
                GeneralScreen(
                    settingsViewModel = settingsViewModel,
                    onNext = dropUnlessResumed {
                        navController.navigate(Routes.ONBOARDING_RECORDINGS)
                    },
                    onBack = dropUnlessResumed { navController.popBackStack() },
                )
            }

            composable(Routes.ONBOARDING_RECORDINGS) {
                RecordingsScreen(
                    onNext = dropUnlessResumed {
                        navController.navigate(Routes.ONBOARDING_COMPLETE)
                    },
                    onBack = dropUnlessResumed { navController.popBackStack() },
                )
            }

            composable(Routes.ONBOARDING_COMPLETE) {
                val graphEntry = remember(it) { navController.getBackStackEntry("onboarding") }
                val onboardingViewModel: OnboardingViewModel = hiltViewModel(graphEntry)
                CompleteScreen(
                    onboardingViewModel = onboardingViewModel,
                    onDone = dropUnlessResumed {
                        if (onboardingViewModel.state.value.imported) {
                            restartAri(context)
                        } else {
                            navController.navigate(Routes.CONVERSATION) {
                                popUpTo("onboarding") { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    },
                    onBrowseCloudSkills = dropUnlessResumed {
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
                showHint = fabHintDue,
                onHintShown = {
                    scope.launch { settingsRepository.setBugReportFabHintShown() }
                },
                onMoved = { x, y ->
                    scope.launch { settingsRepository.setBugReportFabPosition(x, y) }
                },
                // Unguarded: a stuck transition never resumes its entry, and
                // that is exactly the screen someone will want to report.
                onClick = {
                    // Captured before navigating: a moment later the screen
                    // worth photographing is the report form.
                    val activity = context as? Activity
                    if (activity == null) {
                        navController.navigate(Routes.BUG_REPORT) { launchSingleTop = true }
                    } else {
                        captureWindow(activity) { png ->
                            handoff.offer(screenshot = png)
                            navController.navigate(Routes.BUG_REPORT) { launchSingleTop = true }
                        }
                    }
                },
            )
        }
    }
}
