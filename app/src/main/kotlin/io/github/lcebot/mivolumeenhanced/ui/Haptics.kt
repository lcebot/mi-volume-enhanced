package io.github.lcebot.mivolumeenhanced.ui

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * One short tick per deliberate action. A platform constant rather than a vibrator duration, so the
 * user's touch feedback setting applies and the tick feels like the rest of the system. Components
 * that give their own feedback do not get a second one, because a double buzz reads as an error.
 */
@Stable
class Haptics(private val view: View) {
    fun tick() {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    /** Wraps an action so the tick cannot be forgotten at the call site. */
    fun ticking(action: () -> Unit): () -> Unit = {
        tick()
        action()
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}
