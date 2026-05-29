package com.simwheel.ps4.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class SteeringWheelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var angleDeg: Float = 0f
        set(value) { field = value; invalidate() }

    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4fa3e0")
        style = Paint.Style.STROKE
        strokeWidth = 16f
    }

    private val spokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4fa3e0")
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#e94560")
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#aaaacc")
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - rimPaint.strokeWidth - 4f

        canvas.save()
        canvas.rotate(angleDeg, cx, cy)

        // Outer rim
        canvas.drawCircle(cx, cy, r, rimPaint)

        // 3 spokes at 0°, 120°, 240°
        for (deg in intArrayOf(90, 210, 330)) {
            val rad = Math.toRadians(deg.toDouble())
            val sx = cx + r * 0.25f * Math.cos(rad).toFloat()
            val sy = cy + r * 0.25f * Math.sin(rad).toFloat()
            val ex = cx + r * Math.cos(rad).toFloat()
            val ey = cy + r * Math.sin(rad).toFloat()
            canvas.drawLine(sx, sy, ex, ey, spokePaint)
        }

        // Center hub
        canvas.drawCircle(cx, cy, r * 0.22f, hubPaint)

        // Top marker
        canvas.drawLine(cx, cy - r * 0.22f, cx, cy - r * 0.5f, spokePaint)

        canvas.restore()

        // Angle text below wheel
        canvas.drawText("%.1f°".format(angleDeg), cx, cy + r + 28f, textPaint)
    }
}
