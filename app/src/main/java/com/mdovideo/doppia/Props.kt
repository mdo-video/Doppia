package com.mdovideo.doppia

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** Una proprieta' della tabella Sony 0x9209: valore corrente e valori ammessi. */
class Prop(
    val code: Int,
    val type: Int,
    val getSet: Int,
    val enabledFlag: Int,
    val current: Long,
    val options: List<Long>
) {
    /** Regola della guida Sony: bit 0x80 = controllo sempre azionabile, altrimenti conta "IsEnabled" = 1. */
    val settable: Boolean get() = (getSet and 0x80) != 0 || enabledFlag == 1
}

object SonyProps {
    const val SHUTTER = 0xD20D      // uint32: alto = numeratore, basso = denominatore
    const val APERTURE = 0x5007     // uint16: f-number x 100
    const val ISO = 0xD21E          // uint32: 0x00FFFFFF = AUTO, byte alto = flag riduzione rumore
    const val REC_STATE = 0xD21D    // stato registrazione filmato: 0 = fermo
    const val REC_BUTTON = 0xD2C8   // pulsante REC: 2 = giu', 1 = su
    const val EXP_COMP = 0x5010     // int16: EV x 1000
    const val WB = 0x5005
    const val EXP_MODE = 0x500E     // 1 M, 2 P, 3 A, 4 S
    const val METERING = 0x500B
    const val BATTERY_STD = 0x5001  // livello batteria standard PTP (0-100)
    const val BATTERY = 0xD20E      // livello batteria Sony (da verificare nel log)

    // Memoria residua (codici Sony da verificare nel log: se la camera non li espone si vede "—")
    val MEDIA_TIME = intArrayOf(0xD249, 0xD24B)   // tempo residuo filmato, slot 1 e 2 (secondi)
    val MEDIA_SHOTS = intArrayOf(0xD248, 0xD24A)  // foto residue, slot 1 e 2

    /** Tempo residuo in secondi per slot, oppure null se non leggibile. */
    fun mediaSeconds(m: Map<Int, Prop>): List<Long?> = MEDIA_TIME.map { c ->
        val v = m[c]?.current
        if (v == null || v < 0 || v > 0xFFFFFL) null else v
    }

    fun fmtRemain(sec: Long): String {
        val h = sec / 3600
        val mi = (sec % 3600) / 60
        return if (h > 0) "${h}h ${mi}m" else "${mi}m"
    }

    /** Livello batteria in percentuale approssimata, oppure null se la camera non lo espone in modo riconoscibile. */
    fun batteryPercent(m: Map<Int, Prop>): Int? {
        val std = m[BATTERY_STD]
        if (std != null && std.current in 0L..100L) return std.current.toInt()
        val son = m[BATTERY] ?: return null
        return when (son.current.toInt()) {
            0 -> 0
            1 -> 5
            2 -> 25
            3 -> 50
            4 -> 75
            5 -> 100
            6 -> 33
            7 -> 66
            8 -> 100
            else -> null
        }
    }

    fun typeSize(t: Int): Int = when (t) {
        1, 2 -> 1; 3, 4 -> 2; 5, 6 -> 4; 7, 8 -> 8; 9, 10 -> 16; else -> -1
    }

    private fun readVal(bb: ByteBuffer, pos: Int, type: Int): Long = when (type) {
        1 -> bb.get(pos).toLong()
        2 -> (bb.get(pos).toInt() and 0xff).toLong()
        3 -> bb.getShort(pos).toLong()
        4 -> (bb.getShort(pos).toInt() and 0xffff).toLong()
        5 -> bb.getInt(pos).toLong()
        6 -> bb.getInt(pos).toLong() and 0xffffffffL
        7, 8 -> bb.getLong(pos)
        else -> 0L
    }

    /** Risultato dell'analisi: le proprieta' lette e se la tabella e' stata consumata per intero. */
    class Parsed(val props: Map<Int, Prop>, val complete: Boolean)

    fun parse(d: ByteArray): Parsed {
        var best = Parsed(emptyMap(), false)
        for (head in intArrayOf(8, 4)) {
            val r = parseWith(d, head)
            if (r.complete) return r
            if (r.props.size > best.props.size) best = r
        }
        return best
    }

    /** Una sola riga (risposta di 0x9203). */
    fun parseOne(d: ByteArray): Prop? {
        if (d.size < 8) return null
        val bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        return entry(d, bb, 0)?.first
    }

    private fun parseWith(d: ByteArray, head: Int): Parsed {
        val out = LinkedHashMap<Int, Prop>()
        if (d.size < head) return Parsed(out, false)
        val bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val n = if (head == 8) bb.getLong(0) else bb.getInt(0).toLong()
        if (n < 0 || n > 5000) return Parsed(out, false)
        var p = head
        for (k in 0 until n.toInt()) {
            val e = entry(d, bb, p) ?: return Parsed(out, false)
            out[e.first.code] = e.first
            p = e.second
        }
        return Parsed(out, p == d.size)
    }

    /** Legge una riga della tabella; gestisce numeri, stringhe e array. Ritorna null se non riesce a proseguire. */
    private fun entry(d: ByteArray, bb: ByteBuffer, start: Int): Pair<Prop, Int>? {
        var p = start
        if (p + 6 > d.size) return null
        val pc = bb.getShort(p).toInt() and 0xffff
        val dt = bb.getShort(p + 2).toInt() and 0xffff
        val getSet = d[p + 4].toInt() and 0xff
        val enabled = d[p + 5].toInt() and 0xff
        p += 6

        val isStr = dt == 0xFFFF
        val isArr = dt in 0x4001..0x400A
        var cur = 0L
        var setList: List<Long> = emptyList()
        var getList: List<Long> = emptyList()

        if (isStr) {
            for (k in 0 until 2) {
                if (p + 1 > d.size) return null
                val cnt = d[p].toInt() and 0xff
                p += 1 + 2 * cnt
                if (p > d.size) return null
            }
            if (p + 1 > d.size) return null
            if ((d[p].toInt() and 0xff) != 0) return null
            p += 1
        } else if (isArr) {
            val esz = typeSize(dt - 0x4000)
            if (esz < 0) return null
            for (k in 0 until 2) {
                if (p + 4 > d.size) return null
                val cnt = bb.getInt(p)
                if (cnt < 0 || cnt > 100000) return null
                p += 4 + cnt * esz
                if (p > d.size) return null
            }
            if (p + 1 > d.size) return null
            if ((d[p].toInt() and 0xff) != 0) return null
            p += 1
        } else {
            val sz = typeSize(dt)
            if (sz < 0) return null
            if (p + 2 * sz + 1 > d.size) return null
            cur = readVal(bb, p + sz, dt)
            p += 2 * sz
            val form = d[p].toInt() and 0xff
            p += 1
            when (form) {
                1 -> p += 3 * sz
                2 -> {
                    for (l in 0 until 2) {
                        if (p + 2 > d.size) return null
                        val cnt = bb.getShort(p).toInt() and 0xffff
                        p += 2
                        if (p + cnt * sz > d.size) return null
                        val list = ArrayList<Long>(cnt)
                        for (j in 0 until cnt) list.add(readVal(bb, p + j * sz, dt))
                        p += cnt * sz
                        if (l == 0) setList = list else getList = list
                    }
                }
            }
            if (p > d.size) return null
        }
        return Pair(Prop(pc, dt, getSet, enabled, cur, if (setList.isNotEmpty()) setList else getList), p)
    }

    fun toBytes(v: Long, size: Int): ByteArray {
        val b = ByteArray(size)
        for (i in 0 until size) b[i] = ((v shr (8 * i)) and 0xff).toByte()
        return b
    }

    fun fmtShutter(v: Long): String {
        if (v == 0L) return "BULB"
        if (v == 0xFFFFFFFFL) return "—"
        val n = ((v shr 16) and 0xffff).toInt()
        val dn = (v and 0xffff).toInt()
        if (n == 0 || dn == 0) return "—"
        val sec = n.toDouble() / dn
        return if (sec >= 0.3) {
            if (Math.abs(sec - Math.round(sec)) < 0.05) "${Math.round(sec)}\""
            else String.format(Locale.ROOT, "%.1f\"", sec)
        } else {
            "1/${Math.round(dn.toDouble() / n)}"
        }
    }

    fun fmtAperture(v: Long): String {
        if (v <= 0L || v >= 0xFFFDL) return "—"
        return if (v >= 1000) "F${v / 100}" else String.format(Locale.ROOT, "F%.1f", v / 100.0)
    }

    fun fmtIso(v: Long): String {
        val base = v and 0xffffffL
        if (base == 0xffffffL) return "AUTO"
        val nr = (v ushr 24) != 0L
        return if (nr) "$base NR" else "$base"
    }

    fun fmtEv(v: Long): String {
        val ev = v / 1000.0
        return if (Math.abs(ev) < 0.05) "0.0" else String.format(Locale.ROOT, "%+.1f", ev)
    }

    private fun hex(v: Long) = "0x" + java.lang.Long.toHexString(v).uppercase(Locale.ROOT)

    fun fmt(code: Int, v: Long): String = when (code) {
        SHUTTER -> fmtShutter(v)
        APERTURE -> fmtAperture(v)
        ISO -> fmtIso(v)
        EXP_COMP -> fmtEv(v)
        EXP_MODE -> when (v) { 1L -> "M"; 2L -> "P"; 3L -> "A"; 4L -> "S"; else -> hex(v) }
        METERING -> when (v) {
            0x8001L -> "Multi"; 0x8002L -> "Centro"; 0x8003L -> "Media schermo"
            0x8004L -> "Spot standard"; 0x8005L -> "Spot largo"; 0x8006L -> "Alte luci"
            else -> hex(v)
        }
        WB -> when (v) {
            2L -> "Auto"; 4L -> "Luce diurna"; 5L -> "Fluorescente"; 6L -> "Incandescenza"; 7L -> "Flash"
            else -> hex(v)
        }
        else -> hex(v)
    }
}
