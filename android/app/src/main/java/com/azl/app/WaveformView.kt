package com.azl.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.max

/** Draws the real loudness of a track as rounded bars; bars before [progress] use the accent colour. */
class WaveformView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var peaks: FloatArray? = null
        set(v) { field = v; invalidate() }

    /** 0..1 playback position */
    var progress: Float = 1f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    var activeColor = ContextCompat.getColor(ctx, R.color.accent)
    private val offColor = ContextCompat.getColor(ctx, R.color.wave_off)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val d = resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        val p = peaks ?: return
        if (p.isEmpty() || width == 0) return
        val bar = 3 * d
        val gap = 2 * d
        val n = max(1, ((width + gap) / (bar + gap)).toInt())
        val h = height.toFloat()
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        for (i in 0 until n) {
            // max of the peaks that fall into this bar
            val from = i * p.size / n
            val to = max(from + 1, (i + 1) * p.size / n)
            var v = 0f
            for (k in from until minOf(to, p.size)) v = max(v, p[k])
            val bh = max(3 * d, v * h)
            val x = if (rtl) width - (i + 1) * (bar + gap) + gap else i * (bar + gap)
            rect.set(x, (h - bh) / 2, x + bar, (h + bh) / 2)
            paint.color = if ((i + 0.5f) / n <= progress) activeColor else offColor
            c.drawRoundRect(rect, bar / 2, bar / 2, paint)
        }
    }
}
