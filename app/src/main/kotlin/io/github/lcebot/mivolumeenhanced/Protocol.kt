package io.github.lcebot.mivolumeenhanced

import android.net.Uri
import android.os.Bundle

/**
 * Names shared by the settings app and the hooked processes. Both sides are built from this file,
 * so an action or key can never be spelled differently on one end.
 */
object Protocol {
    const val PACKAGE = "io.github.lcebot.mivolumeenhanced"

    const val SYSTEM_UI = "com.android.systemui"
    const val BLUETOOTH = "com.xiaomi.bluetooth"
    const val MISOUND = "com.miui.misound"
    val SCOPES = listOf(SYSTEM_UI, BLUETOOTH, MISOUND)

    const val CONFIG_AUTHORITY = "$PACKAGE.config"
    val CONFIG_URI: Uri = Uri.parse("content://$CONFIG_AUTHORITY")
    const val CONFIG_METHOD_GET = "get"

    /** Carries no values: receivers re-read the provider, so a spoofed broadcast changes nothing. */
    const val ACTION_CONFIG_CHANGED = "$PACKAGE.action.CONFIG_CHANGED"

    const val ACTION_HOOK_ALIVE = "$PACKAGE.action.HOOK_ALIVE"
    const val ACTION_PING = "$PACKAGE.action.PING"
    const val ACTION_PONG = "$PACKAGE.action.PONG"
    const val EXTRA_PROCESS = "process"
    const val EXTRA_NONCE = "nonce"
    const val EXTRA_REPORT = "report"

    /** SystemUI asks MiSound to open its per-app volume panel. */
    const val ACTION_EXPAND_APP_VOLUME = "$PACKAGE.action.EXPAND_APP_VOLUME"

    /**
     * Where the volume bar is on screen, as left, top, right and bottom in pixels, so the per-app
     * panel opens beside the volume keys the bar is placed for. Missing when it is unknown.
     */
    const val EXTRA_BAR_BOUNDS = "bar_bounds"

    /** The display rotation the bounds were measured in; bounds from another rotation are ignored. */
    const val EXTRA_BAR_ROTATION = "bar_rotation"

    /** How the plugin draws its expanded volume slider, as a Bundle with the STYLE_ keys. */
    const val EXTRA_SLIDER_STYLE = "slider_style"
    const val STYLE_ADVANCED = "advanced"
    const val STYLE_RADIUS = "radius"
    const val STYLE_TRACK = "track_color"
    const val STYLE_FILL = "fill_color"

    /** The Bluetooth extension forwards OppoPods state to SystemUI. */
    const val ACTION_OPPO_STATE = "$PACKAGE.action.OPPO_STATE"
    const val EXTRA_OPPO_STATUS = "status"
    const val EXTRA_OPPO_ADDRESS = "address"
}

/**
 * Where the ANC control goes in a panel state that is too tall with the row stacked under the native
 * buttons. That happens in landscape on phones. A state that fits always keeps the row.
 */
enum class Placement {
    /** A round button to the left of the panel, or hidden when there is no room for it there. */
    SIDE,

    /** Not shown. */
    HIDDEN,
}

/** Every user option, with the defaults a fresh install starts from. */
data class Options(
    val ancRow: Boolean = true,
    val cycleIncludesOff: Boolean = true,
    val island: Boolean = false,
    val appVolumeEntry: Boolean = true,
    val overflowPlacement: Placement = Placement.SIDE,
) {
    fun toBundle(): Bundle = Bundle().apply {
        putBoolean(KEY_ANC_ROW, ancRow)
        putBoolean(KEY_CYCLE_INCLUDES_OFF, cycleIncludesOff)
        putBoolean(KEY_ISLAND, island)
        putBoolean(KEY_APP_VOLUME_ENTRY, appVolumeEntry)
        putString(KEY_OVERFLOW_PLACEMENT, overflowPlacement.name)
    }

    companion object {
        const val KEY_ANC_ROW = "anc_row"
        const val KEY_CYCLE_INCLUDES_OFF = "cycle_includes_off"
        const val KEY_ISLAND = "island"
        const val KEY_APP_VOLUME_ENTRY = "app_volume_entry"
        const val KEY_OVERFLOW_PLACEMENT = "overflow_placement"

        fun placementOf(name: String?): Placement =
            Placement.entries.firstOrNull { it.name == name } ?: Placement.SIDE

        fun fromBundle(bundle: Bundle): Options {
            val defaults = Options()
            return Options(
                ancRow = bundle.getBoolean(KEY_ANC_ROW, defaults.ancRow),
                cycleIncludesOff = bundle.getBoolean(KEY_CYCLE_INCLUDES_OFF, defaults.cycleIncludesOff),
                island = bundle.getBoolean(KEY_ISLAND, defaults.island),
                appVolumeEntry = bundle.getBoolean(KEY_APP_VOLUME_ENTRY, defaults.appVolumeEntry),
                overflowPlacement = placementOf(bundle.getString(KEY_OVERFLOW_PLACEMENT)),
            )
        }
    }
}
