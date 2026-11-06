package com.mdovideo.doppia

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Client PTP/IP minimo (porta 15740): una connessione comandi + una connessione eventi.
 * Le transazioni sono sincrone, una alla volta.
 */
class PtpIpClient(
    private val host: String,
    private val guid: ByteArray,
    private val deviceName: String,
    private val port: Int = 15740
) : Closeable {

    class Result(val code: Int, val params: IntArray, val data: ByteArray) {
        val ok: Boolean get() = code == RC_OK
    }

    private class Packet(val type: Int, val payload: ByteArray)

    companion object {
        const val RC_OK = 0x2001
        private const val T_INIT_CMD = 1
        private const val T_INIT_CMD_ACK = 2
        private const val T_INIT_EVT = 3
        private const val T_INIT_EVT_ACK = 4
        private const val T_INIT_FAIL = 5
        private const val T_OP_REQ = 6
        private const val T_OP_RESP = 7
        private const val T_START_DATA = 9
        private const val T_DATA = 10
        private const val T_CANCEL = 11
        private const val T_END_DATA = 12
        private const val T_PING = 13
        private const val T_PONG = 14
        private const val MAX_PACKET = 64 * 1024 * 1024
    }

    private var cmdSock: Socket? = null
    private var cmdIn: DataInputStream? = null
    private var cmdOut: OutputStream? = null
    private var evtSock: Socket? = null
    private var evtThread: Thread? = null
    private var txId = 0
    @Volatile private var open = false

    var responderName: String = ""
        private set

    fun connect(timeoutMs: Int = 5000) {
        close()
        val cs = Socket()
        cs.tcpNoDelay = true
        cs.soTimeout = timeoutMs
        cs.connect(InetSocketAddress(host, port), timeoutMs)
        val cin = DataInputStream(cs.getInputStream().buffered(256 * 1024))
        val cout = cs.getOutputStream()

        val nameBytes = (deviceName + "\u0000").toByteArray(Charsets.UTF_16LE)
        send(cout, T_INIT_CMD, guid + nameBytes + le32(0x00010000))
        val ack = readPacket(cin)
        if (ack.type == T_INIT_FAIL) throw IllegalStateException("La camera ha rifiutato la connessione (InitFail)")
        if (ack.type != T_INIT_CMD_ACK || ack.payload.size < 4) {
            throw IllegalStateException("Risposta inattesa dalla camera (tipo ${ack.type})")
        }
        val connectionNumber = ByteBuffer.wrap(ack.payload, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        responderName = readUtf16(ack.payload, 20)

        val es = Socket()
        es.tcpNoDelay = true
        es.soTimeout = timeoutMs
        es.connect(InetSocketAddress(host, port), timeoutMs)
        val ein = DataInputStream(es.getInputStream().buffered(16 * 1024))
        val eout = es.getOutputStream()
        send(eout, T_INIT_EVT, le32(connectionNumber))
        val eack = readPacket(ein)
        if (eack.type != T_INIT_EVT_ACK) throw IllegalStateException("Canale eventi rifiutato (tipo ${eack.type})")
        es.soTimeout = 0

        cmdSock = cs
        cmdIn = cin
        cmdOut = cout
        evtSock = es
        open = true
        evtThread = Thread({
            try {
                while (open) {
                    val p = readPacket(ein)
                    if (p.type == T_PING) send(eout, T_PONG, p.payload)
                }
            } catch (_: Throwable) {
            }
        }, "doppia-eventi").apply {
            isDaemon = true
            start()
        }
        txId = 0
    }

    fun openSession(sessionId: Int = 1): Result {
        // OpenSession usa sempre il numero di transazione 0.
        val r = run(0, 0x1002, intArrayOf(sessionId), null)
        txId = 1
        return r
    }

    @Synchronized
    fun transaction(op: Int, params: IntArray = IntArray(0)): Result {
        val id = txId++
        return run(id, op, params, null)
    }

    private fun run(id: Int, op: Int, params: IntArray, dataOut: ByteArray?): Result {
        val input = cmdIn ?: throw IllegalStateException("Non connesso")
        val output = cmdOut ?: throw IllegalStateException("Non connesso")
        val phase = if (dataOut == null) 1 else 2
        val req = ByteBuffer.allocate(10 + params.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        req.putInt(phase).putShort(op.toShort()).putInt(id)
        for (p in params) req.putInt(p)
        send(output, T_OP_REQ, req.array())

        val data = ByteArrayOutputStream(64 * 1024)
        while (true) {
            val p = readPacket(input)
            when (p.type) {
                T_OP_RESP -> {
                    if (p.payload.size < 6) throw IllegalStateException("Risposta troppo corta")
                    val b = ByteBuffer.wrap(p.payload).order(ByteOrder.LITTLE_ENDIAN)
                    val code = b.short.toInt() and 0xffff
                    b.int // numero transazione
                    val n = (p.payload.size - 6) / 4
                    val out = IntArray(n) { b.int }
                    return Result(code, out, data.toByteArray())
                }
                T_START_DATA -> {
                    if (p.payload.size > 12) data.write(p.payload, 12, p.payload.size - 12)
                }
                T_DATA, T_END_DATA -> {
                    if (p.payload.size > 4) data.write(p.payload, 4, p.payload.size - 4)
                }
                T_PING -> send(output, T_PONG, p.payload)
                T_CANCEL -> throw IllegalStateException("La camera ha annullato la richiesta")
                else -> throw IllegalStateException("Pacchetto inatteso (tipo ${p.type})")
            }
        }
    }

    override fun close() {
        open = false
        try { evtSock?.close() } catch (_: Throwable) {}
        try { cmdSock?.close() } catch (_: Throwable) {}
        evtSock = null
        cmdSock = null
        cmdIn = null
        cmdOut = null
        evtThread = null
    }

    private fun send(out: OutputStream, type: Int, payload: ByteArray) {
        val head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        head.putInt(8 + payload.size).putInt(type)
        out.write(head.array())
        out.write(payload)
        out.flush()
    }

    private fun readPacket(input: DataInputStream): Packet {
        val head = ByteArray(8)
        input.readFully(head)
        val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val len = b.int
        val type = b.int
        if (len < 8 || len > MAX_PACKET) throw IllegalStateException("Lunghezza pacchetto non valida: $len")
        val payload = ByteArray(len - 8)
        input.readFully(payload)
        return Packet(type, payload)
    }

    private fun le32(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun readUtf16(bytes: ByteArray, start: Int): String {
        var end = start
        while (end + 1 < bytes.size && !(bytes[end] == 0.toByte() && bytes[end + 1] == 0.toByte())) end += 2
        return if (end <= start) "" else String(bytes, start, end - start, Charsets.UTF_16LE)
    }
}
