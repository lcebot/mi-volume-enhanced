package io.github.lcebot.mivolumeenhanced.hook.systemui

import android.content.Context
import android.os.Bundle
import io.github.lcebot.mivolumeenhanced.Protocol
import io.github.lcebot.mivolumeenhanced.hook.core.HookLog
import io.github.lcebot.mivolumeenhanced.hook.core.Res

/**
 * How the plugin draws an expanded volume slider, read from the plugin so MiSound's per-app sliders
 * can be drawn the same way. MiSound cannot load the plugin's slider, which is part of SystemUI, so
 * the inputs are sent along with the request to open the panel: the column's corner radius and the
 * colours of the track and the fill.
 *
 * The colours are the plugin's flat ones, which render in any window: the white fill
 * (miui_volume_color_accent), and as the track the plugin's flat glass colour under advanced
 * material (glass_flat_bg_color) or its plain track colour otherwise (miui_volume_bg_color). The
 * plugin's blended material is not used: its blend colours need the HyperOS blur in the window
 * behind them, and MiSound's panel window draws nothing for them.
 */
internal object SliderStyle {
    private const val PKG = "com.android.systemui.miui.volume."

    fun read(context: Context, loader: ClassLoader): Bundle? = try {
        val util = loader.loadClass(PKG + "Util")
        val res = loader.loadClass(PKG + "VolumeColumnRes")
        val bool = Boolean::class.javaPrimitiveType!!
        val advanced = util.getDeclaredMethod("isAdvancedMaterialEffective", Context::class.java).invoke(null, context) as Boolean
        // (dialog, expanded), as VolumeColumn.setRadius passes them.
        val radius = res.getDeclaredMethod("getRadius", Context::class.java, bool, bool).invoke(null, context, true, true) as Int
        Bundle().apply {
            putBoolean(Protocol.STYLE_ADVANCED, advanced)
            putInt(Protocol.STYLE_RADIUS, radius)
            putInt(
                Protocol.STYLE_TRACK,
                if (advanced) Res.color(context, "glass_flat_bg_color", 0x3D000000)
                else Res.color(context, "miui_volume_bg_color", 0x40E3E3E3),
            )
            putInt(Protocol.STYLE_FILL, Res.color(context, "miui_volume_color_accent", 0xFFFFFFFF.toInt()))
        }
    } catch (t: Throwable) {
        HookLog.w("volume slider style unreadable, MiSound keeps its own", t)
        null
    }
}
