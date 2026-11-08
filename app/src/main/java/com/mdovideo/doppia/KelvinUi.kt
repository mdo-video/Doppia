package com.mdovideo.doppia

import androidx.core.graphics.ColorUtils

const val K_MIN = 2000f
const val K_MAX = 10000f

/** 0 = 2000 K (caldissimo), 1 = 10000 K (freddissimo). */
fun kFrac(k: Float): Float = ((k - K_MIN) / (K_MAX - K_MIN)).coerceIn(0f, 1f)

/** Caldo = arancio, neutro = giallo, freddo = azzurro. */
fun kColor(k: Float): Int {
    val h = 1f - kFrac(k)
    return if (h < 0.5f) ColorUtils.blendARGB(Pal.COLD, Pal.MID, h * 2f)
    else ColorUtils.blendARGB(Pal.MID, Pal.HOT, (h - 0.5f) * 2f)
}
