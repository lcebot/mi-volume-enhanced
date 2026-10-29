package io.github.lcebot.mivolumeenhanced.hook.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import io.github.lcebot.mivolumeenhanced.Protocol

/**
 * Tells the settings app that the module is loaded in this process, and answers its probes with
 * the diagnostics report, so the app shows live state rather than a stale one.
 */
object Heartbeat {
    fun install(process: String) {
        ProcessContext.whenReady { context ->
            Diagnostics.attachVersion(context)
            send(context, Protocol.ACTION_HOOK_ALIVE, process, 0L)
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    send(c, Protocol.ACTION_PONG, process, intent.getLongExtra(Protocol.EXTRA_NONCE, 0L))
                }
            }, IntentFilter(Protocol.ACTION_PING), Context.RECEIVER_EXPORTED)
        }
    }

    private fun send(context: Context, action: String, process: String, nonce: Long) {
        runCatching {
            context.sendBroadcast(
                Intent(action)
                    .setPackage(Protocol.PACKAGE)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(Protocol.EXTRA_PROCESS, process)
                    .putExtra(Protocol.EXTRA_NONCE, nonce)
                    .putExtra(Protocol.EXTRA_REPORT, Diagnostics.report(process)),
            )
        }.onFailure { HookLog.w("status report failed", it) }
    }
}
