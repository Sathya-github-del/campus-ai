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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.quatrioai.app.R
import dev.quatrioai.app.core.HistoryText
import kotlinx.coroutines.launch

/**
 * The ask flow: home or an open conversation, with a chats drawer on the left (this session's
 * answers, then everything saved). Kept as one destination over one view model so a question in
 * flight, the reply box and camera text survive moving between them.
 */
@Composable
fun AskScreen(vm: AskViewModel, isGuest: Boolean, onOpenWallet: () -> Unit, onOpenCamera: () -> Unit, onOpenProfile: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    // Re-read prices and balance whenever this screen comes back (app reopened, back from wallet or
    // profile). Before, they were fetched once per process: a failed first fetch left the rate meters
    // blank until the app was killed, and a server price change never reached a running app.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh(); vm.loadHistory() }

    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    LaunchedEffect(drawer.isOpen) { if (drawer.isOpen) vm.loadHistory() }
    val closeDrawer: () -> Unit = { scope.launch { drawer.close() } }

    BackHandler(enabled = drawer.isOpen || s.openThread != null) {
        if (drawer.isOpen) closeDrawer() else vm.closeThread()
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Ink.Surface, drawerContentColor = Ink.Paper) {
                ChatsDrawer(s, isGuest,
                    onNewChat = { vm.closeThread(); closeDrawer() },
                    onOpenThread = { vm.openThread(it); closeDrawer() },
                    onOpenSaved = { vm.openSaved(it); closeDrawer() },
                    onOpenProfile = { closeDrawer(); onOpenProfile() })
            }
        },
    ) {
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
                if (thread == null) HomeScreen(s, vm, isGuest, onOpenWallet, onOpenCamera, onOpenDrawer = { scope.launch { drawer.open() } })
                else AnswerScreen(s, thread, vm, onOpenWallet, onOpenCamera)
            }
            // A saved conversation is being fetched from history: a small pill until it opens.
            if (s.openingSaved) {
                Row(
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 110.dp)
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
    }

    if (s.confirmExpert != null) ExpertDialog(s.prices, onConfirm = vm::confirmExpert, onDismiss = vm::dismissExpertConfirm)
}

/** The drawer: new chat, this session's answers, saved chats, and the account row at the bottom. */
@Composable
private fun ChatsDrawer(
    s: AskUiState,
    isGuest: Boolean,
    onNewChat: () -> Unit,
    onOpenThread: (Long) -> Unit,
    onOpenSaved: (String) -> Unit,
    onOpenProfile: () -> Unit,
) {
    val sessionIds = s.threads.mapNotNull { it.conversationId }.toSet()
    val saved = s.history?.filter { it.conversationId !in sessionIds }
    Column(Modifier.fillMaxHeight().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PilotLight()
            Spacer(Modifier.width(8.dp))
            Text("Quatrio AI", style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif), color = Ink.Paper)
        }
        DrawerRow(icon = R.drawable.ic_add, title = "New chat", tint = Lit.Tangerine, onClick = onNewChat)
        HorizontalDivider(color = Ink.LineSoft, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (s.threads.isNotEmpty()) {
                Legend("This session", Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp))
                s.threads.forEach { t ->
                    ChatRow(t.first.question, trailing = { if (t.pending) MeterRunning(12.dp, cells = 3) else Fare(t.totalCostInr, 12.dp) }) { onOpenThread(t.id) }
                }
            }
            Legend("Chats", Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
            when {
                saved == null && s.historyError == null -> Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    MeterRunning(12.dp, cells = 3); Spacer(Modifier.width(10.dp))
                    Text("Loading…", style = MaterialTheme.typography.bodySmall, color = Ink.PaperFaint)
                }
                saved == null -> Text("Couldn't load your chats. ${s.historyError}", style = MaterialTheme.typography.bodySmall, color = Ink.PaperFaint,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
                saved.isEmpty() -> Text("Nothing saved yet. Everything you ask shows up here.", style = MaterialTheme.typography.bodySmall,
                    color = Ink.PaperFaint, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
                else -> saved.forEach { item ->
                    ChatRow(HistoryText.question(item.firstQuestion), trailing = { Legend(HistoryText.date(item.createdAt), color = Ink.PaperFaint) }) {
                        onOpenSaved(item.conversationId)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        HorizontalDivider(color = Ink.LineSoft, modifier = Modifier.padding(horizontal = 16.dp))
        DrawerRow(
            icon = R.drawable.ic_person,
            title = if (isGuest) "Guest on this phone" else "Your account",
            subtitle = if (isGuest) "No account yet · tap to create one" else "Profile, history, balance",
            tint = Ink.PaperDim, onClick = onOpenProfile,
        )
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun DrawerRow(icon: Int, title: String, subtitle: String? = null, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Ink.Paper)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Ink.PaperFaint) }
        }
    }
}

@Composable
private fun ChatRow(title: String, trailing: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = "Open this chat", onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = Ink.Paper, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        trailing()
    }
}
