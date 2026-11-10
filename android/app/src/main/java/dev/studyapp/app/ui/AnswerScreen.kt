package dev.studyapp.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.selection.SelectionContainer
import dev.studyapp.app.core.Markdown
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.studyapp.app.R
import dev.studyapp.app.core.MeterText
import dev.studyapp.app.core.Money
import dev.studyapp.app.core.Tier

/**
 * One question, one answer card, with its fare. A follow-up is an explicit choice ("Follow up"),
 * which adds the next part to the same card stack and a running total, rather than an open-ended
 * chat log being the default.
 */
@Composable
fun AnswerScreen(
    s: AskUiState,
    thread: AskThread,
    vm: AskViewModel,
    onOpenWallet: () -> Unit,
    onOpenCamera: () -> Unit,
) {
    val scroll = rememberScrollState()
    // A new follow-up part was added: bring it into view (don't jump when an answer lands, so a long
    // answer is read from its start).
    LaunchedEffect(thread.exchanges.size) { if (thread.isThread) scroll.animateScrollTo(scroll.maxValue) }

    Column(Modifier.fillMaxSize().background(Ink.Bg).statusBarsPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = vm::closeThread) {
                Icon(painterResource(R.drawable.ic_back), "Back to questions", tint = Ink.Paper)
            }
            Legend(if (thread.isThread) "Your question · ${thread.exchanges.size} parts" else "Your answer", color = Ink.PaperDim)
            Spacer(Modifier.weight(1f))
            BalanceMeter(s.balance, onOpenWallet)
        }

        Column(
            Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            thread.exchanges.forEachIndexed { i, e ->
                LegCard(
                    e,
                    label = if (!thread.isThread) null else if (i == 0) "Question" else "Follow-up $i",
                    // Only the latest part can be continued; an earlier cut-off part just keeps its note.
                    onContinue = if (i == thread.exchanges.lastIndex && !s.sending) vm::continueCutOff else null,
                )
            }
            if (thread.isThread && !thread.pending) TotalFare(thread)
            if (!thread.isThread && !thread.pending && !s.composingFollowUp) {
                Text(
                    "Need more? Follow up on this answer and it continues from here. Otherwise, it's done: you paid for this one answer.",
                    style = MaterialTheme.typography.bodySmall, color = Ink.PaperFaint,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            if (s.needsTopUp) AddFareBanner(s.balance, onOpenWallet, Modifier.padding(bottom = 10.dp))
            s.error?.let { ErrorBanner(it, vm::dismissError, Modifier.padding(bottom = 10.dp)) }
        }

        if (s.composingFollowUp) {
            FollowUpComposer(s, thread, vm, onOpenCamera)
        } else {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = vm::startFollowUp,
                    enabled = !s.sending,
                    shape = MaterialTheme.shapes.medium,
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (s.sending) Ink.LineSoft else Lit.Tangerine.copy(alpha = 0.7f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Lit.Tangerine),
                    modifier = Modifier.weight(1f).height(54.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_follow_up), null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Follow up")
                }
                Button(
                    onClick = vm::closeThread,
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(containerColor = Lit.Tangerine, contentColor = Color(0xFF2B1600)),
                    modifier = Modifier.weight(1f).height(54.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_add), null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("New question")
                }
            }
        }
    }
}

/** One question + answer, laid out like a receipt: question, tear line, answer, fare. */
@Composable
private fun LegCard(e: Exchange, label: String?, onContinue: (() -> Unit)?) {
    val context = LocalContext.current
    val shape = MaterialTheme.shapes.large
    var expanded by remember(e.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(shape).background(Ink.Surface).border(1.dp, Ink.LineSoft, shape)) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TierTag(e.tier)
                Spacer(Modifier.weight(1f))
                label?.let { Legend(it) }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                e.question,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
                color = Ink.Paper,
                maxLines = if (expanded) Int.MAX_VALUE else 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClickLabel = if (expanded) "Collapse question" else "Show whole question") { expanded = !expanded },
            )
        }
        Perforation(Modifier.padding(vertical = 6.dp), notch = Ink.Bg)
        AnimatedContent(
            targetState = e.pending,
            transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(150)) },
            label = "answer",
        ) { pending ->
            if (pending) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    MeterPanel(contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
                        MeterRunning(22.dp, cells = 3)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Working on it…", style = MaterialTheme.typography.titleSmall, color = Ink.Paper)
                        Text(
                            "The meter's running. The exact fare lands with the answer.",
                            style = MaterialTheme.typography.bodySmall, color = Ink.PaperFaint,
                        )
                    }
                }
            } else {
                Column {
                    SelectionContainer {
                        AnswerText(
                            e.answer.orEmpty(),
                            Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 14.dp),
                        )
                    }
                    if (e.truncated) CutOffStrip(onContinue)
                    // Copy gives clean text (no ** or LaTeX), so it pastes readably into chats and notes.
                    FareStrip(e, onCopy = { context.copy(Markdown.plainText(e.answer.orEmpty())) })
                }
            }
        }
    }
}

/**
 * The answer stopped at the model's length limit. Styled like the "Follow up" action (tangerine
 * outline, follow-up icon) because that is the fix: tapping it opens the follow-up box with
 * "please continue" ready to send. On an earlier part of a thread it is just the note.
 */
@Composable
private fun CutOffStrip(onContinue: (() -> Unit)?) {
    val shape = MaterialTheme.shapes.medium
    Row(
        Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp).fillMaxWidth().clip(shape)
            .background(Lit.Tangerine.copy(alpha = 0.07f))
            .border(1.dp, Lit.Tangerine.copy(alpha = if (onContinue != null) 0.7f else 0.35f), shape)
            .then(if (onContinue != null) Modifier.clickable(onClickLabel = "Follow up to continue", onClick = onContinue) else Modifier)
            .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_follow_up), null, tint = Lit.Tangerine, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Legend("Cut off", color = Lit.Tangerine)
            Text(
                if (onContinue != null) "This answer hit its length limit. Follow up to continue."
                else "This answer hit its length limit before it finished.",
                style = MaterialTheme.typography.bodySmall, color = Ink.PaperDim, modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (onContinue != null) {
            Spacer(Modifier.width(8.dp))
            Text("Continue", style = MaterialTheme.typography.labelLarge, color = Lit.Tangerine)
        }
    }
}

/** The bottom of an answer card: the fare lands here, ticking up from ₹0.00 to the exact charge. */
@Composable
private fun FareStrip(e: Exchange, onCopy: () -> Unit) {
    val cost = e.costInr ?: 0.0
    Row(
        Modifier.fillMaxWidth().background(Ink.Panel).padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Legend("Fare", color = Lit.Amber.copy(alpha = 0.8f))
            Fare(cost, 26.dp, startFrom = 0.0, modifier = Modifier.padding(top = 4.dp))
            Text(
                buildString {
                    if (MeterText.isSubPaisa(cost)) append("Under ₹0.01 · ")
                    append("exact charge")
                    e.balanceAfter?.let { append(" · balance ${Money.inr(it)}") }
                },
                style = MaterialTheme.typography.bodySmall, color = Ink.PaperFaint, modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onCopy) {
            Icon(painterResource(R.drawable.ic_copy), "Copy answer", tint = Ink.PaperDim, modifier = Modifier.size(20.dp))
        }
    }
}

/** Running total once a question has follow-ups. */
@Composable
private fun TotalFare(thread: AskThread) {
    MeterPanel(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, border = Lit.Tangerine.copy(alpha = 0.4f),
        contentPadding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Legend("Total for this question", color = Lit.Amber.copy(alpha = 0.8f))
                Text(
                    "${thread.exchanges.size} parts. Each follow-up re-reads the earlier parts, so it costs a bit more than a fresh question.",
                    style = MaterialTheme.typography.bodySmall, color = Ink.PaperFaint, modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Fare(thread.totalCostInr, 30.dp)
        }
    }
}

@Composable
private fun FollowUpComposer(s: AskUiState, thread: AskThread, vm: AskViewModel, onOpenCamera: () -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(s.followUp, TextRange(s.followUp.length))) }
    if (field.text != s.followUp) field = TextFieldValue(s.followUp, TextRange(s.followUp.length))
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
        Modifier.fillMaxWidth().background(Ink.Raised).navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TierMenu(s.tier, s, vm::setTier)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = vm::cancelFollowUp) {
                Icon(painterResource(R.drawable.ic_close), "Cancel follow-up", tint = Ink.PaperDim, modifier = Modifier.size(20.dp))
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = onOpenCamera, enabled = !s.sending) {
                Icon(painterResource(R.drawable.ic_camera), "Add text from a photo", tint = Lit.Tangerine)
            }
            OutlinedTextField(
                value = field,
                onValueChange = { field = it; vm.setFollowUp(it.text) },
                placeholder = { Text("Ask more about this answer…", color = Ink.PaperFaint) },
                maxLines = 5,
                shape = MaterialTheme.shapes.large,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Lit.Tangerine, unfocusedBorderColor = Ink.Line,
                    cursorColor = Lit.Tangerine, focusedContainerColor = Ink.Surface, unfocusedContainerColor = Ink.Surface,
                ),
                modifier = Modifier.weight(1f).heightIn(min = 52.dp).focusRequester(focus),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = vm::followUp,
                enabled = s.canFollowUp,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Lit.Tangerine, contentColor = Color(0xFF2B1600),
                    disabledContainerColor = Ink.Raised2, disabledContentColor = Ink.PaperFaint,
                ),
                modifier = Modifier.size(52.dp),
            ) {
                Icon(painterResource(R.drawable.ic_send), s.followUpEstimate?.let { "Send follow-up, ${Money.estimate(it)}" } ?: "Send follow-up")
            }
        }
        Text(
            "A follow-up re-reads the answer${if (thread.isThread) "s" else ""} above, so it costs more than a fresh question. The rate shown includes that.",
            style = MaterialTheme.typography.bodySmall, color = Ink.PaperFaint,
            modifier = Modifier.padding(start = 48.dp, top = 6.dp, end = 8.dp),
        )
    }
}

/** Compact tier switch for follow-ups: current tier + rate, tap to change. */
@Composable
private fun TierMenu(tier: Tier, s: AskUiState, onSelect: (Tier) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(MaterialTheme.shapes.small).clickable(onClickLabel = "Change answer level") { open = true }
                .border(1.dp, Ink.Line, MaterialTheme.shapes.small).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TierTag(tier)
            s.followUpEstimate?.let {
                Spacer(Modifier.width(10.dp))
                RupeeSign(12.dp)
                MeterReadout(it, 12.dp)
            }
            Text("  ▾", color = Ink.PaperFaint)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Ink.Raised2) {
            Tier.entries.forEach { t ->
                val est = s.followUpPrices.firstOrNull { it.tier == t }?.estCostInr
                DropdownMenuItem(
                    text = {
                        Column {
                            TierTag(t)
                            Text(
                                t.blurb + (est?.let { " · ${Money.estimate(it)}" } ?: ""),
                                style = MaterialTheme.typography.bodySmall, color = Ink.PaperFaint, maxLines = 2,
                            )
                        }
                    },
                    onClick = { onSelect(t); open = false },
                )
            }
        }
    }
}

private fun Context.copy(text: String) {
    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Answer", text))
    // Android 13+ shows its own clipboard confirmation.
    if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Answer copied", Toast.LENGTH_SHORT).show()
}
