package com.mdovideo.doppia

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.Locale

enum class CamState { IDLE, CONNECTING, WAITING, LIVE, ERROR }

/**
 * Una camera Sony vista via PTP/IP: connessione, handshake Sony e ciclo del live view.
 * Tutto gira su un thread dedicato; i risultati arrivano con i callback (anche da thread diverso dal principale).
 */
class SonyCam(
    private val host: String,
    private val guid: ByteArray,
    private val tag: String,
    private val log: (String) -> Unit,
    private val onState: (CamState) -> Unit,
    private val onFrame: (Bitmap, Int) -> Unit
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var client: PtpIpClient? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "doppia-$tag").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try { client?.close() } catch (_: Throwable) {}
        thread?.interrupt()
        thread = null
        onState(CamState.IDLE)
    }

    private fun say(s: String) = log("[$tag] $s")

    private fun hex(v: Int) = String.format(Locale.ROOT, "0x%04X", v and 0xffff)

    private fun loop() {
        try {
            onState(CamState.CONNECTING)
            val c = PtpIpClient(host, guid, "Doppia")
            client = c
            say("Mi collego a $host:15740")
            c.connect()
            say("Connesso, la camera risponde come \"${c.responderName}\"")

            val os = c.openSession()
            say("OpenSession: ${hex(os.code)}")
            if (!os.ok) throw IllegalStateException("OpenSession rifiutata (${hex(os.code)})")

            // Informazioni sulla camera (nomi scritti in UTF-16 alla fine del blocco).
            val di = c.transaction(0x1001)
            if (di.ok) {
                val names = readableStrings(di.data).takeLast(4)
                say("Camera: ${names.joinToString(" | ")}")
            } else {
                say("GetDeviceInfo: ${hex(di.code)}")
            }

            // Handshake Sony (SDIO), come da "Camera Control PTP 3": Connect 1, Connect 2,
            // GetExtDeviceInfo (da ripetere finche' risponde con dati), Connect 3.
            val p1 = c.transaction(0x9201, intArrayOf(1, 0, 0))
            say("SDIO fase 1: ${hex(p1.code)}")
            val p2 = c.transaction(0x9201, intArrayOf(2, 0, 0))
            say("SDIO fase 2: ${hex(p2.code)}")
            var info = c.transaction(0x9202, intArrayOf(0x012C, 0))
            var tries = 0
            while ((!info.ok || info.data.isEmpty()) && tries < 8 && running) {
                tries++
                say("Info estese: ${hex(info.code)}, ${info.data.size} byte, riprovo ($tries)")
                if (tries == 3) info = c.transaction(0x9202, intArrayOf(0x00C8, 0))
                else { Thread.sleep(150); info = c.transaction(0x9202, intArrayOf(0x012C, 0)) }
            }
            say("Info estese: ${hex(info.code)} (${info.data.size} byte)")
            val p3 = c.transaction(0x9201, intArrayOf(3, 0, 0))
            say("SDIO fase 3: ${hex(p3.code)}")
            if (!p3.ok) say("Attenzione: la fase 3 non e' andata a buon fine, provo comunque")

            // Con "Controllo remoto con trasferimento" il live view va abilitato (controllo 0xD313 = 2).
            val en = c.transaction(0x9207, intArrayOf(0xD313), le16(2))
            say("Abilita live view (0xD313): ${hex(en.code)}")

            onState(CamState.WAITING)

            // Attendo che "Live View Status" (0xD221) diventi 1, leggendo la tabella proprieta'.
            var status = -1
            var waits = 0
            while (running && status != 1) {
                val t = c.transaction(0x9209, intArrayOf(0, 0))
                status = if (t.ok) livePropValue(t.data, 0xD221) else -2
                if (waits == 0) say("Tabella proprieta' (0x9209): ${hex(t.code)}, ${t.data.size} byte, Live View Status = $status")
                if (status == 1) break
                if (status == -3 && waits >= 3) {
                    say("Non riesco a leggere lo stato del live view dalla tabella: provo comunque")
                    break
                }
                waits++
                if (waits % 10 == 0) say("Live view non ancora attivo (stato $status), attendo...")
                Thread.sleep(300)
            }
            if (!running) return
            say("Live view attivo")

            // Info sull'oggetto live view: una volta sola, come richiede la guida.
            val oi = c.transaction(0x1008, intArrayOf(0xFFFFC002.toInt()))
            say("GetObjectInfo live view: ${hex(oi.code)}")

            var misses = 0
            var frames = 0
            var lastPoll = System.nanoTime()
            var lastStart = 0L
            while (running) {
                // Massimo 30 fps: non chiedere due fotogrammi a meno di 33 ms.
                val gap = 33_000_000L - (System.nanoTime() - lastStart)
                if (gap > 0) Thread.sleep(gap / 1_000_000L, (gap % 1_000_000L).toInt())
                val t0 = System.nanoTime()
                lastStart = t0
                val r = c.transaction(0x1009, intArrayOf(0xFFFFC002.toInt()))
                val jpeg = if (r.ok) extractJpeg(r.data) else null
                if (jpeg == null) {
                    // 0x200F con dimensione zero = fotogramma non ancora pronto: riprovare.
                    misses++
                    if (misses <= 3 || misses % 40 == 0) {
                        say("Fotogramma non pronto: ${hex(r.code)}, ${r.data.size} byte (tentativo $misses)")
                    }
                    if (r.code == 0x2009) { // oggetto non valido: il live view si e' spento
                        status = 0
                        say("Live view spento dalla camera, attendo di nuovo")
                        while (running && status != 1) {
                            Thread.sleep(300)
                            val t = c.transaction(0x9209, intArrayOf(0, 0))
                            status = if (t.ok) livePropValue(t.data, 0xD221) else -2
                        }
                    }
                    Thread.sleep(30)
                    continue
                }
                misses = 0
                val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                if (bmp == null) {
                    say("Fotogramma non leggibile (${jpeg.size} byte)")
                    continue
                }
                val ms = ((System.nanoTime() - t0) / 1_000_000L).toInt()
                if (frames == 0) say("Primo fotogramma: ${bmp.width}x${bmp.height}, ${jpeg.size} byte")
                frames++
                onState(CamState.LIVE)
                onFrame(bmp, ms)

                // La guida chiede di rileggere la tabella proprieta' a intervalli regolari (tiene viva la sessione).
                if (System.nanoTime() - lastPoll > 2_000_000_000L) {
                    c.transaction(0x9209, intArrayOf(1, 0))
                    lastPoll = System.nanoTime()
                }
            }
        } catch (e: InterruptedException) {
            // stop richiesto dall'utente
        } catch (t: Throwable) {
            if (running) {
                say("Errore: ${t.message ?: t.javaClass.simpleName}")
                onState(CamState.ERROR)
            }
        } finally {
            running = false
            try { client?.close() } catch (_: Throwable) {}
            client = null
        }
    }

    /** LiveView Dataset: offset+dimensione del JPEG nei primi 8 byte; se non tornano, cerca FF D8 ... FF D9. */
    private fun extractJpeg(d: ByteArray): ByteArray? {
        if (d.size < 16) return null
        val bb = java.nio.ByteBuffer.wrap(d).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val off = bb.getInt(0)
        val size = bb.getInt(4)
        if (size > 0 && off >= 8 && off.toLong() + size <= d.size &&
            d[off] == 0xFF.toByte() && d[off + 1] == 0xD8.toByte()
        ) return d.copyOfRange(off, off + size)
        if (size == 0) return null
        var s = -1
        val limit = minOf(d.size - 1, 4096)
        for (i in 0 until limit) {
            if (d[i] == 0xFF.toByte() && d[i + 1] == 0xD8.toByte()) { s = i; break }
        }
        if (s < 0) return null
        var e = d.size
        for (i in d.size - 2 downTo s + 2) {
            if (d[i] == 0xFF.toByte() && d[i + 1] == 0xD9.toByte()) { e = i + 2; break }
        }
        return d.copyOfRange(s, e)
    }

    private fun le16(v: Int): ByteArray = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())

    private fun typeSize(t: Int): Int = when (t) {
        1, 2 -> 1; 3, 4 -> 2; 5, 6 -> 4; 7, 8 -> 8; 9, 10 -> 16; else -> -1
    }

    /**
     * Cerca una proprieta' nella tabella 0x9209 (SDIExtDevicePropInfo Array) e ne restituisce il valore corrente
     * (solo tipi interi). Prova prima con il contatore a 8 byte, poi a 4. Ritorna -3 se non la trova.
     */
    private fun livePropValue(d: ByteArray, code: Int): Int {
        for (head in intArrayOf(8, 4)) {
            val v = parseProps(d, head, code)
            if (v != Int.MIN_VALUE) return v
        }
        return -3
    }

    private fun parseProps(d: ByteArray, head: Int, code: Int): Int {
        if (d.size < head) return Int.MIN_VALUE
        val bb = java.nio.ByteBuffer.wrap(d).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val n = if (head == 8) bb.getLong(0) else bb.getInt(0).toLong()
        if (n < 0 || n > 5000) return Int.MIN_VALUE
        var p = head
        var found = Int.MIN_VALUE
        for (k in 0 until n.toInt()) {
            if (p + 6 > d.size) return Int.MIN_VALUE
            val pc = bb.getShort(p).toInt() and 0xffff
            val dt = bb.getShort(p + 2).toInt() and 0xffff
            val sz = typeSize(dt)
            if (sz < 0) return Int.MIN_VALUE // tipo non gestito (stringhe): non so proseguire
            p += 6 // codice, tipo, GetSet, IsEnabled
            if (p + 2 * sz + 1 > d.size) return Int.MIN_VALUE
            val cur = when (sz) {
                1 -> d[p + sz].toInt() and 0xff
                2 -> bb.getShort(p + sz).toInt() and 0xffff
                4 -> bb.getInt(p + sz)
                else -> 0
            }
            p += 2 * sz
            val form = d[p].toInt() and 0xff
            p += 1
            when (form) {
                1 -> p += 3 * sz
                2 -> {
                    for (l in 0 until 2) {
                        if (p + 2 > d.size) return Int.MIN_VALUE
                        val cnt = bb.getShort(p).toInt() and 0xffff
                        p += 2 + cnt * sz
                    }
                }
            }
            if (p > d.size) return Int.MIN_VALUE
            if (pc == code) found = cur
        }
        return if (p == d.size) found else Int.MIN_VALUE
    }

    /** Estrae le stringhe leggibili (UTF-16LE) di almeno 3 caratteri da un blocco binario. */
    private fun readableStrings(d: ByteArray): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < d.size) {
            val lo = d[i].toInt() and 0xff
            val hi = d[i + 1].toInt() and 0xff
            if (hi == 0 && lo in 32..126) {
                sb.append(lo.toChar())
                i += 2
            } else {
                if (sb.length >= 3) out.add(sb.toString())
                sb.setLength(0)
                i += if (hi == 0 && lo == 0) 2 else 1
            }
        }
        if (sb.length >= 3) out.add(sb.toString())
        return out
    }
}
