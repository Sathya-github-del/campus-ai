package dev.studyapp.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.studyapp.app.R
import dev.studyapp.app.core.MeterText
import dev.studyapp.app.core.Money
import dev.studyapp.app.core.Tier
import dev.studyapp.app.core.TierPrice
import dev.studyapp.app.core.Tiers

val Tier.icon: Int
    get() = when (this) {
        Tier.FAST -> R.drawable.ic_tier_fast
        Tier.SMART -> R.drawable.ic_tier_smart
        Tier.EXPERT -> R.drawable.ic_tier_expert
    }

val Tier.heatLevel: Int get() = ordinal + 1

/** Tier label with its icon and heat bars, in the tier's heat colour. */
@Composable
fun TierTag(tier: Tier, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(tier.icon), null, tint = tier.heat, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(tier.label, style = MaterialTheme.typography.labelLarge, color = tier.heat)
        Spacer(Modifier.width(6.dp))
        HeatBars(tier.heatLevel, tier.heat, height = 10.dp)
    }
}

/**
 * The tier picker: three tactile cards instead of a segmented control. Each card says when to use
 * the tier and shows its live per-question rate on a small meter, lit only on the selected card.
 */
@Composable
fun TierCards(selected: Tier, prices: List<TierPrice>, onSelect: (Tier) -> Unit, enabled: Boolean = true, forTyped: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Max).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Tier.entries.forEach { tier ->
            val est = prices.firstOrNull { it.tier == tier }?.estCostInr
            TierCard(tier, est, tier == selected, enabled, forTyped, { onSelect(tier) }, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun TierCard(tier: Tier, est: Double?, selected: Boolean, enabled: Boolean, forTyped: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val shape = MaterialTheme.shapes.medium
    val border by animateColorAsState(if (selected) tier.heat else Ink.LineSoft, label = "border")
    val bg by animateColorAsState(if (selected) Ink.Raised2 else Ink.Surface, label = "bg")
    Column(
        modifier
            .clip(shape)
            .background(bg)
            .border(if (selected) 1.5.dp else 1.dp, border, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics {
                contentDescription = "${tier.label}. ${tier.blurb}. " +
                    (est?.let { Money.estimate(it) + if (forTyped) " for this question" else " per question" } ?: "")
            }
            .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(tier.icon), null, tint = if (selected) tier.heat else Ink.PaperFaint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.weight(1f))
            HeatBars(tier.heatLevel, if (selected) tier.heat else Ink.PaperFaint.copy(alpha = 0.6f))
        }
        Spacer(Modifier.height(8.dp))
        Text(tier.label, style = MaterialTheme.typography.titleSmall, color = if (selected) Ink.Paper else Ink.PaperDim)
        Text(
            tier.blurb, style = MaterialTheme.typography.bodySmall, color = Ink.PaperFaint,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.weight(1f).height(8.dp))
        MeterPanel(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            shape = MaterialTheme.shapes.extraSmall,
            border = if (selected) tier.heat.copy(alpha = 0.35f) else Ink.LineSoft,
            contentPadding = PaddingValues(horizontal = 7.dp, vertical = 5.dp),
        ) {
            val lit = if (selected) Lit.Amber else Lit.Amber.copy(alpha = 0.5f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RupeeSign(12.dp, lit)
                // A live readout: it ticks as the typed question grows.
                if (est != null) MeterReadout(est, 12.dp, lit = lit) else SegmentDisplay("-.--", 12.dp, lit = lit)
            }
            Legend(if (forTyped) "this ask" else "per ask", Modifier.padding(top = 3.dp), color = if (selected) Ink.PaperDim else Ink.PaperFaint)
        }
    }
}

/**
 * The primary action: "Ask" plus a small inset meter showing the rate for the chosen tier. Switching
 * tiers makes the rate tick to the new price, so the price of the next question is never a surprise.
 */
@Composable
fun AskButton(label: String, estimate: Double?, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 60.dp) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = Lit.Tangerine, contentColor = Color(0xFF2B1600),
            disabledContainerColor = Ink.Raised2, disabledContentColor = Ink.PaperFaint,
        ),
        contentPadding = PaddingValues(start = 22.dp, end = 8.dp),
        modifier = modifier.fillMaxWidth().height(height).semantics {
            contentDescription = label + (estimate?.let { ", ${Money.estimate(it)}" } ?: "")
        },
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        if (estimate != null) {
            Row(
                Modifier.clip(MaterialTheme.shapes.small).background(Ink.Panel).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Legend("about", Modifier.padding(end = 6.dp), color = Ink.PaperFaint)
                RupeeSign(16.dp, if (enabled) Lit.Amber else Lit.Amber.copy(alpha = 0.4f))
                MeterReadout(estimate, 16.dp, lit = if (enabled) Lit.Amber else Lit.Amber.copy(alpha = 0.4f))
            }
        }
    }
}

/** Out of balance: framed as "add fare", not as an error. Nothing was charged and nothing typed is lost. */
@Composable
fun AddFareBanner(balance: Double?, onAddFare: () -> Unit, modifier: Modifier = Modifier) {
    MeterPanel(modifier.fillMaxWidth(), border = Lit.Vermilion.copy(alpha = 0.6f), contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Legend("Not enough fare", color = Lit.Vermilion)
                Text(
                    (balance?.let { "Your balance (${Money.inr(it)}) won't cover this answer. " } ?: "Your balance won't cover this answer. ") +
                        "Nothing was charged, and your question is still here.",
                    style = MaterialTheme.typography.bodySmall, color = Ink.PaperDim, modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = onAddFare, colors = ButtonDefaults.buttonColors(containerColor = Lit.Tangerine)) { Text("Add fare") }
        }
    }
}

@Composable
fun ErrorBanner(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.errorContainer)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp))
        TextButton(onClick = onDismiss) { Text("OK", color = MaterialTheme.colorScheme.onErrorContainer) }
    }
}

/** Blueprint §7: Expert costs ~80× Fast, so the first Expert send asks first and says why. */
@Composable
fun ExpertDialog(prices: List<TierPrice>, onConfirm: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var dontAsk by remember { mutableStateOf(false) }
    val est = prices.firstOrNull { it.tier == Tier.EXPERT }?.estCostInr
    val times = Tiers.multipleOfFast(prices, Tier.EXPERT)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.Raised,
        icon = { Icon(painterResource(R.drawable.ic_tier_expert), null, tint = Lit.Vermilion) },
        title = { Text("Use Expert for this one?") },
        text = {
            Column {
                if (est != null) {
                    MeterPanel(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 14.dp), border = Lit.Vermilion.copy(alpha = 0.5f)) {
                        Legend("Rate per ask")
                        Fare(est, 26.dp, modifier = Modifier.padding(top = 4.dp), lit = Lit.Amber)
                    }
                }
                Text(
                    buildString {
                        append("Expert is for genuinely hard problems")
                        if (times != null && times > 1) append(" and costs roughly $times× a Fast answer")
                        append(". Fast or Smart handles most questions well.")
                    },
                    color = Ink.PaperDim,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Checkbox(checked = dontAsk, onCheckedChange = { dontAsk = it },
                        colors = CheckboxDefaults.colors(checkedColor = Lit.Tangerine))
                    Text("Don't ask me again", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(dontAsk) }, colors = ButtonDefaults.buttonColors(containerColor = Lit.Vermilion)) { Text("Ask Expert") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A tiny glowing dot, used as the "meter on" indicator. */
@Composable
fun PilotLight(color: Color = Lit.Tangerine, size: Dp = 8.dp) {
    Box(Modifier.size(size + 6.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size).clip(CircleShape).background(color))
    }
}
