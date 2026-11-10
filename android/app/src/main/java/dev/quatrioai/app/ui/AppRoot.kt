package dev.quatrioai.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.quatrioai.app.QuatrioAiApp
import dev.quatrioai.app.core.LegalDoc
import dev.quatrioai.app.data.Session

private object Routes {
    const val WELCOME = "welcome"
    const val AUTH = "auth?mode={mode}&expired={expired}"
    const val ASK = "ask"
    const val WALLET = "wallet"
    const val CAMERA = "camera"
    const val PROFILE = "profile"
    const val LEGAL = "legal/{doc}"

    fun auth(mode: AuthMode, expired: Boolean = false) = "auth?mode=${mode.name.lowercase()}&expired=$expired"
    fun legal(doc: LegalDoc) = "legal/${doc.route}"
    /** Screens that make sense without a session (everything else needs a guest or account token). */
    fun isPublic(route: String?) = route == WELCOME || route == AUTH || route == LEGAL
}

/** Clears the whole back stack and opens [route], so Back can't return to a signed-out or stale screen. */
private fun NavHostController.resetTo(route: String) = navigate(route) { popUpTo(graph.id) { inclusive = true } }

@Composable
fun AppRoot(app: QuatrioAiApp) {
    // Decide the first screen once: anyone with a token (guest or account) goes straight to the ask screen.
    var start by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { start = if (app.backend.restoreSession() is Session.SignedOut) Routes.WELCOME else Routes.ASK }
    val startRoute = start ?: run { Box(Modifier.fillMaxSize().background(Ink.Bg)); return }

    val nav = rememberNavController()
    // Activity-scoped so questions in flight and this session's answers survive visits to the wallet and camera.
    val askVm: AskViewModel = viewModel(factory = viewModelFactory {
        initializer { AskViewModel(app.backend, app.store) }
    })

    // Log out, delete account and an account token the server rejected all end up here: back to login.
    val session by app.backend.session.collectAsStateWithLifecycle()
    LaunchedEffect(session) {
        val s = session
        val here = nav.currentDestination?.route
        if (s is Session.SignedOut && here != null && !Routes.isPublic(here)) {
            askVm.reset()
            nav.resetTo(Routes.auth(AuthMode.LOGIN, expired = s.expired))
        }
    }

    val signedIn: () -> Unit = {
        askVm.reset()
        askVm.refresh()
        nav.resetTo(Routes.ASK)
    }
    val openLegal: (LegalDoc) -> Unit = { nav.navigate(Routes.legal(it)) { launchSingleTop = true } }

    NavHost(navController = nav, startDestination = startRoute) {
        composable(Routes.WELCOME) {
            val vm: WelcomeViewModel = viewModel(factory = viewModelFactory { initializer { WelcomeViewModel(app.backend) } })
            WelcomeScreen(
                vm,
                onRegistered = {
                    askVm.refresh()
                    nav.resetTo(Routes.ASK)
                },
                onSignIn = { nav.navigate(Routes.auth(AuthMode.SIGNUP)) },
            )
        }
        composable(
            Routes.AUTH,
            arguments = listOf(
                navArgument("mode") { type = NavType.StringType; defaultValue = "signup" },
                navArgument("expired") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            val mode = if (entry.arguments?.getString("mode") == "login") AuthMode.LOGIN else AuthMode.SIGNUP
            val vm: AuthViewModel = viewModel(factory = viewModelFactory { initializer { AuthViewModel(app.backend, mode) } })
            AuthScreen(
                vm,
                expired = entry.arguments?.getBoolean("expired") == true,
                onDone = signedIn,
                onOpenLegal = openLegal,
                onBack = if (nav.previousBackStackEntry != null) ({ nav.popBackStack() }) else null,
            )
        }
        composable(Routes.ASK) {
            AskScreen(
                vm = askVm,
                isGuest = session == Session.Guest,
                onOpenWallet = { nav.navigate(Routes.WALLET) { launchSingleTop = true } },
                onOpenCamera = { nav.navigate(Routes.CAMERA) { launchSingleTop = true } },
                onOpenProfile = { nav.navigate(Routes.PROFILE) { launchSingleTop = true } },
            )
        }
        composable(Routes.WALLET) {
            val vm: WalletViewModel = viewModel(factory = viewModelFactory {
                initializer { WalletViewModel(app.backend, app.topUpFlow) }
            })
            WalletScreen(vm, onBack = { nav.popBackStack() })
        }
        composable(Routes.PROFILE) {
            val vm: ProfileViewModel = viewModel(factory = viewModelFactory { initializer { ProfileViewModel(app.backend) } })
            ProfileScreen(
                vm,
                onBack = { nav.popBackStack() },
                onOpenWallet = { nav.navigate(Routes.WALLET) { launchSingleTop = true } },
                onOpenAuth = { nav.navigate(Routes.auth(AuthMode.SIGNUP)) },
                onOpenLegal = openLegal,
            )
        }
        composable(Routes.LEGAL, arguments = listOf(navArgument("doc") { type = NavType.StringType })) { entry ->
            LegalScreen(LegalDoc.fromRoute(entry.arguments?.getString("doc")), onBack = { nav.popBackStack() })
        }
        composable(Routes.CAMERA) {
            CameraScreen(
                onText = { text ->
                    askVm.appendRecognised(text)
                    nav.popBackStack()
                },
                onBack = { nav.popBackStack() },
            )
        }
    }
}
