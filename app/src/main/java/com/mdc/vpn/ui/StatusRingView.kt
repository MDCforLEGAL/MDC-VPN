package com.mdc.vpn.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.mdc.vpn.R

/**
 * Baglanti durumunu gosteren sade halka.
 *
 * - Bagli: halka tamamen dolar ([setProgress]).
 * - Baglaniyor / kesiliyor: donen kisa yay ([setIndeterminate]).
 * - Bagli degil: yalnizca iz cizgisi.
 *
 * Neon efekt, gradyan veya golge yok; ince cizgi ve mat renkler kullanilir.
 */
class StatusRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val ringStroke = 3f * density
    private val edgeInset = 6f * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringStroke
        color = ContextCompat.getColor(context, R.color.ring_track)
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringStroke
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.accent)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.ring_fill)
    }

    private val arcBounds = RectF()

    private var progress = 0f
    private var indeterminate = false
    private var sweepAngle = 0f
    private var rotation = -90f

    private var rotationAnimator: ValueAnimator? = null
    private var progressAnimator: ValueAnimator? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val radius = (minOf(w, h) / 2f) - (ringStroke / 2f) - edgeInset
        val cx = w / 2f
        val cy = h / 2f
        arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (arcBounds.isEmpty) return

        val cx = arcBounds.centerX()
        val cy = arcBounds.centerY()
        val radius = arcBounds.width() / 2f

        canvas.drawCircle(cx, cy, radius, fillPaint)
        canvas.drawCircle(cx, cy, radius, trackPaint)

        val sweep = if (indeterminate) sweepAngle else 360f * progress
        if (sweep > 1f) {
            val start = if (indeterminate) rotation else -90f
            canvas.drawArc(arcBounds, start, sweep, false, progressPaint)
        }
    }

    /** @param value 0..1 arasinda doluluk orani. */
    fun setProgress(value: Float, animated: Boolean = true) {
        val target = value.coerceIn(0f, 1f)
        progressAnimator?.cancel()
        if (!animated) {
            progress = target
            invalidate()
            return
        }
        progressAnimator = ValueAnimator.ofFloat(progress, target).apply {
            duration = 450L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun setIndeterminate(value: Boolean) {
        if (indeterminate == value) return
        indeterminate = value
        if (value) {
            startRotation()
        } else {
            rotationAnimator?.cancel()
            rotationAnimator = null
            invalidate()
        }
    }

    fun setAccent(@ColorInt color: Int) {
        if (progressPaint.color == color) return
        progressPaint.color = color
        invalidate()
    }

    private fun startRotation() {
        rotationAnimator?.cancel()
        sweepAngle = 60f
        rotationAnimator = ValueAnimator.ofFloat(-90f, 270f).apply {
            duration = 1250L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                rotation = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        rotationAnimator?.cancel()
        rotationAnimator = null
        progressAnimator?.cancel()
        progressAnimator = null
        super.onDetachedFromWindow()
    }
}
