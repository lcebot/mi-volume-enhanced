package io.github.lcebot.mivolumeenhanced.hook.systemui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import io.github.lcebot.mivolumeenhanced.Protocol
import io.github.lcebot.mivolumeenhanced.hook.core.Diagnostics
import io.github.lcebot.mivolumeenhanced.hook.core.FieldRef
import io.github.lcebot.mivolumeenhanced.hook.core.HookLog
import io.github.lcebot.mivolumeenhanced.hook.core.HookOptions
import io.github.lcebot.mivolumeenhanced.hook.core.MethodRef
import io.github.lcebot.mivolumeenhanced.hook.core.Res
import io.github.lcebot.mivolumeenhanced.hook.core.findByName
import io.github.lcebot.mivolumeenhanced.hook.core.localized
import java.lang.ref.WeakReference
import java.lang.reflect.Method

/**
 * The per-app volume button above the volume slider. It appears only while an app is playing media,
 * and opens MiSound's per-app volume panel.
 *
 * It sits in the gap above the panel: while shown, the panel's top margin is one row smaller, so
 * the slider and the native buttons keep their position on screen. In landscape, where the plugin
 * centres the panel, the entry and the panel are centred together instead. It uses the same template and
 * RingerButtonHelper as the native buttons. Control Center's panel is left alone.
 */
object AppVolumeEntry {
    private const val TAG = "mve:app-volume-entry"
    /** The entry injected by AppVolumeBarHook; when present, that module's button is kept instead. */
    private const val OTHER_MODULE_TAG = "tag_app_volume_entry_root"
    private const val TICK_MS = 250L

    private val NEED_SHOW_DIALOG = FieldRef("mNeedShowDialog")
    private val IS_EXPANDED = MethodRef("isExpanded")
    private val SET_RINGER_MODE = MethodRef("setRingerMode", Boolean::class.java)
    private val UPDATE_STATE = MethodRef("updateState")
    private val ON_EXPANDED = MethodRef("onExpanded", Boolean::class.java, Boolean::class.java)
    private val CONTROLLER_DISMISS = MethodRef("dismissH", Int::class.java)
    private val DIALOG_DISMISS = MethodRef("dismissH", Boolean::class.java, Runnable::class.java)
    private val MOTION = FieldRef("mMotion")
    private val DISPLAY_METRICS = MethodRef("provideDisplayMetrics")

    @Volatile private var panelHeight: Method? = null
    @Volatile private var panelMarginTop: Method? = null

    private var entry = WeakReference<ViewGroup>(null)
    private var helper = WeakReference<Any>(null)
    private var dialogView = WeakReference<ViewGroup>(null)
    private var dndRow = WeakReference<View>(null)
    private var ringer = WeakReference<View>(null)
    private var container = WeakReference<View>(null)
    private var panelController = WeakReference<Any>(null)

    private var shown = false
    private var expanded = false
    private var panelVisible = false
    private var slideRinger = 0f
    private var slideDnd = 0f
    private var lastTick = 0L
    private var lastDecision = -1

    init {
        HookOptions.addListener { refreshVisibility() }
    }

    // ---------------------------------------------------------------- injection

    /** Called from MiuiRingerModeLayout.onFinishInflate, before the layout has a parent. */
    fun inject(ringer: Any?) {
        val host = ringer as? ViewGroup ?: return
        injectWhenAttached(host, 0)
    }

    private fun injectWhenAttached(host: ViewGroup, attempt: Int) {
        if (attempt > 8) return
        host.post {
            val dialog = host.parent as? ViewGroup
            if (dialog != null) injectInto(host, dialog) else injectWhenAttached(host, attempt + 1)
        }
    }

    private fun injectInto(host: ViewGroup, dialog: ViewGroup) {
        if (dialog.findViewWithTag<View>(TAG) != null) return
        if (dialog.findViewWithTag<View>(OTHER_MODULE_TAG) != null) {
            Diagnostics.missing("app_volume_entry", "AppVolumeBarHook provides the entry")
            return
        }
        if (NEED_SHOW_DIALOG.get(host) == false) return
        try {
            val context = dialog.context
            val template = Res.id(context, "layout", "miui_ringer_mode_layout")
            require(template != 0) { "miui_ringer_mode_layout missing" }
            val row = LayoutInflater.from(context).inflate(template, dialog, false) as ViewGroup
            row.tag = TAG
            row.findByName("timer_layout")?.visibility = View.GONE
            val icon = row.findByName("icon") as ImageView
            val blur = row.findByName("bg_blur") ?: error("bg_blur missing")
            val styler = host.javaClass.declaredClasses.first { it.simpleName == "RingerButtonHelper" }
                .getDeclaredConstructor(host.javaClass, View::class.java, Boolean::class.java, Boolean::class.java)
                .apply { isAccessible = true }.newInstance(host, row, false, false)
            helper = WeakReference(styler)
            style(styler, icon)
            blur.contentDescription = context.localized("Per-app volume", "分应用音量")
            blur.setOnClickListener { open(it.context) }
            row.visibility = View.GONE
            dialog.addView(row, 0, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, rowHeight(context)))

            entry = WeakReference(row)
            dialogView = WeakReference(dialog)
            dndRow = WeakReference(host.findByName("dnd_layout"))
            ringer = WeakReference(host)
            container = WeakReference(dialog.findByName("volume_dialog_container"))
            PlaybackWatcher.listen(context, ::refreshVisibility)
            Diagnostics.ok("app_volume_entry", "injected")
        } catch (t: Throwable) {
            Diagnostics.failed("app_volume_entry", t)
        }
    }

    /**
     * RingerButtonHelper.updateState returns early while neither the expanded state nor the on/off
     * state changed, and only that path applies the material and blur. A fresh button starts in the
     * same state as the native ones, so it is toggled on and off once to force the styling through.
     */
    private fun style(styler: Any, icon: ImageView?) {
        SET_RINGER_MODE.invokeQuietly(styler, true)
        UPDATE_STATE.invokeQuietly(styler)
        SET_RINGER_MODE.invokeQuietly(styler, false)
        UPDATE_STATE.invokeQuietly(styler)
        ON_EXPANDED.invokeQuietly(styler, false, true)
        icon?.apply {
            setImageDrawable(EqualizerIcon())
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            visibility = View.VISIBLE
        }
    }

    fun refreshStyle() {
        val row = entry.get() ?: return
        val styler = helper.get() ?: return
        style(styler, row.findByName("icon") as? ImageView)
    }

    /** MiuiVolumeDialogRes.getHeight and getMarginTop, both hooked, for [placeCollapsed]. */
    fun attach(getHeight: Method, getMarginTop: Method) {
        panelHeight = getHeight
        panelMarginTop = getMarginTop
    }

    // ---------------------------------------------------------------- panel lifecycle

    fun setPanelController(controller: Any?) {
        panelController = WeakReference(controller)
    }

    /** The panel is about to show: decide before the plugin applies the collapsed layout. */
    fun prepareShow(dialog: View?) {
        val group = dialog as? ViewGroup ?: dialogView.get() ?: return
        if (dialogView.get() !== group) adopt(group)
        if (NEED_SHOW_DIALOG.get(group) == false) {
            panelVisible = false
            setShown(group, visible = false, moveMargin = true)
            return
        }
        panelVisible = true
        expanded = runCatching { IS_EXPANDED.call(group) as? Boolean }.getOrNull() ?: expanded
        apply(group, moveMargin = true)
        // Also when nothing changed here: the margin may have been laid out for another rotation.
        placeCollapsed(group)
    }

    /** A different panel instance is showing (rebuilt, or Control Center's): move the entry there. */
    private fun adopt(dialog: ViewGroup) {
        (dialog.findByName("miui_volume_ringer_layout") as? ViewGroup)?.let { injectInto(it, dialog) }
    }

    /**
     * The plugin writes the expanded top margin, which has no entry offset in it, before this runs.
     * On collapse the entry shows again, and the collapsed margin is set with its offset.
     */
    fun onExpandedChanged(dialog: View, expanded: Boolean) {
        val group = dialog as? ViewGroup ?: return
        this.expanded = expanded
        apply(group, moveMargin = !expanded)
    }

    /** With an animation the entry slides out with the panel and is reset when the animation ends. */
    fun onDismissStarted(animated: Boolean) {
        if (!animated) onHideComplete()
    }

    fun onHideComplete() {
        dialogView.get()?.let { setShown(it, visible = false, moveMargin = true) }
        panelVisible = false
        resetAnimation()
    }

    fun refreshVisibility() {
        val group = dialogView.get() ?: return
        if (panelVisible) apply(group, moveMargin = true)
    }

    /**
     * Native animation frames call this. At most every [TICK_MS] it re-checks visibility, so the
     * entry still appears if a lifecycle hook was missed on some ROM build.
     */
    fun tick() {
        val group = dialogView.get() ?: return
        if (!group.isAttachedToWindow) return
        val now = SystemClock.uptimeMillis()
        if (now - lastTick < TICK_MS) return
        lastTick = now
        apply(group, moveMargin = true)
    }

    /**
     * What getMarginTop takes off the collapsed dialog's top margin while the entry is shown: one
     * row, or half a row where the panel is centred.
     */
    fun marginOffset(context: Context, dialog: Boolean, expanded: Boolean): Int {
        if (!shown || expanded || !dialog) return 0
        val row = rowHeight(context)
        // Where the plugin centres the panel (landscape, as getMarginTop decides it), the entry
        // and the panel are centred together. Elsewhere the panel keeps its place under the
        // entry, so the slider stays next to the volume keys.
        val centred = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return if (centred) row / 2 else row
    }

    /** The height the entry may take above the collapsed dialog, whether or not it shows now. */
    fun reservedHeight(context: Context, dialog: Boolean): Int =
        if (dialog && HookOptions.current.appVolumeEntry) rowHeight(context) else 0

    private fun apply(dialog: ViewGroup, moveMargin: Boolean) {
        val enabled = HookOptions.current.appVolumeEntry
        val playing = PlaybackWatcher.playing
        val visible = enabled && !expanded && playing
        val decision = (if (enabled) 1 else 0) or (if (expanded) 2 else 0) or (if (playing) 4 else 0)
        if (decision != lastDecision) {
            lastDecision = decision
            Diagnostics.hit("app_volume_entry_state", "option=$enabled expanded=$expanded playing=$playing visible=$visible")
        }
        setShown(dialog, visible, moveMargin)
    }

    private fun setShown(dialog: ViewGroup, visible: Boolean, moveMargin: Boolean) {
        val row = entry.get() ?: return
        if (dialogView.get() !== dialog) return
        val changed = shown != visible
        shown = visible
        val visibility = if (visible) View.VISIBLE else View.GONE
        if (row.visibility != visibility) row.visibility = visibility
        if (visible) syncAnimation() else resetAnimation()
        if (changed && moveMargin) placeCollapsed(dialog)
    }

    /**
     * Sets the collapsed dialog's top margin as the plugin computes it, through the hooked getHeight
     * and getMarginTop, so it accounts for this entry and the ANC row alike. The margin is set, not
     * moved by a row: a relative move drifts as soon as the plugin's own layout and this entry's
     * state change in another order than expected, as they do around a rotation, and the panel then
     * stays off centre until the plugin lays it out again.
     */
    private fun placeCollapsed(dialog: ViewGroup) {
        if (expanded) return
        val height = panelHeight ?: return
        val marginTop = panelMarginTop ?: return
        try {
            val metrics = DISPLAY_METRICS.call(MOTION.get(dialog)) as? DisplayMetrics ?: return
            val context = dialog.context
            val panel = height.invoke(null, context, true, false) as Int
            val top = marginTop.invoke(null, context, true, false, metrics.heightPixels, panel) as Int
            val lp = dialog.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            if (lp.topMargin != top) {
                lp.topMargin = top
                dialog.layoutParams = lp
            }
        } catch (t: Throwable) {
            HookLog.w("collapsed panel margin", t)
        }
    }

    // ---------------------------------------------------------------- animation

    /** Follows the do-not-disturb row's scale and fade during the native show/hide animation. */
    fun syncAnimation() {
        val row = entry.get()?.takeIf { it.visibility == View.VISIBLE } ?: return
        val dnd = dndRow.get()
        val scale = dnd?.scaleX ?: 1f
        row.scaleX = scale
        row.scaleY = scale
        if (dnd != null) row.alpha = dnd.alpha
        container.get()?.let { c ->
            val rowCenter = row.top + row.height / 2f
            val containerCenter = c.top + c.height / 2f
            row.translationY = (scale - 1f) * (rowCenter - containerCenter)
        }
    }

    private fun resetAnimation() {
        entry.get()?.apply {
            scaleX = 1f
            scaleY = 1f
            alpha = 1f
            translationY = 0f
        }
    }

    /**
     * Overscroll and drag stretch move the silent and do-not-disturb rows by an arithmetic
     * progression. The entry sits one step above silent and the ANC row one step below
     * do-not-disturb, so each moves one more step.
     */
    fun onSlideRinger(y: Float) {
        slideRinger = y
        applySlide()
    }

    fun onSlideDnd(y: Float) {
        slideDnd = y
        applySlide()
    }

    fun onSlideReset() {
        slideRinger = 0f
        slideDnd = 0f
        entry.get()?.translationY = 0f
        AncRow.of(ringer.get())?.setSlideTranslation(0f)
    }

    private fun applySlide() {
        entry.get()?.takeIf { it.visibility == View.VISIBLE }?.translationY = 2f * slideRinger - slideDnd
        AncRow.of(ringer.get())?.setSlideTranslation(2f * slideDnd - slideRinger)
    }

    // ---------------------------------------------------------------- click

    private fun open(context: Context) {
        Diagnostics.hit("app_volume_entry_click")
        val intent = Intent(Protocol.ACTION_EXPAND_APP_VOLUME)
            .setPackage(Protocol.MISOUND)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        // Measured before the dismiss animation moves the panel away.
        barBounds()?.let { (bounds, rotation) ->
            intent.putExtra(Protocol.EXTRA_BAR_BOUNDS, bounds).putExtra(Protocol.EXTRA_BAR_ROTATION, rotation)
        }
        ringer.get()?.javaClass?.classLoader?.let { loader ->
            SliderStyle.read(context, loader)?.let { intent.putExtra(Protocol.EXTRA_SLIDER_STYLE, it) }
        }
        try {
            context.sendBroadcast(intent)
        } catch (t: RuntimeException) {
            HookLog.e("open app volume panel", t)
        }
        dismissVolumeDialog()
    }

    /**
     * The on-screen bounds of the volume bar: the dialog content holding the slider, without the
     * entry above it or the buttons below. The plugin places it from per-device resources so that
     * it sits next to the physical volume keys, which is where the per-app panel belongs too.
     */
    private fun barBounds(): Pair<IntArray, Int>? {
        val dialog = dialogView.get() ?: return null
        val bar = dialog.findByName("volume_dialog_content") ?: container.get() ?: return null
        if (!bar.isAttachedToWindow || bar.width == 0 || bar.height == 0) return null
        val rotation = bar.display?.rotation ?: return null
        val xy = IntArray(2)
        bar.getLocationOnScreen(xy)
        return intArrayOf(xy[0], xy[1], xy[0] + bar.width, xy[1] + bar.height) to rotation
    }

    private fun dismissVolumeDialog() {
        try {
            if (CONTROLLER_DISMISS.invoke(panelController.get(), 8)) return
        } catch (t: Throwable) {
            HookLog.w("dismiss through the panel controller", t)
        }
        runCatching { DIALOG_DISMISS.invoke(dialogView.get(), true, null) }
            .onFailure { HookLog.w("dismiss the volume dialog", it) }
    }

    /** One collapsed button plus the gap the native buttons keep between them. */
    private fun rowHeight(context: Context) =
        Res.dimen(context, "o3_miui_ringer_btn_height", 40f) + Res.dimen(context, "miui_volume_footer_margin_top", 10f)
}
