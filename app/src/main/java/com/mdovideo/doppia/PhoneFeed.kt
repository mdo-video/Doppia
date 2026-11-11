package com.mdovideo.doppia

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import java.io.ByteArrayOutputStream

/**
 * Fotocamera del telefono (o del tablet) usata come sorgente di immagini per le camere simulate:
 * si vede una scena vera, e le camere finte la schiariscono o scuriscono secondo ISO, diaframma e otturatore.
 * Una sola camera del telefono, condivisa da tutte le camere finte; si accende con la prima e si spegne con l'ultima.
 */
object PhoneFeed {
    /** Voglia dell'utente (Impostazioni > Test): le camere finte, se riescono, usano la camera del telefono. */
    @Volatile var wanted = false

    @Volatile var lastError = ""
        private set

    @Volatile private var latestBmp: Bitmap? = null
    private var appCtx: Context? = null
    private var rotation: () -> Int = { 0 }
    private var users = 0
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var sensorOrientation = 90
    private var lastAt = 0L

    /** Da chiamare una volta all'avvio; displayRotationDeg dice di quanto e' ruotato lo schermo (0, 90, 180, 270). */
    fun init(ctx: Context, displayRotationDeg: () -> Int) {
        appCtx = ctx.applicationContext
        rotation = displayRotationDeg
    }

    /** Ultimo fotogramma ricevuto, gia' ruotato come lo schermo; null finche' la camera non e' partita. */
    fun latest(): Bitmap? = latestBmp

    /** Una camera finta ne ha bisogno: accende la camera del telefono se serve. Falso se non si puo' (permesso o camera). */
    @Synchronized
    fun acquire(): Boolean {
        val ctx = appCtx ?: return false
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            lastError = "manca il permesso della fotocamera"
            return false
        }
        users++
        if (users == 1) {
            try {
                open(ctx)
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                users--
                close()
                return false
            }
        }
        return true
    }

    @Synchronized
    fun release() {
        if (users > 0) users--
        if (users == 0) close()
    }

    @SuppressLint("MissingPermission")
    private fun open(ctx: Context) {
        lastError = ""
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = cm.cameraIdList
        val id = ids.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            ?: ids.firstOrNull()
            ?: throw IllegalStateException("nessuna fotocamera nel dispositivo")
        val ch = cm.getCameraCharacteristics(id)
        sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("la camera non elenca le risoluzioni")
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888)
            ?: throw IllegalStateException("la camera non da' immagini YUV")
        val size = sizes.filter { it.width * 9 == it.height * 16 && it.width <= 1280 }.maxByOrNull { it.width * it.height }
            ?: sizes.filter { it.width <= 1280 && it.height <= 960 }.maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }
            ?: throw IllegalStateException("nessuna risoluzione disponibile")
        val th = HandlerThread("doppia-phonecam")
        th.start()
        val h = Handler(th.looper)
        thread = th
        handler = h
        val rd = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        rd.setOnImageAvailableListener({ r -> onImage(r) }, h)
        reader = rd
        cm.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                val ok = synchronized(this@PhoneFeed) {
                    if (users == 0 || reader == null) false else { device = d; true }
                }
                if (!ok) { d.close(); return }
                startSession(d)
            }

            override fun onDisconnected(d: CameraDevice) {
                d.close()
                synchronized(this@PhoneFeed) { if (device === d) device = null }
            }

            override fun onError(d: CameraDevice, error: Int) {
                lastError = "errore della camera ($error)"
                d.close()
                synchronized(this@PhoneFeed) { if (device === d) device = null }
            }
        }, h)
    }

    @Suppress("DEPRECATION")
    private fun startSession(d: CameraDevice) {
        try {
            val rd = reader ?: return
            d.createCaptureSession(listOf(rd.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    try {
                        session = s
                        val rq = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        rq.addTarget(rd.surface)
                        rq.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        s.setRepeatingRequest(rq.build(), null, handler)
                    } catch (t: Throwable) {
                        lastError = t.message ?: t.javaClass.simpleName
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    lastError = "configurazione della camera non riuscita"
                }
            }, handler)
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
        }
    }

    private fun onImage(r: ImageReader) {
        val img = r.acquireLatestImage() ?: return
        try {
            val now = System.currentTimeMillis()
            if (now - lastAt < 60L) return        // circa 15 fotogrammi al secondo bastano per il test
            lastAt = now
            val w = img.width
            val h = img.height
            val yuv = YuvImage(nv21(img), ImageFormat.NV21, w, h, null)
            val bos = ByteArrayOutputStream(64 * 1024)
            yuv.compressToJpeg(Rect(0, 0, w, h), 80, bos)
            var bmp = BitmapFactory.decodeByteArray(bos.toByteArray(), 0, bos.size()) ?: return
            val deg = (sensorOrientation - rotation() + 360) % 360
            if (deg != 0) {
                val m = Matrix()
                m.postRotate(deg.toFloat())
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            latestBmp = bmp
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
        } finally {
            img.close()
        }
    }

    /** Dai tre piani YUV_420_888 al formato NV21, tenendo conto di passo di riga e di pixel. */
    private fun nv21(img: Image): ByteArray {
        val w = img.width
        val h = img.height
        val yP = img.planes[0]
        val uP = img.planes[1]
        val vP = img.planes[2]
        val out = ByteArray(w * h * 3 / 2)
        val yb = yP.buffer
        var pos = 0
        for (row in 0 until h) {
            yb.position(row * yP.rowStride)
            yb.get(out, pos, w)
            pos += w
        }
        val ub = uP.buffer
        val vb = vP.buffer
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                out[pos++] = vb.get(row * vP.rowStride + col * vP.pixelStride)
                out[pos++] = ub.get(row * uP.rowStride + col * uP.pixelStride)
            }
        }
        return out
    }

    private fun close() {
        try { session?.close() } catch (_: Throwable) {}
        try { device?.close() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        try { thread?.quitSafely() } catch (_: Throwable) {}
        session = null
        device = null
        reader = null
        thread = null
        handler = null
        latestBmp = null
    }
}
