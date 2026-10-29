package io.github.lcebot.mivolumeenhanced.hook.systemui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.lcebot.mivolumeenhanced.hook.core.Diagnostics
import io.github.lcebot.mivolumeenhanced.hook.core.FieldRef
import io.github.lcebot.mivolumeenhanced.hook.core.HookOptions
import io.github.lcebot.mivolumeenhanced.hook.core.MethodRef
import io.github.lcebot.mivolumeenhanced.hook.core.Res
import io.github.lcebot.mivolumeenhanced.hook.core.findByName
import io.github.lcebot.mivolumeenhanced.hook.core.localized
import io.github.lcebot.mivolumeenhanced.hook.core.requireByName
import io.github.lcebot.mivolumeenhanced.hook.headset.AncController
import io.github.lcebot.mivolumeenhanced.hook.headset.AncMode
import io.github.lcebot.mivolumeenhanced.hook.headset.AncState
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * The noise control row added to the volume panel, below the native silent and do-not-disturb rows.
 *
 * It is built from the plugin's own miui_ringer_mode_layout and styled by the plugin's
 * RingerButtonHelper, so material, blur and press feedback are native. Expanded, the row's timer
 * area holds a "disconnect" button: a ringer button stretched to the timer bar's width, since it is
 * an action and not a slider.
 *
 * The native expand/collapse animator only knows two rows, so [syncFrame] copies the do-not-disturb
 * row's animated frame onto this row on every frame.
 */
class AncRow private constructor(
    val host: ViewGroup,
    private val buttonLayout: ViewGroup,
    private val dndRow: View,
    private val nativeDivider: View,
    private val templateId: Int,
) {
    private val context: Context = host.context

    /**
     * The volume dialog (true) or Control Center's panel (false). Read on every use: the plugin
     * sets it after inflation, when the panel is attached to its controller.
     */
    val dialog: Boolean get() = NEED_SHOW_DIALOG.get(host) as? Boolean ?: true

    private val divider = View(context).apply { id = View.generateViewId() }
    private val row: ViewGroup = inflate(templateId, buttonLayout)
    private val rowBlur = row.requireByName("bg_blur")
    private val rowStandard = row.requireByName("miui_standard_btn")
    private val rowIcon = row.requireByName("icon") as ImageView
    private val timer = row.requireByName("timer_layout") as ViewGroup
    private val rowHelper = helper(row)

    private val action: ViewGroup = inflate(templateId, null)
    private val actionHelper = helper(action)
    private val actionStandard = action.requireByName("miui_standard_btn")
    private val actionBlur = action.requireByName("bg_blur")
    private val actionText = TextView(context)

    // The do-not-disturb row's parts whose animated bounds this row copies each frame.
    private val dndBlur = dndRow.findByName("bg_blur")
    private val dndStandard = dndRow.findByName("miui_standard_btn")
    private val dndIcon = dndRow.findByName("icon")
    private val dndTimer = dndRow.findByName("timer_layout")

    private var side: SideButton? = null
    private var state = AncState(available = false, mode = AncMode.OFF, cycleIncludesOff = false)
    private var rendered = false
    private var fadingOut = false
    private var fadeIndex = -1
    private var fadeParams: ViewGroup.LayoutParams? = null
    private var actionStyled = false
    private var expanded = false
    private var icon: AncIcon? = null
    private val controller = AncController.get(context)
    private val listener: (AncState) -> Unit = ::render

    /** Injection order: a rebuilt panel's row is newer than the one it replaces. */
    private val serial = nextSerial++

    init {
        val source = nativeDivider.layoutParams
        divider.layoutParams = (source as? LinearLayout.LayoutParams)?.let { LinearLayout.LayoutParams(it) }
            ?: LinearLayout.LayoutParams(source)
        row.id = View.generateViewId()
        val at = buttonLayout.indexOfChild(dndRow) + 1
        buttonLayout.addView(divider, at)
        buttonLayout.addView(row, at + 1)
        // The state layout around the rows has a fixed height in some builds; it has to grow.
        wrapHeight(host.findByName("miui_ringer_state_layout"))

        timer.removeAllViews()
        action.findByName("timer_layout")?.visibility = View.GONE
        action.findByName("icon")?.visibility = View.GONE
        actionText.apply {
            gravity = Gravity.CENTER
            text = context.localized("Disconnect", "断开连接")
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        (actionStandard as ViewGroup).addView(actionText, ViewGroup.LayoutParams(-1, -1))
        timer.addView(action, FrameLayout.LayoutParams(actionWidth(), rowHeight()).apply { marginStart = actionMargin() })

        rowStandard.accessibilityDelegate = null
        actionStandard.accessibilityDelegate = null
        actionBlur.contentDescription = context.localized("Disconnect the headset", "断开耳机连接")
        rowBlur.setOnClickListener { controller.toggle() }
        actionBlur.setOnClickListener { controller.disconnect() }

        row.tag = this
        row.visibility = View.GONE
        divider.visibility = View.GONE
        timer.visibility = View.GONE

        host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                controller.addListener(listener)
            }

            override fun onViewDetachedFromWindow(v: View) {
                controller.removeListener(listener)
            }
        })
        if (host.isAttachedToWindow) {
            controller.addListener(listener)
        } else {
            // The plugin sizes the panel while attaching it, before this row's listener is added on
            // the children's attach; the extra height has to know about the headset by then. The
            // row itself is shown when the listener renders this state.
            state = controller.current()
        }
        // A placement change alters the plan without changing the headset state. The listener list
        // outlives panels, so it must not keep this row alive.
        val self = WeakReference(this)
        HookOptions.addListener { self.get()?.takeIf { it.isAlive }?.onOptionsChanged() }
    }

    private fun onOptionsChanged() {
        applyVisibility(expanded)
        refreshPanelSize()
    }

    /** While it fades out the row lives in the overlay, and still belongs to this panel. */
    val isAlive get() = row.parent === buttonLayout || fadingOut

    // ---------------------------------------------------------------- state

    private fun render(new: AncState) {
        val availabilityChanged = !rendered || new.available != state.available
        if (!availabilityChanged && new == state) return
        rendered = true
        state = new
        if (new.available) applyMode()
        applyVisibility(expanded)
        if (availabilityChanged) refreshPanelSize()
    }

    /**
     * Showing or hiding the row changes the panel height, which the plugin recomputes in
     * updateVolumePanelSize. That does not refresh the shadow behind the panel, so the motion
     * helper's updateShadowState follows.
     */
    private fun refreshPanelSize() = host.post {
        val dialogView = host.parent
        UPDATE_PANEL_SIZE.invokeQuietly(dialogView)
        UPDATE_SHADOW.invokeQuietly(MOTION.get(dialogView))
    }

    private fun applyMode() {
        SET_RINGER_MODE.invokeQuietly(rowHelper, state.mode != AncMode.OFF)
        UPDATE_STATE.invokeQuietly(rowHelper)
        // updateState can put the native icon back, so the glyph is set every time.
        val glyph = icon?.takeIf { it.mode == state.mode } ?: AncIcon(state.mode).also { icon = it }
        rowIcon.setImageDrawable(glyph)
        rowIcon.imageTintList = ColorStateList.valueOf(tint())
        rowBlur.contentDescription = when (state.mode) {
            AncMode.NOISE_CANCELLING -> context.localized("Noise cancelling. Tap for transparency", "降噪模式，点击切换到通透模式")
            AncMode.TRANSPARENCY -> if (state.cycleIncludesOff) context.localized("Transparency. Tap to turn off", "通透模式，点击关闭")
            else context.localized("Transparency. Tap for noise cancelling", "通透模式，点击切换到降噪模式")
            AncMode.OFF -> context.localized("Noise control off. Tap for noise cancelling", "降噪已关闭，点击开启降噪")
        }
        side?.update(state.mode, tint())
    }

    private fun tint(): Int = when (state.mode) {
        AncMode.TRANSPARENCY -> Res.color(context, "vp_o3_dnd_on", 0xFF7767F9.toInt())
        AncMode.NOISE_CANCELLING -> Res.color(context, "vp_o3_silent_on", 0xFFFF4F3F.toInt())
        AncMode.OFF -> Res.color(context, "vp_o3_silent_off", 0xFFFFFFFF.toInt())
    }

    private fun plan(): Plan = Planner.plan(context, dialog, rowHeight() + dividerGap(), collapsedRowHeight() + collapsedGap())

    /** The height this row adds to the expanded panel: zero unless it is in that panel. */
    fun expandedExtra(): Int =
        if (state.available && plan().expanded == Spot.PANEL) rowHeight() + dividerGap() else 0

    /** The same for the collapsed panel, which the plugin centres by its height in landscape. */
    fun collapsedExtra(): Int =
        if (state.available && plan().collapsed == Spot.PANEL) collapsedRowHeight() + collapsedGap() else 0

    /**
     * Sets what is visible for the panel state the plugin is about to lay out. Called before the
     * plugin measures the expand animation's target, so the measurement already includes the row.
     */
    fun prepare(expanding: Boolean) {
        expanded = expanding
        applyVisibility(expanding, transition = true)
    }

    /** After a configuration change the plan may differ, for example after rotating. */
    fun reapply() = applyVisibility(expanded)

    /**
     * Shows the control where the plan puts it for [expandedState]. With [transition], when the
     * panel starts to expand or collapse, the control moves with M3's fade through: the row leaving
     * the panel fades out while the panel starts to move, and the round button beside the panel
     * fades in, sliding outwards, once the panel has largely reached its new size. In the other
     * direction the button fades out at once and the row fades back in as the panel settles.
     */
    private fun applyVisibility(expandedState: Boolean, transition: Boolean = false) {
        val spot = if (state.available) plan().spot(expandedState) else Spot.HIDDEN
        val inPanel = spot == Spot.PANEL
        val rowShown = row.visibility == View.VISIBLE && !fadingOut
        if (inPanel) {
            finishFadeOut()
            if (row.visibility != View.VISIBLE) {
                row.visibility = View.VISIBLE
                if (transition) fadeInRow() else row.alpha = 1f
            }
        } else if (rowShown) {
            if (transition && isAlive && row.width > 0) fadeOutRow() else row.visibility = View.GONE
        }
        divider.visibility = if (inPanel) View.VISIBLE else View.GONE
        timer.visibility = if (inPanel && expandedState) View.VISIBLE else View.GONE
        if (spot == Spot.SIDE) {
            sideButton().show(state.mode, tint(), animate = transition, delayed = transition && rowShown)
        } else {
            side?.hide(animate = transition)
        }
    }

    /**
     * The row leaves the layout at once, so the plugin measures the panel without it, and is drawn
     * in the button layout's overlay while it fades. Views in an overlay keep their hardware
     * rendering, so the row's material stays as it was.
     */
    private fun fadeOutRow() {
        fadeIndex = buttonLayout.indexOfChild(row)
        fadeParams = row.layoutParams
        fadingOut = true
        buttonLayout.overlay.add(row)
        row.animate().cancel()
        row.animate().alpha(0f).setStartDelay(0).setDuration(ROW_EXIT_MS).setInterpolator(LINEAR)
            .withEndAction { finishFadeOut() }.start()
    }

    private fun finishFadeOut() {
        if (!fadingOut) return
        fadingOut = false
        row.animate().cancel()
        buttonLayout.overlay.remove(row)
        row.visibility = View.GONE
        row.alpha = 1f
        buttonLayout.addView(row, fadeIndex.coerceIn(0, buttonLayout.childCount), fadeParams ?: row.layoutParams)
    }

    private fun fadeInRow() {
        row.animate().cancel()
        row.alpha = 0f
        row.animate().alpha(1f).setStartDelay(ROW_ENTER_DELAY_MS).setDuration(ROW_ENTER_MS)
            .setInterpolator(LINEAR).start()
    }

    // ---------------------------------------------------------------- native callbacks

    fun onExpanded(expanded: Boolean, force: Boolean) {
        this.expanded = expanded
        ON_EXPANDED.invokeQuietly(rowHelper, expanded, force)
        ON_EXPANDED.invokeQuietly(actionHelper, true, true)
        if (state.available) applyMode()
        applyVisibility(expanded)
        styleAction()
        syncDividerSize()
    }

    fun refreshStyle() {
        actionStyled = false
        side?.restyle()
        onExpanded(expanded, true)
    }

    /** Copies the do-not-disturb row's animated frame; runs on every expand/collapse frame. */
    fun syncFrame() {
        side?.sync()
        if (row.visibility != View.VISIBLE || !isAlive || dndRow.width == 0 || dndRow.height == 0) return
        val gap = nativeDivider.height.takeIf { it > 0 } ?: dividerGap()
        val top = dndRow.bottom + gap
        divider.setLeftTopRightBottom(dndRow.left, dndRow.bottom, dndRow.right, top)
        row.setLeftTopRightBottom(dndRow.left, top, dndRow.right, top + dndRow.height)
        copyBounds(dndBlur, rowBlur)
        copyBounds(dndStandard, rowStandard)
        copyBounds(dndIcon, rowIcon)
        if (dndTimer != null && timer.visibility == View.VISIBLE) {
            copyBounds(dndTimer, timer)
            timer.alpha = dndTimer.alpha
            timer.pivotX = dndTimer.pivotX
            timer.pivotY = dndTimer.pivotY
            timer.scaleX = dndTimer.scaleX
            timer.scaleY = dndTimer.scaleY
        }
    }

    fun setSlideTranslation(y: Float) {
        if (row.visibility == View.VISIBLE) row.translationY = y
    }

    /** Lets the native show/hide animation move this row along with the two native rows. */
    fun joinShowHideAnimator(animator: Any, volumeView: View) {
        val rows = RINGER_ROWS.get(animator) as? Array<*> ?: return
        if (row in rows) return
        @Suppress("UNCHECKED_CAST")
        val extended = (rows as Array<View?>).copyOf(rows.size + 1).also { it[rows.size] = row }
        RINGER_ROWS.set(animator, extended)
        val positions = (RINGER_ROWS_X.get(animator) as? Array<*>)?.map { it as? Float ?: 0f } ?: emptyList()
        val extendedX = Array(extended.size) { positions.getOrNull(it) ?: 0f }
        extendedX[extended.size - 1] = row.x + volumeView.x
        RINGER_ROWS_X.set(animator, extendedX)
        Diagnostics.ok("anc_row_animation", "joined the show and hide animation")
    }

    /**
     * The disconnect button always has the expanded style, stretched to the timer bar's size.
     *
     * updateState only applies the material when the expanded or on/off state changed since the
     * last call, and the button's never change. After a theme or material change the plugin
     * restyles its own buttons, so this one is pushed through the styling path by toggling its
     * state on and off once. That happens on those changes only, not on every expand.
     */
    private fun styleAction() {
        val width = actionWidth()
        val height = rowHeight()
        (action.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            if (lp.width != width || lp.height != height || lp.marginStart != actionMargin()) {
                lp.width = width
                lp.height = height
                lp.marginStart = actionMargin()
                action.layoutParams = lp
            }
        }
        setSize(actionStandard, width, height)
        setSize(actionBlur, width, height)
        if (!actionStyled) {
            SET_RINGER_MODE.invokeQuietly(actionHelper, true)
            UPDATE_STATE.invokeQuietly(actionHelper)
            SET_RINGER_MODE.invokeQuietly(actionHelper, false)
            UPDATE_STATE.invokeQuietly(actionHelper)
            actionStyled = true
        }
        action.findByName("icon")?.visibility = View.GONE
        actionText.setTextColor(Res.color(context, "vp_o3_silent_off", 0xFFFFFFFF.toInt()))
    }

    private fun syncDividerSize() {
        val source = nativeDivider.layoutParams ?: return
        val target = divider.layoutParams ?: return
        if (target.width != source.width || target.height != source.height) {
            target.width = source.width
            target.height = source.height
            divider.layoutParams = target
        }
    }

    // ---------------------------------------------------------------- side button

    private fun sideButton(): SideButton = side ?: SideButton(panelRoot()).also { side = it }

    /**
     * The ANC button beside the panel, for a panel state too tall for the row. It is the native
     * round button of the expanded panel, the same as silent and do-not-disturb there. It is a
     * child of the panel's full-screen root, so it is drawn and receives touches outside the
     * panel's bounds, and it follows the panel on every frame while shown: left of the expanded
     * panel's background, or left of the collapsed panel's buttons, level with do-not-disturb.
     */
    private inner class SideButton(private val root: ViewGroup) {
        private val view: ViewGroup = inflate(templateId, null)
        private val helper = helper(view)
        private val iconView = view.requireByName("icon") as ImageView
        private val blurFrame = root.findByName("blur_frame")
        private var glyph: AncIcon? = null
        private var observer: ViewTreeObserver? = null
        private val follower = ViewTreeObserver.OnPreDrawListener {
            follow()
            true
        }

        /** Whether the button is meant to be on screen, including while it fades in. */
        private var shown = false

        /**
         * Whether the button stands beside the expanded panel's background or beside the collapsed
         * button column. Set while the button is shown and kept while it fades out, so a collapsing
         * panel does not make a leaving button jump to the collapsed column first.
         */
        private var besideFrame = false

        /** Where [follow] last placed the button, kept for frames in which its anchor is gone. */
        private var baseX = 0f
        private var baseY = 0f

        /**
         * 0 hidden, 1 in place, moving linearly in time. The alpha follows it as it is; the slide
         * follows it through emphasized decelerate, which played backwards is the accelerating
         * exit, so one value serves both directions and a reversal mid-way stays continuous.
         */
        private var fade = 0f
        private var animator: ValueAnimator? = null

        init {
            view.findByName("timer_layout")?.visibility = View.GONE
            view.requireByName("bg_blur").apply {
                setOnClickListener { controller.toggle() }
                // The stacked row has a disconnect button; beside the panel there is room for only
                // one button, so disconnecting is its long press.
                setOnLongClickListener {
                    controller.disconnect()
                    true
                }
                contentDescription = context.localized("Noise control, long press to disconnect", "降噪控制，长按断开连接")
            }
            view.visibility = View.GONE
            root.addView(view, FrameLayout.LayoutParams(-2, -2))
            ON_EXPANDED.invokeQuietly(helper, true, true)
            restyle()
        }

        /**
         * updateState applies the material only when the expanded or on/off state changed, so the
         * state is toggled once to push the current theme's material through.
         */
        fun restyle() {
            SET_RINGER_MODE.invokeQuietly(helper, true)
            UPDATE_STATE.invokeQuietly(helper)
            SET_RINGER_MODE.invokeQuietly(helper, false)
            UPDATE_STATE.invokeQuietly(helper)
            update(state.mode, tint())
        }

        /**
         * [delayed] holds the fade back while the panel is still growing, so the button does not
         * appear beside a panel edge that is still moving. The button fades whenever the panel is
         * on screen, also when the plan changes outside an expand or collapse, so it never pops in.
         */
        fun show(mode: AncMode, tint: Int, animate: Boolean, delayed: Boolean) {
            update(mode, tint)
            attach()
            besideFrame = expanded
            if (shown) return
            shown = true
            view.visibility = View.VISIBLE
            if (animate || root.isShown) animateTo(1f, if (delayed) SIDE_ENTER_DELAY_MS else 0L, SIDE_ENTER_MS)
            else setFade(1f)
        }

        fun hide(animate: Boolean) {
            if (!shown) return
            shown = false
            if ((animate || root.isShown) && view.isAttachedToWindow) {
                animateTo(0f, 0L, SIDE_EXIT_MS) { if (!shown) gone() }
            } else {
                gone()
            }
        }

        private fun gone() {
            animator?.cancel()
            setFade(0f)
            view.visibility = View.GONE
            detach()
        }

        private fun animateTo(target: Float, delay: Long, duration: Long, end: (() -> Unit)? = null) {
            animator?.cancel()
            animator = ValueAnimator.ofFloat(fade, target).apply {
                startDelay = delay
                this.duration = duration
                interpolator = LINEAR
                addUpdateListener { setFade(it.animatedValue as Float) }
                if (end != null) {
                    addListener(object : AnimatorListenerAdapter() {
                        private var cancelled = false

                        override fun onAnimationCancel(animation: Animator) {
                            cancelled = true
                        }

                        override fun onAnimationEnd(animation: Animator) {
                            if (!cancelled) end()
                        }
                    })
                }
                start()
            }
        }

        private fun setFade(value: Float) {
            fade = value
            follow()
        }

        /** The window, and with it the observer, can change between shows; follow the current one. */
        private fun attach() {
            val current = root.viewTreeObserver
            if (observer !== current) {
                detach()
                current.addOnPreDrawListener(follower)
                observer = current
            }
        }

        private fun detach() {
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(follower)
            observer = null
        }

        fun update(mode: AncMode, tint: Int) {
            SET_RINGER_MODE.invokeQuietly(helper, mode != AncMode.OFF)
            UPDATE_STATE.invokeQuietly(helper)
            val icon = glyph?.takeIf { it.mode == mode } ?: AncIcon(mode).also { glyph = it }
            iconView.setImageDrawable(icon)
            iconView.imageTintList = ColorStateList.valueOf(tint)
        }

        /** Places the button for the current frame of the expand or collapse animation. */
        fun sync() = follow()

        private fun follow() {
            if (view.visibility != View.VISIBLE || view.width == 0) return
            // Expanded, the panel's edge is its background, which the expand animation moves and
            // fades; collapsed, the background is hidden and the edge is the button column.
            val frame = blurFrame?.takeIf { besideFrame && it.visibility == View.VISIBLE }
            val anchor = if (besideFrame) frame else host
            if (anchor != null && anchor.isAttachedToWindow) {
                root.getLocationInWindow(rootXY)
                anchor.getLocationInWindow(anchorXY)
                dndRow.getLocationInWindow(dndXY)
                baseX = (anchorXY[0] - rootXY[0] - Res.dp(context, 12f) - view.width).toFloat()
                baseY = dndXY[1] - rootXY[1] + (dndRow.height * dndRow.scaleY - view.height) / 2f
            }
            view.translationX = baseX + Res.dp(context, SIDE_SLIDE_DP) * (1f - EMPHASIZED_DECELERATE.getInterpolation(fade))
            view.translationY = baseY
            view.alpha = (frame?.alpha ?: 1f) * fade
        }

        private val rootXY = IntArray(2)
        private val anchorXY = IntArray(2)
        private val dndXY = IntArray(2)
    }

    // ---------------------------------------------------------------- dimensions

    private fun rowHeight() = if (dialog) Res.dimen(context, "o3_miui_ringer_btn_height_expended", 56f)
    else Res.dimen(context, "o3_miui_ringer_btn_height_cc", 60f)

    private fun dividerGap() = if (dialog) Res.dimen(context, "o3_miui_volume_ringer_divider_height", 14f)
    else Res.dimen(context, "o3_miui_volume_ringer_divider_height_cc", 16f)

    private fun collapsedRowHeight() = if (dialog) Res.dimen(context, "o3_miui_ringer_btn_height", 40f)
    else Res.dimen(context, "o3_miui_ringer_btn_height_cc", 60f)

    private fun collapsedGap() = if (dialog) Res.dimen(context, "miui_volume_ringer_divider_height", 14f)
    else Res.dimen(context, "o3_miui_volume_ringer_divider_height_cc", 16f)

    private fun actionMargin() = if (dialog) Res.dimen(context, "miui_volume_timer_margin_left", 12f)
    else Res.dimen(context, "miui_volume_timer_margin_left_cc", 16f)

    /** Follows the native timer slider: 152dp, 206dp with four volume columns, 204dp in Control Center. */
    private fun actionWidth(): Int = when {
        !dialog -> Res.dimen(context, "miui_volume_timer_seekbar_width_cc", 204f)
        notificationSingle() -> Res.dimen(context, "miui_volume_timer_seekbar_width_4stream", 206f)
        else -> Res.dimen(context, "miui_volume_timer_seekbar_width", 152f)
    }

    private fun notificationSingle(): Boolean = runCatching {
        host.javaClass.classLoader?.loadClass("com.android.systemui.miui.volume.Util")
            ?.getDeclaredField("sIsNotificationSingle")?.apply { isAccessible = true }?.get(null) == true
    }.getOrDefault(false)

    /** MiuiRingerModeLayout, then MiuiVolumeDialogView, then the root that also holds blur_frame. */
    private fun panelRoot(): ViewGroup =
        (host.parent?.parent as? ViewGroup) ?: (host.parent as? ViewGroup) ?: host.rootView as ViewGroup

    private fun inflate(layoutId: Int, parent: ViewGroup?): ViewGroup =
        LayoutInflater.from(context).inflate(layoutId, parent, false) as ViewGroup

    private fun helper(view: View): Any {
        val type = host.javaClass.declaredClasses.firstOrNull { it.simpleName == "RingerButtonHelper" }
            ?: throw ClassNotFoundException("${host.javaClass.name}\$RingerButtonHelper")
        return type.getDeclaredConstructor(host.javaClass, View::class.java, Boolean::class.java, Boolean::class.java)
            .apply { isAccessible = true }.newInstance(host, view, false, false)
    }

    companion object {
        private val NEED_SHOW_DIALOG = FieldRef("mNeedShowDialog")
        private val RINGER_ROWS = FieldRef("mRingerBtnLayouts")
        private val RINGER_ROWS_X = FieldRef("ringerBtnLayoutsX")
        private val SET_RINGER_MODE = MethodRef("setRingerMode", Boolean::class.java)
        private val UPDATE_STATE = MethodRef("updateState")
        private val ON_EXPANDED = MethodRef("onExpanded", Boolean::class.java, Boolean::class.java)
        private val UPDATE_PANEL_SIZE = MethodRef("updateVolumePanelSize")
        private val UPDATE_SHADOW = MethodRef("updateShadowState")
        private val MOTION = FieldRef("mMotion")

        /** M3's emphasized decelerate, for the side button's movement. */
        private val EMPHASIZED_DECELERATE = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

        /**
         * Fades run linearly. An emphasized curve on the alpha reaches most of its change within the
         * first frames, so a fade on it reads as the view popping in or out.
         */
        private val LINEAR = LinearInterpolator()
        private const val ROW_EXIT_MS = 150L
        private const val ROW_ENTER_DELAY_MS = 120L
        private const val ROW_ENTER_MS = 250L
        private const val SIDE_EXIT_MS = 150L
        private const val SIDE_ENTER_MS = 300L

        /** About two thirds of the expand animation: the panel's edge has nearly arrived by then. */
        private const val SIDE_ENTER_DELAY_MS = 220L

        /** How far the side button travels outwards while it fades in. */
        private const val SIDE_SLIDE_DP = 16f

        /** Weak values: each row holds its host, which would otherwise keep the key alive. */
        private val rows = WeakHashMap<View, WeakReference<AncRow>>()

        private fun live(): List<AncRow> = rows.values.mapNotNull { it.get() }.filter { it.isAlive }

        /** The row injected into [host], creating it the first time. */
        fun inject(host: Any?) {
            val ringer = host as? ViewGroup ?: return
            if (rows[ringer]?.get() != null) return
            try {
                val buttonLayout = ringer.requireByName("miui_ringer_btn_layout") as ViewGroup
                val dnd = ringer.requireByName("dnd_layout")
                val divider = ringer.requireByName("miui_volume_ringer_divider")
                val templateId = Res.id(ringer.context, "layout", "miui_ringer_mode_layout")
                require(templateId != 0) { "miui_ringer_mode_layout missing" }
                rows[ringer] = WeakReference(AncRow(ringer, buttonLayout, dnd, divider, templateId))
                Diagnostics.ok("anc_row", "injected")
            } catch (t: Throwable) {
                Diagnostics.failed("anc_row", t)
            }
        }

        fun of(host: Any?): AncRow? = (host as? View)?.let { rows[it]?.get() }?.takeIf { it.isAlive }

        /**
         * The row of the panel of the given kind. A rebuilt panel can leave an old one behind until
         * it is collected, so a panel that is attached to a window wins, and otherwise the newest:
         * the plugin lays a panel out while attaching it, before the panel's children count as
         * attached.
         */
        fun forPanel(dialog: Boolean): AncRow? = live().filter { it.dialog == dialog }
            .let { rows -> rows.firstOrNull { it.host.isAttachedToWindow } ?: rows.maxByOrNull { it.serial } }

        private var nextSerial = 0

        private fun wrapHeight(view: View?) {
            val lp = view?.layoutParams ?: return
            if (lp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                view.layoutParams = lp
            }
        }

        private fun copyBounds(source: View?, target: View) {
            source ?: return
            target.setLeftTopRightBottom(source.left, source.top, source.right, source.bottom)
        }

        private fun setSize(view: View, width: Int, height: Int) {
            val lp = view.layoutParams ?: return
            if (lp.width != width || lp.height != height) {
                lp.width = width
                lp.height = height
                view.layoutParams = lp
            }
        }
    }
}
