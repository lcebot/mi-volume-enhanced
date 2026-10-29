package io.github.lcebot.mivolumeenhanced.hook

import io.github.lcebot.mivolumeenhanced.Protocol
import io.github.lcebot.mivolumeenhanced.hook.bluetooth.BluetoothHooks
import io.github.lcebot.mivolumeenhanced.hook.core.Diagnostics
import io.github.lcebot.mivolumeenhanced.hook.core.Heartbeat
import io.github.lcebot.mivolumeenhanced.hook.core.HookLog
import io.github.lcebot.mivolumeenhanced.hook.core.HookOptions
import io.github.lcebot.mivolumeenhanced.hook.core.ProcessContext
import io.github.lcebot.mivolumeenhanced.hook.misound.MiSoundHooks
import io.github.lcebot.mivolumeenhanced.hook.systemui.PluginLocator
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/** Listed in META-INF/xposed/java_init.list; created by the framework once per scoped process. */
class HookEntry : XposedModule() {
    @Volatile
    private var started = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        HookLog.attach(this)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val pkg = param.packageName
        if (pkg !in Protocol.SCOPES) return
        synchronized(this) {
            if (started) return
            started = true
        }
        Diagnostics.ok("scope", pkg)
        ProcessContext.install(this)
        Heartbeat.install(pkg)
        when (pkg) {
            Protocol.SYSTEM_UI -> {
                HookOptions.install()
                PluginLocator.install(this, param.classLoader)
            }
            Protocol.MISOUND -> {
                HookOptions.install()
                MiSoundHooks.install(this, param.classLoader)
            }
            Protocol.BLUETOOTH -> BluetoothHooks.install(this, param.classLoader)
        }
    }
}
