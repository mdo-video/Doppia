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
    private val onFrame: (Bitmap, Int) -> Unit,
    private val onProps: (Map<Int, Prop>) -> Unit = {},
    private val sim: Boolean = false
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var client: PtpIpClient? = null

    private val cmds = java.util.concurrent.LinkedBlockingQueue<(PtpIpClient) -> Unit>()
    @Volatile private var pollNow = false
    @Volatile private var lastMap: Map<Int, Prop> = emptyMap()
    @Volatile private var diffBase: Map<Int, Prop>? = null
    @Volatile private var diffLeft = 0
    /** Ora (ms) dell'ultima richiesta REC fatta da Doppia: serve a capire se uno stop e' stato chiesto da noi o no. */
    @Volatile var lastRecCmd = 0L
        private set

    /** Imposta ISO / otturatore / diaframma con SDIO_SetExtDevicePropValue (0x9205). */
    fun setProp(p: Prop, value: Long) {
        if (sim) { synchronized(simState) { simState[p.code] = value }; simDirty = true; say("TEST imposta ${hex(p.code)} = ${SonyProps.fmt(p.code, value)}"); return }
        cmds.add { c ->
            val r = c.transaction(0x9205, intArrayOf(p.code), SonyProps.toBytes(value, SonyProps.typeSize(p.type)))
            say("Imposta ${hex(p.code)} = ${SonyProps.fmt(p.code, value)} (0x${value.toString(16)}): ${hex(r.code)}")
            pollNow = true
        }
    }

    companion object {
        /** Metodo REC che ha funzionato, per indirizzo (ricordato finche' l'app e' aperta). */
        val learnedRecipe = java.util.concurrent.ConcurrentHashMap<String, Int>()
    }

    /** Ultima tabella proprieta' letta (per l'interfaccia). */
    val props: Map<Int, Prop> get() = lastMap

    private fun recipeSteps(i: Int, start: Boolean): List<Pair<Int, Int>> = when (i) {
        0 -> listOf(SonyProps.REC_BUTTON to (if (start) 2 else 1))
        1 -> listOf(SonyProps.REC_BUTTON to 2, SonyProps.REC_BUTTON to 1)
        2 -> listOf(0xD2FE to 2, 0xD2FE to 1)
        else -> listOf(0xF001 to 2, 0xF001 to 1)
    }

    /**
     * Avvia o ferma la registrazione. Prova i metodi uno alla volta (pulsante premi/rilascia, valore diretto,
     * due codici "toggle" delle camere piu' nuove) e dopo ciascuno controlla se lo stato REC e' cambiato davvero.
     */
    fun toggleRec() {
        lastRecCmd = System.currentTimeMillis()
        if (sim) {
            synchronized(simState) { simState[SonyProps.REC_STATE] = if ((simState[SonyProps.REC_STATE] ?: 0L) == 0L) 1L else 0L }
            say("TEST REC: ${if ((simState[SonyProps.REC_STATE] ?: 0L) != 0L) "avvio" else "stop"}")
            simDirty = true
            return
        }
        cmds.add { c ->
            val before = lastMap[SonyProps.REC_STATE]?.current
            val start = (before ?: 0L) == 0L
            val order = ArrayList<Int>()
            learnedRecipe[host]?.let { order.add(it) }
            for (i in intArrayOf(1, 0, 2, 3)) if (i !in order) order.add(i)
            diffBase = lastMap
            diffLeft = 8
            say("REC: richiedo ${if (start) "avvio" else "stop"} (stato attuale: ${before ?: "sconosciuto"})")
            for (idx in order) {
                for ((code, v) in recipeSteps(idx, start)) {
                    val r = c.transaction(0x9207, intArrayOf(code), SonyProps.toBytes(v.toLong(), 2))
                    say("REC metodo ${idx + 1}: ${hex(code)} <- $v: ${hex(r.code)}")
                    Thread.sleep(150)
                }
                if (before == null) { // senza stato non posso verificare: mi fermo al primo metodo
                    pollNow = true
                    return@add
                }
                var waited = 0
                var changed = false
                while (waited < 2500 && running) {
                    Thread.sleep(250)
                    waited += 250
                    val tr = c.transaction(0x9209, intArrayOf(0, 0))
                    if (tr.ok) handleProps(tr.data, c)
                    if (lastMap[SonyProps.REC_STATE]?.current != before) { changed = true; break }
                }
                if (changed) {
                    learnedRecipe[host] = idx
                    say("REC: fatto, metodo ${idx + 1}")
                    pollNow = true
                    return@add
                }
                say("REC: nessun cambio con il metodo ${idx + 1}")
            }
            say("REC: nessun metodo ha cambiato lo stato. La camera e' in modalita' filmato? Guarda la riga 'Cambiate dopo REC'")
        }
    }

    // ---------- Modalita' test: camera finta ----------

    private val simState = LinkedHashMap<Int, Long>()
    @Volatile private var simDirty = false

    private fun simProps(): Map<Int, Prop> {
        fun sh(n: Int, d: Int) = (n.toLong() shl 16) or d.toLong()
        val out = LinkedHashMap<Int, Prop>()
        fun add(code: Int, type: Int, opts: List<Long>) {
            val cur = simState[code] ?: opts.firstOrNull() ?: 0L
            out[code] = Prop(code, type, 1, 1, cur, opts)
        }
        synchronized(simState) {
            add(SonyProps.SHUTTER, 6, listOf(sh(1, 25), sh(1, 30), sh(1, 50), sh(1, 60), sh(1, 100), sh(1, 125), sh(1, 250), sh(1, 500), sh(1, 1000)))
            add(SonyProps.APERTURE, 4, listOf(280L, 400L, 560L, 800L, 1100L, 1600L))
            add(SonyProps.ISO, 6, listOf(100L, 200L, 400L, 800L, 1600L, 3200L, 6400L))
            add(SonyProps.EXP_COMP, 3, listOf(-2000L, -1000L, 0L, 1000L, 2000L))
            add(SonyProps.EXP_MODE, 4, listOf(1L, 2L, 3L, 4L))
            add(SonyProps.METERING, 4, listOf(0x8001L, 0x8002L, 0x8004L))
            add(SonyProps.WB, 4, listOf(2L, 4L, 5L, 6L, SonyProps.WB_KELVIN))
            add(SonyProps.KELVIN, 4, listOf(2800L, 3200L, 4300L, 5000L, 5600L, 6500L))
            out[SonyProps.REC_STATE] = Prop(SonyProps.REC_STATE, 4, 0x80, 1, simState[SonyProps.REC_STATE] ?: 0L, emptyList())
            out[SonyProps.BATTERY_STD] = Prop(SonyProps.BATTERY_STD, 2, 0, 0, simState[SonyProps.BATTERY_STD] ?: 30L, emptyList())
            out[SonyProps.MEDIA_TIME[0]] = Prop(SonyProps.MEDIA_TIME[0], 6, 0, 1, simState[SonyProps.MEDIA_TIME[0]] ?: 1500L, emptyList())
            out[SonyProps.MEDIA_TIME[1]] = Prop(SonyProps.MEDIA_TIME[1], 6, 0, 1, simState[SonyProps.MEDIA_TIME[1]] ?: 3600L, emptyList())
        }
        return out
    }

    /** Camera finta: fotogrammi disegnati al momento, valori che si possono cambiare, REC, batteria e memoria che scendono in fretta. */
    private fun simLoop() {
        try {
            onState(CamState.CONNECTING)
            say("MODALITA' TEST: camera simulata, nessun collegamento reale")
            Thread.sleep(600)
            onState(CamState.WAITING)
            Thread.sleep(600)
            val isA = host.endsWith("0")
            synchronized(simState) {
                simState[SonyProps.SHUTTER] = (1L shl 16) or 50L
                simState[SonyProps.APERTURE] = 400L
                simState[SonyProps.ISO] = 800L
                simState[SonyProps.EXP_MODE] = 1L
                simState[SonyProps.METERING] = 0x8001L
                simState[SonyProps.WB] = 2L
                simState[SonyProps.KELVIN] = 5600L
                simState[SonyProps.EXP_COMP] = 0L
                simState[SonyProps.REC_STATE] = 0L
                simState[SonyProps.BATTERY_STD] = 30L
                simState[SonyProps.MEDIA_TIME[0]] = 1500L
                simState[SonyProps.MEDIA_TIME[1]] = 3600L
            }
            val w = 854
            val h = 480
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            val t0 = System.currentTimeMillis()
            var last = t0
            var lastProps = 0L
            var lastTick = t0
            var frames = 0
            val clock = java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT)
            say("Camera simulata pronta")
            while (running) {
                val now = System.currentTimeMillis()
                val fs = System.nanoTime()
                // batteria e memoria scendono in fretta (batteria -1% ogni 6 s, memoria 12 s al secondo in REC)
                if (now - lastTick >= 1000) {
                    val dt = (now - lastTick) / 1000
                    lastTick = now
                    synchronized(simState) {
                        if (now / 6000 != (now - dt * 1000) / 6000) simState[SonyProps.BATTERY_STD] = maxOf(3L, (simState[SonyProps.BATTERY_STD] ?: 30L) - 1)
                        if ((simState[SonyProps.REC_STATE] ?: 0L) != 0L) {
                            simState[SonyProps.MEDIA_TIME[0]] = maxOf(0L, (simState[SonyProps.MEDIA_TIME[0]] ?: 0L) - 12 * dt)
                            simState[SonyProps.MEDIA_TIME[1]] = maxOf(0L, (simState[SonyProps.MEDIA_TIME[1]] ?: 0L) - 12 * dt)
                        }
                    }
                }
                // esposizione finta: ISO, diaframma, otturatore e compensazione cambiano la luminosita'
                val (iso, ap, shv, ev) = synchronized(simState) {
                    listOf(
                        (simState[SonyProps.ISO] ?: 800L) and 0xffffffL,
                        simState[SonyProps.APERTURE] ?: 400L,
                        simState[SonyProps.SHUTTER] ?: ((1L shl 16) or 50L),
                        simState[SonyProps.EXP_COMP] ?: 0L
                    )
                }
                val shSec = ((shv shr 16) and 0xffff).toDouble() / maxOf(1L, shv and 0xffff).toDouble()
                val fn = ap / 100.0
                var k = (iso / 800.0) * (shSec / (1.0 / 50.0)) / ((fn / 4.0) * (fn / 4.0)) * Math.pow(2.0, ev / 1000.0)
                k = k.coerceIn(0.05, 6.0)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val cv = android.graphics.Canvas(bmp)
                fun lum(v: Int) = (v * k).toInt().coerceIn(0, 255)
                // sfondo a gradiente (bianco a destra: fa scattare zebra e istogramma)
                for (x in 0 until w step 6) {
                    val g = lum(20 + 235 * x / w)
                    paint.color = android.graphics.Color.rgb(if (isA) lum(g * 90 / 255 + 10) else g, if (isA) g else lum(g * 70 / 100), if (isA) lum(g * 120 / 100) else lum(g * 40 / 100))
                    cv.drawRect(x.toFloat(), 0f, (x + 6).toFloat(), h.toFloat(), paint)
                }
                // zona a scacchi fitti (per il focus peaking)
                for (yy in 0 until 8) for (xx in 0 until 16) {
                    paint.color = if ((xx + yy) % 2 == 0) android.graphics.Color.rgb(lum(230), lum(230), lum(230)) else android.graphics.Color.rgb(lum(25), lum(25), lum(25))
                    cv.drawRect((60 + xx * 10).toFloat(), (260 + yy * 10).toFloat(), (70 + xx * 10).toFloat(), (270 + yy * 10).toFloat(), paint)
                }
                // pallina che si muove
                val ang = (now - t0) / 700.0
                paint.color = android.graphics.Color.rgb(lum(255), lum(200), lum(40))
                cv.drawCircle((w / 2 + Math.cos(ang) * 240).toFloat(), (h / 2 + Math.sin(ang * 1.3) * 120).toFloat(), 46f, paint)
                // scritte
                paint.color = android.graphics.Color.WHITE
                paint.textSize = 64f
                paint.isFakeBoldText = true
                cv.drawText("TEST", 40f, 100f, paint)
                paint.textSize = 30f
                cv.drawText("$tag  ${clock.format(java.util.Date(now))}", 40f, 150f, paint)
                frames++
                val ms = ((System.nanoTime() - fs) / 1_000_000L).toInt() + 8
                if (frames == 1) say("Primo fotogramma simulato: ${w}x$h")
                onState(CamState.LIVE)
                onFrame(bmp, ms)
                if (simDirty || now - lastProps > 600) {
                    simDirty = false
                    lastProps = now
                    val m = simProps()
                    lastMap = m
                    onProps(m)
                }
                val spent = (System.nanoTime() - fs) / 1_000_000L
                Thread.sleep(maxOf(5L, 40L - spent))
            }
        } catch (e: InterruptedException) {
        } catch (t: Throwable) {
            if (running) { say("Errore simulazione: ${t.message ?: t.javaClass.simpleName}"); onState(CamState.ERROR) }
        } finally {
            running = false
        }
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ if (sim) simLoop() else loop() }, "doppia-$tag").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        cmds.clear()
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
                while (true) { val cmd = cmds.poll() ?: break; cmd(c) }
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

                // Rilettura della tabella proprieta' (tiene viva la sessione e aggiorna REC/ISO/otturatore/diaframma).
                if (pollNow || System.nanoTime() - lastPoll > 600_000_000L) {
                    pollNow = false
                    val t = c.transaction(0x9209, intArrayOf(0, 0))
                    lastPoll = System.nanoTime()
                    if (t.ok) handleProps(t.data, c)
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

    private var propsLogged = false

    private var apFail = 0
    private var apLogged = false

    /** Se il diaframma non e' nella tabella completa, lo chiede da solo con 0x9203 (descrittore di una proprieta'). */
    private fun withAperture(t: Map<Int, Prop>, c: PtpIpClient): Map<Int, Prop> {
        if (t.isEmpty() || t.containsKey(SonyProps.APERTURE) || apFail >= 3) return t
        val a = c.transaction(0x9203, intArrayOf(SonyProps.APERTURE))
        val pr = if (a.ok) SonyProps.parseOne(a.data) else null
        if (!apLogged) {
            apLogged = true
            say("Diaframma non nella tabella: provo 0x9203 -> ${hex(a.code)}, ${a.data.size} byte, " +
                if (pr != null) "valore ${SonyProps.fmt(SonyProps.APERTURE, pr.current)}" else "non leggibile (obiettivo manuale?)")
        }
        if (pr == null) { apFail++; return t }
        return t + (SonyProps.APERTURE to pr)
    }

    private fun handleProps(data: ByteArray, c: PtpIpClient) {
        val r = SonyProps.parse(data)
        val m = withAperture(r.props, c)
        if (m.isEmpty()) return
        if (!propsLogged) {
            propsLogged = true
            say("Tabella proprieta': ${m.size} voci${if (r.complete) "" else " (lettura parziale)"}")
            say("Valori: " + m.values.joinToString(" ") { "${hex(it.code)}=${it.current.toString(16)}" })
            for (code in intArrayOf(SonyProps.SHUTTER, SonyProps.APERTURE, SonyProps.ISO, SonyProps.REC_STATE, SonyProps.WB, SonyProps.KELVIN)) {
                val p = m[code]
                if (p == null) say("${hex(code)}: assente nella tabella")
                else say("${hex(code)}: ${SonyProps.fmt(code, p.current)}, modificabile=${p.settable}, ${p.options.size} valori ammessi")
            }
            m[SonyProps.WB]?.let { w ->
                say("WB ammessi (esadecimale): " + w.options.joinToString(" ") { java.lang.Long.toHexString(it) })
            }
        }
        // Dopo REC: mostra quali proprieta' sono cambiate, per controllare il codice dello stato di registrazione.
        val base = diffBase
        if (base != null && diffLeft > 0) {
            diffLeft--
            val ch = m.values.filter { base[it.code]?.current != it.current }
            if (ch.isNotEmpty()) {
                say("Cambiate dopo REC: " + ch.joinToString(" ") { "${hex(it.code)} ${base[it.code]?.current?.toString(16) ?: "-"}>${it.current.toString(16)}" })
                diffBase = null
            } else if (diffLeft == 0) {
                say("Dopo REC nessuna proprieta' e' cambiata")
                diffBase = null
            }
        }
        val prev = lastMap
        // REC fermato senza che Doppia l'abbia chiesto (caldo, scheda piena, batteria, tasto sulla camera): il log mostra cosa e' cambiato
        val pr0 = prev[SonyProps.REC_STATE]?.current
        if (pr0 != null && pr0 != 0L && m[SonyProps.REC_STATE]?.current == 0L && System.currentTimeMillis() - lastRecCmd > 15000L) {
            val ch = m.values.filter { prev[it.code]?.current != it.current }
            say("REC fermato dalla camera (non da Doppia). Proprieta' cambiate: " +
                ch.joinToString(" ") { "${hex(it.code)} ${prev[it.code]?.current?.toString(16) ?: "-"}>${it.current.toString(16)}" })
        }
        // Proprieta' che non conosco e che cambiano: si scrivono nel log (poche righe) per scoprire i codici giusti (es. temperatura)
        for (pr in m.values) {
            if (pr.code in knownCodes) continue
            val o = prev[pr.code]?.current ?: continue
            if (o == pr.current) continue
            val n = (unkCount[pr.code] ?: 0) + 1
            unkCount[pr.code] = n
            if (n <= 6) say("Proprieta' ${hex(pr.code)} cambiata: ${o.toString(16)} > ${pr.current.toString(16)}")
        }
        lastMap = m
        onProps(m)
    }

    private val unkCount = HashMap<Int, Int>()
    private val knownCodes: Set<Int> = hashSetOf(
        SonyProps.SHUTTER, SonyProps.APERTURE, SonyProps.ISO, SonyProps.REC_STATE, SonyProps.REC_BUTTON,
        SonyProps.EXP_COMP, SonyProps.WB, SonyProps.KELVIN, SonyProps.EXP_MODE, SonyProps.METERING,
        SonyProps.BATTERY_STD, SonyProps.BATTERY, SonyProps.BATTERY_PCT, 0xD221,
        SonyProps.MEDIA_TIME[0], SonyProps.MEDIA_TIME[1], SonyProps.MEDIA_SHOTS[0], SonyProps.MEDIA_SHOTS[1]
    )

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
