package com.mdovideo.doppia

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.core.graphics.ColorUtils
import java.net.Inet4Address
import java.net.NetworkInterface

/** Stessa palette di Vicino: dal freddo al caldo. */
object Pal {
    val COLD: Int = Color.parseColor("#4FC3F7")
    val MID: Int = Color.parseColor("#FFC107")
    val HOT: Int = Color.parseColor("#FF7043")
    val BG: Int = Color.parseColor("#0B0F15")
    val SURFACE: Int = Color.parseColor("#12161C")
    val CARD: Int = Color.parseColor("#1A2029")
    val LINE: Int = Color.parseColor("#2A313B")
    val TEXT: Int = Color.parseColor("#E8EDF3")
    val MUTED: Int = Color.parseColor("#94A0B0")
    val OK: Int = Color.parseColor("#81C784")
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun View.dp(v: Int): Int = context.dp(v)

fun heatColor(h: Float): Int {
    val x = h.coerceIn(0f, 1f)
    return if (x < 0.5f) ColorUtils.blendARGB(Pal.COLD, Pal.MID, x * 2f)
    else ColorUtils.blendARGB(Pal.MID, Pal.HOT, (x - 0.5f) * 2f)
}

/** Ritardo del fotogramma in ms -> colore: sotto 150 ms freddo, sopra 600 ms caldo. */
fun lagColor(ms: Int): Int = heatColor((ms - 150f) / 450f)

fun roundBg(color: Int, radiusDp: Int, ctx: Context): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(color)
    g.cornerRadius = ctx.dp(radiusDp).toFloat()
    return g
}

/** Riga raggruppata stile iOS: angoli grandi sulla prima/ultima, piccoli in mezzo. */
fun Context.rowBg(first: Boolean, last: Boolean, color: Int): GradientDrawable {
    val big = dp(24).toFloat()
    val small = dp(6).toFloat()
    val t = if (first) big else small
    val b = if (last) big else small
    val g = GradientDrawable()
    g.setColor(color)
    g.cornerRadii = floatArrayOf(t, t, t, t, b, b, b, b)
    return g
}

/** Indirizzi IPv4 locali del tablet (Wi-Fi o hotspot), per sapere quale rete cercare. */
fun localIpv4(): List<String> {
    val out = ArrayList<String>()
    try {
        val ifs = NetworkInterface.getNetworkInterfaces() ?: return out
        for (nif in ifs) {
            if (!nif.isUp || nif.isLoopback) continue
            val name = nif.name.lowercase()
            if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("dummy")) continue
            for (a in nif.inetAddresses) {
                if (a is Inet4Address && a.isSiteLocalAddress) a.hostAddress?.let { out.add(it) }
            }
        }
    } catch (_: Throwable) {
    }
    return out
}
