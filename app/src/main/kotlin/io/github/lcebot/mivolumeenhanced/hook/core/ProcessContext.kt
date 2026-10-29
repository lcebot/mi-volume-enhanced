package io.github.lcebot.mivolumeenhanced.hook.core

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedInterface

/**
 * The hooked process's Application context. onPackageReady can run before the Application exists,
 * so callers that need a context wait for Instrumentation.callApplicationOnCreate.
 */
object ProcessContext {
    val main = Handler(Looper.getMainLooper())

    @Volatile
    var application: Context? = null
        private set

    private val pending = mutableListOf<(Context) -> Unit>()

    fun install(xposed: XposedInterface) {
        currentApplication()?.let { deliver(it); return }
        val target = Instrumentation::class.java.getDeclaredMethod("callApplicationOnCreate", Application::class.java)
        xposed.after(target, "process:application-create") { chain, _ ->
            (chain.getArg(0) as? Context)?.let(::deliver)
        }
    }

    /** Runs [action] with the Application context, now or once it exists. */
    fun whenReady(action: (Context) -> Unit) {
        val ready = synchronized(pending) {
            application ?: run { pending += action; null }
        }
        ready?.let(action)
    }

    private fun deliver(context: Context) {
        val app = context.applicationContext ?: context
        val actions = synchronized(pending) {
            if (application != null) return
            application = app
            pending.toList().also { pending.clear() }
        }
        actions.forEach { runCatching { it(app) }.onFailure { e -> HookLog.e("startup action failed", e) } }
    }
}
