package dev.quatrioai.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.quatrioai.app.BuildConfig
import dev.quatrioai.app.R
import dev.quatrioai.app.core.AboutText

/** App + owner info: name, tagline, what it does, version, and a credit line. Static content, no ViewModel. */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Ink.Bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Back", tint = Ink.Paper) }
            Text("About", style = MaterialTheme.typography.headlineSmall, color = Ink.Paper)
        }
        SelectionContainer(Modifier.weight(1f)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(16.dp))
                Text("QUATRIO AI", style = MaterialTheme.typography.displaySmall, color = Ink.Paper)
                Spacer(Modifier.height(6.dp))
                Legend(AboutText.TAGLINE, color = Lit.Tangerine)
                AboutText.SECTIONS.forEach { sec ->
                    Spacer(Modifier.height(22.dp))
                    Text(
                        sec.heading,
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = MaterialTheme.typography.headlineSmall.fontFamily),
                        color = Ink.Paper, modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(sec.body, style = MaterialTheme.typography.bodyLarge, color = Ink.PaperDim)
                }
                Spacer(Modifier.height(28.dp))
                Legend("Version ${BuildConfig.VERSION_NAME}")
                Spacer(Modifier.height(8.dp))
                Legend(AboutText.OWNER_PLACEHOLDER)
                Spacer(Modifier.height(32.dp))
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}
