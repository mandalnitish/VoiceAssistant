package com.example.voiceassistant.widget

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.min

/**
 * A circular drag-to-set dial, styled like the MirAIe app's temperature /
 * fan-speed rings: a soft background track, a bright progress arc from a
 * fixed start angle up to the current value, a draggable knob at the tip of
 * that arc, and a two-line label in the centre (icon glyph + text).
 *
 * The dial sweeps 270° starting at the bottom-left (135°) and ending at the
 * bottom-right (45°, i.e. 405°), leaving a 90° gap at the bottom — the same
 * layout used by most circular sliders / thermostats.
 *
 * Works two ways:
 *  - Continuous: set [steps] to null and use [minValue]/[maxValue] for a
 *    free-flowing drag (used for temperature).
 *  - Stepped: set [steps] to a list of labels; the dial snaps to the
 *    nearest step on drag/release (used for fan speed).
 */
class RotaryDialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ------------------------------------------------------------
    // CONFIG
    // ------------------------------------------------------------

    var minValue: Float = 16f
    var maxValue: Float = 30f

    /** When non-null, the dial snaps to one of these positions (index-based). */
    var steps: List<String>? = null

    var value: Float = 24f
        set(v) {
            val steps = steps
            field = if (steps != null && steps.size > 1) {
                v.coerceIn(0f, (steps.size - 1).toFloat())
            } else {
                v.coerceIn(minValue, maxValue)
            }
            invalidate()
        }

    var centerIcon: String = "❄"
    var centerLabel: String = "Cool"

    var trackColor: Int = Color.parseColor("#26313F")
    var progressStartColor: Int = Color.parseColor("#4FC3F7")
    var progressEndColor: Int = Color.parseColor("#29B6F6")
    var knobColor: Int = Color.WHITE
    var labelColor: Int = Color.WHITE
    var subLabelColor: Int = Color.parseColor("#8FA3B8")

    /** Called continuously while dragging, and once more on release. */
    var onValueChanged: ((Float, Boolean) -> Unit)? = null

    /** Tap anywhere in the empty centre (not the ring) — used to cycle mode. */
    var onCenterTap: (() -> Unit)? = null

    // ------------------------------------------------------------
    // INTERNAL
    // ------------------------------------------------------------

    private val startAngle = 135f
    private val sweepAngle = 270f

    private val arcRect = RectF()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val knobRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private var strokeWidthPx = 0f
    private var isDragging = false

    private fun fraction(): Float {
        val steps = steps
        return if (steps != null && steps.size > 1) {
            value / (steps.size - 1)
        } else {
            (value - minValue) / (maxValue - minValue)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val size = min(measuredWidth, measuredHeight)
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        strokeWidthPx = w * 0.09f

        trackPaint.strokeWidth = strokeWidthPx
        progressPaint.strokeWidth = strokeWidthPx
        knobRingPaint.strokeWidth = strokeWidthPx * 0.22f

        val inset = strokeWidthPx / 2f + 4f
        arcRect.set(inset, inset, w - inset, h - inset)

        iconPaint.textSize = w * 0.16f
        labelPaint.textSize = w * 0.095f
        labelPaint.isFakeBoldText = true
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)

        trackPaint.color = trackColor
        canvas.drawArc(arcRect, startAngle, sweepAngle, false, trackPaint)

        val progressSweep = sweepAngle * fraction().coerceIn(0f, 1f)

        progressPaint.shader = android.graphics.SweepGradient(
            width / 2f, height / 2f,
            intArrayOf(progressStartColor, progressEndColor, progressEndColor),
            floatArrayOf(0f, progressSweep / 360f, 1f)
        )
        canvas.drawArc(arcRect, startAngle, progressSweep, false, progressPaint)

        // Knob at the tip of the progress arc.
        val knobAngleDeg = startAngle + progressSweep
        val knobAngleRad = Math.toRadians(knobAngleDeg.toDouble())
        val radius = arcRect.width() / 2f
        val cx = width / 2f + radius * kotlin.math.cos(knobAngleRad).toFloat()
        val cy = height / 2f + radius * kotlin.math.sin(knobAngleRad).toFloat()

        knobPaint.color = knobColor
        canvas.drawCircle(cx, cy, strokeWidthPx * 0.62f, knobPaint)
        knobRingPaint.color = progressEndColor
        canvas.drawCircle(cx, cy, strokeWidthPx * 0.62f, knobRingPaint)

        // Centre icon + label.
        iconPaint.color = labelColor
        canvas.drawText(
            centerIcon,
            width / 2f,
            height / 2f - height * 0.02f,
            iconPaint
        )

        labelPaint.color = subLabelColor
        canvas.drawText(
            centerLabel,
            width / 2f,
            height / 2f + height * 0.16f,
            labelPaint
        )
    }

    /** True once a touch-down has been confirmed to land on the ring itself. */
    private var isOnRing = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {

            MotionEvent.ACTION_DOWN -> {
                isOnRing = isTouchOnRing(event.x, event.y)

                if (isOnRing) {
                    isDragging = true
                    updateFromTouch(event.x, event.y, final = false)
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    updateFromTouch(event.x, event.y, final = false)
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    updateFromTouch(event.x, event.y, final = true)
                    isDragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                } else if (!isOnRing && isTouchInsideCenter(event.x, event.y)) {
                    performClick()
                    onCenterTap?.invoke()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun isTouchOnRing(x: Float, y: Float): Boolean {
        val dx = x - width / 2f
        val dy = y - height / 2f
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        val ringRadius = arcRect.width() / 2f
        return dist > ringRadius - strokeWidthPx && dist < ringRadius + strokeWidthPx
    }

    private fun isTouchInsideCenter(x: Float, y: Float): Boolean {
        val dx = x - width / 2f
        val dy = y - height / 2f
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        return dist < arcRect.width() / 2f - strokeWidthPx
    }

    private fun updateFromTouch(x: Float, y: Float, final: Boolean) {
        val cx = width / 2f
        val cy = height / 2f

        var angle = Math.toDegrees(
            atan2((y - cy).toDouble(), (x - cx).toDouble())
        ).toFloat()

        if (angle < 0f) angle += 360f

        // Normalize relative to startAngle so the 90° "gap" at the bottom
        // never gets treated as being mid-ring.
        var relative = angle - startAngle
        if (relative < 0f) relative += 360f

        relative = relative.coerceIn(0f, sweepAngle)

        val frac = relative / sweepAngle

        val stepsList = steps
        value = if (stepsList != null && stepsList.size > 1) {
            val rawIndex = frac * (stepsList.size - 1)
            if (final) Math.round(rawIndex).toFloat() else rawIndex
        } else {
            minValue + frac * (maxValue - minValue)
        }

        onValueChanged?.invoke(value, final)
    }

    /** Convenience for stepped dials: current step label. */
    fun currentStepLabel(): String? = steps?.getOrNull(Math.round(value))
}
