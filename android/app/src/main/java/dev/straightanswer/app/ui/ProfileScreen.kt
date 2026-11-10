package dev.straightanswer.app.ui

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.straightanswer.app.BuildConfig
import dev.straightanswer.app.R
import dev.straightanswer.app.core.HistoryText
import dev.straightanswer.app.core.LegalDoc
import dev.straightanswer.app.core.Money
import dev.straightanswer.app.data.HistoryItem

/**
 * Profile: who you're signed in as and your balance, your saved history as fare receipts (each
 * deletable after a confirmation), the draft legal pages, log out and delete account.
 */
@Composable
fun ProfileScreen(
    vm: ProfileViewModel,
    onBack: () -> Unit,
    onOpenWallet: () -> Unit,
    onOpenAuth: () -> Unit,
    onOpenLegal: (LegalDoc) -> Unit,
) {
    val s by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(Ink.Bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Back", tint = Ink.Paper) }
            Text("Profile", style = MaterialTheme.typography.headlineSmall, color = Ink.Paper)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(12.dp))
            IdentityCard(s, onOpenWallet, onOpenAuth)

            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Legend("History")
                Spacer(Modifier.weight(1f))
                if (!s.history.isNullOrEmpty()) Legend("${Money.cost(s.historyTotalInr)} total", color = Ink.PaperDim)
            }
            Spacer(Modifier.height(8.dp))
            s.error?.let {
                ErrorBanner(it, vm::dismissError)
                Spacer(Modifier.height(8.dp))
            }
            HistorySection(s, vm)

            Spacer(Modifier.height(28.dp))
            Legend("Terms & privacy")
            Spacer(Modifier.height(8.dp))
            LinkRow("Terms of Service", "Draft") { onOpenLegal(LegalDoc.TERMS) }
            Spacer(Modifier.height(8.dp))
            LinkRow("Privacy Policy", "Draft") { onOpenLegal(LegalDoc.PRIVACY) }

            Spacer(Modifier.height(28.dp))
            Legend("Account")
            Spacer(Modifier.height(8.dp))
            if (s.email != null) {
                OutlinedButton(onClick = vm::askLogout, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(painterResource(R.drawable.ic_logout), null, tint = Ink.Paper, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Log out", color = Ink.Paper)
                }
                Spacer(Modifier.height(10.dp))
            }
            TextButton(onClick = vm::openDeleteAccount, modifier = Modifier.fillMaxWidth()) {
                Icon(painterResource(R.drawable.ic_delete), null, tint = Lit.Vermilion, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (s.isGuest) "Delete guest wallet and history" else "Delete account", color = Lit.Vermilion)
            }
            Text(
                "Deleting is permanent and forfeits any balance left.",
                style = MaterialTheme.typography.bodySmall, color = Ink.PaperFaint,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Legend("Version ${BuildConfig.VERSION_NAME}", Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    s.confirmDelete?.let { item -> DeleteItemDialog(item, vm::confirmDelete, vm::cancelDelete) }
    if (s.confirmLogout) LogoutDialog(s.email, vm::logout, vm::cancelLogout)
    s.deleteAccount?.let { d -> DeleteAccountDialog(d, s.balance, s.isGuest, s.email, vm::setDeleteConfirm, vm::deleteAccount, vm::closeDeleteAccount) }
}

@Composable
private fun IdentityCard(s: ProfileUiState, onOpenWallet: () -> Unit, onOpenAuth: () -> Unit) {
    MeterPanel(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, contentPadding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val email = s.email
                if (email != null) {
                    Legend("Signed in as")
                    Text(email, style = MaterialTheme.typography.titleMedium, color = Ink.Paper,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                } else {
                    Legend("Guest on this phone", color = Lit.Amber)
                    Text("No account yet", style = MaterialTheme.typography.titleMedium, color = Ink.Paper, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Legend("Balance")
                if (s.balance == null) Row(verticalAlignment = Alignment.CenterVertically) { RupeeSign(22.dp); SegmentDisplay("--.--", 22.dp) }
                else Fare(s.balance, 22.dp, modifier = Modifier.padding(top = 4.dp))
                Text("+ Add fare", style = MaterialTheme.typography.labelLarge, color = Lit.Tangerine,
                    modifier = Modifier.padding(top = 4.dp).clip(MaterialTheme.shapes.extraSmall).clickable(onClick = onOpenWallet).padding(4.dp))
            }
        }
        if (s.isGuest) {
            Text(
                "Your balance and history are tied to this phone. If you uninstall the app or change phones, they're gone. " +
                    "An account lets you log in again anywhere.",
                style = MaterialTheme.typography.bodySmall, color = Ink.PaperDim, modifier = Modifier.padding(top = 12.dp),
            )
            Button(
                onClick = onOpenAuth, shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(containerColor = Lit.Tangerine, contentColor = Color(0xFF2B1600)),
                modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
            ) { Text("Create an account or log in") }
        }
    }
}

@Composable
private fun HistorySection(s: ProfileUiState, vm: ProfileViewModel) {
    val items = s.history
    when {
        items == null && s.loadError == null -> Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically) {
            MeterRunning(14.dp, cells = 3)
            Spacer(Modifier.width(12.dp))
            Text("Loading your history…", style = MaterialTheme.typography.bodyMedium, color = Ink.PaperDim)
        }
        items == null -> Column(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).border(1.dp, Ink.LineSoft, MaterialTheme.shapes.medium).padding(14.dp),
        ) {
            Text("Couldn't load your history. ${s.loadError}", style = MaterialTheme.typography.bodyMedium, color = Ink.PaperDim)
            TextButton(onClick = vm::load, enabled = !s.loading) { Text(if (s.loading) "Trying…" else "Try again", color = Lit.Tangerine) }
        }
        items.isEmpty() -> Text(
            "No saved questions yet. Everything you ask shows up here with its fare.",
            style = MaterialTheme.typography.bodyMedium, color = Ink.PaperFaint,
            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).border(1.dp, Ink.LineSoft, MaterialTheme.shapes.medium).padding(14.dp),
        )
        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { item -> HistoryRow(item, deleting = item.conversationId in s.deleting, onDelete = { vm.askDelete(item) }) }
        }
    }
}

/** A saved question as a compact fare receipt (same look as home's "This session" rows), with a delete key. */
@Composable
private fun HistoryRow(item: HistoryItem, deleting: Boolean, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(Ink.Surface)
            .border(1.dp, Ink.LineSoft, MaterialTheme.shapes.medium)
            .padding(start = 14.dp, end = 2.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(HistoryText.question(item.firstQuestion), style = MaterialTheme.typography.bodyMedium,
                color = if (deleting) Ink.PaperFaint else Ink.Paper, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                item.tier?.let { TierTag(it); Spacer(Modifier.width(8.dp)) }
                Legend(listOf(HistoryText.parts(item.parts), HistoryText.date(item.createdAt)).filter { it.isNotEmpty() }.joinToString("  ·  "))
            }
        }
        Spacer(Modifier.width(10.dp))
        Fare(item.totalCostInr, 14.dp)
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            if (deleting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Lit.Vermilion)
            else IconButton(onClick = onDelete) {
                Icon(painterResource(R.drawable.ic_delete), "Delete this question from history", tint = Ink.PaperFaint, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun LinkRow(title: String, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).border(1.dp, Ink.LineSoft, MaterialTheme.shapes.medium)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_doc), null, tint = Lit.Tangerine.copy(alpha = 0.85f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, color = Ink.Paper, modifier = Modifier.weight(1f))
        Legend(tag, color = Lit.Marigold)
        Spacer(Modifier.width(8.dp))
        Icon(painterResource(R.drawable.ic_arrow_outward), null, tint = Ink.PaperFaint, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun DeleteItemDialog(item: HistoryItem, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.Raised,
        icon = { Icon(painterResource(R.drawable.ic_delete), null, tint = Lit.Vermilion) },
        title = { Text("Delete this question?") },
        text = {
            Column {
                Text("“${HistoryText.question(item.firstQuestion)}”", style = MaterialTheme.typography.bodyMedium, color = Ink.Paper,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                Text("It's removed from your history for good, with its answer" + (if (item.parts > 1) " and follow-ups" else "") +
                    ". The fare already paid isn't refunded.", color = Ink.PaperDim)
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = Lit.Vermilion)) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep it") } },
    )
}

@Composable
private fun LogoutDialog(email: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.Raised,
        title = { Text("Log out?") },
        text = {
            Text("Your balance and history stay safe on your account. Log in again any time" +
                (email?.let { " with $it" } ?: "") + ".", color = Ink.PaperDim)
        },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = Lit.Tangerine)) { Text("Log out") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Deleting an account is permanent and forfeits the balance, so it takes two deliberate steps: open
 * this dialog, then type DELETE before the button arms.
 */
@Composable
private fun DeleteAccountDialog(
    d: DeleteAccountState,
    balance: Double?,
    isGuest: Boolean,
    email: String?,
    onTyped: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!d.busy) onDismiss() },
        containerColor = Ink.Raised,
        icon = { Icon(painterResource(R.drawable.ic_delete), null, tint = Lit.Vermilion) },
        title = { Text(if (isGuest) "Delete your guest wallet?" else "Delete your account?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (balance != null && balance > 0.0) {
                    MeterPanel(Modifier.fillMaxWidth().padding(bottom = 14.dp), border = Lit.Vermilion.copy(alpha = 0.6f)) {
                        Legend("Balance you will lose", color = Lit.Vermilion)
                        Fare(balance, 24.dp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                Bullet(if (email != null) "Your account ($email) and all your history are deleted for good." else "This phone's wallet and all its history are deleted for good.")
                Bullet("Any balance left is forfeited. It's non-transferable credit, so it can't be refunded, moved or paid out.")
                Bullet("This can't be undone, and support can't bring it back.")
                Spacer(Modifier.height(10.dp))
                Text("Type ${ProfileViewModel.CONFIRM_WORD} to confirm.", style = MaterialTheme.typography.bodyMedium, color = Ink.Paper)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = d.typed,
                    onValueChange = onTyped,
                    singleLine = true,
                    placeholder = { Text(ProfileViewModel.CONFIRM_WORD, color = Ink.PaperFaint) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    enabled = !d.busy,
                    shape = MaterialTheme.shapes.medium,
                    colors = nightFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                d.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm, enabled = d.armed,
                colors = ButtonDefaults.buttonColors(containerColor = Lit.Vermilion, disabledContainerColor = Ink.Raised3, disabledContentColor = Ink.PaperFaint),
            ) { Text(if (d.busy) "Deleting…" else "Delete forever") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !d.busy) { Text("Keep my account") } },
    )
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(bottom = 8.dp)) {
        Box(Modifier.padding(top = 7.dp).size(width = 8.dp, height = 3.dp).clip(MaterialTheme.shapes.extraSmall).background(Lit.Vermilion))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Ink.PaperDim)
    }
}
