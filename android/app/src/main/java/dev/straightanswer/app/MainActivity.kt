package dev.straightanswer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import dev.straightanswer.app.ui.AppRoot
import dev.straightanswer.app.ui.StraightAnswerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The UI is always dark (see ui/Theme.kt), so system bar icons stay light whatever the phone's setting.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val app = application as StraightAnswerApp
        setContent {
            StraightAnswerTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AppRoot(app)
                }
            }
        }
    }
}
