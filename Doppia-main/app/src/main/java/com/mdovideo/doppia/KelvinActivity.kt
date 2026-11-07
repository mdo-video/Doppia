package com.mdovideo.doppia

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Rational
import android.util.Size
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Misuratore Kelvin: usa la camera posteriore del tablet su una superficie bianca/grigia
 * e restituisce la temperatura colore a Doppia, che la imposta sulle camere con un tasto.
 */
class KelvinActivity : AppCompatActivity() {

    private lateinit var cm: CameraManager
    private lateinit var texture: TextureView
    private lateinit var kelvinText: TextView
    private lateinit var descText: TextView
    private lateinit var infoText: TextView
    private lateinit var pill: TextView
    private lateinit var bar: KelvinBar
    private lateinit var holdBtn: MaterialButton
    private lateinit var setBtn: MaterialButton
    private lateinit var numSpring: SpringFloat

    private var camThread: HandlerThread? = null
    private var camHandler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var opening = false

    private var camId: String? = null
    private var chars: CameraCharacteristics? = null
    private var previewSize = Size(1280, 960)
    private var model: ColorModel? = null
    private var approx = false

    private val history = mutableListOf<Double>()
    @Volatile private var hold = false
    private var shownK = 0
    private var gotReading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        for (id in cm.cameraIdList) {
            val c = cm.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                camId = id
                chars = c
                break
            }
        }
        chars?.let {
            model = buildModel(it) ?: findModelElsewhere()
            if (model == null) {
                model = ColorModel.generic()
                approx = true
            }
            pickPreviewSize(it)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
            setBackgroundColor(Pal.BG)
        }

        root.addView(TextView(this).apply {
            textSize = 28f
            setTextColor(Pal.TEXT)
            setTypeface(typeface, Typeface.BOLD)
            text = "Kelvin"
            setPadding(dp(8), 0, dp(8), 0)
        })
        root.addView(TextView(this).apply {
            textSize = 14f
            setTextColor(Pal.MUTED)
            text = "Inquadra con il tablet una superficie bianca o grigia neutra che riempia tutta l'inquadratura, illuminata dalla luce da misurare."
            setPadding(dp(8), dp(4), dp(8), dp(16))
        })

        // --- anteprima ---
        val previewInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        pill = TextView(this).apply {
            textSize = 13f
            setTextColor(Pal.TEXT)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = roundBg(Pal.LINE, 20, this@KelvinActivity)
            text = "Avvio camera…"
        }
        previewInner.addView(pill, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })

        val previewW = (resources.displayMetrics.widthPixels * 0.5).toInt()
        val previewH = (previewW.toDouble() * previewSize.width / previewSize.height).toInt()
        val screen = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            background = roundBg(Pal.SURFACE, 24, this@KelvinActivity)
            clipToOutline = true
        }
        texture = TextureView(this)
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { openCamera() }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
        screen.addView(texture, LinearLayout.LayoutParams(previewW, previewH))
        previewInner.addView(screen, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(card(previewInner))

        // --- risultato ---
        val resInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        val numRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        }
        kelvinText = TextView(this).apply {
            textSize = 64f
            setTextColor(Pal.TEXT)
            setTypeface(typeface, Typeface.BOLD)
            text = "--"
        }
        val unit = TextView(this).apply {
            textSize = 22f
            setTextColor(Pal.MUTED)
            text = "K"
            setPadding(dp(6), 0, 0, dp(12))
        }
        numRow.addView(kelvinText)
        numRow.addView(unit)
        resInner.addView(numRow)

        descText = TextView(this).apply {
            textSize = 17f
            setTextColor(Pal.MUTED)
            gravity = Gravity.CENTER_HORIZONTAL
            text = "In attesa della misura"
        }
        resInner.addView(descText)

        bar = KelvinBar(this)
        resInner.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(20) })

        val scale = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, g) in listOf("2000 K" to Gravity.START, "10000 K" to Gravity.END)) {
            scale.addView(TextView(this).apply {
                textSize = 11f
                setTextColor(Pal.MUTED)
                gravity = g
                text = label
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        resInner.addView(scale)
        root.addView(card(resInner))

        numSpring = SpringFloat(K_MIN, damp = 0.85f, stiff = 120f, minVisible = 1f) { v ->
            if (!gotReading) return@SpringFloat
            val shown = ((v / 10f).roundToInt()) * 10
            kelvinText.text = if (approx) "≈ $shown" else "$shown"
            kelvinText.setTextColor(kColor(v))
            bar.frac = kFrac(v)
            bar.markerColor = kColor(v)
        }

        // --- pulsanti ---
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        holdBtn = bigButton("Blocca", Pal.LINE, Pal.TEXT)
        holdBtn.setOnClickListener {
            hold = !hold
            holdBtn.text = if (hold) "Sblocca" else "Blocca"
            holdBtn.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            updatePill()
        }
        row.addView(holdBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginEnd = dp(6) })

        setBtn = bigButton("Imposta camere", Pal.MID, Pal.BG)
        setBtn.isEnabled = false
        setBtn.alpha = 0.4f
        setBtn.setOnClickListener {
            if (shownK <= 0) return@setOnClickListener
            setBtn.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            setResult(RESULT_OK, Intent().putExtra("k", shownK))
            finish()
        }
        row.addView(setBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f)
            .apply { marginStart = dp(6) })
        root.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4); bottomMargin = dp(8) })

        val closeBtn = bigButton("Chiudi", Pal.SURFACE, Pal.MUTED)
        closeBtn.setOnClickListener { finish() }
        root.addView(closeBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })

        infoText = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Pal.MUTED)
            setPadding(dp(20), dp(16), dp(20), dp(16))
            text = if (approx)
                "Questa camera non espone le matrici colore: stima approssimata (modello generico)."
            else ""
        }
        root.addView(card(infoText))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Pal.BG)
            isFillViewport = true
            addView(root)
        })

        for (i in 0 until root.childCount) Springs.enter(root.getChildAt(i), 60L * i, dp(24).toFloat())
        updatePill()
    }

    private fun bigButton(label: String, bg: Int, fg: Int): MaterialButton = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTextColor(fg)
        backgroundTintList = android.content.res.ColorStateList.valueOf(bg)
        cornerRadius = dp(28)
        minimumHeight = dp(56)
        Springs.pressable(this)
    }

    private fun card(content: View): LinearLayout = LinearLayout(this).apply {
        background = roundBg(Pal.CARD, 28, this@KelvinActivity)
        addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
    }

    private fun updatePill() {
        pill.text = when {
            hold -> "Valore bloccato"
            !gotReading -> "In attesa della camera…"
            approx -> "Stima approssimata"
            else -> "Misura in tempo reale"
        }
    }

    // ---------- modello colore ----------

    private fun toMat(t: ColorSpaceTransform): DoubleArray {
        val arr = Array(9) { Rational(0, 1) }
        t.copyElements(arr, 0)
        return DoubleArray(9) { arr[it].toDouble() }
    }

    private fun buildModel(c: CameraCharacteristics): ColorModel? {
        val t1 = c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1) ?: return null
        val t2opt = c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)
        val i1 = c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1) ?: 17
        val i2 = if (t2opt != null) (c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt() ?: 21) else i1

        var m1 = toMat(t1)
        var m2 = if (t2opt != null) toMat(t2opt) else m1
        val cc1o = c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)
        val cc2o = c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)
        var cc1 = if (cc1o != null) toMat(cc1o) else ColorModel.IDENT
        var cc2 = if (cc2o != null) toMat(cc2o) else cc1

        var k1 = ColorModel.illuminantToK(i1)
        var k2 = ColorModel.illuminantToK(i2)
        if (k1 > k2) {
            val tk = k1; k1 = k2; k2 = tk
            val tm = m1; m1 = m2; m2 = tm
            val tc = cc1; cc1 = cc2; cc2 = tc
        }
        return ColorModel(m1, m2, cc1, cc2, k1, k2)
    }

    /** Cerca le matrici colore in un'altra camera posteriore o in una camera fisica del gruppo. */
    private fun findModelElsewhere(): ColorModel? {
        for (id in cm.cameraIdList) {
            try {
                val c = cm.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
                buildModel(c)?.let { return it }
                if (Build.VERSION.SDK_INT >= 28) {
                    for (pid in c.physicalCameraIds) {
                        try {
                            buildModel(cm.getCameraCharacteristics(pid))?.let { return it }
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun pickPreviewSize(c: CameraCharacteristics) {
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val sizes = map.getOutputSizes(SurfaceTexture::class.java) ?: return
        if (sizes.isEmpty()) return
        val ratio43 = sizes.filter {
            abs(it.width.toDouble() / it.height - 4.0 / 3.0) < 0.02 && it.width <= 1600
        }
        previewSize = ratio43.maxByOrNull { it.width }
            ?: sizes.filter { it.width <= 1600 }.maxByOrNull { it.width * it.height }
            ?: sizes[0]
    }

    // ---------- camera ----------

    private val captureCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult
        ) {
            val m = model ?: return

            var kNeutral: Double? = null
            val np = res.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
            if (np != null && np.size >= 3) {
                kNeutral = m.estimate(doubleArrayOf(np[0].toDouble(), np[1].toDouble(), np[2].toDouble()))
            }

            var kGains: Double? = null
            val g = res.get(CaptureResult.COLOR_CORRECTION_GAINS)
            if (g != null && g.red > 0f && g.blue > 0f) {
                val gg = (g.greenEven + g.greenOdd) / 2.0
                if (gg > 0.0) {
                    kGains = m.estimate(doubleArrayOf(1.0 / g.red, 1.0 / gg, 1.0 / g.blue))
                }
            }

            val k = kNeutral ?: kGains
            val info = "neutral point: ${kNeutral?.roundToInt() ?: "n/d"} K\n" +
                "guadagni AWB:  ${kGains?.roundToInt() ?: "n/d"} K"
            runOnUiThread { show(k, info) }
        }
    }

    private fun show(k: Double?, info: String) {
        infoText.text = info
        if (hold || k == null) return
        history.add(k)
        if (history.size > 12) history.removeAt(0)
        val median = history.sorted()[history.size / 2]
        shownK = ((median / 10.0).roundToInt()) * 10
        val target = median.toFloat()
        if (!gotReading) {
            gotReading = true
            bar.showMarker = true
            numSpring.snapTo(target)
            Springs.pop(kelvinText)
            setBtn.isEnabled = true
            setBtn.alpha = 1f
            updatePill()
        } else {
            numSpring.to(target)
        }
        descText.text = describe(shownK)
        descText.setTextColor(kColor(target))
    }

    private fun describe(k: Int): String = when {
        k < 2500 -> "Candela / molto calda"
        k < 3300 -> "Tungsteno / lampadina calda"
        k < 4000 -> "Alogena / bianco caldo"
        k < 5000 -> "Bianco neutro / LED"
        k < 6000 -> "Luce del giorno"
        k < 7000 -> "Cielo coperto"
        else -> "Ombra / cielo azzurro"
    }

    private fun openCamera() {
        val id = camId ?: run {
            infoText.text = "Nessuna camera posteriore trovata sul tablet."
            return
        }
        if (device != null || opening) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            return
        }
        opening = true
        val t = HandlerThread("kelvincam").also { it.start() }
        camThread = t
        camHandler = Handler(t.looper)
        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    opening = false
                    device = d
                    startPreview()
                }
                override fun onDisconnected(d: CameraDevice) {
                    opening = false
                    d.close()
                    device = null
                }
                override fun onError(d: CameraDevice, error: Int) {
                    opening = false
                    d.close()
                    device = null
                    runOnUiThread { infoText.text = "Errore camera: $error" }
                }
            }, camHandler)
        } catch (e: Exception) {
            opening = false
            infoText.text = "Errore apertura camera: ${e.message}"
        }
    }

    private fun startPreview() {
        val d = device ?: return
        val st = texture.surfaceTexture ?: return
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        val surface = Surface(st)
        try {
            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            }
            d.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        s.setRepeatingRequest(req.build(), captureCb, camHandler)
                    } catch (e: Exception) {
                        runOnUiThread { infoText.text = "Errore anteprima: ${e.message}" }
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    runOnUiThread { infoText.text = "Configurazione camera fallita" }
                }
            }, camHandler)
        } catch (e: Exception) {
            runOnUiThread { infoText.text = "Errore: ${e.message}" }
        }
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        opening = false
        camThread?.quitSafely()
        camThread = null
        camHandler = null
    }

    override fun onResume() {
        super.onResume()
        if (texture.isAvailable) openCamera()
    }

    override fun onPause() {
        closeCamera()
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED && texture.isAvailable) {
            openCamera()
        } else if (grantResults.isNotEmpty()) {
            infoText.text = "Serve il permesso camera per misurare."
        }
    }
}
