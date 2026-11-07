package com.mdovideo.doppia

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tipi di segno: colore e nome. */
object MarkCat {
    const val GOOD = 0
    const val BAD = 1
    const val NOTE = 2
    val names = arrayOf("Buona", "Scarto", "Nota")
    val keys = arrayOf("good", "bad", "note")
    val colors = intArrayOf(0xFF66BB6A.toInt(), 0xFFEF5350.toInt(), 0xFFFFCA28.toInt())
}

/** Una registrazione di una camera. endWall = 0 finche' sta registrando. */
class Take(val cam: Int, val num: Int, val startWall: Long, var endWall: Long) {
    /** Guide di inquadratura accese all'inizio del take: json e testo (vuoti se nessuna). */
    var gj: String = ""
    var gt: String = ""
}

/** Riferimento di un segno a un take: quanti ms dopo l'inizio del take. */
class Ref(val cam: Int, val take: Int, val offMs: Long)

class Mark(val wall: Long, val cat: Int, val note: String, val refs: List<Ref>)

/** Take e segni della sessione di riprese, salvati nelle preferenze, con esportazione per Premiere. */
object Session {
    val takes = ArrayList<Take>()
    val marks = ArrayList<Mark>()
    private const val KEY = "session"
    private const val MAX = 2000

    val fpsNames = listOf("23.976", "24", "25", "29.97", "30", "50", "59.94", "60")
    private val fpsBase = intArrayOf(24, 24, 25, 30, 30, 50, 60, 60)
    private val fpsNtsc = booleanArrayOf(true, false, false, true, false, false, true, false)

    private fun fi(i: Int): Int = i.coerceIn(0, fpsNames.size - 1)

    fun fpsOf(i: Int): Double {
        val k = fi(i)
        return if (fpsNtsc[k]) fpsBase[k] * 1000.0 / 1001.0 else fpsBase[k].toDouble()
    }

    fun clockOf(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(ms))

    private fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).format(Date(ms))

    fun hms(ms: Long): String {
        val s = (ms.coerceAtLeast(0L) / 1000L).toInt()
        return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }

    fun load(p: SharedPreferences) {
        takes.clear()
        marks.clear()
        try {
            val j = JSONObject(p.getString(KEY, "") ?: "")
            val ta = j.optJSONArray("takes") ?: JSONArray()
            for (k in 0 until ta.length()) {
                val o = ta.getJSONObject(k)
                val tk = Take(o.getInt("c").coerceIn(0, 1), o.getInt("n"), o.getLong("s"), o.getLong("e"))
                tk.gj = o.optString("gj", "")
                tk.gt = o.optString("gt", "")
                takes.add(tk)
            }
            val ma = j.optJSONArray("marks") ?: JSONArray()
            for (k in 0 until ma.length()) {
                val o = ma.getJSONObject(k)
                val ra = o.optJSONArray("r") ?: JSONArray()
                val refs = ArrayList<Ref>()
                for (q in 0 until ra.length()) {
                    val r = ra.getJSONArray(q)
                    refs.add(Ref(r.getInt(0).coerceIn(0, 1), r.getInt(1), r.getLong(2)))
                }
                marks.add(Mark(o.getLong("w"), o.getInt("c").coerceIn(0, 2), o.optString("t", ""), refs))
            }
        } catch (_: Throwable) {
            takes.clear()
            marks.clear()
        }
        // take rimasti aperti (app chiusa durante il REC): li chiude all'ultimo segno noto
        for (t in takes) {
            if (t.endWall != 0L) continue
            var end = t.startWall
            for (m in marks) for (r in m.refs) {
                if (r.cam == t.cam && r.take == t.num) end = maxOf(end, t.startWall + r.offMs)
            }
            t.endWall = end
        }
    }

    fun save(p: SharedPreferences) {
        val j = JSONObject()
        val ta = JSONArray()
        for (t in takes) {
            val o = JSONObject()
            o.put("c", t.cam)
            o.put("n", t.num)
            o.put("s", t.startWall)
            o.put("e", t.endWall)
            if (t.gj.isNotEmpty()) { o.put("gj", t.gj); o.put("gt", t.gt) }
            ta.put(o)
        }
        j.put("takes", ta)
        val ma = JSONArray()
        for (m in marks) {
            val o = JSONObject()
            o.put("w", m.wall)
            o.put("c", m.cat)
            o.put("t", m.note)
            val ra = JSONArray()
            for (r in m.refs) {
                val x = JSONArray()
                x.put(r.cam)
                x.put(r.take)
                x.put(r.offMs)
                ra.put(x)
            }
            o.put("r", ra)
            ma.put(o)
        }
        j.put("marks", ma)
        p.edit().putString(KEY, j.toString()).apply()
    }

    fun reset() {
        takes.clear()
        marks.clear()
    }

    fun openTake(cam: Int): Take? = takes.lastOrNull { it.cam == cam && it.endWall == 0L }

    fun startTake(cam: Int, wall: Long): Take {
        closeTake(cam, wall)
        val n = (takes.filter { it.cam == cam }.maxOfOrNull { it.num } ?: 0) + 1
        val t = Take(cam, n, wall, 0L)
        takes.add(t)
        while (takes.size > MAX) takes.removeAt(0)
        return t
    }

    fun closeTake(cam: Int, wall: Long) {
        val t = openTake(cam) ?: return
        t.endWall = maxOf(wall, t.startWall + 1)
    }

    fun addMark(m: Mark) {
        marks.add(m)
        while (marks.size > MAX) marks.removeAt(0)
    }

    private fun durMs(t: Take): Long {
        val end = if (t.endWall == 0L) System.currentTimeMillis() else t.endWall
        return (end - t.startWall).coerceAtLeast(0L)
    }

    /** I segni di un take con il loro tempo dall'inizio del take, in ordine. */
    private fun marksOf(t: Take): List<Pair<Mark, Long>> {
        val out = ArrayList<Pair<Mark, Long>>()
        for (m in marks) for (r in m.refs) {
            if (r.cam == t.cam && r.take == t.num) out.add(Pair(m, r.offMs))
        }
        return out.sortedBy { it.second }
    }

    private fun takeName(names: List<String>, t: Take): String =
        names[t.cam.coerceIn(0, names.size - 1)] + " - take " + String.format(Locale.ROOT, "%02d", t.num)

    /** Elenco leggibile: una riga per take. */
    fun summary(names: List<String>): String {
        val sb = StringBuilder()
        for (t in takes) {
            sb.append(takeName(names, t))
                .append("   ").append(clockOf(t.startWall))
                .append("   ").append(hms(durMs(t)))
                .append(if (t.endWall == 0L) " ●" else "")
                .append("   ⚑ ").append(marksOf(t).size).append('\n')
        }
        val loose = marks.count { it.refs.isEmpty() }
        if (loose > 0) sb.append(loose).append(" segni fuori dal REC\n")
        return sb.toString().trimEnd()
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun label(m: Mark): String =
        if (m.note.isEmpty()) MarkCat.names[m.cat] else MarkCat.names[m.cat] + ": " + m.note

    /** XML per Premiere (formato Final Cut Pro 7 / xmeml): una sequenza per take, con i segni come marker di sequenza. */
    fun xmeml(names: List<String>, fpsIdx: Int, title: String): String {
        val k = fi(fpsIdx)
        val f = fpsOf(k)
        val rate = "<rate><timebase>" + fpsBase[k] + "</timebase><ntsc>" + (if (fpsNtsc[k]) "TRUE" else "FALSE") + "</ntsc></rate>"
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE xmeml>\n<xmeml version=\"4\">\n")
        sb.append("<project>\n<name>").append(esc(title)).append("</name>\n<children>\n")
        var idx = 0
        for (t in takes) {
            idx++
            val mk = marksOf(t)
            var last = 0L
            for (pr in mk) last = maxOf(last, Math.round(pr.second / 1000.0 * f))
            val dur = maxOf(Math.ceil(durMs(t) / 1000.0 * f).toLong(), last + 1L, 1L)
            sb.append("<sequence id=\"seq").append(idx).append("\">\n")
            sb.append("<name>").append(esc(takeName(names, t) + " (" + clockOf(t.startWall) + ")")).append("</name>\n")
            sb.append("<duration>").append(dur).append("</duration>\n")
            sb.append(rate).append('\n')
            sb.append("<media><video><format><samplecharacteristics>").append(rate)
            sb.append("<width>1920</width><height>1080</height><pixelaspectratio>square</pixelaspectratio>")
            sb.append("</samplecharacteristics></format><track></track></video></media>\n")
            sb.append("<timecode>").append(rate)
            sb.append("<string>00:00:00:00</string><frame>0</frame><displayformat>NDF</displayformat></timecode>\n")
            for (pr in mk) {
                val m = pr.first
                val frame = Math.round(pr.second / 1000.0 * f)
                sb.append("<marker><name>").append(esc(label(m))).append("</name>")
                sb.append("<comment>").append(esc(label(m) + " (" + clockOf(m.wall) + ")")).append("</comment>")
                sb.append("<in>").append(frame).append("</in><out>-1</out></marker>\n")
            }
            sb.append("</sequence>\n")
        }
        sb.append("</children>\n</project>\n</xmeml>\n")
        return sb.toString()
    }

    /** JSON per il plugin di Premiere: take e segni con tempi in ms e in fotogrammi. */
    fun json(names: List<String>, fpsIdx: Int): String {
        val f = fpsOf(fpsIdx)
        val o = JSONObject()
        o.put("app", "Doppia")
        o.put("version", 1)
        o.put("fps", Math.round(f * 1000.0) / 1000.0)
        o.put("exported", iso(System.currentTimeMillis()))
        val cams = JSONArray()
        for (n in names) cams.put(n)
        o.put("cameras", cams)
        val ta = JSONArray()
        for (t in takes) {
            val x = JSONObject()
            x.put("cam", t.cam)
            x.put("camera", names[t.cam.coerceIn(0, names.size - 1)])
            x.put("take", t.num)
            x.put("start", iso(t.startWall))
            x.put("startMs", t.startWall)
            x.put("durationMs", durMs(t))
            if (t.gj.isNotEmpty()) {
                try { x.put("guides", JSONArray(t.gj)) } catch (_: Exception) {}
            }
            ta.put(x)
        }
        o.put("takes", ta)
        val ma = JSONArray()
        for (m in marks) {
            val x = JSONObject()
            x.put("time", clockOf(m.wall))
            x.put("timeMs", m.wall)
            x.put("type", MarkCat.keys[m.cat])
            x.put("label", MarkCat.names[m.cat])
            x.put("note", m.note)
            val ra = JSONArray()
            for (r in m.refs) {
                val y = JSONObject()
                y.put("cam", r.cam)
                y.put("take", r.take)
                y.put("offsetMs", r.offMs)
                y.put("frame", Math.round(r.offMs / 1000.0 * f))
                ra.put(y)
            }
            x.put("refs", ra)
            ma.put(x)
        }
        o.put("marks", ma)
        return o.toString(2)
    }

    private fun csvf(s: String): String =
        if (s.contains(';') || s.contains('"') || s.contains('\n')) "\"" + s.replace("\"", "\"\"") + "\"" else s

    /** Elenco take in CSV (separatore ;, UTF-8 con BOM: si apre bene in Excel italiano). */
    fun csv(names: List<String>): String {
        val sb = StringBuilder("\uFEFF")
        sb.append("camera;take;inizio;fine;durata;segni;note;guide\n")
        for (t in takes) {
            val mk = marksOf(t)
            val parts = mk.joinToString(" | ") { pr ->
                hms(pr.second) + " " + label(pr.first)
            }
            sb.append(csvf(names[t.cam.coerceIn(0, names.size - 1)])).append(';')
                .append(t.num).append(';')
                .append(clockOf(t.startWall)).append(';')
                .append(if (t.endWall == 0L) "" else clockOf(t.endWall)).append(';')
                .append(hms(durMs(t))).append(';')
                .append(mk.size).append(';')
                .append(csvf(parts)).append(';')
                .append(csvf(t.gt)).append('\n')
        }
        return sb.toString()
    }
}
