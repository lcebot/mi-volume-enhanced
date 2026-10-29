package io.github.lcebot.mivolumeenhanced.hook.systemui

import android.content.Context
import io.github.lcebot.mivolumeenhanced.hook.core.Diagnostics
import io.github.lcebot.mivolumeenhanced.hook.core.ProcessContext
import io.github.lcebot.mivolumeenhanced.hook.core.after
import io.github.lcebot.mivolumeenhanced.hook.core.around
import io.github.lcebot.mivolumeenhanced.hook.core.find
import io.github.lcebot.mivolumeenhanced.hook.core.overloadId
import io.github.libxposed.api.XposedInterface

/**
 * Finds the class loader of the HyperOS volume plugin (package miui.systemui.plugin), which SystemUI
 * loads some time after it starts.
 *
 * The cheap sources are tried first: the plugin injector's loader map, then the plugin lifecycle
 * callbacks. Watching ClassLoader.loadClass is the last resort, because every class load in SystemUI
 * passes through it, and with several modules doing the same, startup has been seen to time out.
 */
object PluginLocator {
    private const val RINGER_LAYOUT = "com.android.systemui.miui.volume.MiuiRingerModeLayout"
    private const val INJECTOR = "com.miui.systemui.plugin.PluginInstanceInjector"
    private const val PLUGIN_INSTANCE = "com.android.systemui.shared.plugins.PluginInstance"
    private const val ACTION_MANAGER = "com.android.systemui.shared.plugins.PluginActionManager"
    private const val RETRY_MS = 500L
    private const val RETRIES = 24

    @Volatile private var found = false
    private var loadClassHook: XposedInterface.HookHandle? = null
    private val inspecting = ThreadLocal<Boolean>()

    fun install(xposed: XposedInterface, systemUi: ClassLoader) {
        if (tryLoader(xposed, fromInjector(systemUi))) return
        Diagnostics.waiting("plugin", "waiting for the volume plugin")
        watchLifecycle(xposed, systemUi)
        retry(xposed, systemUi, 0)
    }

    private fun retry(xposed: XposedInterface, systemUi: ClassLoader, attempt: Int) {
        ProcessContext.main.postDelayed({
            if (found || tryLoader(xposed, fromInjector(systemUi))) return@postDelayed
            if (attempt < RETRIES) retry(xposed, systemUi, attempt + 1) else watchClassLoading(xposed)
        }, RETRY_MS)
    }

    /** HyperOS keeps the loader of each plugin package in PluginInstanceInjector.sClassLoaders. */
    private fun fromInjector(systemUi: ClassLoader): ClassLoader? = runCatching {
        val field = systemUi.find(INJECTOR)?.getDeclaredField("sClassLoaders") ?: return null
        field.isAccessible = true
        (field.get(null) as? Map<*, *>)?.get("miui.systemui.plugin") as? ClassLoader
    }.getOrNull()

    private fun watchLifecycle(xposed: XposedInterface, systemUi: ClassLoader) {
        systemUi.find(PLUGIN_INSTANCE)?.declaredMethods?.filter { it.name == "loadPlugin" }?.forEach { m ->
            xposed.after(m, overloadId("plugin-instance-load", m)) { chain, _ -> inspect(xposed, chain.thisObject) }
        }
        systemUi.find(ACTION_MANAGER)?.declaredMethods
            ?.filter { it.name == "onPluginConnected" && it.parameterCount >= 1 }?.forEach { m ->
                xposed.after(m, overloadId("plugin-connected", m)) { chain, _ -> inspect(xposed, chain.getArg(0)) }
            }
    }

    private fun inspect(xposed: XposedInterface, instance: Any?) {
        if (found || instance == null || inspecting.get() == true) return
        inspecting.set(true)
        try {
            for (name in PLUGIN_FIELDS) {
                val loader = loaderOf(read(instance, name), 0) ?: continue
                if (loader.find(RINGER_LAYOUT) != null && tryLoader(xposed, loader)) return
            }
        } finally {
            inspecting.set(false)
        }
    }

    private fun loaderOf(value: Any?, depth: Int): ClassLoader? = when {
        value == null || depth > 2 -> null
        value is ClassLoader -> value
        value is Context -> value.classLoader
        else -> NESTED_FIELDS.firstNotNullOfOrNull { loaderOf(read(value, it), depth + 1) }
    }

    private fun read(target: Any, name: String): Any? = runCatching {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    }.getOrNull()

    private fun watchClassLoading(xposed: XposedInterface) {
        Diagnostics.waiting("plugin", "not found through the plugin manager, watching class loading")
        val loadClass = ClassLoader::class.java.getDeclaredMethod("loadClass", String::class.java, Boolean::class.javaPrimitiveType)
        loadClassHook = xposed.around(loadClass, "plugin-load-class") { chain ->
            val result = chain.proceed()
            if (chain.getArg(0) == RINGER_LAYOUT && result is Class<*>) {
                ProcessContext.main.post { tryLoader(xposed, result.classLoader) }
            }
            result
        }
    }

    @Synchronized
    private fun tryLoader(xposed: XposedInterface, loader: ClassLoader?): Boolean {
        if (found) return true
        loader ?: return false
        if (loader.find(RINGER_LAYOUT) == null) return false
        return try {
            VolumePanelHooks.install(xposed, loader)
            found = true
            loadClassHook?.unhook()
            loadClassHook = null
            Diagnostics.ok("plugin", "volume plugin hooked")
            true
        } catch (t: Throwable) {
            Diagnostics.failed("plugin", t)
            false
        }
    }

    private val PLUGIN_FIELDS = listOf("pluginData", "mPlugin", "plugin", "mPluginContext", "pluginContext", "pluginFactory", "mPluginFactory")
    private val NESTED_FIELDS = listOf("plugin", "context", "hostContext", "pluginContext", "mPlugin", "mPluginContext")
}
