package io.github.lcebot.mivolumeenhanced.hook.misound

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.animation.AccelerateInterpolator
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.DecelerateInterpolator
import android.view.animation.TranslateAnimation
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import io.github.lcebot.mivolumeenhanced.hook.core.HookLog
import io.github.lcebot.mivolumeenhanced.hook.core.MethodRef
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * The look of MiSound's per-app volume panel: a rounded, blurred card with slider proportions taken
 * from the native card (slider height 22.1% of the long screen edge, width 15.8% and column gap 3.8%
 * of the short edge), and a page indicator below it. The native panel blurs and dims the whole
 * screen and scales in from a corner; here only the card is blurred and it slides in from its edge.
 *
 * The card takes the place of SystemUI's volume bar: same top, same outer edge, when SystemUI sent
 * the bar's bounds (see [anchorTo]). The plugin positions that bar per device so that it sits next to
 * the physical volume keys. Without bounds, or after a rotation, the card is centred vertically at
 * the right edge.
 */
object AppVolumeCard {
    private const val KEEP = Int.MIN_VALUE
    private const val CORNER_DP = 28f
    private val GET_VIEW_ROOT = MethodRef("getViewRootImpl")
    private val CREATE_BLUR = MethodRef("createBackgroundBlurDrawable")
    private val GET_CURRENT_ITEM = MethodRef("getCurrentItem")

    /** Which window and appearance each card's blur was made for; the same pair is not redone. */
    private val blurs = WeakHashMap<View, Pair<WeakReference<Any>, Boolean>>()
    private val styled = WeakHashMap<View, Boolean>()
    private val placed = WeakHashMap<View, Boolean>()

    /** The volume bar in screen pixels, and the rotation it was measured in. */
    private class Bar(val left: Int, val top: Int, val right: Int, val rotation: Int)

    @Volatile private var bar: Bar? = null

    /** Whether the card currently sits at the left edge, which decides the slide direction. */
    private var atLeft = false

    /**
     * Remembers where SystemUI's volume bar is. Kept across openings, so the panel opened from
     * MiSound's own floating ball lands in the same place as long as the rotation is the same.
     */
    fun anchorTo(bounds: IntArray?, rotation: Int) {
        if (bounds == null || bounds.size < 4 || rotation < 0 || bounds[2] <= bounds[0]) return
        bar = Bar(bounds[0], bounds[1], bounds[2], rotation)
    }

    fun layout(controller: PanelController) {
        val page = controller.pageView ?: return
        try {
            page.clipChildren = false
            page.clipToPadding = false
            val card = cardOf(page) ?: return
            val context = card.context
            val density = context.resources.displayMetrics.density
            val padding = (16 * density).toInt()
            val inner = max(padding - columnGap(context) / 2, 0)

            val pager = findPager(card)
            if (pager != null) {
                pager.visibility = View.VISIBLE
                val count = controller.columns?.size ?: 1
                val width = min(max(count, 1), 3) * (sliderWidth(context) + columnGap(context))
                val height = sliderHeight(context)
                if (pager.layoutParams != null) update(pager, width, height, KEEP, KEEP, 0, 0)
                else pager.layoutParams = LinearLayout.LayoutParams(width, height)
                pager.minimumHeight = height
                styleIndicator(controller, card, pager, count, density)
                sizeSliders(pager)
            }

            if (card.layoutParams !is LinearLayout.LayoutParams) card.layoutParams = LinearLayout.LayoutParams(-2, -2)
            update(card, -2, -2, KEEP, KEEP, KEEP, KEEP)
            place(page, card)
            if (placed.put(page, true) == null) followPlacement(page)
            card.setPadding(inner, padding, inner, padding)
            card.isClickable = true
            card.isFocusable = true
            card.clipToOutline = true
            card.elevation = 16f * density
            if (styled.put(card, true) == null) {
                card.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, CORNER_DP * view.resources.displayMetrics.density)
                    }
                }
                card.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) = applyBlur(v)
                    override fun onViewDetachedFromWindow(v: View) = Unit
                })
            }
            if (card.isAttachedToWindow) applyBlur(card)
        } catch (t: Throwable) {
            HookLog.w("app volume card layout", t)
        }
    }

    /**
     * Puts the card at the bar's top and outer edge, or centres it at the right edge without a bar
     * for this rotation. The top is kept on screen once the card's height is known. Returns whether
     * anything changed, which then needs another layout pass.
     */
    private fun place(page: ViewGroup, card: ViewGroup): Boolean {
        // The rotation and the window's position are only known while attached; the pre-draw pass
        // places the card before its first frame.
        if (!page.isAttachedToWindow) return false
        val lp = card.layoutParams as? LinearLayout.LayoutParams ?: return false
        val context = page.context
        val edge = (16 * context.resources.displayMetrics.density).toInt()
        val anchor = bar?.takeIf { page.display?.rotation == it.rotation }
        val pageGravity: Int
        var left = 0
        var right = 0
        var top = 0
        if (anchor == null) {
            pageGravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            right = edge
            atLeft = false
        } else {
            // The window may not start at the screen origin, for example below the status bar.
            val origin = IntArray(2).also(page::getLocationOnScreen)
            val pageWidth = page.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
            val pageHeight = page.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
            atLeft = (anchor.left + anchor.right) / 2 - origin[0] < pageWidth / 2
            pageGravity = (if (atLeft) Gravity.LEFT else Gravity.RIGHT) or Gravity.TOP
            if (atLeft) left = max(anchor.left - origin[0], 0) else right = max(origin[0] + pageWidth - anchor.right, 0)
            top = anchor.top - origin[1]
            if (card.height > 0) top = min(top, pageHeight - card.height - edge)
            top = max(top, edge)
        }
        var changed = false
        (page as? LinearLayout)?.let { if (it.gravity != pageGravity) { it.gravity = pageGravity; changed = true } }
        val childGravity = if (anchor == null) Gravity.CENTER_VERTICAL else Gravity.TOP
        // The bar's edge is a physical side of the screen, so relative margins are mapped from it.
        val rtl = card.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val start = if (rtl) right else left
        val end = if (rtl) left else right
        if (lp.gravity != childGravity || lp.marginStart != start || lp.marginEnd != end || lp.topMargin != top) {
            lp.gravity = childGravity
            lp.marginStart = start
            lp.marginEnd = end
            lp.topMargin = top
            lp.bottomMargin = 0
            card.layoutParams = lp
            changed = true
        }
        return changed
    }

    /**
     * The panel window is added anew on every opening and the page view is reused, so the pre-draw
     * listener moves with it: each window has its own tree observer. A frame whose placement changed
     * is skipped, so the card never shows in its previous place.
     */
    private fun followPlacement(page: ViewGroup) {
        val listener = ViewTreeObserver.OnPreDrawListener { cardOf(page)?.let { !place(page, it) } ?: true }
        var observer: ViewTreeObserver? = null
        page.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                observer = v.viewTreeObserver.also { it.addOnPreDrawListener(listener) }
            }

            override fun onViewDetachedFromWindow(v: View) {
                observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
                observer = null
            }
        })
        if (page.isAttachedToWindow) observer = page.viewTreeObserver.also { it.addOnPreDrawListener(listener) }
    }

    fun slide(controller: PanelController, enter: Boolean) {
        controller.viewPager?.clearAnimation()
        val card = cardOf(controller.pageView ?: return) ?: return
        card.clearAnimation()
        val away = if (atLeft) -1f else 1f
        card.startAnimation(AnimationSet(true).apply {
            addAnimation(TranslateAnimation(
                Animation.RELATIVE_TO_SELF, if (enter) away else 0f, Animation.RELATIVE_TO_SELF, if (enter) 0f else away,
                Animation.RELATIVE_TO_SELF, 0f, Animation.RELATIVE_TO_SELF, 0f,
            ))
            addAnimation(if (enter) AlphaAnimation(0f, 1f) else AlphaAnimation(1f, 0f))
            duration = if (enter) 220L else 200L
            interpolator = if (enter) DecelerateInterpolator(1.8f) else AccelerateInterpolator(1.8f)
            fillAfter = !enter
        })
    }

    /** Applies the card's slider size to every seek bar under [view]. */
    fun sizeSliders(view: View) {
        val name = view.javaClass.name
        if (view is SeekBar || name.contains("MiuiVolumeSeekBar") || name.contains("VerticalSeekBar")) {
            val context = view.context
            val half = columnGap(context) / 2
            update(view, sliderWidth(context), sliderHeight(context), half, half, 0, 0)
            SliderLook.apply(view)
            (view.parent as? View)?.let { parent ->
                parent.setPadding(0, 0, 0, 0)
                if (parent.layoutParams is ViewGroup.MarginLayoutParams) update(parent, -2, KEEP, 0, 0, 0, 0)
            }
            return
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) sizeSliders(view.getChildAt(i))
    }

    /** Sizes one column's own views right after the column inflates them. */
    fun sizeColumn(seekBar: View, root: View?) {
        val context = seekBar.context
        val half = columnGap(context) / 2
        update(seekBar, sliderWidth(context), sliderHeight(context), half, half, 0, 0)
        SliderLook.apply(seekBar)
        root ?: return
        // The native column keeps 15dp of bottom padding, which makes the card lopsided.
        root.setPadding(0, 0, 0, 0)
        if (root.layoutParams is ViewGroup.MarginLayoutParams) update(root, -2, -2, 0, 0, 0, 0)
    }

    fun styleDot(dot: View?, selected: Boolean) {
        dot ?: return
        dot.isSelected = selected
        val color = if (selected) Color.WHITE else 0x55FFFFFF
        val tint = ColorStateList.valueOf(color)
        if (dot is ImageView) {
            dot.setColorFilter(color, PorterDuff.Mode.SRC_IN)
            dot.imageTintList = tint
            dot.imageTintMode = PorterDuff.Mode.SRC_IN
        }
        dot.backgroundTintList = tint
    }

    private fun styleIndicator(controller: PanelController, card: ViewGroup, pager: View, count: Int, density: Float) {
        val indicator = controller.indicator ?: run {
            val id = card.resources.getIdentifier("volume_indicator", "id", card.context.packageName)
            if (id == 0) null else card.findViewById<View>(id) as? ViewGroup
        } ?: return
        if (indicator === pager || indicator.isInside(pager)) return
        val dots = controller.dots
        val pages = dots?.size ?: if (count > 3) 2 else 1
        if (pages <= 1) {
            indicator.visibility = View.GONE
            return
        }
        indicator.visibility = View.VISIBLE
        update(indicator, KEEP, KEEP, KEEP, KEEP, (8 * density).toInt(), 0)
        val current = GET_CURRENT_ITEM.callOrNull(pager) as? Int ?: 0
        if (indicator.childCount == 0 && dots != null) {
            dots.forEachIndexed { i, dot ->
                if (dot !is View) return@forEachIndexed
                (dot.parent as? ViewGroup)?.removeView(dot)
                styleDot(dot, i == current)
                indicator.addView(dot)
            }
        } else {
            for (i in 0 until indicator.childCount) styleDot(indicator.getChildAt(i), i == current)
        }
    }

    /** The blur belongs to the window's ViewRootImpl, which is new every time the panel is added. */
    private fun applyBlur(card: View) {
        val night = (card.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val radius = CORNER_DP * card.resources.displayMetrics.density
        try {
            val root = GET_VIEW_ROOT.call(card)
            val done = blurs[card]
            if (root != null && done != null && done.first.get() === root && done.second == night) return
            val blur = root?.let { CREATE_BLUR.call(it) } as? Drawable
            if (blur != null) {
                blur.alpha = 255
                invokeNumeric(blur, "setBlurRadius", 100f)
                if (!invokeNumeric(blur, "setCornerRadius", radius, radius, radius, radius)) {
                    invokeNumeric(blur, "setCornerRadius", radius)
                }
                setColor(blur, (if (night) 0x801E1E22 else 0x77626262).toInt())
                card.background = blur
                card.setWillNotDraw(false)
                blurs[card] = WeakReference(root) to night
                return
            }
        } catch (t: Throwable) {
            HookLog.w("card blur unavailable, using a flat card", t)
        }
        card.background = GradientDrawable().apply {
            setColor(if (night) 0xD01E1E22.toInt() else 0xCC454548.toInt())
            cornerRadius = radius
        }
    }

    /**
     * BackgroundBlurDrawable's setters differ between releases in arity and in int versus float
     * parameters; this picks whichever overload exists.
     */
    private fun invokeNumeric(target: Any, name: String, vararg values: Float): Boolean {
        for (m in target.javaClass.methods) {
            if (m.name != name || m.parameterCount != values.size) continue
            val args = m.parameterTypes.mapIndexed<Class<*>, Any?> { i, t ->
                when (t) {
                    Float::class.javaPrimitiveType -> values[i]
                    Int::class.javaPrimitiveType -> values[i].toInt()
                    else -> null
                }
            }
            if (args.any { it == null }) continue
            m.invoke(target, *args.toTypedArray())
            return true
        }
        return false
    }

    /** A colour must not pass through a float: 32 bits do not fit its 24-bit mantissa. */
    private fun setColor(target: Any, color: Int) {
        val m = target.javaClass.methods.firstOrNull { it.name == "setColor" && it.parameterCount == 1 } ?: return
        when (m.parameterTypes[0]) {
            Int::class.javaPrimitiveType -> m.invoke(target, color)
            Float::class.javaPrimitiveType -> m.invoke(target, color.toFloat())
        }
    }

    /** The card is the page view's first child holding the ViewPager2. */
    private fun cardOf(page: ViewGroup): ViewGroup? {
        if (page.childCount == 0) return null
        for (i in 0 until page.childCount) {
            val child = page.getChildAt(i)
            if (child is ViewGroup && !child.isPager() && findPager(child) != null) return child
        }
        return (page.getChildAt(0) as? ViewGroup)?.takeIf { !it.isPager() } ?: page
    }

    private fun findPager(view: View): View? {
        if (view.isPager()) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findPager(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun View.isPager() = javaClass.name.contains("ViewPager2")

    private fun View.isInside(ancestor: View): Boolean {
        var p = parent
        while (p is View) {
            if (p === ancestor) return true
            p = p.parent
        }
        return false
    }

    private fun MethodRef.callOrNull(target: Any?) = runCatching { call(target) }.getOrNull()

    private fun sliderWidth(c: Context) = (shortEdge(c) * 0.158f).toInt()
    private fun sliderHeight(c: Context) = (longEdge(c) * 0.221f).toInt()
    private fun columnGap(c: Context) = (shortEdge(c) * 0.038f).toInt()
    private fun shortEdge(c: Context) = c.resources.displayMetrics.let { min(it.widthPixels, it.heightPixels) }
    private fun longEdge(c: Context) = c.resources.displayMetrics.let { max(it.widthPixels, it.heightPixels) }

    /**
     * Commits layout params only when a value changes: setLayoutParams always requests a layout,
     * and these sizes are re-applied on every bind, page change and show. [KEEP] leaves a value as
     * it is; margins apply only to MarginLayoutParams.
     */
    private fun update(view: View, width: Int, height: Int, start: Int, end: Int, top: Int, bottom: Int) {
        val lp = view.layoutParams ?: return
        var changed = false
        if (width != KEEP && lp.width != width) { lp.width = width; changed = true }
        if (height != KEEP && lp.height != height) { lp.height = height; changed = true }
        if (lp is ViewGroup.MarginLayoutParams) {
            if (start != KEEP && lp.marginStart != start) { lp.marginStart = start; changed = true }
            if (end != KEEP && lp.marginEnd != end) { lp.marginEnd = end; changed = true }
            if (top != KEEP && lp.topMargin != top) { lp.topMargin = top; changed = true }
            if (bottom != KEEP && lp.bottomMargin != bottom) { lp.bottomMargin = bottom; changed = true }
        }
        if (changed) view.layoutParams = lp
    }
}
