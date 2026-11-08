package com.mdovideo.doppia

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/** Riga che manda a capo i figli quando non c'e' abbastanza larghezza (telefono in orizzontale). */
class FlowRow(ctx: Context, private val gap: Int) : ViewGroup(ctx) {

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val maxW = MeasureSpec.getSize(wSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var lineH = 0
        var usedW = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            c.measure(
                MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            val cw = c.measuredWidth
            val ch = c.measuredHeight
            if (x > 0 && x + cw > maxW) {
                x = 0
                y += lineH + gap
                lineH = 0
            }
            x += cw + gap
            usedW = max(usedW, x - gap)
            lineH = max(lineH, ch)
        }
        val h = y + lineH + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(usedW + paddingLeft + paddingRight, wSpec),
            resolveSize(h, hSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var lineH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            val cw = c.measuredWidth
            val ch = c.measuredHeight
            if (x > 0 && x + cw > maxW) {
                x = 0
                y += lineH + gap
                lineH = 0
            }
            val left = paddingLeft + x
            val top = paddingTop + y
            c.layout(left, top, left + cw, top + ch)
            x += cw + gap
            lineH = max(lineH, ch)
        }
    }
}
