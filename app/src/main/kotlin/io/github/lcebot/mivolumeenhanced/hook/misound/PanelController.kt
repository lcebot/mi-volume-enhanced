package io.github.lcebot.mivolumeenhanced.hook.misound

import android.os.Handler
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import io.github.lcebot.mivolumeenhanced.hook.core.FieldRef
import io.github.lcebot.mivolumeenhanced.hook.core.fieldOfType
import io.github.lcebot.mivolumeenhanced.hook.core.method
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Typed access to MiSound's obfuscated per-app volume controller, com.miui.misound.playervolume.a.
 * Fields are found by their current names first and by type second, so a rename in a MiSound
 * update degrades instead of breaking outright.
 *
 * Field map of the current build: a status, d window manager, e audio manager, m floating ball
 * root, n floating ball button, o MediaVolumePageView, p ViewPager2, q page indicator, r indicator
 * dots, s media column, t active players, u columns, x handler.
 */
class PanelController(val instance: Any) {
    var status: Int
        get() = statusField(instance.javaClass)?.getInt(instance) ?: STATUS_IDLE
        set(value) {
            statusField(instance.javaClass)?.setInt(instance, value)
        }

    val pageView: ViewGroup?
        get() = PAGE_VIEW.get(instance) as? ViewGroup ?: byTypeName("MediaVolumePageView") as? ViewGroup

    val viewPager: View?
        get() = VIEW_PAGER.get(instance) as? View ?: byTypeName("ViewPager2") as? View

    val indicator: ViewGroup? get() = INDICATOR.get(instance) as? ViewGroup

    val floatingButton: View?
        get() = FLOATING_BUTTON.get(instance) as? View ?: byTypeName("FloatingActionButton") as? View

    val windowManager: WindowManager? get() = instance.fieldOfType(WindowManager::class.java)

    val handler: Handler? get() = HANDLER.get(instance) as? Handler ?: instance.fieldOfType(Handler::class.java)

    /** The media column; the key handler updates only this one. */
    val mediaColumn: Any? get() = MEDIA_COLUMN.get(instance)

    @Suppress("UNCHECKED_CAST")
    val players: List<Any?>? get() = (ACTIVE_PLAYERS.get(instance) ?: nthList(1)) as? List<Any?>

    @Suppress("UNCHECKED_CAST")
    val columns: MutableList<Any?>? get() = (COLUMNS.get(instance) ?: nthList(2)) as? MutableList<Any?>

    @Suppress("UNCHECKED_CAST")
    val dots: List<Any?>? get() = (DOTS.get(instance) ?: nthList(0)) as? List<Any?>

    fun call(name: String) {
        instance.javaClass.method(name, 0)?.invoke(instance)
    }

    private fun byTypeName(suffix: String): Any? =
        instance.javaClass.declaredFields.firstOrNull { it.type.name.endsWith(suffix) }
            ?.let { f -> runCatching { f.isAccessible = true; f.get(instance) }.getOrNull() }

    private fun nthList(index: Int): Any? = instance.javaClass.declaredFields
        .filter { !Modifier.isStatic(it.modifiers) && List::class.java.isAssignableFrom(it.type) }
        .getOrNull(index)?.let { f -> runCatching { f.isAccessible = true; f.get(instance) }.getOrNull() }

    companion object {
        const val STATUS_IDLE = 0
        const val STATUS_BALL = 2000
        const val STATUS_CLOSING = 301
        const val STATUS_EXPANDED = 5000

        private val STATUS = FieldRef("a")
        private val PAGE_VIEW = FieldRef("o")
        private val VIEW_PAGER = FieldRef("p")
        private val INDICATOR = FieldRef("q")
        private val DOTS = FieldRef("r")
        private val MEDIA_COLUMN = FieldRef("s")
        private val ACTIVE_PLAYERS = FieldRef("t")
        private val COLUMNS = FieldRef("u")
        private val FLOATING_BUTTON = FieldRef("n")
        private val HANDLER = FieldRef("x")

        private fun statusField(type: Class<*>): Field? = STATUS.resolve(type)?.takeIf { it.type == Int::class.javaPrimitiveType }
    }
}
