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
    const val KELVIN = 0xD20F       // uint16: temperatura colore in K (da verificare nel log)
    const val WB_KELVIN = 0x8012L   // valore di WB "temperatura colore" (da verificare nel log)
    const val EXP_MODE = 0x500E     // 1 M, 2 P, 3 A, 4 S
    const val FOCUS_MODE = 0x500A   // modo di messa a fuoco (codice standard PTP): 1 = MF, 2 = AF-S, 0x8004 = AF-C (valori Sony da verificare nel log)
    const val METERING = 0x500B
    const val BATTERY_STD = 0x5001  // livello batteria standard PTP (0-100)
    const val BATTERY = 0xD20E      // livello batteria Sony a gradini (solo ripiego)
    const val BATTERY_PCT = 0xD218  // batteria in percentuale (int8, -1 = sconosciuto), verificata sul log

    /** Proprieta' Sony di avviso temperatura/surriscaldamento (da riempire quando il log mostra il codice giusto). */
    val TEMP_WARN = intArrayOf()

    fun overheat(m: Map<Int, Prop>): Boolean = TEMP_WARN.any { (m[it]?.current ?: 0L) != 0L }

    // Memoria residua (codici Sony da verificare nel log: se la camera non li espone si vede "—")
    val MEDIA_TIME = intArrayOf(0xD249, 0xD24A)   // tempo residuo filmato (secondi); D24A verificata sul log, D249 e' spenta se manca la scheda
    val MEDIA_SHOTS = intArrayOf(0xD248, 0xD24A)  // foto residue, slot 1 e 2

    /** Tempo residuo in secondi per slot, oppure null se non leggibile. */
    fun mediaSeconds(m: Map<Int, Prop>): List<Long?> = MEDIA_TIME.map { c ->
        val p = m[c]
        // enabledFlag 0 = proprieta' spenta (scheda assente): non e' "0 minuti"
        if (p == null || p.enabledFlag == 0 || p.current < 0 || p.current > 0xFFFFFL) null else p.current
    }

    /** Testo della memoria residua ("1h 12m", con due schede "1: 1h 12m  2: 40m"), oppure null se non leggibile. */
    fun memSummary(m: Map<Int, Prop>): String? {
        val mem = mediaSeconds(m)
        val valid = mem.filterNotNull()
        if (valid.isEmpty()) return null
        return mem.mapIndexedNotNull { i, s ->
            if (s == null) null else (if (mem.size > 1 && valid.size > 1) "${i + 1}: " else "") + fmtRemain(s)
        }.joinToString("  ")
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
        val pct = m[BATTERY_PCT]
        if (pct != null && pct.current in 0L..100L) return pct.current.toInt()
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
                1 -> {
                    // intervallo: minimo, massimo, passo -> elenco dei valori (se non e' enorme)
                    if (p + 3 * sz > d.size) return null
                    val mn = readVal(bb, p, dt)
                    val mx = readVal(bb, p + sz, dt)
                    val st = readVal(bb, p + 2 * sz, dt)
                    p += 3 * sz
                    if (st > 0 && mx >= mn && (mx - mn) / st <= 600) {
                        val list = ArrayList<Long>()
                        var v = mn
                        while (v <= mx) { list.add(v); v += st }
                        setList = list
                    }
                }
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
        WB -> fmtWb(v)
        KELVIN -> "${v}K"
        FOCUS_MODE -> when (v) { 1L -> "MF"; 2L -> "AF-S"; 0x8004L -> "AF-C"; 0x8005L -> "AF-A"; 0x8006L -> "DMF"; else -> hex(v) }
        else -> hex(v)
    }

    /** Bilanciamento del bianco, nome lungo (i codici oltre 0x8000 sono da verificare nel log). */
    fun fmtWb(v: Long): String = when (v) {
        2L -> "Auto"; 4L -> "Luce diurna"; 5L -> "Fluorescente"; 6L -> "Incandescenza"; 7L -> "Flash"
        0x8001L -> "Fluo calda"; 0x8002L -> "Fluo fredda"; 0x8003L -> "Fluo bianca"; 0x8004L -> "Fluo diurna"
        0x8010L -> "Nuvoloso"; 0x8011L -> "Ombra"; WB_KELVIN -> "Temperatura colore"
        0x8020L -> "Personalizzato 1"; 0x8021L -> "Personalizzato 2"; 0x8022L -> "Personalizzato 3"
        else -> hex(v)
    }

    /** Bilanciamento del bianco, nome corto per la barra. */
    fun fmtWbShort(v: Long): String = when (v) {
        2L -> "AWB"; 4L -> "DAY"; 5L -> "FLUO"; 6L -> "TUNG"; 7L -> "FLASH"
        0x8001L, 0x8002L, 0x8003L, 0x8004L -> "FLUO"
        0x8010L -> "CLOUD"; 0x8011L -> "SHADE"; WB_KELVIN -> "K"
        0x8020L -> "C1"; 0x8021L -> "C2"; 0x8022L -> "C3"
        else -> hex(v)
    }
}
