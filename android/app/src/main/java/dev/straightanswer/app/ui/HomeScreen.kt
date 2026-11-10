package dev.straightanswer.app.ui

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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.straightanswer.app.R
import dev.straightanswer.app.core.Example
import dev.straightanswer.app.core.Examples
import dev.straightanswer.app.core.Money
import dev.straightanswer.app.core.Tier
import dev.straightanswer.app.core.TopUpAmount
import kotlinx.coroutines.delay

/**
 * Home: "Got a question?". A big question box and an equally big camera tile side by side, the
 * tier cards, one "Ask" button that shows the rate, then this session's answers and rotating
 * examples that show the range of things people ask.
 */
@Composable
fun HomeScreen(
    s: AskUiState,
    vm: AskViewModel,
    isGuest: Boolean,
    onOpenWallet: () -> Unit,
    onOpenCamera: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Ink.Bg).statusBarsPadding().imePadding()) {
        HomeTopBar(s.balance, isGuest, onOpenWallet, onOpenProfile)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            Text("Got a question?", style = MaterialTheme.typography.displaySmall, color = Ink.Paper)
            Spacer(Modifier.height(8.dp))
            Text(
                "Ask anything. You pay for this one answer, never a monthly plan.",
                style = MaterialTheme.typography.bodyLarge, color = Ink.PaperDim,
            )

            if (s.balance != null && s.balance <= 0.0 && s.threads.isEmpty()) {
                Spacer(Modifier.height(18.dp))
                FirstFareCard(s, onOpenWallet)
            }

            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                QuestionBox(
                    text = s.draft,
                    onText = vm::setDraft,
                    rotate = s.draft.isEmpty(),
                    enabled = !s.sending,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                CameraTile(onOpenCamera, enabled = !s.sending, modifier = Modifier.width(108.dp).fillMaxHeight())
            }

            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Legend("How much thinking?")
                Spacer(Modifier.weight(1f))
                Legend("Price per answer")
            }
            Spacer(Modifier.height(8.dp))
            // Rates follow the question as it's typed: a long or pasted question shows a higher "about ₹X".
            TierCards(s.tier, s.livePrices, vm::setTier, enabled = !s.sending, forTyped = s.draft.isNotBlank())

            Spacer(Modifier.height(16.dp))
            if (s.needsTopUp) {
                AddFareBanner(s.balance, onOpenWallet)
                Spacer(Modifier.height(12.dp))
            }
            s.error?.let {
                ErrorBanner(it, vm::dismissError)
                Spacer(Modifier.height(12.dp))
            }
            AskButton(
                label = if (s.sending) "Meter running…" else "Ask",
                estimate = s.askEstimate,
                enabled = s.canAsk,
                onClick = vm::ask,
            )
            Text(
                "The exact fare shows on your answer. If something fails, nothing is charged.",
                style = MaterialTheme.typography.bodySmall, color = Ink.PaperFaint, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )

            if (s.threads.isNotEmpty()) {
                Spacer(Modifier.height(30.dp))
                SessionAnswers(s, vm::openThread)
            }

            Spacer(Modifier.height(30.dp))
            ExampleRotator(paused = s.draft.isNotEmpty(), onPick = vm::useExample)
            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun HomeTopBar(balance: Double?, isGuest: Boolean, onOpenWallet: () -> Unit, onOpenProfile: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PilotLight()
        Spacer(Modifier.width(8.dp))
        Legend("Pay per answer", color = Ink.PaperDim)
        Spacer(Modifier.weight(1f))
        BalanceMeter(balance, onOpenWallet)
        Spacer(Modifier.width(8.dp))
        ProfileButton(isGuest, onOpenProfile)
    }
}

/**
 * Profile and history, beside the balance meter: a round bezel key the same height as the meter.
 * Guests get a small lit pilot dot, a quiet nudge that they have no account yet.
 */
@Composable
private fun ProfileButton(isGuest: Boolean, onClick: () -> Unit) {
    Box {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(Ink.Panel).border(1.dp, Ink.Line, CircleShape)
                .clickable(onClickLabel = "Open profile and history", onClick = onClick)
                .semantics { contentDescription = if (isGuest) "Profile and history. No account yet." else "Profile and history" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_person), null, tint = Ink.PaperDim, modifier = Modifier.size(22.dp))
        }
        if (isGuest) {
            Box(Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 2.dp).size(9.dp).clip(CircleShape).background(Lit.Tangerine)
                .border(1.5.dp, Ink.Bg, CircleShape))
        }
    }
}

/** Shown on a fresh install with a ₹0 balance: what the minimum top-up actually buys. */
@Composable
private fun FirstFareCard(s: AskUiState, onOpenWallet: () -> Unit) {
    val fast = s.prices.firstOrNull { it.tier == Tier.FAST }?.estCostInr
    val n = fast?.let { Money.messagesFor(TopUpAmount.MIN.toDouble(), it) } ?: 0
    // Fast can be free (its rate is ₹0.00): then a ₹0 balance doesn't stop anyone asking, so say so.
    val fastFree = fast != null && fast <= 0.0
    MeterPanel(Modifier.fillMaxWidth(), border = Lit.Tangerine.copy(alpha = 0.45f), contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Legend("Your meter reads ₹0.00", color = Lit.Amber)
                Text(
                    if (fastFree) "Fast answers are free right now. Add ${Money.inrWhole(TopUpAmount.MIN)} for Smart and Expert."
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

/**
 * The question box. Its placeholder rolls through example questions (an odometer-style roll,
 * same motion as the meter) so the breadth of the app is visible before anything is typed.
 */
@Composable
private fun QuestionBox(text: String, onText: (String) -> Unit, rotate: Boolean, enabled: Boolean, modifier: Modifier) {
    // Local TextFieldValue so text set from outside (example, camera) puts the cursor at the end.
    var field by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    if (field.text != text) field = TextFieldValue(text, TextRange(text.length))
    var focused by remember { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.large

    var hint by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(rotate) {
        while (rotate) {
            delay(3200)
            hint = (hint + 1) % Examples.ALL.size
        }
    }

    Box(
        modifier
            .clip(shape)
            .background(Ink.Raised)
            .border(if (focused) 1.5.dp else 1.dp, if (focused) Lit.Tangerine else Ink.Line, shape)
            .heightIn(min = 168.dp)
            .padding(16.dp),
    ) {
        BasicTextField(
            value = field,
            onValueChange = { field = it; onText(it.text) },
            enabled = enabled,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink.Paper),
            cursorBrush = SolidColor(Lit.Tangerine),
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp)
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = "Your question" },
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) {
                        Column {
                            Text("Type it, paste it, or snap it.", style = MaterialTheme.typography.bodyLarge, color = Ink.PaperDim)
                            Spacer(Modifier.height(6.dp))
                            AnimatedContent(
                                targetState = hint,
                                transitionSpec = {
                                    (slideInVertically(tween(420)) { it } + fadeIn(tween(420))) togetherWith
                                        (slideOutVertically(tween(420)) { -it } + fadeOut(tween(300)))
                                },
                                label = "hint",
                            ) { i ->
                                Text(
                                    "e.g. “${Examples.ALL[i].text}”",
                                    style = MaterialTheme.typography.bodyMedium, color = Ink.PaperFaint,
                                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    inner()
                }
            },
        )
        if (text.isNotEmpty() && enabled) {
            IconButton(onClick = { onText("") }, modifier = Modifier.align(Alignment.BottomEnd).size(32.dp)) {
                Icon(painterResource(R.drawable.ic_close), "Clear question", tint = Ink.PaperFaint, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** The camera as a first-class way to ask: as tall as the question box, right beside it. */
@Composable
private fun CameraTile(onClick: () -> Unit, enabled: Boolean, modifier: Modifier) {
    val shape = MaterialTheme.shapes.large
    Column(
        modifier
            .clip(shape)
            .background(Ink.Raised2)
            .border(1.dp, Lit.Tangerine.copy(alpha = 0.45f), shape)
            .clickable(enabled = enabled, onClickLabel = "Ask with a photo", onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(54.dp).clip(CircleShape).background(Lit.Tangerine),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_camera), null, tint = Color(0xFF2B1600), modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text("Snap it", style = MaterialTheme.typography.titleSmall, color = Ink.Paper, textAlign = TextAlign.Center)
        Text(
            "A page, a screen, a note",
            style = MaterialTheme.typography.bodySmall, color = Ink.PaperFaint, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** This session's answers as compact fare receipts, newest first. Tap to reopen (and follow up). */
@Composable
private fun SessionAnswers(s: AskUiState, onOpen: (Long) -> Unit) {
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

/**
 * Example questions in pages of four that roll over every few seconds (paused while the user is
 * typing, so nothing moves under their thumb). No subject labels: the mix of homework, code,
 * emails, contracts, travel and curiosity is the message.
 */
@Composable
private fun ExampleRotator(paused: Boolean, onPick: (Example) -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(paused, page) {
        if (!paused) {
            delay(7000)
            page++
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Legend("People ask things like")
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { page++ }) { Text("More ideas", color = Lit.Tangerine, style = MaterialTheme.typography.labelLarge) }
    }
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            (slideInVertically(tween(450)) { it / 4 } + fadeIn(tween(450))) togetherWith fadeOut(tween(200))
        },
        label = "examples",
    ) { p ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Examples.page(p).forEach { ex -> ExampleRow(ex, onClick = { onPick(ex) }) }
        }
    }
}

@Composable
private fun ExampleRow(ex: Example, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .border(1.dp, Ink.LineSoft, MaterialTheme.shapes.medium)
            .clickable(onClickLabel = "Use this example", onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(ex.text, style = MaterialTheme.typography.bodyMedium, color = Ink.PaperDim, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Icon(painterResource(R.drawable.ic_arrow_outward), null, tint = Lit.Tangerine.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
    }
}
