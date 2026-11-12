package dev.quatrioai.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The ask flow: home ("Got a question?") or an open answer card. Kept as one destination over one
 * view model so a question in flight, the follow-up box and camera text survive moving between them.
 */
@Composable
fun AskScreen(vm: AskViewModel, isGuest: Boolean, onOpenWallet: () -> Unit, onOpenCamera: () -> Unit, onOpenProfile: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    // Re-read prices and balance whenever this screen comes back (app reopened, back from wallet or
    // profile). Before, they were fetched once per process: a failed first fetch left the rate meters
    // blank until the app was killed, and a server price change never reached a running app.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    BackHandler(enabled = s.openThread != null) { vm.closeThread() }

    Box(Modifier.fillMaxSize()) {
    AnimatedContent(
        targetState = s.openThread?.id,
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally(tween(320)) { it / 3 } + fadeIn(tween(320))) togetherWith fadeOut(tween(160))
            } else {
                fadeIn(tween(260)) togetherWith (slideOutHorizontally(tween(300)) { it / 3 } + fadeOut(tween(220)))
            }
        },
        label = "ask",
    ) { id ->
        val thread = id?.let { i -> s.threads.firstOrNull { it.id == i } }
        if (thread == null) HomeScreen(s, vm, isGuest, onOpenWallet, onOpenCamera, onOpenProfile)
        else AnswerScreen(s, thread, vm, onOpenWallet, onOpenCamera)
    }
    // A saved conversation is being fetched from history: a small pill until it opens.
    if (s.openingSaved) {
        Row(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp)
                .clip(MaterialTheme.shapes.extraLarge).background(Ink.Raised2).border(1.dp, Ink.Line, MaterialTheme.shapes.extraLarge)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MeterRunning(12.dp, cells = 3)
            Spacer(Modifier.width(10.dp))
            Text("Opening that conversation…", style = MaterialTheme.typography.bodySmall, color = Ink.PaperDim)
        }
    }
    }

    if (s.confirmExpert != null) ExpertDialog(s.prices, onConfirm = vm::confirmExpert, onDismiss = vm::dismissExpertConfirm)
}
