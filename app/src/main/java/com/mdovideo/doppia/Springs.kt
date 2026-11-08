package com.mdovideo.doppia

import android.view.MotionEvent
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/** Un numero che "insegue" il valore richiesto con una molla, come nelle animazioni di iOS. */
class SpringFloat(
    initial: Float,
    damp: Float = 0.75f,
    stiff: Float = 200f,
    minVisible: Float = 0.01f,
    private val onChange: (Float) -> Unit
) {
    var value: Float = initial
        private set

    private val prop = object : FloatPropertyCompat<SpringFloat>("v") {
        override fun getValue(o: SpringFloat): Float = o.value
        override fun setValue(o: SpringFloat, v: Float) {
            o.value = v
            o.onChange(v)
        }
    }

    private val anim: SpringAnimation = SpringAnimation(this, prop)

    init {
        val f = SpringForce()
        f.setDampingRatio(damp)
        f.setStiffness(stiff)
        anim.setSpring(f)
        anim.setMinimumVisibleChange(minVisible)
    }

    /** Anima verso il valore (con rimbalzo morbido). */
    fun to(v: Float) {
        anim.animateToFinalPosition(v)
    }

    /** Salta subito al valore, senza animazione. */
    fun snapTo(v: Float) {
        anim.cancel()
        value = v
        onChange(v)
    }
}

object Springs {

    /** Effetto "pressione" stile iOS: il tocco rimpicciolisce la vista, il rilascio la fa tornare con rimbalzo. */
    fun pressable(v: View, pressed: Float = 0.96f, onTouch: ((MotionEvent) -> Unit)? = null) {
        val sx = SpringAnimation(v, DynamicAnimation.SCALE_X)
        val sy = SpringAnimation(v, DynamicAnimation.SCALE_Y)
        val fx = SpringForce(1f)
        fx.setDampingRatio(0.55f)
        fx.setStiffness(400f)
        sx.setSpring(fx)
        sx.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE)
        val fy = SpringForce(1f)
        fy.setDampingRatio(0.55f)
        fy.setStiffness(400f)
        sy.setSpring(fy)
        sy.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE)
        v.setOnTouchListener { _, e ->
            onTouch?.invoke(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx.animateToFinalPosition(pressed)
                    sy.animateToFinalPosition(pressed)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    sx.animateToFinalPosition(1f)
                    sy.animateToFinalPosition(1f)
                }
            }
            false
        }
    }

    /** Piccolo "pop": parte rimpicciolita e torna a dimensione normale con rimbalzo. */
    fun pop(v: View, from: Float = 0.92f) {
        v.scaleX = from
        v.scaleY = from
        val sx = SpringAnimation(v, DynamicAnimation.SCALE_X, 1f)
        val sy = SpringAnimation(v, DynamicAnimation.SCALE_Y, 1f)
        val fx = SpringForce(1f)
        fx.setDampingRatio(0.45f)
        fx.setStiffness(300f)
        sx.setSpring(fx)
        sx.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE)
        val fy = SpringForce(1f)
        fy.setDampingRatio(0.45f)
        fy.setStiffness(300f)
        sy.setSpring(fy)
        sy.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE)
        sx.start()
        sy.start()
    }

    /** Entrata: la vista sale dal basso con dissolvenza e molla, con un ritardo a scelta. */
    fun enter(v: View, delayMs: Long, fromY: Float) {
        v.alpha = 0f
        v.translationY = fromY
        v.postDelayed({
            v.animate().alpha(1f).setDuration(280).start()
            val a = SpringAnimation(v, DynamicAnimation.TRANSLATION_Y, 0f)
            val f = SpringForce(0f)
            f.setDampingRatio(0.8f)
            f.setStiffness(160f)
            a.setSpring(f)
            a.start()
        }, delayMs)
    }
}
