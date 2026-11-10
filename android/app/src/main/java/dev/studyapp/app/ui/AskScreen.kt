package dev.studyapp.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The ask flow: home ("Got a question?") or an open answer card. Kept as one destination over one
 * view model so a question in flight, the follow-up box and camera text survive moving between them.
 */
@Composable
fun AskScreen(vm: AskViewModel, onOpenWallet: () -> Unit, onOpenCamera: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()

    BackHandler(enabled = s.openThread != null) {
        if (s.composingFollowUp) vm.cancelFollowUp() else vm.closeThread()
    }

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
        if (thread == null) HomeScreen(s, vm, onOpenWallet, onOpenCamera)
        else AnswerScreen(s, thread, vm, onOpenWallet, onOpenCamera)
    }

    if (s.confirmExpert != null) ExpertDialog(s.prices, onConfirm = vm::confirmExpert, onDismiss = vm::dismissExpertConfirm)
}
