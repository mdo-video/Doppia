package com.mdovideo.doppia

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/** Barra della scala Kelvin (arancio -> bianco -> azzurro) con un indicatore che scorre. */
class KelvinBar(ctx: Context) : View(ctx) {

    /** Posizione dell'indicatore, da 0 (2000 K) a 1 (10000 K). */
    var frac: Float = 0.5f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    var markerColor: Int = Color.WHITE
        set(v) {
            field = v
            invalidate()
        }

    var showMarker: Boolean = false
        set(v) {
            field = v
            invalidate()
        }

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val barH = dp(12).toFloat()
    private val r = dp(13).toFloat()

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), dp(36))
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        bar.shader = LinearGradient(
            r, 0f, w - r, 0f,
            intArrayOf(
                Color.parseColor("#FF7043"),
                Color.parseColor("#FFC107"),
                Color.parseColor("#FFF8E1"),
                Color.parseColor("#B3E5FC"),
                Color.parseColor("#4FC3F7")
            ),
            floatArrayOf(0f, 0.28f, 0.5f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        rect.set(r, cy - barH / 2f, width - r, cy + barH / 2f)
        c.drawRoundRect(rect, barH / 2f, barH / 2f, bar)
        if (!showMarker) return
        val cx = r + (width - 2 * r) * frac
        c.drawCircle(cx, cy, r, ring)
        dot.color = markerColor
        c.drawCircle(cx, cy, r - dp(4), dot)
    }
}
