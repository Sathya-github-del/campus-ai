package dev.quatrioai.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.quatrioai.app.R
import dev.quatrioai.app.core.Markdown
import dev.quatrioai.app.core.MeterText
import dev.quatrioai.app.core.Money
import dev.quatrioai.app.core.Tier

/**
 * A conversation, laid out like a chat: your questions as bubbles on the right, answers as plain
 * flowing text on the left with a quiet fare line under each, and a reply box always at the bottom.
 * Every reply is a follow-up on the same conversation (so it costs a little more than a fresh
 * question); "+" in the top bar starts a new one.
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
    // A new part was added: bring it into view (don't jump when an answer lands, so a long answer is read from its start).
    LaunchedEffect(thread.exchanges.size) { if (thread.isThread) scroll.animateScrollTo(scroll.maxValue) }
    // The reply box is the follow-up box, so camera text lands there while this screen is open.
    LaunchedEffect(thread.id, s.composingFollowUp) { if (!s.composingFollowUp) vm.startFollowUp() }

    Column(Modifier.fillMaxSize().background(Ink.Bg).statusBarsPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = vm::closeThread) {
                Icon(painterResource(R.drawable.ic_back), "Back to questions", tint = Ink.Paper)
            }
            Text(
                thread.first.question,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
                color = Ink.Paper, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = vm::closeThread, enabled = !s.sending) {
                Icon(painterResource(R.drawable.ic_add), "New question", tint = Lit.Tangerine)
            }
            BalanceMeter(s.balance, onOpenWallet)
        }

        Column(
            Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            thread.exchanges.forEachIndexed { i, e ->
                Turn(
                    e,
                    // Only the latest part can be continued; an earlier cut-off part just keeps its note.
                    onContinue = if (i == thread.exchanges.lastIndex && !s.sending) vm::continueCutOff else null,
                )
            }
            if (thread.isThread && !thread.pending) {
                Legend(
                    "${thread.exchanges.size} parts · total ${Money.inr(thread.totalCostInr)} · each reply re-reads the parts above",
                    color = Ink.PaperFaint,
                )
            }
            Spacer(Modifier.height(4.dp))
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            if (s.needsTopUp) AddFareBanner(s.balance, onOpenWallet, Modifier.padding(bottom = 10.dp))
            s.error?.let { ErrorBanner(it, vm::dismissError, Modifier.padding(bottom = 10.dp)) }
        }

        ReplyComposer(s, vm, onOpenCamera)
    }
}

/** One question (your bubble) and its answer (plain text with a quiet fare line). */
@Composable
private fun Turn(e: Exchange, onContinue: (() -> Unit)?) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Column(
                Modifier.align(Alignment.CenterEnd).widthIn(max = maxWidth * 0.86f)
                    .clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)).background(Ink.Raised2)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                SelectionContainer {
                    Text(e.question, style = MaterialTheme.typography.bodyLarge, color = Ink.Paper)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        AnimatedContent(
            targetState = e.pending,
            transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(150)) },
            label = "answer",
        ) { pending ->
            if (pending) {
                Row(Modifier.padding(start = 2.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    MeterRunning(16.dp, cells = 3)
                    Spacer(Modifier.width(12.dp))
                    Text("Thinking… the exact fare lands with the answer.", style = MaterialTheme.typography.bodySmall, color = Ink.PaperFaint)
                }
            } else {
                Column(Modifier.fillMaxWidth()) {
                    SelectionContainer { AnswerText(e.answer.orEmpty(), Modifier.padding(start = 2.dp, end = 2.dp)) }
                    if (e.truncated) CutOffStrip(onContinue)
                    FareLine(e, onCopy = { context.copy(Markdown.plainText(e.answer.orEmpty())) })
                }
            }
        }
    }
}

/** Under each answer: tier, exact fare, which model answered, and copy. Quiet, like a receipt footer. */
@Composable
private fun FareLine(e: Exchange, onCopy: () -> Unit) {
    val cost = e.costInr ?: 0.0
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        TierTag(e.tier)
        Spacer(Modifier.width(10.dp))
        Fare(cost, 13.dp, startFrom = 0.0)
        Spacer(Modifier.width(8.dp))
        Text(
            buildString {
                append(if (MeterText.isSubPaisa(cost)) "under ₹0.01" else "exact fare")
                e.modelName?.let { append(" · $it") }
            },
            style = MaterialTheme.typography.bodySmall, color = Ink.PaperFaint,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
            Icon(painterResource(R.drawable.ic_copy), "Copy answer", tint = Ink.PaperDim, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * The answer stopped at the model's length limit. Styled like the reply action (tangerine outline,
 * follow-up icon) because that is the fix: tapping it puts "please continue" in the reply box.
 */
@Composable
private fun CutOffStrip(onContinue: (() -> Unit)?) {
    val shape = MaterialTheme.shapes.medium
    Row(
        Modifier.padding(top = 10.dp).fillMaxWidth().clip(shape)
            .background(Lit.Tangerine.copy(alpha = 0.07f))
            .border(1.dp, Lit.Tangerine.copy(alpha = if (onContinue != null) 0.7f else 0.35f), shape)
            .then(if (onContinue != null) Modifier.clickable(onClickLabel = "Reply to continue", onClick = onContinue) else Modifier)
            .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_follow_up), null, tint = Lit.Tangerine, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Legend("Cut off", color = Lit.Tangerine)
            Text(
                if (onContinue != null) "This answer hit its length limit. Reply to continue."
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

/** The always-there reply box: camera, text, tier + rate, send. */
@Composable
private fun ReplyComposer(s: AskUiState, vm: AskViewModel, onOpenCamera: () -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(s.followUp, TextRange(s.followUp.length))) }
    if (field.text != s.followUp) field = TextFieldValue(s.followUp, TextRange(s.followUp.length))
    val shape = RoundedCornerShape(26.dp)

    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(shape).background(Ink.Surface).border(1.dp, Ink.Line, shape)
                .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
                if (field.text.isEmpty()) Text("Reply…", style = MaterialTheme.typography.bodyLarge, color = Ink.PaperFaint)
                BasicTextField(
                    value = field,
                    onValueChange = { field = it; vm.setFollowUp(it.text) },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink.Paper),
                    cursorBrush = SolidColor(Lit.Tangerine),
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onOpenCamera, enabled = !s.sending) {
                    Icon(painterResource(R.drawable.ic_camera), "Add text from a photo", tint = Lit.Tangerine)
                }
                TierMenu(s.tier, s, vm::setTier)
                Spacer(Modifier.weight(1f))
                FilledIconButton(
                    onClick = vm::followUp,
                    enabled = s.canFollowUp,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Lit.Tangerine, contentColor = Color(0xFF2B1600),
                        disabledContainerColor = Ink.Raised2, disabledContentColor = Ink.PaperFaint,
                    ),
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_send), s.followUpEstimate?.let { "Send reply, ${Money.estimate(it)}" } ?: "Send reply")
                }
            }
        }
    }
}

/** Compact tier switch for replies: current tier + rate, tap to change. */
@Composable
private fun TierMenu(tier: Tier, s: AskUiState, onSelect: (Tier) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(MaterialTheme.shapes.small).clickable(onClickLabel = "Change answer level") { open = true }
                .border(BorderStroke(1.dp, Ink.Line), MaterialTheme.shapes.small).padding(horizontal = 10.dp, vertical = 6.dp),
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

internal fun Context.copy(text: String) {
    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Answer", text))
    // Android 13+ shows its own clipboard confirmation.
    if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Answer copied", Toast.LENGTH_SHORT).show()
}
