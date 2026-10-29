package io.github.lcebot.mivolumeenhanced.hook.bluetooth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import io.github.lcebot.mivolumeenhanced.Protocol
import io.github.lcebot.mivolumeenhanced.hook.core.Diagnostics
import io.github.lcebot.mivolumeenhanced.hook.core.HookLog
import io.github.lcebot.mivolumeenhanced.hook.core.ProcessContext
import io.github.lcebot.mivolumeenhanced.hook.core.around
import io.github.lcebot.mivolumeenhanced.hook.core.find
import io.github.lcebot.mivolumeenhanced.hook.headset.Oppo
import io.github.libxposed.api.XposedInterface

/** Hooks in the Xiaomi Bluetooth extension (com.xiaomi.bluetooth). */
object BluetoothHooks {
    private const val PROVIDER =
        "com.android.bluetooth.ble.app.headset.miuibluetoothprovider.MiuiBluetoothContentProvider"
    private const val PERMISSION_CHECKER = "com.android.bluetooth.ble.app.permission.PermissionChecker"

    fun install(xposed: XposedInterface, loader: ClassLoader) {
        allowSystemUiToReadAirPods(xposed, loader)
        ProcessContext.whenReady(::forwardOppoState)
    }

    /**
     * The extension's AirPods repository provider only answers its own allow-listed callers. SystemUI
     * reads the listening mode from it, so SystemUI is let through both checks.
     */
    private fun allowSystemUiToReadAirPods(xposed: XposedInterface, loader: ClassLoader) {
        try {
            val provider = loader.find(PROVIDER) ?: error("provider class missing")
            val checkCaller = provider.getDeclaredMethod("checkCallerPermission", Context::class.java, String::class.java)
            xposed.around(checkCaller, "bt:airpods-caller") { chain ->
                if (chain.getArg(1) == Protocol.SYSTEM_UI) true else chain.proceed()
            }
            val checker = loader.find(PERMISSION_CHECKER) ?: error("permission checker missing")
            val checkSignature = checker.getDeclaredMethod(
                "b", Context::class.java, String::class.java, Array<String>::class.java, Array<String>::class.java,
            )
            xposed.around(checkSignature, "bt:airpods-signature") { chain ->
                if (chain.getArg(1) == Protocol.SYSTEM_UI) true else chain.proceed()
            }
            Diagnostics.ok("bt_airpods", "SystemUI may read the AirPods repository")
        } catch (t: Throwable) {
            Diagnostics.failed("bt_airpods", t)
        }
    }

    /**
     * OppoPods publishes headset state inside this process only. SystemUI needs it to accept an OPPO
     * headset by address and to show the current mode.
     */
    private fun forwardOppoState(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val status = intent.getIntExtra("status", 0)
                if (Oppo.statusToMode(status) == null) return
                runCatching {
                    c.sendBroadcast(
                        Intent(Protocol.ACTION_OPPO_STATE)
                            .setPackage(Protocol.SYSTEM_UI)
                            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                            .putExtra(Protocol.EXTRA_OPPO_STATUS, status)
                            .putExtra(Protocol.EXTRA_OPPO_ADDRESS, intent.getStringExtra("address")),
                    )
                }.onFailure { HookLog.w("OppoPods forward", it) }
            }
        }
        context.registerReceiver(receiver, IntentFilter(Oppo.ACTION_STATE), Context.RECEIVER_EXPORTED)
        Diagnostics.ok("bt_oppo", "forwarding OppoPods state")
    }
}
