package com.mdovideo.doppia

import android.content.SharedPreferences
import android.graphics.Bitmap

/** Risultato dell'analisi di un fotogramma: istogramma, esposizione media e maschere per zebra / peaking. */
class Analysis(
    val hist: IntArray?, val ev: Float, val zebra: Bitmap?, val peaking: Bitmap?,
    /** Media di rosso, verde e blu del fotogramma (0-255), per dominante colore e confronto A/B. */
    val mr: Float = 0f, val mg: Float = 0f, val mb: Float = 0f, val hasColor: Boolean = false
) {
    /** Tinta verde/magenta in punti percentuali: positivo = verde, negativo = magenta. */
    val tint: Float get() = if (!hasColor) 0f else 100f * (mg - (mr + mb) / 2f) / maxOf(1f, (mr + mg + mb) / 3f)
    /** Caldo/freddo in punti percentuali: positivo = piu' caldo (rosso), negativo = piu' freddo (blu). */
    val warm: Float get() = if (!hasColor) 0f else 100f * (mr - mb) / maxOf(1f, (mr + mg + mb) / 3f)
}

/** Numero massimo di camere che Doppia puo' mostrare insieme. */
const val MAX_CAMS = 4

/** Guide di inquadratura: rapporti, nomi e colori (ritagli centrati sull'immagine). */
object Guides {
    val names = arrayOf("9:16", "4:5", "1:1", "16:9", "2.39:1")
    val ratios = floatArrayOf(9f / 16f, 4f / 5f, 1f, 16f / 9f, 2.39f)
    val colors = intArrayOf(0xFFFFC107.toInt(), 0xFF4FC3F7.toInt(), 0xFF81C784.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF7043.toInt())

    /** Posizione di ogni guida per camera: -1 = tutto a sinistra (o in alto), 0 = centro, 1 = tutto a destra (o in basso). */
    val pos = Array(MAX_CAMS) { FloatArray(5) }

    /** Una guida piu' larga dell'immagine (es. 2.39:1 su 16:9) si muove in verticale, le altre in orizzontale. */
    fun axisVertical(k: Int, aspect: Float): Boolean {
        val a = ratios[k]
        val ih = 1f / aspect
        var gw = 1f
        var gh = 1f / a
        if (gh > ih) { gh = ih; gw = ih * a }
        return (ih - gh) / 2f > (1f - gw) / 2f
    }

    fun describe(p: Float, vertical: Boolean): String {
        val pct = Math.round(Math.abs(p) * 100f)
        if (pct == 0) return "centro"
        return if (vertical) (if (p < 0) "$pct% in alto" else "$pct% in basso")
        else (if (p < 0) "$pct% a sinistra" else "$pct% a destra")
    }

    /** Fotografia delle guide accese di una camera: (json, testo). */
    fun snapshot(cam: Int, mask: Int, aspect: Float): Pair<String, String> {
        val arr = org.json.JSONArray()
        val parts = ArrayList<String>()
        for (k in names.indices) {
            if ((mask and (1 shl k)) == 0) continue
            val v = axisVertical(k, aspect)
            val p = pos[cam.coerceIn(0, MAX_CAMS - 1)][k]
            val o = org.json.JSONObject()
            o.put("guide", names[k])
            o.put("pos", Math.round(p * 1000f) / 1000f.toDouble())
            o.put("axis", if (v) "y" else "x")
            o.put("text", describe(p, v))
            arr.put(o)
            parts.add(names[k] + " " + describe(p, v))
        }
        return Pair(arr.toString(), parts.joinToString(" | "))
    }

    fun load(p: SharedPreferences) {
        for (c in 0 until MAX_CAMS) for (k in names.indices) pos[c][k] = p.getFloat("gpos_${c}_$k", 0f).coerceIn(-1f, 1f)
    }

    fun save(e: SharedPreferences.Editor) {
        for (c in 0 until MAX_CAMS) for (k in names.indices) e.putFloat("gpos_${c}_$k", pos[c][k])
    }
}

/** Strumenti del monitor (come Monitor+): impostazioni condivise dalle due camere. */
object Monitor {
    @Volatile var zebra = false
    @Volatile var zebraLevel = 95        // percento (IRE)
    @Volatile var peaking = false
    @Volatile var peakColor = 0xFFFF1744.toInt()
    @Volatile var grid = false
    @Volatile var histogram = true
    @Volatile var meter = true
    @Volatile var cast = true            // dominante colore e confronto A/B
    @Volatile var guides = 0             // bit k acceso = guida Guides.names[k] visibile
    @Volatile var safe = false           // zona sicura 9:16
    @Volatile var guideLock = true       // guide bloccate (non si spostano)
    @Volatile var guidesShow = true      // interruttore rapido: mostra/nasconde guide e zona sicura

    /** Guide effettivamente visibili (rispettano l'interruttore rapido). */
    val guidesOn: Int get() = if (guidesShow) guides else 0
    val safeOn: Boolean get() = guidesShow && safe

    fun load(p: SharedPreferences) {
        zebra = p.getBoolean("mon_zebra", false)
        zebraLevel = p.getInt("mon_zlevel", 95)
        peaking = p.getBoolean("mon_peak", false)
        peakColor = p.getInt("mon_peakcolor", 0xFFFF1744.toInt())
        grid = p.getBoolean("mon_grid", false)
        histogram = p.getBoolean("mon_hist", true)
        meter = p.getBoolean("mon_meter", true)
        cast = p.getBoolean("mon_cast", true)
        guides = p.getInt("mon_guides", 0)
        safe = p.getBoolean("mon_safe", false)
        guideLock = p.getBoolean("mon_glock", true)
        guidesShow = p.getBoolean("mon_gshow", true)
        Guides.load(p)
    }

    fun save(p: SharedPreferences) {
        p.edit()
            .putBoolean("mon_zebra", zebra).putInt("mon_zlevel", zebraLevel)
            .putBoolean("mon_peak", peaking).putInt("mon_peakcolor", peakColor)
            .putBoolean("mon_grid", grid).putBoolean("mon_hist", histogram).putBoolean("mon_meter", meter).putBoolean("mon_cast", cast)
            .putInt("mon_guides", guides).putBoolean("mon_safe", safe)
            .putBoolean("mon_glock", guideLock).putBoolean("mon_gshow", guidesShow)
            .also { Guides.save(it) }
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
        val wantCast = cast
        if (!z && !pk && !wantHist && !wantMeter && !wantCast) return null

        val w = 384
        val h = maxOf(2, (src.height.toLong() * w / src.width).toInt())
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== src) small.recycle()

        val y = IntArray(w * h)
        val hist = IntArray(64)
        var sum = 0.0
        // medie colore: solo i pixel ne' bruciati ne' neri; se sono troppo pochi si usano tutti
        var sr = 0L; var sg = 0L; var sb = 0L; var sn = 0
        var ar = 0L; var ag = 0L; var ab = 0L
        for (i in px.indices) {
            val c = px[i]
            val r8 = (c shr 16) and 0xff
            val g8 = (c shr 8) and 0xff
            val b8 = c and 0xff
            val lum = (77 * r8 + 150 * g8 + 29 * b8) shr 8
            y[i] = lum
            hist[lum shr 2]++
            sum += lin[lum]
            ar += r8; ag += g8; ab += b8
            if (lum in 16..240) { sr += r8; sg += g8; sb += b8; sn++ }
        }
        val useAll = sn < px.size / 100
        val cn = if (useAll) px.size else sn
        val cr = (if (useAll) ar else sr).toFloat() / cn
        val cg = (if (useAll) ag else sg).toFloat() / cn
        val cb = (if (useAll) ab else sb).toFloat() / cn
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
        return Analysis(if (wantHist) hist else null, ev, zb, pb, cr, cg, cb, wantCast)
    }
}
