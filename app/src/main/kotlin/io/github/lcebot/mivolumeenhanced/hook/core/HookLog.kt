package io.github.lcebot.mivolumeenhanced.hook.core

import android.util.Log
import io.github.libxposed.api.XposedInterface

/**
 * Problems only, never progress. Each problem is recorded in [Diagnostics], which the status sheet
 * shows, and its first occurrence also goes to the LSPosed log with the stack trace, which users can
 * export from LSPosed. Repeats only raise the count, so a failure on a per-frame path floods neither.
 *
 * Nothing is written to logcat directly: LSPosed already puts its log there, and a second copy
 * under the module's own tag would only double the noise.
 */
object HookLog {
    private const val TAG = "MiVolumeEnhanced"

    @Volatile
    private var framework: XposedInterface? = null

    fun attach(xposed: XposedInterface) {
        framework = xposed
    }

    /** Something did not work and a fallback took over, or it may work on a later attempt. */
    fun w(message: String, error: Throwable? = null) = report(Log.WARN, message, error)

    /** A feature is broken in this process. */
    fun e(message: String, error: Throwable? = null) = report(Log.ERROR, message, error)

    private fun report(priority: Int, message: String, error: Throwable?) {
        val state = if (priority == Log.ERROR) Diagnostics.State.FAILED else Diagnostics.State.WARNING
        if (Diagnostics.problem(state, message, error)) toLsposed(priority, message, error)
    }

    /** For problems already recorded in the report under a feature id. */
    internal fun toLsposed(priority: Int, message: String, error: Throwable?) {
        // Logging runs inside hooked processes and must never be what breaks them; the problem is
        // already in the report.
        try {
            framework?.log(priority, TAG, message, error)
        } catch (_: Throwable) {
        }
    }
}
