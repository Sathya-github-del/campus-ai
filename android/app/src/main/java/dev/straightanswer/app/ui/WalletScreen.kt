package dev.straightanswer.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.straightanswer.app.R
import dev.straightanswer.app.core.Money
import dev.straightanswer.app.core.Tier
import dev.straightanswer.app.core.TierPrice
import dev.straightanswer.app.core.TopUpAmount

/**
 * "Add fare": the wallet as part of the meter, not a separate banking screen. The balance sits on
 * a big meter (ticking up when a top-up lands), the amounts are chunky fare keys, and a "balance
 * after" readout ticks as you pick an amount. Same view model and backend contract as before.
 */
@Composable
fun WalletScreen(vm: WalletViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(Ink.Bg).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Back", tint = Ink.Paper) }
            Text("Add fare", style = MaterialTheme.typography.headlineSmall, color = Ink.Paper)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(12.dp))
            BalanceHero(s.balance, s.prices)

            if (s.testMode) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().border(1.dp, Ink.Line, MaterialTheme.shapes.small).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Legend("Test mode", color = Lit.Marigold)
                    Spacer(Modifier.width(10.dp))
                    Text("Payments are simulated. No real money is charged.", style = MaterialTheme.typography.bodySmall, color = Ink.PaperDim)
                }
            }

            Spacer(Modifier.height(26.dp))
            Legend("Pick an amount")
            Spacer(Modifier.height(10.dp))
            // "≈ N asks" on each key counts the cheapest tier that actually costs something (Fast, unless
            // Fast is free right now, in which case the key says which tier it's counting).
            val counted = s.prices.firstOrNull { it.estCostInr > 0.0 }
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TopUpAmount.CHIPS.forEach { a ->
                    FareKey(
                        amount = a,
                        answers = counted?.let { Money.messagesFor(a.toDouble(), it.estCostInr) },
                        unit = if (counted == null || counted.tier == Tier.FAST) "asks" else counted.tier.label,
                        selected = s.custom.isBlank() && s.chip == a,
                        enabled = !s.busy,
                        onClick = { vm.pickChip(a) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = s.custom,
                onValueChange = vm::setCustom,
                label = { Text("Other amount") },
                placeholder = { Text("${TopUpAmount.MIN} to ${Money.count(TopUpAmount.MAX)}") },
                prefix = { Text("₹ ", color = Lit.Amber) },
                singleLine = true,
                isError = s.customError != null,
                supportingText = s.customError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = !s.busy,
                shape = MaterialTheme.shapes.medium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Lit.Tangerine, unfocusedBorderColor = Ink.Line,
                    focusedLabelColor = Lit.Tangerine, cursorColor = Lit.Tangerine,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            s.amount?.let { a ->
                Spacer(Modifier.height(8.dp))
                MeterPanel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Legend("Balance after adding ${Money.inrWhole(a)}")
                            WhatItBuys(a.toDouble(), s.prices, prefix = "${Money.inrWhole(a)} alone is about")
                        }
                        Spacer(Modifier.width(10.dp))
                        Fare((s.balance ?: 0.0) + a, 20.dp)
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Button(
                onClick = vm::pay,
                enabled = s.amount != null && !s.busy,
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Lit.Tangerine, contentColor = Color(0xFF2B1600),
                    disabledContainerColor = Ink.Raised2, disabledContentColor = Ink.PaperFaint,
                ),
                modifier = Modifier.fillMaxWidth().height(58.dp),
            ) {
                if (s.busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(0xFF2B1600))
                    Spacer(Modifier.width(10.dp))
                    Text("Adding fare…", style = MaterialTheme.typography.titleMedium)
                } else {
                    Text(s.amount?.let { "Add ${Money.inrWhole(it)} with UPI" } ?: "Add fare with UPI", style = MaterialTheme.typography.titleMedium)
                }
            }
            s.success?.let {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PilotLight(Lit.Marigold)
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = Lit.Marigold)
                }
            }
            s.error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(26.dp))
            Text(
                "No subscription and nothing renews. Fare you add can only be spent on answers in this app; " +
                    "it can't be sent to anyone or withdrawn. Every answer shows exactly what it cost.",
                style = MaterialTheme.typography.bodySmall, color = Ink.PaperFaint,
            )
            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

/** The big meter: balance with ghost leading digits, and what it buys at each tier. */
@Composable
private fun BalanceHero(balance: Double?, prices: List<TierPrice>) {
    MeterPanel(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, contentPadding = PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PilotLight(if (balance != null && balance > 0) Lit.Tangerine else Lit.Vermilion, 6.dp)
            Spacer(Modifier.width(6.dp))
            Legend("Balance")
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RupeeSign(44.dp)
            if (balance == null) SegmentDisplay("  --.--", 44.dp) else MeterReadout(balance, 44.dp, minIntDigits = 4)
        }
        balance?.let { WhatItBuys(it, prices, prefix = "Enough for about") }
    }
}

/** A chunky fare key: the amount, and roughly how many quick answers it buys. */
@Composable
private fun FareKey(amount: Int, answers: Int?, unit: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val shape = MaterialTheme.shapes.medium
    val bg by animateColorAsState(if (selected) Lit.Tangerine else Ink.Raised, label = "key")
    val fg = if (selected) Color(0xFF2B1600) else Ink.Paper
    Column(
        modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, if (selected) Lit.Tangerine else Ink.Line, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(Money.inrWhole(amount), style = MaterialTheme.typography.titleLarge, color = fg)
        if (answers != null && answers > 0) {
            Text(
                "≈${compact(answers)} $unit", style = MaterialTheme.typography.labelSmall,
                color = if (selected) fg.copy(alpha = 0.75f) else Ink.PaperFaint, modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** "1,000" → "1k", "2,500" → "2.5k", "12,500" → "12k": the keys are narrow. Rounds down, never overstates. */
internal fun compact(n: Int): String = when {
    n >= 10_000 -> "${n / 1000}k"
    n >= 1000 -> (n / 100).let { if (it % 10 == 0) "${it / 10}k" else "${it / 10}.${it % 10}k" }
    else -> n.toString()
}

/** "Enough for about 1,000 Fast · 100 Smart · 12 Expert answers": standalone-question estimates. */
@Composable
private fun WhatItBuys(amount: Double, prices: List<TierPrice>, prefix: String) {
    // Free tiers (rate ₹0.00) can't be counted in answers: they're listed as free instead.
    val paid = prices.filter { it.estCostInr > 0.0 }
    val free = prices.filter { it.estCostInr <= 0.0 }
    // Nothing useful to say when the amount doesn't cover even one paid answer.
    if (paid.isEmpty() || Money.messagesFor(amount, paid.first().estCostInr) < 1) return
    val parts = paid.map { "${Money.count(Money.messagesFor(amount, it.estCostInr))} ${it.tier.label}" }
    Text(
        "$prefix ${parts.joinToString(" · ")} answers. Follow-ups use a little more." +
            (if (free.isNotEmpty()) " ${free.joinToString(" and ") { it.tier.label }} is free right now." else ""),
        style = MaterialTheme.typography.bodySmall,
        color = Ink.PaperDim,
        modifier = Modifier.padding(top = 8.dp),
    )
}
