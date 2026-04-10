package dev.heyari.ari.ui

import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.heyari.ari.ui.about.AboutScreen
import dev.heyari.ari.ui.components.AppDrawer
import dev.heyari.ari.ui.conversation.ConversationScreen
import dev.heyari.ari.ui.settings.SettingsScreen
import dev.heyari.ari.ui.settings.pages.GeneralSettingsPage
import dev.heyari.ari.ui.settings.pages.PermissionsSettingsPage
import dev.heyari.ari.ui.settings.pages.AssistantSettingsPage
import dev.heyari.ari.ui.settings.pages.SttSettingsPage
import dev.heyari.ari.ui.settings.pages.WakeWordSettingsPage
import dev.heyari.ari.ui.settings.skills.SkillDetailScreen
import dev.heyari.ari.ui.settings.skills.SkillsScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

object Routes {
    const val CONVERSATION = "conversation"
    const val SETTINGS = "settings"
    const val SETTINGS_GENERAL = "settings/general"
    const val SETTINGS_PERMISSIONS = "settings/permissions"
    const val SETTINGS_WAKEWORD = "settings/wakeword"
    const val SETTINGS_STT = "settings/stt"
    const val SETTINGS_LLM = "settings/llm"
    const val SKILLS = "skills"
    const val SKILL_DETAIL = "skills/detail/{skillId}?source={source}"
    const val ABOUT = "about"

    fun skillDetail(id: String, source: String) = "skills/detail/$id?source=$source"
}

/**
 * Top-level navigation host. The conversation screen is wrapped in a
 * [ModalNavigationDrawer]; subpages use a back-arrow top bar and are
 * navigated to directly from the drawer (Settings / Skills / About).
 *
 * [deepLinkCommands] is an optional flow of navigation commands emitted
 * from outside the NavHost (e.g. MainActivity translating a skill-update
 * notification tap into a [Routes.SKILLS] destination).
 */
@Composable
fun AriNavHost(deepLinkCommands: Flow<String>? = null) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    if (deepLinkCommands != null) {
        LaunchedEffect(deepLinkCommands) {
            deepLinkCommands.collect { route ->
                navController.navigate(route) { launchSingleTop = true }
            }
        }
    }

    fun navigateFromDrawer(route: String) {
        scope.launch { drawerState.close() }
        navController.navigate(route) { launchSingleTop = true }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawer(
                onOpenSettings = { navigateFromDrawer(Routes.SETTINGS) },
                onOpenSkills = { navigateFromDrawer(Routes.SKILLS) },
                onOpenAbout = { navigateFromDrawer(Routes.ABOUT) },
            )
        },
    ) {
        NavHost(
            navController = navController,
            startDestination = Routes.CONVERSATION,
        ) {
            composable(Routes.CONVERSATION) {
                ConversationScreen(
                    onOpenMenu = { scope.launch { drawerState.open() } },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenGeneral = { navController.navigate(Routes.SETTINGS_GENERAL) },
                    onOpenPermissions = { navController.navigate(Routes.SETTINGS_PERMISSIONS) },
                    onOpenWakeWord = { navController.navigate(Routes.SETTINGS_WAKEWORD) },
                    onOpenStt = { navController.navigate(Routes.SETTINGS_STT) },
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
            composable(Routes.SETTINGS_LLM) {
                AssistantSettingsPage(onBack = { navController.popBackStack() })
            }
            composable(Routes.SKILLS) {
                SkillsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenDetail = { id, source ->
                        navController.navigate(Routes.skillDetail(id, source))
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
                )
            }
            composable(Routes.ABOUT) {
                AboutScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
