package io.github.lcebot.mivolumeenhanced.hook.systemui

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import io.github.lcebot.mivolumeenhanced.hook.headset.AncMode
import kotlin.math.min

/**
 * Drawn in code because SystemUI cannot load this module's resources. Both icons take their colour
 * from the ImageView tint, like the native ringer icons.
 */
abstract class TintedIcon : Drawable() {
    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var tint: ColorStateList = ColorStateList.valueOf(Color.WHITE)
    private var filter: ColorFilter? = null
    private var alphaValue = 255

    protected fun preparePaint() {
        paint.color = tint.getColorForState(state, tint.defaultColor)
        paint.colorFilter = filter
        paint.alpha = alphaValue
        paint.style = Paint.Style.FILL
    }

    override fun setTintList(tint: ColorStateList?) {
        this.tint = tint ?: ColorStateList.valueOf(Color.WHITE)
        invalidateSelf()
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
        invalidateSelf()
    }

    override fun getAlpha() = alphaValue

    override fun setColorFilter(colorFilter: ColorFilter?) {
        filter = colorFilter
        invalidateSelf()
    }

    override fun isStateful() = tint.isStateful

    override fun onStateChange(state: IntArray): Boolean {
        invalidateSelf()
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * Listening mode glyphs in the style of Apple's control centre: a ring for noise cancelling, dots
 * for transparency, the adaptive sparkles for off. Geometry is in a 90 by 90 viewport.
 */
class AncIcon(val mode: AncMode) : TintedIcon() {
    private val sparkles = Path().apply {
        addSparkle(73.98f, 21.48f, 14.41f)
        addSparkle(61.25f, 8.75f, 6.96f)
    }
    private val arc = Path().apply {
        moveTo(29.5553f, 46.9375f)
        cubicTo(26.6246f, 43.5143f, 24.8545f, 39.0680f, 24.8545f, 34.2083f)
        cubicTo(24.8545f, 23.3928f, 33.6222f, 14.6250f, 44.4378f, 14.6250f)
        cubicTo(55.2534f, 14.6250f, 64.0212f, 23.3928f, 64.0212f, 34.2083f)
        cubicTo(64.0212f, 39.0680f, 62.2510f, 43.5143f, 59.3203f, 46.9375f)
    }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val scale = min(w, h) / VIEWPORT
        val save = canvas.save()
        canvas.translate(bounds.left + (w - VIEWPORT * scale) / 2f, bounds.top + (h - VIEWPORT * scale) / 2f)
        canvas.scale(scale, scale)
        preparePaint()

        val transparency = mode == AncMode.TRANSPARENCY
        // The head and body sit 1.3 units lower in the transparency glyph, as in the original.
        val shift = if (transparency) 1.3225f else 0f
        canvas.drawCircle(44.4366f, 34.2104f + shift, 12.7291f, paint)
        canvas.drawRoundRect(18f, 53.7933f + shift, 70.875f, 75.3350f + shift, 10.7708f, 10.7708f, paint)
        when (mode) {
            AncMode.TRANSPARENCY -> DOTS.forEach { (x, y) -> canvas.drawCircle(x, y, 2.448f, paint) }
            AncMode.OFF -> canvas.drawPath(sparkles, paint)
            AncMode.NOISE_CANCELLING -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 4.89583f
                canvas.drawPath(arc, paint)
            }
        }
        canvas.restoreToCount(save)
    }

    private companion object {
        const val VIEWPORT = 90f
        val DOTS = listOf(
            44.4364f to 15.9479f, 64.2642f to 35.7760f, 24.6076f to 35.7760f,
            30.4168f to 49.7972f, 58.4586f to 21.7552f, 30.4158f to 21.7565f,
            58.4584f to 49.7976f, 51.9802f to 17.4389f, 36.6549f to 17.2886f,
            62.7735f to 43.3180f, 26.1005f to 28.4005f, 62.6795f to 27.9977f,
            26.1986f to 43.5588f,
        )

        /** A four-pointed star with concave sides. */
        fun Path.addSparkle(cx: Float, cy: Float, r: Float) {
            val waist = r * 0.25f
            moveTo(cx, cy - r)
            cubicTo(cx + waist, cy - waist, cx + waist, cy - waist, cx + r, cy)
            cubicTo(cx + waist, cy + waist, cx + waist, cy + waist, cx, cy + r)
            cubicTo(cx - waist, cy + waist, cx - waist, cy + waist, cx - r, cy)
            cubicTo(cx - waist, cy - waist, cx - waist, cy - waist, cx, cy - r)
            close()
        }
    }
}

/** Three equaliser tracks, the native "app volume" glyph, in a 24 by 24 viewport. */
class EqualizerIcon : TintedIcon() {
    private val knob = RectF()

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        preparePaint()
        val sx = w / 24f
        val sy = h / 24f
        for ((x, knobY) in TRACKS) {
            val cx = bounds.left + x * sx
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * sx
            canvas.drawLine(cx, bounds.top + 4.5f * sy, cx, bounds.top + 19.5f * sy, paint)
            paint.style = Paint.Style.FILL
            val cy = bounds.top + knobY * sy
            knob.set(cx - 2.1f * sx, cy - 2.8f * sy, cx + 2.1f * sx, cy + 2.8f * sy)
            canvas.drawRoundRect(knob, 1.8f * sx, 1.8f * sx, paint)
        }
    }

    private companion object {
        val TRACKS = listOf(5.5f to 13f, 12f to 8f, 18.5f to 15f)
    }
}
