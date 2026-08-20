package com.capstone.chatapp.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.capstone.chatapp.ui.chat.ChatScreen
import com.capstone.chatapp.ui.discover.DiscoverScreen
import com.capstone.chatapp.ui.emergency.EmergencyScreen
import com.capstone.chatapp.ui.home.HomeScreen
import com.capstone.chatapp.ui.login.LoginScreen
import com.capstone.chatapp.ui.pairing.PairingScreen
import com.capstone.chatapp.ui.profile.ProfileScreen
import com.capstone.chatapp.ui.signup.SignupScreen

@Composable
fun AppNavHost(startLoggedIn: Boolean) {
    val navController = rememberNavController()
    val start = if (startLoggedIn) Routes.Home.route else Routes.Login.route

    NavHost(navController = navController, startDestination = start) {

        composable(Routes.Login.route) {
            LoginScreen(
                onLoggedIn = {
                    navController.navigate(Routes.Home.route) {
                        popUpTo(Routes.Login.route) { inclusive = true }
                    }
                },
                onNavigateSignup = { navController.navigate(Routes.Signup.route) },
            )
        }

        composable(Routes.Signup.route) {
            SignupScreen(
                onSignedUp = {
                    navController.navigate(Routes.Home.route) {
                        popUpTo(Routes.Login.route) { inclusive = true }
                    }
                },
                onNavigateLogin = { navController.popBackStack() },
            )
        }

        composable(Routes.Home.route) {
            HomeScreen(
                onOpenChat = { peerUid, peerName ->
                    navController.navigate(Routes.Chat.build(peerUid, peerName))
                },
                onOpenDiscover = { navController.navigate(Routes.Discover.route) },
                onOpenProfile = { navController.navigate(Routes.Profile.route) },
                onEmergency = { navController.navigate(Routes.Emergency.route) },
            )
        }

        composable(Routes.Discover.route) {
            DiscoverScreen(
                onOpenChat = { peerUid, peerName ->
                    navController.navigate(Routes.Chat.build(peerUid, peerName))
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.Emergency.route) {
            EmergencyScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.Chat.route,
            arguments = listOf(
                navArgument(Routes.Chat.ARG_PEER_UID) { type = NavType.StringType },
                navArgument(Routes.Chat.ARG_PEER_NAME) {
                    type = NavType.StringType; defaultValue = "Chat"
                },
            ),
        ) { entry ->
            val peerUid = entry.arguments?.getString(Routes.Chat.ARG_PEER_UID).orEmpty()
            val peerName = Uri.decode(entry.arguments?.getString(Routes.Chat.ARG_PEER_NAME) ?: "Chat")
            ChatScreen(
                peerUid = peerUid,
                peerName = peerName,
                onBack = { navController.popBackStack() },
                onVerify = { navController.navigate(Routes.Pairing.build(peerUid, peerName)) },
            )
        }

        composable(Routes.Profile.route) {
            ProfileScreen(
                onBack = { navController.popBackStack() },
                onLoggedOut = {
                    navController.navigate(Routes.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onOpenPairing = { navController.navigate(Routes.Pairing.build()) },
            )
        }

        composable(
            route = Routes.Pairing.route,
            arguments = listOf(
                navArgument(Routes.Pairing.ARG_PEER_UID) {
                    type = NavType.StringType; nullable = true; defaultValue = null
                },
                navArgument(Routes.Pairing.ARG_PEER_NAME) {
                    type = NavType.StringType; nullable = true; defaultValue = null
                },
            ),
        ) { entry ->
            val pairPeerUid = entry.arguments?.getString(Routes.Pairing.ARG_PEER_UID)?.takeIf { it.isNotBlank() }
            val pairPeerName = entry.arguments?.getString(Routes.Pairing.ARG_PEER_NAME)
                ?.let { Uri.decode(it) }
                ?.takeIf { it.isNotBlank() }
            PairingScreen(
                peerUid = pairPeerUid,
                peerName = pairPeerName,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
