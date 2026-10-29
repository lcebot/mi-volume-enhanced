package io.github.lcebot.mivolumeenhanced.hook.core

import android.content.Context
import android.content.res.Resources
import android.view.View
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Resource lookups by name in the HyperOS volume plugin, which is loaded into SystemUI with its own
 * package name. Resources.getIdentifier is a string search; ids never change for a Resources
 * instance, so each answer (a miss included) is looked up once.
 */
object Res {
    const val PLUGIN = "miui.systemui.plugin"

    private val ids = WeakHashMap<Resources, HashMap<String, Int>>()

    fun id(context: Context, type: String, name: String, preferred: String = PLUGIN): Int {
        val resources = context.resources
        val own = context.packageName
        val key = "$type/$name@$preferred"
        synchronized(ids) {
            val map = ids.getOrPut(resources) { HashMap() }
            map[key]?.let { return it }
            var id = resources.getIdentifier(name, type, preferred)
            if (id == 0 && preferred != own) id = resources.getIdentifier(name, type, own)
            map[key] = id
            return id
        }
    }

    fun dimen(context: Context, name: String, fallbackDp: Float): Int {
        val id = id(context, "dimen", name)
        return if (id == 0) dp(context, fallbackDp) else context.resources.getDimensionPixelSize(id)
    }

    fun color(context: Context, name: String, fallback: Int): Int {
        val id = id(context, "color", name)
        return if (id == 0) fallback else context.getColor(id)
    }

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).roundToInt()
}

fun View.findByName(name: String): View? {
    val id = Res.id(context, "id", name)
    return if (id == 0) null else findViewById(id)
}

fun View.requireByName(name: String): View =
    findByName(name) ?: throw Resources.NotFoundException(name)

/**
 * Text shown inside a hooked process, where this module's string resources are not loaded. The
 * settings app ships English and Chinese, so these strings do too.
 */
fun Context.localized(en: String, zh: String): String =
    if (resources.configuration.locales[0]?.language == "zh") zh else en
