package io.github.lcebot.mivolumeenhanced.hook.systemui

import android.content.Context
import android.view.View
import io.github.lcebot.mivolumeenhanced.hook.core.Diagnostics
import io.github.lcebot.mivolumeenhanced.hook.core.FieldRef
import io.github.lcebot.mivolumeenhanced.hook.core.MethodRef
import io.github.lcebot.mivolumeenhanced.hook.core.ProcessContext
import io.github.lcebot.mivolumeenhanced.hook.core.after
import io.github.lcebot.mivolumeenhanced.hook.core.around
import io.github.lcebot.mivolumeenhanced.hook.core.before
import io.github.lcebot.mivolumeenhanced.hook.core.boolArg
import io.github.lcebot.mivolumeenhanced.hook.core.find
import io.github.lcebot.mivolumeenhanced.hook.core.loaderId
import io.github.lcebot.mivolumeenhanced.hook.core.overloadId
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

/** Every hook on the HyperOS volume plugin (classes under com.android.systemui.miui.volume). */
object VolumePanelHooks {
    private const val PKG = "com.android.systemui.miui.volume."

    private val ANIMATOR_EXPANDED = FieldRef("mExpanded")
    private val ANIMATOR_LISTENER = FieldRef("listener")
    private val ANIMATOR_RINGER_LAYOUT = FieldRef("mRingerModeLayout")
    private val MOTION_RINGER_LAYOUT = FieldRef("mRingerModeLayout")
    private val SLIDE_LISTENER = FieldRef("mAnimListener")
    private val GET_DELAY_X = MethodRef("getDelayX")
    private val SET_DELAY_X = MethodRef("setDelayX", Long::class.java)

    @Volatile private var listenerHooked = false
    @Volatile private var slideListenerHooked = false

    fun install(xposed: XposedInterface, loader: ClassLoader) {
        val ringerLayout = loader.find(PKG + "MiuiRingerModeLayout") ?: error("MiuiRingerModeLayout missing")
        hookRingerLayout(xposed, ringerLayout)
        // The rest loads more plugin classes; deferring it lets the current plugin load return first.
        ProcessContext.main.post {
            section("panel_height") { hookPanelHeight(xposed, loader) }
            section("panel_motion") { hookMotion(xposed, loader) }
            section("show_hide") { hookShowHideAnimator(xposed, loader) }
            section("expand_animation") { hookExpandAnimator(xposed, loader) }
            section("dialog") { hookDialogView(xposed, loader) }
            section("slide") { hookSlideAnim(xposed, loader) }
        }
    }

    private inline fun section(id: String, block: () -> Unit) {
        try {
            block()
            Diagnostics.ok(id, "hooked")
        } catch (t: Throwable) {
            Diagnostics.failed(id, t)
        }
    }

    private fun hookRingerLayout(xposed: XposedInterface, type: Class<*>) {
        val loader = type.classLoader
        xposed.after(type.getDeclaredMethod("onFinishInflate"), loaderId("ringer-inflate", loader)) { chain, _ ->
            AncRow.inject(chain.thisObject)
            AppVolumeEntry.inject(chain.thisObject)
        }
        xposed.after(
            type.getDeclaredMethod("updateExpandedH", Boolean::class.java, Boolean::class.java),
            loaderId("ringer-expanded", loader),
        ) { chain, _ -> AncRow.of(chain.thisObject)?.onExpanded(chain.boolArg(0), chain.boolArg(1)) }
        for (name in listOf("updateResources", "onMaterialModeChanged")) {
            runCatching { type.getDeclaredMethod(name) }.getOrNull()?.let { m ->
                xposed.after(m, loaderId("ringer-$name", loader)) { chain, _ ->
                    AncRow.of(chain.thisObject)?.refreshStyle()
                    AppVolumeEntry.refreshStyle()
                }
            }
        }
        runCatching { type.getDeclaredMethod("prepareCollapsedBlurForShowAnimation") }.getOrNull()?.let { m ->
            xposed.after(m, loaderId("ringer-collapsed-blur", loader)) { chain, _ ->
                AncRow.of(chain.thisObject)?.onExpanded(false, true)
            }
        }
    }

    /**
     * getHeight(context, needShowDialog, expanded) is the panel height the plugin uses for the
     * expanded background and for centring either panel. The ANC row's height is added for each
     * state in which the row is in the panel, and only for the panel kind the call is about. getMarginTop moves the collapsed dialog up one row while
     * the app volume entry is shown above the slider.
     */
    private fun hookPanelHeight(xposed: XposedInterface, loader: ClassLoader) {
        val res = loader.find(PKG + "MiuiVolumeDialogRes") ?: error("MiuiVolumeDialogRes missing")
        val getHeight = res.getDeclaredMethod("getHeight", Context::class.java, Boolean::class.java, Boolean::class.java)
        Planner.attach(getHeight)
        xposed.around(getHeight, loaderId("res-height", loader)) { chain ->
            val result = chain.proceed()
            if (result !is Int || Planner.isMeasuringBase()) return@around result
            val row = AncRow.forPanel(chain.boolArg(1))
            val extra = (if (chain.boolArg(2)) row?.expandedExtra() else row?.collapsedExtra()) ?: 0
            if (extra > 0) Diagnostics.hit("panel_height_extra", "+$extra px")
            result + extra
        }
        val marginTop = res.getDeclaredMethod(
            "getMarginTop", Context::class.java, Boolean::class.java, Boolean::class.java, Int::class.java, Int::class.java,
        )
        AppVolumeEntry.attach(getHeight, marginTop)
        xposed.around(marginTop, loaderId("res-margin-top", loader)) { chain ->
            val result = chain.proceed()
            if (result !is Int) return@around result
            val offset = AppVolumeEntry.marginOffset(chain.getArg(0) as Context, chain.boolArg(1), chain.boolArg(2))
            // A panel too tall to centre keeps its top on screen rather than the entry's offset.
            if (offset > 0) (result - offset).coerceAtLeast(minOf(result, 0)) else result
        }
        deoptimizeCallers(xposed, loader, res)
    }

    /**
     * getHeight is a single resource lookup, and ART inlines methods that small into their callers
     * when it compiles the plugin. An inlined copy never reaches the hook: the background keeps the
     * native height and the stacked row sits below it. Deoptimizing the callers makes them call the
     * hooked method again. The callers are the panel's layout paths, which run a few times per show,
     * so running them interpreted costs nothing noticeable.
     */
    private fun deoptimizeCallers(xposed: XposedInterface, loader: ClassLoader, res: Class<*>) {
        val callers = mapOf(
            "MiuiVolumeDialogMotion" to setOf("getCollapsedStates", "getExpandedStates", "updateExpandBgSize", "updateExpandBgState"),
            "MiuiVolumeDialogView" to setOf("updateDialogViewLP"),
            "DndPopupWindow" to null,
        )
        var count = 0
        var failed = 0
        val targets = res.declaredMethods.asSequence() + callers.asSequence().flatMap { (name, methods) ->
            val type = loader.find(PKG + name) ?: return@flatMap emptySequence()
            type.declaredMethods.asSequence().filter { methods == null || it.name in methods }
        }
        for (m in targets) {
            if (java.lang.reflect.Modifier.isAbstract(m.modifiers)) continue
            if (runCatching { xposed.deoptimize(m) }.getOrDefault(false)) count++ else failed++
        }
        Diagnostics.ok("panel_height_deopt", "$count deoptimized, $failed not")
    }

    /**
     * updateStateToExpand applies the target layout and then registers the pre-draw pass that
     * measures where the expand animation ends. The row's visibility for the target state is set
     * before it runs, so that measurement sees the final panel.
     */
    private fun hookMotion(xposed: XposedInterface, loader: ClassLoader) {
        val motion = loader.find(PKG + "MiuiVolumeDialogMotion") ?: error("MiuiVolumeDialogMotion missing")
        xposed.before(motion.getDeclaredMethod("updateStateToExpand", Boolean::class.java), loaderId("motion-expand", loader)) { chain ->
            AncRow.of(MOTION_RINGER_LAYOUT.get(chain.thisObject))?.prepare(chain.boolArg(0))
        }
        runCatching { motion.getDeclaredMethod("updateStates", Boolean::class.java) }.getOrNull()?.let { m ->
            xposed.after(m, loaderId("motion-states", loader)) { chain, _ ->
                AncRow.of(MOTION_RINGER_LAYOUT.get(chain.thisObject))?.reapply()
            }
        }
    }

    private fun hookShowHideAnimator(xposed: XposedInterface, loader: ClassLoader) {
        val animator = loader.find(PKG + "VolumeShowHideAnimator") ?: error("VolumeShowHideAnimator missing")
        val initView = animator.getDeclaredMethod("initView", View::class.java, View::class.java, View::class.java)
        xposed.after(initView, loaderId("show-hide-init", loader)) { chain, _ ->
            val volumeView = chain.getArg(0) as View
            joinShowHide(chain.thisObject, volumeView, retry = true)
            hookAnimatorListener(xposed, chain.thisObject, loader)
        }
        xposed.after(animator.getDeclaredMethod("setViewX"), loaderId("show-hide-x", loader)) { _, _ ->
            AppVolumeEntry.syncAnimation()
            AppVolumeEntry.tick()
        }
        xposed.after(animator.getDeclaredMethod("onAnimComplete"), loaderId("show-hide-complete", loader)) { chain, _ ->
            if (ANIMATOR_EXPANDED.get(chain.thisObject) == false) AppVolumeEntry.onHideComplete() else AppVolumeEntry.syncAnimation()
        }
        xposed.after(animator.getDeclaredMethod("cancel"), loaderId("show-hide-cancel", loader)) { chain, _ ->
            if (ANIMATOR_EXPANDED.get(chain.thisObject) == false) AppVolumeEntry.onHideComplete()
        }
        // The native show animation staggers its two rows; the third row gets the same delay step.
        loader.find(PKG + "VolumeShowHideAnimatorKt")?.let { kt ->
            val args = kt.getDeclaredMethod("createRingerButtonArgs", Boolean::class.java, Int::class.java, Float::class.java)
            xposed.after(args, loaderId("show-hide-delay", loader)) { chain, result ->
                if (chain.boolArg(0) && (chain.getArg(1) as Int) >= 2 && result != null) {
                    if ((GET_DELAY_X.call(result) as? Long ?: 0L) > 0L) SET_DELAY_X.invoke(result, 70L)
                }
            }
        }
    }

    /** initView runs before the ringer layout is inflated on some builds; one retry covers that. */
    private fun joinShowHide(animator: Any, volumeView: View, retry: Boolean) {
        val row = AncRow.of(volumeView.findViewWithRinger())
        if (row != null) {
            row.joinShowHideAnimator(animator, volumeView)
        } else if (retry) {
            volumeView.post { joinShowHide(animator, volumeView, retry = false) }
        }
    }

    private fun View.findViewWithRinger(): View? =
        io.github.lcebot.mivolumeenhanced.hook.core.Res.id(context, "id", "miui_volume_ringer_layout")
            .takeIf { it != 0 }?.let { findViewById(it) }

    /** The show/hide animation reports each frame through listener.onUpdate. */
    private fun hookAnimatorListener(xposed: XposedInterface, animator: Any, loader: ClassLoader) {
        if (listenerHooked) return
        val listener = ANIMATOR_LISTENER.get(animator) ?: return
        val onUpdate = listener.javaClass.declaredMethods.firstOrNull { it.name == "onUpdate" && it.parameterCount == 2 } ?: return
        xposed.after(onUpdate, loaderId("show-hide-update", loader)) { _, _ -> AppVolumeEntry.syncAnimation() }
        listenerHooked = true
    }

    private fun hookExpandAnimator(xposed: XposedInterface, loader: ClassLoader) {
        val animator = loader.find(PKG + "VolumeExpandCollapsedAnimator") ?: error("VolumeExpandCollapsedAnimator missing")
        val frame: Method = animator.declaredMethods.firstOrNull {
            it.name.startsWith("frameCallback\$lambda") && it.parameterCount == 2 && it.parameterTypes[0] == animator
        } ?: error("expand frame callback missing")
        xposed.after(frame, loaderId("expand-frame", loader)) { chain, _ ->
            AncRow.of(ANIMATOR_RINGER_LAYOUT.get(chain.getArg(0)))?.syncFrame()
        }
    }

    private fun hookDialogView(xposed: XposedInterface, loader: ClassLoader) {
        val dialog = loader.find(PKG + "MiuiVolumeDialogView") ?: error("MiuiVolumeDialogView missing")
        xposed.before(dialog.getDeclaredMethod("showH", Runnable::class.java), loaderId("dialog-show", loader)) { chain ->
            AppVolumeEntry.prepareShow(chain.thisObject as View)
        }
        xposed.after(dialog.getDeclaredMethod("dismissH", Boolean::class.java, Runnable::class.java), loaderId("dialog-dismiss", loader)) { chain, _ ->
            AppVolumeEntry.onDismissStarted(chain.boolArg(0))
        }
        xposed.after(dialog.getDeclaredMethod("onExpandStateUpdated", Boolean::class.java), loaderId("dialog-expand", loader)) { chain, _ ->
            AppVolumeEntry.onExpandedChanged(chain.thisObject as View, chain.boolArg(0))
        }
        // Extra triggers: show order differs between builds, and the entry must not depend on one.
        optional(dialog, "onAttachedToWindow")?.let { m ->
            xposed.after(m, loaderId("dialog-attached", loader)) { chain, _ -> AppVolumeEntry.prepareShow(chain.thisObject as View) }
        }
        optional(dialog, "onDetachedFromWindow")?.let { m ->
            xposed.after(m, loaderId("dialog-detached", loader)) { _, _ -> AppVolumeEntry.onHideComplete() }
        }
        optional(dialog, "updateFooterVisibility", Boolean::class.java)?.let { m ->
            xposed.after(m, loaderId("dialog-footer", loader)) { _, _ -> AppVolumeEntry.refreshVisibility() }
        }
        optional(dialog, "updateDialogViewLP")?.let { m ->
            xposed.after(m, loaderId("dialog-layout", loader)) { _, _ -> AppVolumeEntry.refreshVisibility() }
        }
        loader.find(PKG + "VolumePanelViewController")?.let { controller ->
            optional(controller, "showH", Int::class.java)?.let { m ->
                xposed.before(m, loaderId("controller-show", loader)) { chain ->
                    AppVolumeEntry.setPanelController(chain.thisObject)
                    AppVolumeEntry.prepareShow(null)
                }
            }
        }
    }

    /**
     * Overscroll and drag stretch: SlideContainerAnim drives the two native rows through an
     * AnimListener implementation. The listener is an interface, so the hooks go on the instance's
     * class once one exists.
     */
    private fun hookSlideAnim(xposed: XposedInterface, loader: ClassLoader) {
        val slide = loader.find(PKG + "SlideContainerAnim") ?: error("SlideContainerAnim missing")
        val capture: (Any?) -> Unit = { anim -> hookSlideListener(xposed, SLIDE_LISTENER.get(anim), loader) }
        slide.declaredConstructors.forEach { c ->
            xposed.after(c, overloadId("slide-ctor", c)) { chain, _ -> capture(chain.thisObject) }
        }
        slide.declaredMethods.filter { it.name.startsWith("anim") || it.name == "initView" }.forEach { m ->
            xposed.after(m, overloadId("slide-${m.name}", m)) { chain, _ -> capture(chain.thisObject) }
        }
    }

    @Synchronized
    private fun hookSlideListener(xposed: XposedInterface, listener: Any?, loader: ClassLoader) {
        if (slideListenerHooked || listener == null) return
        for (m in listener.javaClass.declaredMethods) {
            if (java.lang.reflect.Modifier.isAbstract(m.modifiers)) continue
            when {
                m.name == "setRingerY" && m.parameterCount == 2 ->
                    xposed.after(m, loaderId("slide-ringer", loader)) { chain, _ ->
                        (chain.getArg(1) as? Float)?.let(AppVolumeEntry::onSlideRinger)
                    }
                m.name == "setDndY" && m.parameterCount == 2 ->
                    xposed.after(m, loaderId("slide-dnd", loader)) { chain, _ ->
                        (chain.getArg(1) as? Float)?.let(AppVolumeEntry::onSlideDnd)
                    }
                m.name == "resetView" && m.parameterCount == 0 ->
                    xposed.after(m, loaderId("slide-reset", loader)) { _, _ -> AppVolumeEntry.onSlideReset() }
                else -> continue
            }
            slideListenerHooked = true
        }
    }

    private fun optional(type: Class<*>, name: String, vararg params: Class<*>): Method? =
        runCatching { type.getDeclaredMethod(name, *params) }.getOrNull()
}
