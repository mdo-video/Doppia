package com.mdovideo.doppia

import android.content.SharedPreferences
import android.graphics.Bitmap

/** Risultato dell'analisi di un fotogramma: istogramma, esposizione media e maschere per zebra / peaking. */
class Analysis(val hist: IntArray?, val ev: Float, val zebra: Bitmap?, val peaking: Bitmap?)

/** Strumenti del monitor (come Monitor+): impostazioni condivise dalle due camere. */
object Monitor {
    @Volatile var zebra = false
    @Volatile var zebraLevel = 95        // percento (IRE)
    @Volatile var peaking = false
    @Volatile var peakColor = 0xFFFF1744.toInt()
    @Volatile var grid = false
    @Volatile var histogram = true
    @Volatile var meter = true

    fun load(p: SharedPreferences) {
        zebra = p.getBoolean("mon_zebra", false)
        zebraLevel = p.getInt("mon_zlevel", 95)
        peaking = p.getBoolean("mon_peak", false)
        peakColor = p.getInt("mon_peakcolor", 0xFFFF1744.toInt())
        grid = p.getBoolean("mon_grid", false)
        histogram = p.getBoolean("mon_hist", true)
        meter = p.getBoolean("mon_meter", true)
    }

    fun save(p: SharedPreferences) {
        p.edit()
            .putBoolean("mon_zebra", zebra).putInt("mon_zlevel", zebraLevel)
            .putBoolean("mon_peak", peaking).putInt("mon_peakcolor", peakColor)
            .putBoolean("mon_grid", grid).putBoolean("mon_hist", histogram).putBoolean("mon_meter", meter)
            .apply()
    }

    private val lin = FloatArray(256) { Math.pow(it / 255.0, 2.2).toFloat() }

    /** Va chiamata fuori dal thread principale. Ritorna null se nessuno strumento e' acceso. */
    fun analyze(src: Bitmap): Analysis? = try { analyzeInner(src) } catch (_: Throwable) { null }

    private fun analyzeInner(src: Bitmap): Analysis? {
        val z = zebra
        val pk = peaking
        val wantHist = histogram
        val wantMeter = meter
        if (!z && !pk && !wantHist && !wantMeter) return null

        val w = 384
        val h = maxOf(2, (src.height.toLong() * w / src.width).toInt())
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== src) small.recycle()

        val y = IntArray(w * h)
        val hist = IntArray(64)
        var sum = 0.0
        for (i in px.indices) {
            val c = px[i]
            val lum = (77 * ((c shr 16) and 0xff) + 150 * ((c shr 8) and 0xff) + 29 * (c and 0xff)) shr 8
            y[i] = lum
            hist[lum shr 2]++
            sum += lin[lum]
        }
        val mean = sum / px.size
        val ev = (Math.log(Math.max(mean, 1e-4) / 0.18) / Math.log(2.0)).toFloat().coerceIn(-5f, 5f)

        var zb: Bitmap? = null
        if (z) {
            val thr = (zebraLevel * 255 + 50) / 100
            val out = IntArray(w * h)
            for (yy in 0 until h) {
                for (xx in 0 until w) {
                    val i = yy * w + xx
                    if (y[i] >= thr) {
                        out[i] = if (((xx + yy) and 7) < 4) 0xE6FFFFFF.toInt() else 0x66000000
                    }
                }
            }
            zb = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        }

        var pb: Bitmap? = null
        if (pk) {
            val col = peakColor
            val out = IntArray(w * h)
            for (yy in 0 until h - 1) {
                for (xx in 0 until w - 1) {
                    val i = yy * w + xx
                    val g = Math.abs(y[i] - y[i + 1]) + Math.abs(y[i] - y[i + w])
                    if (g > 70) out[i] = col
                }
            }
            pb = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        }
        return Analysis(if (wantHist) hist else null, ev, zb, pb)
    }
}
