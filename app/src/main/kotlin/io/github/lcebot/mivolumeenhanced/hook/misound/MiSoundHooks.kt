package io.github.lcebot.mivolumeenhanced.hook.misound

import android.animation.ObjectAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.SeekBar
import io.github.lcebot.mivolumeenhanced.Protocol
import io.github.lcebot.mivolumeenhanced.hook.core.Diagnostics
import io.github.lcebot.mivolumeenhanced.hook.core.FieldRef
import io.github.lcebot.mivolumeenhanced.hook.core.HookLog
import io.github.lcebot.mivolumeenhanced.hook.core.HookOptions
import io.github.lcebot.mivolumeenhanced.hook.core.ProcessContext
import io.github.lcebot.mivolumeenhanced.hook.core.after
import io.github.lcebot.mivolumeenhanced.hook.core.around
import io.github.lcebot.mivolumeenhanced.hook.core.before
import io.github.lcebot.mivolumeenhanced.hook.core.find
import io.github.lcebot.mivolumeenhanced.hook.core.hierarchy
import io.github.lcebot.mivolumeenhanced.hook.core.method
import io.github.lcebot.mivolumeenhanced.hook.core.overloadId
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/**
 * Hooks in MiSound (com.miui.misound), which owns the per-app volume panel.
 *
 * SystemUI's entry button asks for the panel by broadcast, so MiSound's own floating ball is not
 * needed while the entry is enabled. The panel is restyled by [AppVolumeCard].
 */
object MiSoundHooks {
    private const val CONTROLLER = "com.miui.misound.playervolume.a"
    private const val SERVICE = "com.miui.misound.playervolume.VolumeUIService"
    /** WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY, hidden in the SDK; MiSound's windows use it. */
    private const val TYPE_VOLUME_OVERLAY = 2020

    private val OUTER = FieldRef("this\$0")
    private val CLOSING_STATES = setOf(300, PanelController.STATUS_CLOSING)
    private val INSTANCE = FieldRef("E")

    private var controller = WeakReference<Any>(null)
    private var ball = WeakReference<View>(null)
    private var ballHiddenByUs = false
    private var pagerCallback = WeakReference<Any>(null)
    private var pagerAttach = WeakReference<View.OnAttachStateChangeListener>(null)
    private var unregisterPageCallback: Method? = null

    fun install(xposed: XposedInterface, loader: ClassLoader) {
        hookWindows(xposed)
        val type = controllerClass(loader)
        if (type == null) {
            Diagnostics.missing("misound_panel", "per-app volume controller not found in this MiSound build")
            return
        }
        try {
            hookController(xposed, type, loader)
            Diagnostics.ok("misound_panel", "hooked ${type.name}")
        } catch (t: Throwable) {
            Diagnostics.failed("misound_panel", t)
        }
        ProcessContext.whenReady { context ->
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    AppVolumeCard.anchorTo(
                        intent.getIntArrayExtra(Protocol.EXTRA_BAR_BOUNDS),
                        intent.getIntExtra(Protocol.EXTRA_BAR_ROTATION, -1),
                    )
                    SliderLook.update(intent.getBundleExtra(Protocol.EXTRA_SLIDER_STYLE))
                    expand(c)
                }
            }, IntentFilter(Protocol.ACTION_EXPAND_APP_VOLUME), Context.RECEIVER_EXPORTED)
        }
        HookOptions.addListener { syncBall() }
    }

    // ---------------------------------------------------------------- windows

    /**
     * MiSound adds its floating ball and its panel as overlay windows. The ball is not added at all
     * while SystemUI's entry replaces it; if the entry is switched off, MiSound adds the ball again
     * the next time it shows it. The panel window loses the full-screen blur and dim.
     */
    private fun hookWindows(xposed: XposedInterface) {
        val impl = Class.forName("android.view.WindowManagerImpl")
        val overloads = impl.declaredMethods.filter {
            it.name == "addView" && it.parameterCount >= 2 &&
                View::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                ViewGroup.LayoutParams::class.java.isAssignableFrom(it.parameterTypes[1])
        }
        for (m in overloads) {
            xposed.around(m, overloadId("misound-add-view", m)) { chain ->
                val view = chain.getArg(0) as? View
                val lp = chain.getArg(1) as? WindowManager.LayoutParams
                if (view == null || lp == null ||
                    (lp.type != TYPE_VOLUME_OVERLAY && lp.type != WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                ) return@around chain.proceed()
                if (isFloatingBall(view, lp)) {
                    ball = WeakReference(findFab(view) ?: view)
                    if (HookOptions.current.appVolumeEntry) {
                        Diagnostics.hit("misound_ball", "floating ball not added, the entry replaces it")
                        return@around null
                    }
                } else if (view.javaClass.name.contains("MediaVolumePageView")) {
                    lp.flags = lp.flags and (WindowManager.LayoutParams.FLAG_BLUR_BEHIND or WindowManager.LayoutParams.FLAG_DIM_BEHIND).inv()
                    lp.dimAmount = 0f
                    lp.blurBehindRadius = 0
                }
                chain.proceed()
            }
        }
    }

    private fun isFloatingBall(view: View, lp: WindowManager.LayoutParams): Boolean {
        if (lp.width != ViewGroup.LayoutParams.WRAP_CONTENT || lp.height != ViewGroup.LayoutParams.WRAP_CONTENT) return false
        val context = view.context ?: return false
        if (context.packageName != Protocol.MISOUND) return false
        val close = context.resources.getIdentifier("miui_volume_close_button", "id", Protocol.MISOUND)
        return (close != 0 && view.findViewById<View>(close) != null) || findFab(view) != null
    }

    private fun findFab(view: View): View? {
        if (view.javaClass.name.contains("FloatingActionButton")) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findFab(view.getChildAt(i))?.let { return it }
        return null
    }

    /**
     * The window interception above can miss on builds that add the ball another way, so the ball
     * button is also hidden by visibility, and shown again once the entry is switched off.
     */
    private fun syncBall(from: PanelController? = null) {
        val button = ball.get() ?: from?.floatingButton?.also { ball = WeakReference(it) } ?: return
        if (HookOptions.current.appVolumeEntry) {
            if (button.visibility != View.GONE) {
                button.visibility = View.GONE
                ballHiddenByUs = true
            }
        } else if (ballHiddenByUs) {
            button.visibility = View.VISIBLE
            ballHiddenByUs = false
        }
    }

    // ---------------------------------------------------------------- opening the panel

    /**
     * Opens the panel without the floating ball, the way the ball's own click does: rebuild the
     * columns, rebuild the pager when the columns changed, then show.
     *
     * u() replaces the column objects whenever an app is playing. The pager must then be rebuilt
     * with m(): otherwise the panel keeps showing the previous columns while the volume keys update
     * the new, never inflated media column, so the volume changes and the panel does not move.
     */
    private fun expand(context: Context, attempt: Int = 0) {
        val instance = controller.get() ?: findInstance(context)?.also { controller = WeakReference(it) }
        if (instance == null) {
            tapBall()
            return
        }
        val panel = PanelController(instance)
        if (panel.status == PanelController.STATUS_EXPANDED) return
        // While the ball or the panel is closing, MiSound has a reset to idle queued (about 300ms).
        // Opening now would be overwritten by it, leaving an open panel the controller thinks is
        // closed, which then never dismisses.
        if (panel.status in CLOSING_STATES) {
            if (attempt < 3) ProcessContext.main.postDelayed({ expand(context, attempt + 1) }, 350L)
            return
        }
        try {
            panel.status = PanelController.STATUS_IDLE
            val mediaBefore = panel.mediaColumn
            panel.call("u")
            if (panel.players.isNullOrEmpty()) panel.columns?.clear()
            var rebuilt = panel.mediaColumn !== mediaBefore
            if (panel.columns.isNullOrEmpty()) {
                panel.call("f")
                rebuilt = true
            }
            val pager = panel.viewPager
            val adapter = pager?.javaClass?.method("getAdapter", 0)?.invoke(pager)
            if (adapter == null || rebuilt) panel.call("m")
            pager?.visibility = View.VISIBLE
            AppVolumeCard.layout(panel)
            panel.status = PanelController.STATUS_IDLE
            panel.call("y")
            Diagnostics.hit("misound_expand", "opened from the volume panel entry, pager rebuilt=$rebuilt")
        } catch (t: Throwable) {
            HookLog.e("open the per-app volume panel", t)
            tapBall()
        }
    }

    private fun tapBall() {
        val button = ball.get() ?: return HookLog.w("no way to open the per-app volume panel")
        button.performClick()
    }

    private fun findInstance(context: Context): Any? {
        val type = controllerClass(context.classLoader) ?: return null
        INSTANCE.resolve(type)?.let { f -> runCatching { f.get(null) }.getOrNull()?.let { return it } }
        return runCatching {
            type.getDeclaredMethod("j", Context::class.java).apply { isAccessible = true }.invoke(null, context)
        }.getOrNull()
    }

    private fun controllerClass(loader: ClassLoader): Class<*>? {
        loader.find(CONTROLLER)?.let { return it }
        val service = loader.find(SERVICE) ?: return null
        return service.declaredFields.map { it.type }.firstOrNull { t ->
            t.name.startsWith("com.miui.misound.playervolume.") &&
                t.declaredFields.any { it.type.name.endsWith("MediaVolumePageView") }
        }
    }

    // ---------------------------------------------------------------- controller

    private fun hookController(xposed: XposedInterface, type: Class<*>, loader: ClassLoader) {
        type.declaredConstructors.forEach { c ->
            xposed.after(c, overloadId("misound-controller-ctor", c)) { chain, _ -> controller = WeakReference(chain.thisObject) }
        }
        type.method("n", 0)?.let { m ->
            xposed.after(m, "misound-controller-n") { chain, _ ->
                val panel = remember(chain.thisObject)
                syncBall(panel)
                AppVolumeCard.layout(panel)
            }
        }
        type.method("y", 0)?.let { m ->
            xposed.after(m, "misound-controller-y") { chain, _ ->
                val panel = remember(chain.thisObject)
                syncBall(panel)
                AppVolumeCard.layout(panel)
                AppVolumeCard.slide(panel, enter = true)
            }
        }
        // Without active players u() returns before clearing, so the previous columns would linger.
        type.method("u", 0)?.let { m ->
            xposed.after(m, "misound-controller-u") { chain, _ ->
                val panel = remember(chain.thisObject)
                syncBall(panel)
                if (panel.players.isNullOrEmpty()) panel.columns?.clear()
            }
        }
        type.method("g", 0)?.let { hookDismiss(xposed, it) }
        type.method("m", 0)?.let { hookPagerRegistrations(xposed, type, loader, it) }
        hookAdapter(xposed, type, loader)
        hookPageSelected(xposed, type, loader)
        hookColumns(xposed, type, loader)
    }

    private fun remember(instance: Any): PanelController {
        controller = WeakReference(instance)
        return PanelController(instance)
    }

    /** g() plays a scale-out and restarts the timeout; the card slides out towards its edge instead. */
    private fun hookDismiss(xposed: XposedInterface, dismiss: Method) {
        xposed.around(dismiss, "misound-controller-dismiss") { chain ->
            val panel = PanelController(chain.thisObject)
            if (panel.status != PanelController.STATUS_EXPANDED) return@around chain.proceed()
            panel.status = PanelController.STATUS_CLOSING
            AppVolumeCard.slide(panel, enter = false)
            (panel.handler ?: ProcessContext.main).postDelayed({ finishDismiss(panel) }, 200L)
            null
        }
    }

    private fun finishDismiss(panel: PanelController) {
        runCatching { panel.call("p") }.onFailure { HookLog.w("panel cleanup", it) }
        runCatching {
            val page = panel.pageView
            if (page != null && page.isAttachedToWindow) {
                (panel.windowManager ?: page.context.getSystemService(WindowManager::class.java))?.removeViewImmediate(page)
            }
        }.onFailure { HookLog.w("remove the panel window", it) }
        panel.status = PanelController.STATUS_IDLE
    }

    /**
     * m() registers a new page callback and attach listener on the same ViewPager2 every time the
     * panel opens and never unregisters them. After N opens each page change rebuilt the indicator N
     * times. The previous pair is removed before m() adds the next one.
     */
    private fun hookPagerRegistrations(xposed: XposedInterface, type: Class<*>, loader: ClassLoader, initPager: Method) {
        pageCallbackClass(type, loader)?.declaredConstructors?.forEach { c ->
            xposed.after(c, overloadId("misound-page-callback-ctor", c)) { chain, _ -> pagerCallback = WeakReference(chain.thisObject) }
        }
        val attachClass = type.declaredClasses.firstOrNull { View.OnAttachStateChangeListener::class.java.isAssignableFrom(it) }
            ?: loader.find(type.name + "\$d")
        attachClass?.declaredConstructors?.forEach { c ->
            xposed.after(c, overloadId("misound-pager-attach-ctor", c)) { chain, _ ->
                pagerAttach = WeakReference(chain.thisObject as? View.OnAttachStateChangeListener)
            }
        }
        xposed.before(initPager, "misound-controller-m") { chain ->
            val pager = PanelController(chain.thisObject).viewPager ?: return@before
            pagerCallback.get()?.let { callback ->
                pagerCallback = WeakReference(null)
                runCatching { unregisterMethod(pager, callback)?.invoke(pager, callback) }
                    .onFailure { HookLog.w("unregister the previous page callback", it) }
            }
            pagerAttach.get()?.let { listener ->
                pagerAttach = WeakReference(null)
                pager.removeOnAttachStateChangeListener(listener)
            }
        }
    }

    private fun unregisterMethod(pager: View, callback: Any): Method? {
        unregisterPageCallback?.takeIf { it.declaringClass.isInstance(pager) && it.parameterTypes[0].isInstance(callback) }?.let { return it }
        return pager.javaClass.methods.firstOrNull {
            it.name == "unregisterOnPageChangeCallback" && it.parameterCount == 1 && it.parameterTypes[0].isInstance(callback)
        }.also { unregisterPageCallback = it }
    }

    private fun pageCallbackClass(type: Class<*>, loader: ClassLoader): Class<*>? =
        type.declaredClasses.firstOrNull { c -> c.hierarchy().any { it.name.endsWith("OnPageChangeCallback") } && c.superclass != Any::class.java }
            ?: loader.find(type.name + "\$e")

    private fun hookAdapter(xposed: XposedInterface, type: Class<*>, loader: ClassLoader) {
        val adapter = type.declaredClasses.firstOrNull { c -> c.hierarchy().drop(1).any { it.name.endsWith("Adapter") } }
            ?: loader.find(type.name + "\$i") ?: return HookLog.w("MiSound pager adapter not found")
        val pages = FieldRef("e")
        adapter.declaredConstructors.forEach { c ->
            xposed.after(c, overloadId("misound-adapter-ctor", c)) { chain, _ ->
                (pages.get(chain.thisObject) as? List<*>)?.forEach { (it as? View)?.let(AppVolumeCard::sizeSliders) }
            }
        }
        // The native page closes the panel on any tap, including taps on the card's empty space.
        adapter.method("onCreateViewHolder", 2)?.let { m ->
            xposed.after(m, "misound-adapter-create") { _, holder ->
                val item = ITEM_VIEW.get(holder) as? View ?: return@after
                item.setOnClickListener(null)
                item.isClickable = false
                (item as? ViewGroup)?.clipChildren = false
            }
        }
        // A page view that is still attached to a recycled holder makes binding throw "child already
        // has a parent" and stops paging.
        adapter.method("onBindViewHolder", 2)?.let { m ->
            xposed.around(m, "misound-adapter-bind") { chain ->
                val position = chain.getArg(1) as? Int
                val page = position?.let { (pages.get(chain.thisObject) as? List<*>)?.getOrNull(it) } as? View
                (page?.parent as? ViewGroup)?.removeView(page)
                val result = chain.proceed()
                (ITEM_VIEW.get(chain.getArg(0)) as? View)?.let(AppVolumeCard::sizeSliders)
                result
            }
        }
    }

    private fun hookPageSelected(xposed: XposedInterface, type: Class<*>, loader: ClassLoader) {
        val callback = pageCallbackClass(type, loader) ?: return
        val selected = callback.method("onPageSelected", 1) ?: return
        xposed.after(selected, "misound-page-selected") { chain, _ ->
            val index = chain.getArg(0) as? Int ?: return@after
            val instance = OUTER.get(chain.thisObject) ?: controller.get() ?: return@after
            val panel = PanelController(instance)
            panel.viewPager?.let(AppVolumeCard::sizeSliders)
            panel.indicator?.let { ind -> for (i in 0 until ind.childCount) AppVolumeCard.styleDot(ind.getChildAt(i), i == index) }
        }
    }

    /**
     * Columns are inner classes holding a SeekBar. a() inflates a column's views; f(int) moves the
     * media column's slider and is called by the volume keys. f(int) throws if it runs before a()
     * bound the slider, which would interrupt the key handling after the volume already changed.
     *
     * f(int) also jumps the slider to the new level. While the panel is on screen the jump is turned
     * into the animation SystemUI's own volume bar plays for a key press (see [animateKeyChange]).
     */
    private fun hookColumns(xposed: XposedInterface, type: Class<*>, loader: ClassLoader) {
        val columns = type.declaredClasses.filter { c ->
            !Modifier.isAbstract(c.modifiers) && c.hierarchy().any { h -> h.declaredFields.any { SeekBar::class.java.isAssignableFrom(it.type) } }
        }.ifEmpty { listOfNotNull(loader.find(type.name + "\$j"), loader.find(type.name + "\$h")) }
        val guarded = HashSet<Method>()
        for (column in columns) {
            column.method("a", 0)?.takeIf { !Modifier.isAbstract(it.modifiers) }?.let { init ->
                xposed.after(init, "misound-column-init:${column.name}") { chain, _ ->
                    val seekBar = seekBarOf(chain.thisObject) ?: return@after
                    AppVolumeCard.sizeColumn(seekBar, rootOf(chain.thisObject))
                }
            }
            for (owner in column.hierarchy()) {
                val update = owner.method("f", 1) ?: continue
                if (!guarded.add(update)) continue
                xposed.around(update, overloadId("misound-column-update", update)) { chain ->
                    val seekBar = seekBarOf(chain.thisObject) as? SeekBar
                    if (seekBar == null) {
                        Diagnostics.hit("misound_column_guard", "slider update skipped before the slider was bound")
                        return@around null
                    }
                    val from = seekBar.progress
                    val result = chain.proceed()
                    animateKeyChange(seekBar, from)
                    result
                }
            }
        }
    }

    /**
     * The same animation as SystemUI's VolumePanelViewController plays when a volume key moves its
     * slider: an ObjectAnimator on "progress" over 200ms with a DecelerateInterpolator, retargeted
     * from wherever the running one is. Off screen the slider just takes the new value. MiSound's
     * slider listener only acts on changes from the user, so the animated steps change nothing but
     * the drawing.
     */
    private fun animateKeyChange(seekBar: SeekBar, from: Int) {
        val to = seekBar.progress
        val running = keyAnimators[seekBar]
        if (to == from || !seekBar.isShown) {
            running?.cancel()
            return
        }
        if (running != null && running.isRunning && keyTargets[seekBar] == to) {
            // Already heading there: keep drawing the animation, not the jump f(int) just made.
            seekBar.progress = from
            return
        }
        running?.cancel()
        keyTargets[seekBar] = to
        seekBar.progress = from
        val animator = running ?: ObjectAnimator().apply {
            target = seekBar
            setPropertyName("progress")
            interpolator = DecelerateInterpolator()
            duration = KEY_ANIMATION_MS
            keyAnimators[seekBar] = this
        }
        animator.setIntValues(from, to)
        animator.start()
    }

    private const val KEY_ANIMATION_MS = 200L
    private val keyAnimators = WeakHashMap<SeekBar, ObjectAnimator>()
    private val keyTargets = WeakHashMap<SeekBar, Int>()

    private fun seekBarOf(column: Any): View? = fieldValue(column) { SeekBar::class.java.isAssignableFrom(it) }

    private fun rootOf(column: Any): View? = fieldValue(column) {
        View::class.java.isAssignableFrom(it) && !SeekBar::class.java.isAssignableFrom(it) && !ImageView::class.java.isAssignableFrom(it)
    }

    private fun fieldValue(target: Any, accept: (Class<*>) -> Boolean): View? {
        for (type in target.javaClass.hierarchy()) {
            for (field in type.declaredFields) {
                if (!accept(field.type)) continue
                field.isAccessible = true
                return field.get(target) as? View
            }
        }
        return null
    }

    private val ITEM_VIEW = FieldRef("itemView")
}
