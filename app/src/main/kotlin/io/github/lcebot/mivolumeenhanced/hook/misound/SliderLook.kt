package io.github.lcebot.mivolumeenhanced.hook.misound

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.RelativeLayout
import android.widget.SeekBar
import io.github.lcebot.mivolumeenhanced.Protocol
import io.github.lcebot.mivolumeenhanced.hook.core.Diagnostics
import io.github.lcebot.mivolumeenhanced.hook.core.HookLog
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Draws MiSound's per-app sliders the way the volume plugin draws its expanded sliders, from the
 * style SystemUI sends with each request to open the panel (see SliderStyle there).
 *
 * The plugin's slider has three layers, and so does this one:
 * - the track: the SeekBar itself, with the plugin's track colour and the column's corner radius;
 * - the fill: a view of the same size over the track in the plugin's fill colour, moved down by the
 *   part of the height that is not filled, inside a container clipped to the track's shape, so the
 *   fill has a flat top and the track's rounded bottom, as in the plugin;
 * - the icon, which stays MiSound's own and is drawn above both.
 *
 * The fill follows the SeekBar through its progress drawable: ProgressBar sets the drawable's level
 * on every change, animated ones included, so the fill moves with key presses, drags and the key
 * animation without a listener of its own. Before SystemUI sent a style the slider keeps MiSound's
 * look.
 */
internal object SliderLook {
    private const val TAG_FILL = "mve:slider-fill"

    private data class Style(val radius: Float, val track: Int, val fill: Int)

    @Volatile private var style: Style? = null

    /** Keeps the latest style; a request without one, such as an older SystemUI side, leaves it. */
    fun update(bundle: Bundle?) {
        bundle ?: return
        if (!bundle.containsKey(Protocol.STYLE_TRACK) || !bundle.containsKey(Protocol.STYLE_FILL)) return
        style = Style(
            bundle.getInt(Protocol.STYLE_RADIUS).toFloat(),
            bundle.getInt(Protocol.STYLE_TRACK),
            bundle.getInt(Protocol.STYLE_FILL),
        )
        val material = if (bundle.getBoolean(Protocol.STYLE_ADVANCED)) "advanced material" else "standard material"
        Diagnostics.ok("misound_slider_style", "from the volume plugin, $material")
    }

    /** Idempotent: re-applied on every bind and resize, it only changes what differs. */
    fun apply(seekBar: View) {
        val bar = seekBar as? SeekBar ?: return
        val s = style ?: return
        try {
            val drawable = bar.progressDrawable as? FillLevel ?: FillLevel(bar).also { bar.progressDrawable = it }
            if (styled[bar] !== s) {
                bar.background = GradientDrawable().apply {
                    setColor(s.track)
                    cornerRadius = s.radius
                }
                rounded(bar, s.radius)
            }
            val fill = fillOf(bar) ?: return
            if (styled[fill] !== s) {
                rounded(fill, s.radius)
                fill.getChildAt(0).background = ColorDrawable(s.fill)
                styled[fill] = s
            }
            styled[bar] = s
            drawable.fill = fill
            drawable.syncFromProgress()
        } catch (t: Throwable) {
            HookLog.w("per-app slider style", t)
        }
    }

    private fun rounded(view: View, radius: Float) {
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) = outline.setRoundRect(0, 0, v.width, v.height, radius)
        }
        view.clipToOutline = true
    }

    /**
     * The fill sits right above the SeekBar in the column, below the icon, with the SeekBar's size
     * and position. It takes no touches, so drags still reach the SeekBar.
     */
    private fun fillOf(bar: SeekBar): FrameLayout? {
        val parent = bar.parent as? ViewGroup ?: return null
        val existing = parent.findViewWithTag<FrameLayout>(TAG_FILL)
        val fill = existing ?: FrameLayout(bar.context).apply {
            tag = TAG_FILL
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(View(context), FrameLayout.LayoutParams(-1, -1))
            // The fill's offset depends on the height, which changes with the card's proportions.
            addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) (bar.progressDrawable as? FillLevel)?.syncFromProgress()
            }
            // A reopened panel reattaches the same column views; place the fill again for the
            // progress they hold now.
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    (bar.progressDrawable as? FillLevel)?.syncFromProgress()
                }

                override fun onViewDetachedFromWindow(v: View) = Unit
            })
        }
        val lp = when (parent) {
            is RelativeLayout -> {
                if (bar.id == View.NO_ID) bar.id = View.generateViewId()
                RelativeLayout.LayoutParams(0, 0).apply {
                    addRule(RelativeLayout.ALIGN_LEFT, bar.id)
                    addRule(RelativeLayout.ALIGN_TOP, bar.id)
                    addRule(RelativeLayout.ALIGN_RIGHT, bar.id)
                    addRule(RelativeLayout.ALIGN_BOTTOM, bar.id)
                }
            }
            is FrameLayout -> {
                val source = bar.layoutParams as? FrameLayout.LayoutParams ?: return null
                FrameLayout.LayoutParams(source)
            }
            else -> return null
        }
        if (existing == null) {
            parent.addView(fill, parent.indexOfChild(bar) + 1, lp)
        } else if (lp is FrameLayout.LayoutParams && !sameFrame(existing.layoutParams, lp)) {
            existing.layoutParams = lp
        }
        return fill
    }

    private fun sameFrame(a: ViewGroup.LayoutParams?, b: FrameLayout.LayoutParams): Boolean {
        val l = a as? FrameLayout.LayoutParams ?: return false
        return l.width == b.width && l.height == b.height && l.gravity == b.gravity &&
            l.marginStart == b.marginStart && l.marginEnd == b.marginEnd && l.topMargin == b.topMargin && l.bottomMargin == b.bottomMargin
    }

    /**
     * Draws nothing: the fill view does the drawing. It only turns the level ProgressBar sets
     * (0 to 10000, following the visual progress) into the fill's offset.
     */
    private class FillLevel(bar: SeekBar) : Drawable() {
        private val bar = WeakReference(bar)
        var fill: FrameLayout? = null

        /**
         * Level changes come from ProgressBar while the value moves, animated steps included, so
         * they are what the fill follows during a drag or a key animation.
         */
        override fun onLevelChange(level: Int): Boolean {
            place(level / 10000f)
            return false
        }

        /**
         * Everywhere else the fill is placed from the SeekBar's value. The level alone is not
         * enough there: ProgressBar sets it before this drawable has its fill view, and a fill
         * created or laid out after that would stay at the empty position until the value moves
         * again, which on a freshly opened panel means the fill is not shown at all.
         */
        fun syncFromProgress() {
            val seekBar = bar.get() ?: return
            val range = seekBar.max - seekBar.min
            place(if (range > 0) (seekBar.progress - seekBar.min).toFloat() / range else 0f)
        }

        private fun place(fraction: Float) {
            val container = fill ?: return
            val inner = container.getChildAt(0) ?: return
            val height = container.height
            if (height == 0) return
            inner.translationY = height * (1f - fraction.coerceIn(0f, 1f))
        }

        override fun draw(canvas: Canvas) = Unit
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSPARENT
    }

    /** Which style each view last received, so a re-apply with the same style changes nothing. */
    private val styled = WeakHashMap<View, Style>()
}
