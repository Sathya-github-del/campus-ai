package dev.studyapp.app.ui

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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.studyapp.app.StudyApp
import kotlinx.coroutines.flow.first

private object Routes {
    const val WELCOME = "welcome"
    const val ASK = "ask"
    const val WALLET = "wallet"
    const val CAMERA = "camera"
}

@Composable
fun AppRoot(app: StudyApp) {
    // Decide the first screen once: registered devices go straight to the ask screen.
    var start by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { start = if (app.store.hasToken.first()) Routes.ASK else Routes.WELCOME }
    val startRoute = start ?: run { Box(Modifier.fillMaxSize().background(Ink.Bg)); return }

    val nav = rememberNavController()
    // Activity-scoped so questions in flight and this session's answers survive visits to the wallet and camera.
    val askVm: AskViewModel = viewModel(factory = viewModelFactory {
        initializer { AskViewModel(app.backend, app.store) }
    })

    NavHost(navController = nav, startDestination = startRoute) {
        composable(Routes.WELCOME) {
            val vm: WelcomeViewModel = viewModel(factory = viewModelFactory { initializer { WelcomeViewModel(app.backend) } })
            WelcomeScreen(vm, onRegistered = {
                askVm.refresh()
                nav.navigate(Routes.ASK) { popUpTo(Routes.WELCOME) { inclusive = true } }
            })
        }
        composable(Routes.ASK) {
            AskScreen(
                vm = askVm,
                onOpenWallet = { nav.navigate(Routes.WALLET) { launchSingleTop = true } },
                onOpenCamera = { nav.navigate(Routes.CAMERA) { launchSingleTop = true } },
            )
        }
        composable(Routes.WALLET) {
            val vm: WalletViewModel = viewModel(factory = viewModelFactory {
                initializer { WalletViewModel(app.backend, app.topUpFlow) }
            })
            WalletScreen(vm, onBack = { nav.popBackStack() })
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
