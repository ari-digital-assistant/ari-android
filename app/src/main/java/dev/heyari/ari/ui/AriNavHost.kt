package dev.heyari.ari.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.heyari.ari.ui.conversation.ConversationScreen
import dev.heyari.ari.ui.settings.SettingsScreen

object Routes {
    const val CONVERSATION = "conversation"
    const val SETTINGS = "settings"
}

@Composable
fun AriNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.CONVERSATION,
    ) {
        composable(Routes.CONVERSATION) {
            ConversationScreen(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }
}
