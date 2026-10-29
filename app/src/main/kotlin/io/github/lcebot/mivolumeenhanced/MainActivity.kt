package io.github.lcebot.mivolumeenhanced

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.lcebot.mivolumeenhanced.ui.AppTheme
import io.github.lcebot.mivolumeenhanced.ui.MainScreen
import io.github.lcebot.mivolumeenhanced.ui.drawEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        drawEdgeToEdge()
        val prefs = Prefs(this)
        setContent {
            AppTheme {
                MainScreen(prefs)
            }
        }
    }
}
