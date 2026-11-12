package dev.quatrioai.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.quatrioai.app.R
import dev.quatrioai.app.core.Example
import dev.quatrioai.app.core.Examples
import dev.quatrioai.app.core.Money
import dev.quatrioai.app.core.Tier
import dev.quatrioai.app.core.TopUpAmount
import kotlinx.coroutines.delay

/**
 * Home, laid out like a chat app's start screen: a menu for your chats, a greeting, a few things
 * people ask, this session's answers, and the question box pinned at the bottom with the tier
 * picker (the "model" chip) and send. The theme is the same night meter: the balance stays in the
 * top bar and every rate still reads as a fare.
 */
@Composable
fun HomeScreen(
    s: AskUiState,
    vm: AskViewModel,
    isGuest: Boolean,
    onOpenWallet: () -> Unit,
    onOpenCamera: () -> Unit,
    onOpenDrawer: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Ink.Bg).statusBarsPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                IconButton(onClick = onOpenDrawer) { Icon(painterResource(R.drawable.ic_menu), "Your chats", tint = Ink.Paper) }
                // Guests get a small lit pilot dot: a quiet nudge that they have no account yet.
                if (isGuest) Box(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp).size(8.dp)
                    .clip(RoundedCornerShape(50)).background(Lit.Tangerine))
            }
            PilotLight()
            Spacer(Modifier.width(8.dp))
            Text("Quatrio AI", style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif), color = Ink.Paper)
            Spacer(Modifier.weight(1f))
            BalanceMeter(s.balance, onOpenWallet)
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(if (s.threads.isEmpty()) 64.dp else 24.dp))
            Text(
                "What can I help with?",
                style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Serif),
                color = Ink.Paper, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Pay for the answer, not the month.",
                style = MaterialTheme.typography.bodyMedium, color = Ink.PaperDim, textAlign = TextAlign.Center,
            )

            if (s.balance != null && s.balance <= 0.0 && s.threads.isEmpty()) {
                Spacer(Modifier.height(22.dp))
                FirstFareCard(s, onOpenWallet)
            }

            Spacer(Modifier.height(22.dp))
            SuggestionChips(paused = s.draft.isNotEmpty(), onPick = vm::useExample)

            if (s.threads.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                SessionAnswers(s, vm::openThread)
            }
            Spacer(Modifier.height(16.dp))
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            if (s.needsTopUp) AddFareBanner(s.balance, onOpenWallet, Modifier.padding(bottom = 10.dp))
            s.error?.let { ErrorBanner(it, vm::dismissError, Modifier.padding(bottom = 10.dp)) }
        }
        AskComposer(s, vm, onOpenCamera)
    }
}

/** The question box pinned at the bottom: camera, text, tier + rate, send. */
@Composable
private fun AskComposer(s: AskUiState, vm: AskViewModel, onOpenCamera: () -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(s.draft, TextRange(s.draft.length))) }
    if (field.text != s.draft) field = TextFieldValue(s.draft, TextRange(s.draft.length))
    val shape = RoundedCornerShape(26.dp)
    var hint by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(s.draft.isEmpty()) {
        while (s.draft.isEmpty()) {
            delay(3200)
            hint = (hint + 1) % Examples.ALL.size
        }
    }

    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(shape).background(Ink.Surface).border(1.dp, Ink.Line, shape).padding(6.dp),
        ) {
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
                if (field.text.isEmpty()) {
                    AnimatedContent(
                        targetState = hint,
                        transitionSpec = {
                            (slideInVertically(tween(420)) { it } + fadeIn(tween(420))) togetherWith
                                (slideOutVertically(tween(420)) { -it } + fadeOut(tween(300)))
                        },
                        label = "hint",
                    ) { i ->
                        Text("Ask anything… e.g. “${Examples.ALL[i].text}”", style = MaterialTheme.typography.bodyLarge, color = Ink.PaperFaint,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                BasicTextField(
                    value = field,
                    onValueChange = { field = it; vm.setDraft(it.text) },
                    enabled = !s.sending,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink.Paper),
                    cursorBrush = SolidColor(Lit.Tangerine),
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Your question" },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onOpenCamera, enabled = !s.sending) {
                    Icon(painterResource(R.drawable.ic_camera), "Ask with a photo", tint = Lit.Tangerine)
                }
                // Rates follow the question as it's typed: a long or pasted question shows a higher "about ₹X".
                TierMenu(s.tier, s.askEstimate, s.livePrices, vm::setTier)
                Spacer(Modifier.weight(1f))
                if (s.sending) {
                    MeterRunning(14.dp, cells = 3)
                    Spacer(Modifier.width(12.dp))
                } else {
                    FilledIconButton(
                        onClick = vm::ask,
                        enabled = s.canAsk,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Lit.Tangerine, contentColor = Color(0xFF2B1600),
                            disabledContainerColor = Ink.Raised2, disabledContentColor = Ink.PaperFaint,
                        ),
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_send), s.askEstimate?.let { "Ask, ${Money.estimate(it)}" } ?: "Ask")
                    }
                }
            }
        }
        Text(
            "The exact fare shows on your answer. If something fails, nothing is charged.",
            style = MaterialTheme.typography.bodySmall, color = Ink.PaperFaint, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

/** Shown on a fresh install with a ₹0 balance: what the minimum top-up actually buys. */
@Composable
private fun FirstFareCard(s: AskUiState, onOpenWallet: () -> Unit) {
    val fast = s.prices.firstOrNull { it.tier == Tier.FAST }?.estCostInr
    val smart = s.prices.firstOrNull { it.tier == Tier.SMART }?.estCostInr
    val expert = s.prices.firstOrNull { it.tier == Tier.EXPERT }?.estCostInr
    val n = fast?.let { Money.messagesFor(TopUpAmount.MIN.toDouble(), it) } ?: 0
    // Any tier can be free (rate ₹0.00): then a ₹0 balance doesn't stop anyone asking, so say so.
    val fastFree = fast != null && fast <= 0.0
    val smartFree = smart != null && smart <= 0.0
    val expertFree = expert != null && expert <= 0.0
    val allFree = fastFree && smartFree && expertFree
    MeterPanel(Modifier.fillMaxWidth(), border = Lit.Tangerine.copy(alpha = 0.45f), contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Legend("Your meter reads ₹0.00", color = Lit.Amber)
                Text(
                    if (allFree) "Every tier is free right now — ask away. Add ${Money.inrWhole(TopUpAmount.MIN)} whenever you want it ready for later."
                    else if (fastFree) "Fast answers are free right now. Add ${Money.inrWhole(TopUpAmount.MIN)} for Smart and Expert."
                    else "Add ${Money.inrWhole(TopUpAmount.MIN)} to start" +
                        (if (n > 0) ". That's roughly ${Money.count(n)} quick answers." else "."),
                    style = MaterialTheme.typography.bodyMedium, color = Ink.Paper, modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = onOpenWallet, colors = ButtonDefaults.buttonColors(containerColor = Lit.Tangerine)) {
                Text("Add ${Money.inrWhole(TopUpAmount.MIN)}")
            }
        }
    }
}

/** A few things people ask, as tappable pills that rotate every few seconds (paused while typing). */
@Composable
private fun SuggestionChips(paused: Boolean, onPick: (Example) -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(paused, page) {
        if (!paused) {
            delay(7000)
            page++
        }
    }
    AnimatedContent(
        targetState = page,
        transitionSpec = { (slideInVertically(tween(450)) { it / 4 } + fadeIn(tween(450))) togetherWith fadeOut(tween(200)) },
        label = "examples",
    ) { p ->
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Examples.page(p, size = 3).forEach { ex ->
                Text(
                    ex.text,
                    style = MaterialTheme.typography.bodyMedium, color = Ink.PaperDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, Ink.LineSoft, RoundedCornerShape(50))
                        .clickable(onClickLabel = "Use this example") { onPick(ex) }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }
    }
}

/** This session's answers, newest first. Tap to reopen (and reply). */
@Composable
private fun SessionAnswers(s: AskUiState, onOpen: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Legend("This session")
            Spacer(Modifier.weight(1f))
            Legend("${Money.cost(s.sessionCostInr)} total", color = Ink.PaperDim)
        }
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            s.threads.forEach { t -> ReceiptRow(t, onClick = { onOpen(t.id) }) }
        }
    }
}

@Composable
private fun ReceiptRow(t: AskThread, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(Ink.Surface)
            .border(1.dp, Ink.LineSoft, MaterialTheme.shapes.medium)
            .clickable(onClickLabel = "Open answer", onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(t.first.question, style = MaterialTheme.typography.bodyMedium, color = Ink.Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                TierTag(t.first.tier)
                if (t.isThread) Legend("  · ${t.exchanges.size} parts")
            }
        }
        Spacer(Modifier.width(12.dp))
        if (t.pending) MeterRunning(14.dp, cells = 3)
        else Fare(t.totalCostInr, 14.dp)
    }
}
