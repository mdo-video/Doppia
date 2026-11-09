package com.mdovideo.doppia

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.HorizontalScrollView

/**
 * Elenco di valori che si fa scorrere col dito: il valore scelto e' sempre quello al centro (evidenziato).
 * Alla fine del movimento si ferma da solo sul valore piu' vicino al centro. E' lui stesso lo scorrimento
 * (non sta dentro a un altro contenitore), quindi funziona uguale anche con il riquadro a tutto schermo.
 */
class Wheel(ctx: Context) : HorizontalScrollView(ctx) {

    /** Vero = modo "ruota" (centro evidenziato e aggancio); falso = elenco normale. */
    var wheelMode = false
        set(v) {
            field = v
            invalidate()
        }

    /** Indice dell'elemento al centro; settled = true quando il dito e' staccato e la ruota si e' fermata. */
    var onCenter: ((Int, Boolean) -> Unit)? = null

    var touching = false
        private set

    private var lastIdx = -1
    private var pending = -1
    private var silent = false
    private val settleRun = Runnable { settleNow() }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0x33, 0xFF, 0xC1, 0x07) }
    private val rect = RectF()

    private fun row(): ViewGroup? = getChildAt(0) as? ViewGroup

    /** Indice dell'elemento piu' vicino al centro dello schermo della ruota, -1 se non ce ne sono. */
    fun centerIndex(): Int {
        val r = row() ?: return -1
        val mid = scrollX + width / 2
        var best = -1
        var bestD = Int.MAX_VALUE
        for (i in 0 until r.childCount) {
            val c = r.getChildAt(i)
            val d = Math.abs((c.left + c.right) / 2 - mid)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    private fun targetFor(i: Int): Int {
        val c = row()?.getChildAt(i) ?: return scrollX
        return (c.left + c.right) / 2 - width / 2
    }

    /** Mette l'elemento i al centro senza animazione e senza avvisare (usata per seguire la camera). */
    fun centerOn(i: Int) {
        if (i < 0) return
        val r = row()
        if (width == 0 || r == null || r.width == 0 || r.childCount <= i || isLayoutRequested) {
            pending = i
            return
        }
        pending = -1
        silent = true
        removeCallbacks(settleRun)
        scrollTo(targetFor(i), 0)
        lastIdx = i
        silent = false
        invalidate()
    }

    /** Riparte da zero quando cambia l'elenco. */
    fun reset() {
        removeCallbacks(settleRun)
        lastIdx = -1
        pending = -1
        touching = false
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (pending >= 0) post { if (pending >= 0) centerOn(pending) }
    }

    override fun onScrollChanged(l: Int, t: Int, ol: Int, ot: Int) {
        super.onScrollChanged(l, t, ol, ot)
        if (!wheelMode || silent) return
        val i = centerIndex()
        if (i != lastIdx) {
            lastIdx = i
            invalidate()
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onCenter?.invoke(i, false)
        }
        removeCallbacks(settleRun)
        if (!touching) postDelayed(settleRun, 90L)
    }

    /**
     * Segue il dito da dispatchTouchEvent: se il tocco parte su un valore (che e' cliccabile) il primo
     * ACTION_DOWN non arriva a onTouchEvent, e la ruota si aggancerebbe mentre si sta ancora scorrendo.
     */
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (wheelMode) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touching = true
                    removeCallbacks(settleRun)
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touching = false
                    removeCallbacks(settleRun)
                    postDelayed(settleRun, 120L)
                }
            }
        }
        return super.dispatchTouchEvent(e)
    }

    private fun settleNow() {
        if (touching) return
        val i = centerIndex()
        if (i < 0) return
        val target = targetFor(i)
        if (Math.abs(target - scrollX) > 1) {
            smoothScrollTo(target, 0)
        } else {
            lastIdx = i
            onCenter?.invoke(i, true)
        }
    }

    /** Il valore al centro sta su una fascia evidenziata, dietro ai numeri. */
    override fun dispatchDraw(canvas: Canvas) {
        if (wheelMode) {
            val i = centerIndex()
            val c = row()?.getChildAt(i)
            if (c != null) {
                val half = c.width / 2f
                val cx = scrollX + width / 2f   // il canvas e' nelle coordinate del contenuto: va sommato lo scorrimento
                rect.set(cx - half, dp(4).toFloat(), cx + half, height - dp(4).toFloat())
                canvas.drawRoundRect(rect, dp(10).toFloat(), dp(10).toFloat(), mark)
            }
        }
        super.dispatchDraw(canvas)
    }
}

