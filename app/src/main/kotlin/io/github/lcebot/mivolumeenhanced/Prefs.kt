package io.github.lcebot.mivolumeenhanced

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * The options as stored by the settings app. The hooked processes read them through
 * [ConfigProvider] and are told to re-read after every change.
 */
class Prefs(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("options", Context.MODE_PRIVATE)

    val options: Options
        get() {
            val d = Options()
            return Options(
                ancRow = prefs.getBoolean(Options.KEY_ANC_ROW, d.ancRow),
                cycleIncludesOff = prefs.getBoolean(Options.KEY_CYCLE_INCLUDES_OFF, d.cycleIncludesOff),
                island = prefs.getBoolean(Options.KEY_ISLAND, d.island),
                appVolumeEntry = prefs.getBoolean(Options.KEY_APP_VOLUME_ENTRY, d.appVolumeEntry),
                overflowPlacement = Options.placementOf(prefs.getString(Options.KEY_OVERFLOW_PLACEMENT, null)),
            )
        }

    fun save(options: Options) {
        prefs.edit()
            .putBoolean(Options.KEY_ANC_ROW, options.ancRow)
            .putBoolean(Options.KEY_CYCLE_INCLUDES_OFF, options.cycleIncludesOff)
            .putBoolean(Options.KEY_ISLAND, options.island)
            .putBoolean(Options.KEY_APP_VOLUME_ENTRY, options.appVolumeEntry)
            .putString(Options.KEY_OVERFLOW_PLACEMENT, options.overflowPlacement.name)
            .apply()
        for (scope in Protocol.SCOPES) {
            // One scope refusing the broadcast must not keep the others from re-reading.
            try {
                app.sendBroadcast(
                    Intent(Protocol.ACTION_CONFIG_CHANGED).setPackage(scope).addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                )
            } catch (_: RuntimeException) {
            }
        }
    }

    /**
     * The launcher icon is a separate activity alias, so hiding it leaves the activity reachable
     * from LSPosed's module settings entry. Its state lives in the package manager.
     */
    var launcherIconHidden: Boolean
        get() = app.packageManager.getComponentEnabledSetting(launcher) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        set(hidden) {
            app.packageManager.setComponentEnabledSetting(
                launcher,
                if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                PackageManager.DONT_KILL_APP,
            )
        }

    private val launcher = ComponentName(app, "${Protocol.PACKAGE}.Launcher")
}
