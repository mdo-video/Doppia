package com.mdovideo.doppia

import kotlin.math.abs

/**
 * Stima la temperatura colore della luce a partire dal "neutral point" del sensore,
 * con lo stesso metodo iterativo usato dai file DNG (ColorMatrix1/2 interpolate).
 */
class ColorModel(
    private val cm1: DoubleArray,
    private val cm2: DoubleArray,
    private val cc1: DoubleArray,
    private val cc2: DoubleArray,
    private val k1: Double,
    private val k2: Double
) {

    fun estimate(neutral: DoubleArray): Double? {
        var x = 0.3457
        var y = 0.3585 // parte da D50
        for (iter in 0 until 60) {
            val k = xyToCct(x, y).coerceIn(1500.0, 20000.0)
            val inv = inv3(matFor(k)) ?: return null
            val bx = inv[0] * neutral[0] + inv[1] * neutral[1] + inv[2] * neutral[2]
            val by = inv[3] * neutral[0] + inv[4] * neutral[1] + inv[5] * neutral[2]
            val bz = inv[6] * neutral[0] + inv[7] * neutral[1] + inv[8] * neutral[2]
            val s = bx + by + bz
            if (s <= 0.0 || s.isNaN()) return null
            val nx = bx / s
            val ny = by / s
            val done = abs(nx - x) < 1e-6 && abs(ny - y) < 1e-6
            x = (x + nx) / 2.0
            y = (y + ny) / 2.0
            if (done) break
        }
        val result = xyToCct(x, y)
        return if (result.isNaN()) null else result
    }

    private fun matFor(k: Double): DoubleArray {
        val w: Double = when {
            k2 - k1 < 1.0 || k <= k1 -> 1.0
            k >= k2 -> 0.0
            else -> (1.0 / k - 1.0 / k2) / (1.0 / k1 - 1.0 / k2)
        }
        val cm = DoubleArray(9) { w * cm1[it] + (1.0 - w) * cm2[it] }
        val cc = DoubleArray(9) { w * cc1[it] + (1.0 - w) * cc2[it] }
        return mul3(cc, cm)
    }

    companion object {
        /**
         * Modello generico per camere senza matrici colore: XYZ->sRGB lineare (D65) con una
         * risposta tipica del sensore (rosso e blu meno sensibili del verde). Stima approssimata.
         */
        fun generic(): ColorModel {
            val xyzToSrgb = doubleArrayOf(
                3.2406, -1.5372, -0.4986,
                -0.9689, 1.8758, 0.0415,
                0.0557, -0.2040, 1.0570
            )
            val d = doubleArrayOf(0.50, 1.0, 0.65) // neutral tipico del sensore sotto D65
            val m = DoubleArray(9) { xyzToSrgb[it] * d[it / 3] }
            return ColorModel(m, m, IDENT, IDENT, 2856.0, 6504.0)
        }

        val IDENT = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

        /** Approssimazione di McCamy, valida circa tra 2000 K e 12500 K. */
        fun xyToCct(x: Double, y: Double): Double {
            val n = (x - 0.3320) / (0.1858 - y)
            return 449.0 * n * n * n + 3525.0 * n * n + 6823.3 * n + 5520.33
        }

        fun mul3(a: DoubleArray, b: DoubleArray): DoubleArray {
            val r = DoubleArray(9)
            for (i in 0 until 3) for (j in 0 until 3) {
                var s = 0.0
                for (k in 0 until 3) s += a[i * 3 + k] * b[k * 3 + j]
                r[i * 3 + j] = s
            }
            return r
        }

        fun inv3(m: DoubleArray): DoubleArray? {
            val a = m[0]; val b = m[1]; val c = m[2]
            val d = m[3]; val e = m[4]; val f = m[5]
            val g = m[6]; val h = m[7]; val i = m[8]
            val ca = e * i - f * h
            val cb = -(d * i - f * g)
            val cc = d * h - e * g
            val det = a * ca + b * cb + c * cc
            if (abs(det) < 1e-12) return null
            return doubleArrayOf(
                ca / det, -(b * i - c * h) / det, (b * f - c * e) / det,
                cb / det, (a * i - c * g) / det, -(a * f - c * d) / det,
                cc / det, -(a * h - b * g) / det, (a * e - b * d) / det
            )
        }

        /** Temperatura (K) dei vari illuminanti di riferimento definiti da Android/DNG. */
        fun illuminantToK(code: Int): Double = when (code) {
            1 -> 5500.0   // daylight
            2 -> 4150.0   // fluorescent
            3 -> 2850.0   // tungsten
            4 -> 5500.0   // flash
            9 -> 5500.0   // fine weather
            10 -> 6500.0  // cloudy
            11 -> 7500.0  // shade
            12 -> 6430.0
            13 -> 5000.0
            14 -> 4150.0
            15 -> 3500.0
            16 -> 3000.0
            17 -> 2856.0  // standard A
            18 -> 4874.0  // standard B
            19 -> 6774.0  // standard C
            20 -> 5503.0  // D55
            21 -> 6504.0  // D65
            22 -> 7504.0  // D75
            23 -> 5003.0  // D50
            24 -> 3200.0  // ISO studio tungsten
            else -> 6504.0
        }
    }
}
