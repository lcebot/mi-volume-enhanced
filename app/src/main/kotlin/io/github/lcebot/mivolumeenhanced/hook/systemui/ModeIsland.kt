package io.github.lcebot.mivolumeenhanced.hook.systemui

import android.content.Context
import android.os.Bundle
import io.github.lcebot.mivolumeenhanced.Protocol
import io.github.lcebot.mivolumeenhanced.hook.core.HookLog
import io.github.lcebot.mivolumeenhanced.hook.core.localized
import io.github.lcebot.mivolumeenhanced.hook.headset.AncMode
import org.json.JSONObject

/**
 * The HyperOS device notification ("island") shown after switching to noise cancelling or
 * transparency. SystemUI drops a strong toast request unless it carries an island_param it can
 * parse; the payload mirrors what the MIUI Bluetooth extension sends. The icon is a drawable of this
 * module, resolved by name from package_name.
 */
object ModeIsland {
    fun show(context: Context, mode: AncMode) {
        if (mode == AncMode.OFF) return
        val transparency = mode == AncMode.TRANSPARENCY
        val text = if (transparency) context.localized("Transparency on", "通透开启")
        else context.localized("Noise cancelling on", "降噪开启")
        val icon = if (transparency) "mode_transparency" else "mode_noise_cancelling"
        try {
            val bundle = Bundle().apply {
                putString("package_name", Protocol.PACKAGE)
                putString("notifyId", "mve_mode")
                putString("strong_toast_category", "text_bitmap_intent")
                putString("status_bar_strong_toast", "show_custom_strong_toast")
                putLong("duration", 2_500L)
                putString("island_param", islandParam(text, icon).toString())
                // Older SystemUI builds read this one instead.
                putString("param", guideParam(text, icon).toString())
            }
            @Suppress("WrongConstant")
            val statusBar = context.getSystemService("statusbar") ?: return
            val setStatus = runCatching {
                statusBar.javaClass.getMethod("setStatus", Int::class.java, String::class.java, Bundle::class.java)
            }.getOrNull() ?: statusBar.javaClass.getDeclaredMethod(
                "setStatus", Int::class.java, String::class.java, Bundle::class.java,
            ).apply { isAccessible = true }
            setStatus.invoke(statusBar, 1, "strong_toast_action", bundle)
        } catch (t: Throwable) {
            HookLog.w("mode island", t)
        }
    }

    private fun islandParam(text: String, icon: String) = JSONObject()
        .put("left", JSONObject().put("iconParams", JSONObject()
            .put("iconResName", icon).put("iconType", 0).put("iconFormat", "png").put("category", "drawable")))
        .put("right", JSONObject().put("textParams", JSONObject()
            .put("text", text).put("textColor", -1).put("turnAnim", true)))

    private fun guideParam(text: String, icon: String) = JSONObject()
        .put("left", JSONObject().put("iconParams", JSONObject()
            .put("iconResName", icon).put("iconType", 0).put("iconFormat", "png")
            .put("type", "drawable").put("resPackageName", Protocol.PACKAGE)))
        .put("right", JSONObject().put("textParams", JSONObject()
            .put("text", text).put("textColor", -1).put("viewFlags", 0)))
}
