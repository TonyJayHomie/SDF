package com.simwheel.ps4.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class BarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var value: Float = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    var barColor: Int = Color.parseColor("#00d9a3")

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0f0f1a")
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333355")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f

        canvas.drawRoundRect(0f, 0f, w, h, r, r, bgPaint)
        fillPaint.color = barColor
        canvas.drawRoundRect(0f, 0f, w * value, h, r, r, fillPaint)
        canvas.drawRoundRect(0f, 0f, w, h, r, r, borderPaint)
    }
}
