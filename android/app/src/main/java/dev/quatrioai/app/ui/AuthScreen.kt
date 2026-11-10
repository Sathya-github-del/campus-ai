package dev.quatrioai.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.quatrioai.app.R
import dev.quatrioai.app.core.AuthText
import dev.quatrioai.app.core.LegalDoc
import dev.quatrioai.app.core.Money

/** Text field colours shared by the auth and profile screens: tangerine focus on the warm dark panel. */
@Composable
internal fun nightFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Lit.Tangerine, unfocusedBorderColor = Ink.Line,
    focusedLabelColor = Lit.Tangerine, cursorColor = Lit.Tangerine,
)

/** The primary tangerine action, with an in-place spinner while [busy]. */
@Composable
internal fun PrimaryButton(text: String, busyText: String, busy: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = Lit.Tangerine, contentColor = Color(0xFF2B1600),
            disabledContainerColor = if (busy) Lit.Tangerine.copy(alpha = 0.6f) else Ink.Raised2,
            disabledContentColor = if (busy) Color(0xFF2B1600) else Ink.PaperFaint,
        ),
        modifier = modifier.fillMaxWidth().height(56.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(0xFF2B1600))
            Spacer(Modifier.width(10.dp))
            Text(busyText, style = MaterialTheme.typography.titleMedium)
        } else {
            Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Create account / Log in on one screen (a two-way switch at the top), with "Skip for now" for people
 * who just want to ask something. Signing up is never required before the first question.
 *
 * [expired] = we got here because the server rejected the account's token. [onBack] is non-null when
 * there is somewhere to go back to (opened from Profile); [onSkip] is the guest path.
 */
@Composable
fun AuthScreen(
    vm: AuthViewModel,
    expired: Boolean,
    onDone: () -> Unit,
    onOpenLegal: (LegalDoc) -> Unit,
    onBack: (() -> Unit)?,
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    LaunchedEffect(s.done) { if (s.done) onDone() }
    val signup = s.mode == AuthMode.SIGNUP

    Column(Modifier.fillMaxSize().background(Ink.Bg).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Back", tint = Ink.Paper) }
            else Spacer(Modifier.width(16.dp))
            PilotLight()
            Spacer(Modifier.width(8.dp))
            Legend("Your account", color = Ink.PaperDim)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (signup) "Keep your balance safe." else "Welcome back.",
                style = MaterialTheme.typography.displaySmall, color = Ink.Paper,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (signup) "With an account you can log in again on this or a new phone, and your balance and history come with you."
                else "Log in to get your balance and history back.",
                style = MaterialTheme.typography.bodyLarge, color = Ink.PaperDim,
            )

            if (expired) {
                Spacer(Modifier.height(16.dp))
                Notice("Logged out", "Your session ended, so please log in again. Your balance is safe on your account.", Lit.Vermilion)
            }
            s.guestBalanceAtRisk?.let { bal ->
                Spacer(Modifier.height(16.dp))
                Notice(
                    "About your guest balance",
                    "You have ${Money.inr(bal)} as a guest on this phone. Creating an account or logging in starts " +
                        "a separate wallet, and the guest balance can't be moved into it.",
                    Lit.Tangerine,
                )
            }

            Spacer(Modifier.height(22.dp))
            ModeSwitch(s.mode, enabled = !s.busy, onMode = vm::setMode)
            Spacer(Modifier.height(18.dp))

            OutlinedTextField(
                value = s.email,
                onValueChange = vm::setEmail,
                label = { Text("Email") },
                placeholder = { Text("you@example.com", color = Ink.PaperFaint) },
                singleLine = true,
                isError = s.emailError != null,
                supportingText = s.emailError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                enabled = !s.busy,
                shape = MaterialTheme.shapes.medium,
                colors = nightFieldColors(),
                modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.EmailAddress + ContentType.Username },
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = s.password,
                onValueChange = vm::setPassword,
                label = { Text("Password") },
                singleLine = true,
                isError = s.passwordError != null,
                supportingText = s.passwordError?.let { { Text(it) } },
                visualTransformation = if (s.showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = vm::togglePassword) {
                        Icon(
                            painterResource(if (s.showPassword) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                            if (s.showPassword) "Hide password" else "Show password",
                            tint = if (s.showPassword) Lit.Tangerine else Ink.PaperDim,
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); vm.submit() }),
                enabled = !s.busy,
                shape = MaterialTheme.shapes.medium,
                colors = nightFieldColors(),
                modifier = Modifier.fillMaxWidth().semantics {
                    contentType = if (signup) ContentType.NewPassword else ContentType.Password
                },
            )
            if (!signup) {
                TextButton(onClick = vm::openForgot, enabled = !s.busy, modifier = Modifier.align(Alignment.End)) {
                    Text("Forgot password?", color = Lit.Tangerine)
                }
            }

            s.error?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.errorContainer)
                        .padding(start = 14.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                    if (s.duplicateEmail) TextButton(onClick = vm::loginInstead) { Text("Log in", color = MaterialTheme.colorScheme.onErrorContainer) }
                }
            }

            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                text = if (signup) "Create account" else "Log in",
                busyText = if (signup) "Creating account…" else "Logging in…",
                busy = s.busy,
                onClick = { focus.clearFocus(); vm.submit() },
            )
            if (signup) {
                Spacer(Modifier.height(12.dp))
                LegalLine(onOpenLegal)
            }

            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(Modifier.weight(1f), color = Ink.LineSoft)
                Legend("or", Modifier.padding(horizontal = 12.dp))
                HorizontalDivider(Modifier.weight(1f), color = Ink.LineSoft)
            }
            Spacer(Modifier.height(16.dp))
            if (s.isGuest && onBack != null) {
                OutlinedButton(onClick = onBack, enabled = !s.busy, shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Keep using as a guest", color = Ink.Paper)
                }
            } else {
                OutlinedButton(onClick = vm::continueAsGuest, enabled = !s.busy, shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Skip for now, use without an account", color = Ink.Paper)
                }
                Text(
                    "You can ask right away. Your balance then lives on this phone only, and you can create an account later from Profile.",
                    style = MaterialTheme.typography.bodySmall, color = Ink.PaperFaint, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            if (!signup) {
                Spacer(Modifier.height(16.dp))
                LegalLine(onOpenLegal, prefix = "Read our")
            }
            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    s.forgot?.let { f ->
        ForgotDialog(f, vm::setForgotEmail, vm::sendReset, vm::closeForgot)
    }
}

/** Sign up / Log in as two tactile keys, styled like the tier cards. */
@Composable
private fun ModeSwitch(mode: AuthMode, enabled: Boolean, onMode: (AuthMode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(Ink.Panel)
            .border(1.dp, Ink.LineSoft, MaterialTheme.shapes.medium).padding(4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(AuthMode.SIGNUP to "Create account", AuthMode.LOGIN to "Log in").forEach { (m, label) ->
            val selected = m == mode
            val bg by animateColorAsState(if (selected) Ink.Raised2 else Ink.Panel, label = "mode")
            Box(
                Modifier.weight(1f).clip(MaterialTheme.shapes.small).background(bg)
                    .border(1.dp, if (selected) Lit.Tangerine.copy(alpha = 0.7f) else Color.Transparent, MaterialTheme.shapes.small)
                    .selectable(selected, enabled = enabled, role = Role.Tab, onClick = { onMode(m) })
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.titleSmall, color = if (selected) Ink.Paper else Ink.PaperFaint)
            }
        }
    }
}

/** "By creating an account you agree to the Terms of Service and Privacy Policy (drafts)." */
@Composable
internal fun LegalLine(onOpenLegal: (LegalDoc) -> Unit, prefix: String = "By creating an account you agree to the") {
    val link = TextLinkStyles(SpanStyle(color = Lit.Tangerine, fontWeight = FontWeight.SemiBold))
    val text = buildAnnotatedString {
        append("$prefix ")
        withLink(LinkAnnotation.Clickable("terms", link) { onOpenLegal(LegalDoc.TERMS) }) { append("Terms of Service") }
        append(" and ")
        withLink(LinkAnnotation.Clickable("privacy", link) { onOpenLegal(LegalDoc.PRIVACY) }) { append("Privacy Policy") }
        append(" (drafts).")
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = Ink.PaperFaint, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

/** A small framed note in the meter-panel style: a coloured legend and a line of explanation. */
@Composable
internal fun Notice(title: String, body: String, accent: Color, modifier: Modifier = Modifier) {
    MeterPanel(modifier.fillMaxWidth(), border = accent.copy(alpha = 0.55f), contentPadding = PaddingValues(14.dp)) {
        Legend(title, color = accent)
        Text(body, style = MaterialTheme.typography.bodySmall, color = Ink.PaperDim, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ForgotDialog(f: ForgotState, onEmail: (String) -> Unit, onSend: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!f.busy) onClose() },
        containerColor = Ink.Raised,
        title = { Text(if (f.sent) "Check your email" else "Reset your password") },
        text = {
            Column {
                if (f.sent) {
                    Text(AuthText.RESET_SENT, color = Ink.PaperDim)
                } else {
                    Text("Enter the email you signed up with and we'll send a link to choose a new password.", color = Ink.PaperDim)
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = f.email,
                        onValueChange = onEmail,
                        label = { Text("Email") },
                        singleLine = true,
                        isError = f.emailError != null,
                        supportingText = f.emailError?.let { { Text(it) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onSend = { onSend() }),
                        enabled = !f.busy,
                        shape = MaterialTheme.shapes.medium,
                        colors = nightFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    f.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            if (f.sent) {
                Button(onClick = onClose, colors = ButtonDefaults.buttonColors(containerColor = Lit.Tangerine)) { Text("Done") }
            } else {
                Button(onClick = onSend, enabled = !f.busy, colors = ButtonDefaults.buttonColors(containerColor = Lit.Tangerine)) {
                    Text(if (f.busy) "Sending…" else "Send link")
                }
            }
        },
        dismissButton = { if (!f.sent) TextButton(onClick = onClose, enabled = !f.busy) { Text("Cancel") } },
    )
}
