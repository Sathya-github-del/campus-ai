package dev.studyapp.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.graphics.Color
import dev.studyapp.app.core.Tier
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.studyapp.app.core.ErrorText
import dev.studyapp.app.core.Money
import dev.studyapp.app.core.TopUpAmount
import dev.studyapp.app.data.Backend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WelcomeViewModel(private val backend: Backend) : ViewModel() {
    data class State(val busy: Boolean = false, val error: String? = null, val done: Boolean = false)

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    fun register() {
        if (_state.value.busy) return
        _state.value = State(busy = true)
        viewModelScope.launch {
            _state.value = try {
                backend.ensureRegistered()
                State(done = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                State(error = ErrorText.of(e))
            }
        }
    }
}

/** Two short pages explaining pay-per-answer, then registers this device and opens the ask screen. */
@Composable
fun WelcomeScreen(vm: WelcomeViewModel, onRegistered: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(state.done) { if (state.done) onRegistered() }

    Column(
        Modifier.fillMaxSize().background(Ink.Bg).safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
            Spacer(Modifier.height(24.dp))
            if (page == 0) PageOne() else PageTwo()
        }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 12.dp))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PageDots(page)
            Spacer(Modifier.weight(1f))
            if (page == 1) TextButton(onClick = { page = 0 }, enabled = !state.busy) { Text("Back", color = Ink.PaperDim) }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { if (page == 0) page = 1 else vm.register() },
                enabled = !state.busy,
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(containerColor = Lit.Tangerine, contentColor = Color(0xFF2B1600)),
                modifier = Modifier.height(52.dp),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(0xFF2B1600))
                    Spacer(Modifier.width(8.dp))
                    Text("Setting up…")
                } else {
                    Text(if (page == 0) "How it works" else if (state.error != null) "Try again" else "Start asking")
                }
            }
        }
    }
}

/** Sample fares that cycle on the welcome meter, one per tier (illustrative, not live prices). */
private val DEMO = listOf(Tier.FAST to 0.02, Tier.SMART to 0.24, Tier.EXPERT to 1.60)

@Composable
private fun PageOne() {
    var i by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2600)
            i = (i + 1) % DEMO.size
        }
    }
    val (tier, fare) = DEMO[i]
    MeterPanel(shape = MaterialTheme.shapes.large, contentPadding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Legend("Fare for one answer")
            Spacer(Modifier.width(14.dp))
            AnimatedContent(tier, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) }, label = "tier") { TierTag(it) }
        }
        Spacer(Modifier.height(10.dp))
        Fare(fare, 40.dp, minIntDigits = 2, startFrom = 0.0)
    }
    Spacer(Modifier.height(28.dp))
    Text("Pay for the answer.\nNot the month.", style = MaterialTheme.typography.displaySmall, color = Ink.Paper)
    Spacer(Modifier.height(14.dp))
    Text(
        "Quick AI answers for anything: homework, code, emails, a contract clause, plain curiosity. " +
            "Each answer shows its exact fare, usually a few paise.",
        style = MaterialTheme.typography.bodyLarge, color = Ink.PaperDim,
    )
    Spacer(Modifier.height(24.dp))
    Point("No subscription", "Nothing renews and nothing is charged automatically.")
    Point("Start with ${Money.inrWhole(TopUpAmount.MIN)}", "Top up with UPI from ${Money.inrWhole(TopUpAmount.MIN)}, only when you want to.")
    Point("See the rate before you ask", "Every question shows its price up front, and the exact fare after.")
}

@Composable
private fun PageTwo() {
    Text("How it works", style = MaterialTheme.typography.displaySmall, color = Ink.Paper)
    Spacer(Modifier.height(28.dp))
    Step(1, "Type it or snap it", "Write your question, or photograph a page, a screen or a note. You can edit the text before sending. Photos are read on your phone, never uploaded.")
    Step(2, "Pick how much thinking", "Fast for everyday questions, Smart for step-by-step work, Expert for the really hard stuff. Each shows its rate.")
    Step(3, "Watch the fare land", "Your answer arrives with exactly what it cost. Want to dig deeper? Follow up on that answer.")
    Spacer(Modifier.height(4.dp))
    Text("Your balance stays in the app and is only spent on your answers.",
        style = MaterialTheme.typography.bodySmall, color = Ink.PaperFaint)
}

@Composable
private fun Step(n: Int, title: String, body: String) {
    Row(Modifier.padding(bottom = 22.dp)) {
        MeterPanel(shape = MaterialTheme.shapes.small, contentPadding = PaddingValues(horizontal = 9.dp, vertical = 8.dp)) {
            SegmentDisplay(n.toString(), 22.dp)
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Ink.Paper)
            Spacer(Modifier.height(3.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Ink.PaperDim)
        }
    }
}

@Composable
private fun Point(title: String, body: String) {
    Row(Modifier.padding(bottom = 16.dp)) {
        Column(Modifier.padding(top = 4.dp)) { PilotLight(size = 6.dp) }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Ink.Paper)
            Spacer(Modifier.height(2.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Ink.PaperDim)
        }
    }
}

@Composable
private fun PageDots(page: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(2) { i ->
            Surface(
                shape = CircleShape,
                color = if (i == page) Lit.Tangerine else Ink.Raised3,
                modifier = Modifier.size(width = if (i == page) 18.dp else 8.dp, height = 8.dp),
            ) {}
        }
    }
}
