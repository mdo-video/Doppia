package com.mdovideo.doppia

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** Una proprieta' della tabella Sony 0x9209: valore corrente e valori ammessi. */
class Prop(
    val code: Int,
    val type: Int,
    val canSet: Boolean,
    val enabled: Boolean,
    val current: Long,
    val options: List<Long>
) {
    val settable: Boolean get() = canSet && enabled
}

object SonyProps {
    const val SHUTTER = 0xD20D      // uint32: alto = numeratore, basso = denominatore
    const val APERTURE = 0x5007     // uint16: f-number x 100
    const val ISO = 0xD21E          // uint32: 0x00FFFFFF = AUTO, byte alto = flag riduzione rumore
    const val REC_STATE = 0xD21D    // stato registrazione (da verificare sul log)
    const val REC_BUTTON = 0xD2C8   // pulsante REC: 2 = giu', 1 = su

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

    private fun parseWith(d: ByteArray, head: Int): Parsed {
        val out = LinkedHashMap<Int, Prop>()
        if (d.size < head) return Parsed(out, false)
        val bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val n = if (head == 8) bb.getLong(0) else bb.getInt(0).toLong()
        if (n < 0 || n > 5000) return Parsed(out, false)
        var p = head
        for (k in 0 until n.toInt()) {
            if (p + 6 > d.size) return Parsed(out, false)
            val pc = bb.getShort(p).toInt() and 0xffff
            val dt = bb.getShort(p + 2).toInt() and 0xffff
            val getSet = d[p + 4].toInt() and 0xff
            val enabled = d[p + 5].toInt() and 0xff
            val sz = typeSize(dt)
            if (sz < 0) return Parsed(out, false) // stringhe o array: non so proseguire
            p += 6
            if (p + 2 * sz + 1 > d.size) return Parsed(out, false)
            val cur = readVal(bb, p + sz, dt)
            p += 2 * sz
            val form = d[p].toInt() and 0xff
            p += 1
            var setList: List<Long> = emptyList()
            var getList: List<Long> = emptyList()
            when (form) {
                1 -> p += 3 * sz
                2 -> {
                    for (l in 0 until 2) {
                        if (p + 2 > d.size) return Parsed(out, false)
                        val cnt = bb.getShort(p).toInt() and 0xffff
                        p += 2
                        if (p + cnt * sz > d.size) return Parsed(out, false)
                        val list = ArrayList<Long>(cnt)
                        for (j in 0 until cnt) list.add(readVal(bb, p + j * sz, dt))
                        p += cnt * sz
                        if (l == 0) setList = list else getList = list
                    }
                }
            }
            if (p > d.size) return Parsed(out, false)
            out[pc] = Prop(pc, dt, getSet == 1, enabled == 1, cur, if (setList.isNotEmpty()) setList else getList)
        }
        return Parsed(out, p == d.size)
    }

    fun toBytes(v: Long, size: Int): ByteArray {
        val b = ByteArray(size)
        for (i in 0 until size) b[i] = ((v shr (8 * i)) and 0xff).toByte()
        return b
    }

    fun fmtShutter(v: Long): String {
        if (v == 0L) return "BULB"
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
        if (v <= 0L || v == 0xffffL) return "—"
        return if (v >= 1000) "F${v / 100}" else String.format(Locale.ROOT, "F%.1f", v / 100.0)
    }

    fun fmtIso(v: Long): String {
        val base = v and 0xffffffL
        if (base == 0xffffffL) return "AUTO"
        val nr = (v ushr 24) != 0L
        return if (nr) "$base NR" else "$base"
    }

    fun fmt(code: Int, v: Long): String = when (code) {
        SHUTTER -> fmtShutter(v)
        APERTURE -> fmtAperture(v)
        ISO -> fmtIso(v)
        else -> v.toString()
    }
}
