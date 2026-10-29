package io.github.lcebot.mivolumeenhanced.hook.systemui

import android.content.Context
import android.view.WindowManager
import io.github.lcebot.mivolumeenhanced.Placement
import io.github.lcebot.mivolumeenhanced.hook.core.HookLog
import io.github.lcebot.mivolumeenhanced.hook.core.HookOptions
import io.github.lcebot.mivolumeenhanced.hook.core.Res
import java.lang.reflect.Method

/** Where the ANC control goes in one panel state. */
enum class Spot {
    /** A row in the panel, below do-not-disturb. */
    PANEL,

    /** A round button beside the panel. */
    SIDE,

    /** Not shown. */
    HIDDEN,
}

/** The spot for each panel state. They differ when only the expanded panel is too tall. */
data class Plan(val collapsed: Spot, val expanded: Spot) {
    fun spot(expandedState: Boolean) = if (expandedState) expanded else collapsed

    companion object {
        val STACKED = Plan(Spot.PANEL, Spot.PANEL)
    }
}

/**
 * Decides where the ANC control goes, from the same numbers the plugin uses to place the panel.
 *
 * In landscape, and in the Control Center panel in any orientation, the plugin centres the panel
 * vertically: top = (screen height - panel height) / 2, for the collapsed and the expanded panel
 * alike. Stacking the row adds its height, and on a landscape phone that can make the expanded
 * panel, and on short screens the collapsed one too, taller than the screen.
 *
 * Each state is decided on its own: a state whose panel fits keeps the row, whatever the other
 * state does, so the row leaves the panel only where it has to. The collapsed dialog also counts
 * the app volume entry above it. A state that does not fit puts the
 * control where the user's [Placement] option says: beside the panel, or hidden. Beside the panel
 * needs room to the left of the expanded panel; without it the control is hidden.
 *
 * This is a pure function of the configuration, so the getHeight hook and the row's visibility,
 * which ask separately, always agree.
 */
object Planner {
    private const val MIN_MARGIN_DP = 8f

    private val measuringBase = ThreadLocal<Boolean>()

    @Volatile private var getHeight: Method? = null

    fun attach(getHeight: Method) {
        this.getHeight = getHeight
    }

    /** True while [layout] asks the plugin for the native height, so the getHeight hook adds nothing. */
    fun isMeasuringBase() = measuringBase.get() == true

    /** [expandedExtra] and [collapsedExtra] are what the stacked row adds to each panel. */
    fun plan(context: Context, dialog: Boolean, expandedExtra: Int, collapsedExtra: Int): Plan {
        if (expandedExtra <= 0) return Plan.STACKED
        val screen = context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds ?: return Plan.STACKED
        // From the window bounds rather than the configuration: on rotation the plugin lays the
        // panel out from the display change, which can arrive before the configuration change.
        val landscape = screen.width() > screen.height()
        // The portrait volume dialog hangs from a fixed top margin, so stacking always fits.
        if (dialog && !landscape) return Plan.STACKED
        val margin = 2 * Res.dp(context, MIN_MARGIN_DP)
        val outside = if (HookOptions.current.overflowPlacement == Placement.SIDE && sideFits(context, screen.width())) Spot.SIDE else Spot.HIDDEN

        fun fits(expanded: Boolean, extra: Int): Boolean {
            val native = nativeHeight(context, dialog, expanded) ?: return true
            return native + extra + margin <= screen.height()
        }

        val expanded = if (fits(expanded = true, expandedExtra)) Spot.PANEL else outside
        // The app volume entry stands above the collapsed dialog and is centred with it, so it
        // counts whenever it may show; counting it only while it shows would move the row each
        // time playback starts or stops.
        val entry = AppVolumeEntry.reservedHeight(context, dialog)
        val collapsed = if (fits(expanded = false, collapsedExtra + entry)) Spot.PANEL else outside
        return Plan(collapsed, expanded)
    }

    private fun nativeHeight(context: Context, dialog: Boolean, expanded: Boolean): Int? {
        val method = getHeight ?: return null
        measuringBase.set(true)
        return try {
            method.invoke(null, context, dialog, expanded) as? Int
        } catch (t: Throwable) {
            HookLog.w("native panel height", t)
            null
        } finally {
            measuringBase.set(false)
        }
    }

    /** The side button needs its own width plus a gap on each side, left of the expanded panel. */
    private fun sideFits(context: Context, screenWidth: Int): Boolean {
        val panel = Res.dimen(context, "miui_volume_content_width_expanded", 220f) + Res.dp(context, 32f)
        val button = Res.dimen(context, "o3_miui_ringer_btn_width_expended", 56f) + Res.dp(context, 24f)
        return screenWidth - panel >= button
    }
}
