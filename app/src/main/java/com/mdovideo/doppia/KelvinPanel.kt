package com.mdovideo.doppia

import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
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
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Rational
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** Cio' che il misuratore Kelvin chiede a Doppia. */
interface KelvinHost {
    val activity: AppCompatActivity
    /** Quante camere Sony ci sono nell'app (1 per riquadro). */
    val camCount: Int
    fun camLabel(i: Int): String
    fun isLive(i: Int): Boolean
    fun frame(i: Int): Bitmap?
    /** Kelvin del WB impostato sulla camera (valore o preset), oppure null se Auto/sconosciuto. */
    fun wbKelvin(i: Int): Int?
    fun applyKelvin(k: Int, targets: List<Int>)
    fun askCameraPermission(done: (Boolean) -> Unit)
    fun dialogWidth(landscapeFraction: Float): Int
    fun portrait(): Boolean
    fun onKelvinClosed()
}

/** Riquadro di misura disegnato sopra l'immagine (centro del fotogramma). */
private class MeasureBox(ctx: Context) : View(ctx) {
    var aspect = 16f / 9f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2f * ctx.resources.displayMetrics.density
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        var iw = w
        var ih = w / aspect
        if (ih > h) { ih = h; iw = h * aspect }
        val l = (w - iw) / 2f
        val t = (h - ih) / 2f
        val r = RectF(l + iw * 0.4f, t + ih * 0.4f, l + iw * 0.6f, t + ih * 0.6f)
        c.drawRect(r, p)
    }
}

/**
 * Misuratore Kelvin: finestra dentro Doppia. Sorgente a scelta: una delle camere Sony
 * (dal live view, con una carta grigia/bianca al centro) o la camera del tablet/telefono.
 */
class KelvinPanel(private val host: KelvinHost) {

    private val act: AppCompatActivity = host.activity
    private val main = Handler(Looper.getMainLooper())
    private val dlg = Dialog(act)

    private var source = -1          // 0.. = camera Sony, TABLET = camera del dispositivo
    private val TABLET = 100
    private fun isCam(s: Int): Boolean = s in 0 until host.camCount
    private val history = mutableListOf<Double>()
    private var hold = false
    private var gotReading = false
    private var shownK = 0
    private var approx = true

    // viste
    private lateinit var chipsRow: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var left: LinearLayout
    private lateinit var right: LinearLayout
    private lateinit var previewFrame: FrameLayout
    private lateinit var image: ImageView
    private lateinit var box: MeasureBox
    private lateinit var texture: TextureView
    private lateinit var hint: TextView
    private lateinit var pill: TextView
    private lateinit var kelvinText: TextView
    private lateinit var descText: TextView
    private lateinit var infoText: TextView
    private lateinit var bar: KelvinBar
    private lateinit var numSpring: SpringFloat
    private lateinit var holdBtn: MaterialButton
    private lateinit var setAllBtn: MaterialButton
    private lateinit var setOneBtn: MaterialButton
    private lateinit var modeBtn: MaterialButton

    // camera del dispositivo
    private val cm: CameraManager = act.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var camThread: HandlerThread? = null
    private var camHandler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var opening = false
    private var camId: String? = null
    private var chars: CameraCharacteristics? = null
    private var previewSize = Size(1280, 960)
    private var model: ColorModel? = null
    private var tabletInit = false

    private val lin = DoubleArray(256) { (it / 255.0).pow(2.2) }

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()

    // ---------- finestra ----------

    fun show() {
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        // titolo + chiudi
        val head = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(act).apply {
            text = "Set WB"
            textSize = 22f
            setTextColor(Pal.TEXT)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(TextView(act).apply {
            text = "Chiudi"
            textSize = 15f
            setTextColor(Pal.MUTED)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { dlg.dismiss() }
        })
        root.addView(head)
        root.addView(TextView(act).apply {
            text = "Bilanciamento del bianco: misura il colore della luce e lo imposta sulle camere."
            textSize = 13f
            setTextColor(Pal.MUTED)
            setPadding(0, dp(2), 0, 0)
        })

        // passo 1: sorgente
        root.addView(stepLabel("1  Scegli con cosa misurare"), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) })
        chipsRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val hs = HorizontalScrollView(act).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipsRow)
        }
        root.addView(hs, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8); bottomMargin = dp(12) })

        // passo 2: anteprima + risultato
        root.addView(stepLabel("2  Inquadra una carta grigia o bianca"), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })
        body = LinearLayout(act)
        left = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        right = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

        previewFrame = FrameLayout(act).apply {
            background = roundBg(Color.BLACK, 16, act)
            clipToOutline = true
        }
        image = ImageView(act).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        previewFrame.addView(image, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        texture = TextureView(act)
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {
                if (source == TABLET) openTablet()
            }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
        previewFrame.addView(texture, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        box = MeasureBox(act)
        previewFrame.addView(box, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        left.addView(previewFrame)

        hint = TextView(act).apply {
            textSize = 12f
            setTextColor(Pal.MUTED)
            setPadding(0, dp(8), 0, 0)
        }
        left.addView(hint)

        pill = TextView(act).apply {
            textSize = 12f
            setTextColor(Pal.TEXT)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = roundBg(Pal.LINE, 20, act)
            text = "In attesa della misura…"
        }
        right.addView(pill, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val numRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        }
        kelvinText = TextView(act).apply {
            textSize = 52f
            setTextColor(Pal.TEXT)
            typeface = Typeface.DEFAULT_BOLD
            text = "--"
        }
        numRow.addView(kelvinText)
        numRow.addView(TextView(act).apply {
            textSize = 20f
            setTextColor(Pal.MUTED)
            text = "K"
            setPadding(dp(6), 0, 0, dp(10))
        })
        right.addView(numRow)

        descText = TextView(act).apply {
            textSize = 15f
            setTextColor(Pal.MUTED)
            gravity = Gravity.CENTER_HORIZONTAL
            text = "In attesa della misura"
        }
        right.addView(descText)

        bar = KelvinBar(act)
        right.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) })
        val scale = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, g) in listOf("2000 K" to Gravity.START, "10000 K" to Gravity.END)) {
            scale.addView(TextView(act).apply {
                textSize = 11f
                setTextColor(Pal.MUTED)
                gravity = g
                text = label
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        right.addView(scale)

        infoText = TextView(act).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Pal.MUTED)
            setPadding(0, dp(10), 0, 0)
        }
        right.addView(infoText)

        body.addView(left)
        body.addView(right)
        root.addView(body)

        numSpring = SpringFloat(K_MIN, damp = 0.85f, stiff = 120f, minVisible = 1f) { v ->
            if (!gotReading) return@SpringFloat
            val shown = ((v / 10f).roundToInt()) * 10
            kelvinText.text = if (approx) "≈ $shown" else "$shown"
            kelvinText.setTextColor(kColor(v))
            bar.frac = kFrac(v)
            bar.markerColor = kColor(v)
        }

        // passo 3: pulsanti
        root.addView(stepLabel("3  Quando vedi il numero, imposta le camere"), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })
        val buttons = FlowRow(act, dp(8))
        holdBtn = button("Blocca il valore", Pal.LINE, Pal.TEXT)
        holdBtn.setOnClickListener {
            hold = !hold
            holdBtn.text = if (hold) "Sblocca il valore" else "Blocca il valore"
            updatePill()
        }
        setAllBtn = button("Imposta le camere", Pal.MID, Pal.BG)
        setAllBtn.setOnClickListener {
            if (shownK > 0) {
                host.applyKelvin(shownK, (0 until host.camCount).filter { host.isLive(it) })
                pill.text = "Impostato su $shownK K"
            }
        }
        setOneBtn = button("Solo camera", Pal.LINE, Pal.TEXT)
        setOneBtn.setOnClickListener {
            if (shownK > 0 && isCam(source)) {
                host.applyKelvin(shownK, listOf(source))
                pill.text = "Impostato su $shownK K (solo ${host.camLabel(source)})"
            }
        }
        modeBtn = button("Metti la camera in WB Kelvin", Pal.LINE, Pal.TEXT)
        modeBtn.setOnClickListener {
            if (isCam(source)) host.applyKelvin(5600, listOf(source))
        }
        buttons.addView(holdBtn)
        buttons.addView(setAllBtn)
        buttons.addView(setOneBtn)
        buttons.addView(modeBtn)
        root.addView(buttons, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        dlg.setContentView(ScrollView(act).apply { addView(root) })
        dlg.window?.setBackgroundDrawable(roundBg(Pal.SURFACE_HIGH, 28, act))
        dlg.setOnDismissListener {
            stopAll()
            host.onKelvinClosed()
        }
        dlg.show()
        relayout()

        val firstLive = (0 until host.camCount).firstOrNull { host.isLive(it) }
        select(firstLive ?: 2)
    }

    /** Adatta la finestra all'orientamento (richiamato anche alla rotazione). */
    fun relayout() {
        if (!dlg.isShowing) return
        val port = host.portrait()
        dlg.window?.setLayout(host.dialogWidth(0.72f), ViewGroup.LayoutParams.WRAP_CONTENT)
        body.orientation = if (port) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        if (port) {
            left.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            right.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        } else {
            left.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            right.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(16) }
        }
        val ph = dp(if (port) 220 else 160)
        previewFrame.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ph)

        // la TextureView ruota con il display: dimensioni in base alla rotazione
        val rot = displayRotation()
        val tw: Int
        val th = ph
        tw = if (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270)
            (ph * previewSize.width.toFloat() / previewSize.height).toInt()
        else (ph * previewSize.height.toFloat() / previewSize.width).toInt()
        texture.layoutParams = FrameLayout.LayoutParams(tw, th, Gravity.CENTER)
        configureTransform(tw, th)
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = act.windowManager.defaultDisplay.rotation

    private fun configureTransform(viewW: Int, viewH: Int) {
        val rotation = displayRotation()
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewW.toFloat(), viewH.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val cx = viewRect.centerX()
        val cy = viewRect.centerY()
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = max(viewH.toFloat() / previewSize.height, viewW.toFloat() / previewSize.width)
            matrix.postScale(scale, scale, cx, cy)
            matrix.postRotate(90f * (rotation - 2), cx, cy)
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180f, cx, cy)
        }
        texture.setTransform(matrix)
    }

    private fun stepLabel(text: String): TextView = TextView(act).apply {
        this.text = text
        textSize = 14f
        setTextColor(Pal.MID)
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(label: String, bg: Int, fg: Int): MaterialButton = MaterialButton(act).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(fg)
        backgroundTintList = android.content.res.ColorStateList.valueOf(bg)
        cornerRadius = dp(26)
        minimumHeight = dp(48)
        Springs.pressable(this)
    }

    // ---------- scelta sorgente ----------

    private fun rebuildChips() {
        chipsRow.removeAllViews()
        for (i in 0 until host.camCount) {
            val live = host.isLive(i)
            chipsRow.addView(chip(host.camLabel(i), source == i, live) { select(i) },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) })
        }
        chipsRow.addView(chip("Tablet", source == TABLET, true) { select(TABLET) })
    }

    private fun chip(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit): TextView =
        TextView(act).apply {
            this.text = text
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (selected) Pal.BG else if (enabled) Pal.TEXT else Pal.MUTED)
            setPadding(dp(16), dp(9), dp(16), dp(9))
            background = roundBg(if (selected) Pal.MID else Pal.LINE, 18, act)
            alpha = if (enabled) 1f else 0.5f
            if (enabled) setOnClickListener { onClick() }
        }

    private fun select(s: Int) {
        if (s == source) return
        stopAll()
        source = s
        history.clear()
        gotReading = false
        hold = false
        shownK = 0
        holdBtn.text = "Blocca il valore"
        kelvinText.text = "--"
        kelvinText.setTextColor(Pal.TEXT)
        bar.showMarker = false
        descText.text = "In attesa della misura"
        descText.setTextColor(Pal.MUTED)
        infoText.text = ""
        setAllBtn.text = "Imposta le camere"
        setAllBtn.isEnabled = false
        setAllBtn.alpha = 0.4f
        setOneBtn.isEnabled = false
        setOneBtn.alpha = 0.4f
        setOneBtn.visibility = if (isCam(s) && host.camCount > 1) View.VISIBLE else View.GONE
        if (isCam(s)) setOneBtn.text = "Solo ${host.camLabel(s)}"
        modeBtn.visibility = View.GONE
        rebuildChips()
        updatePill()

        if (s == TABLET) {
            image.visibility = View.INVISIBLE
            box.visibility = View.INVISIBLE
            texture.visibility = View.VISIBLE
            hint.text = "Inquadra con il tablet una superficie bianca o grigia che riempia tutta l'immagine, sotto la luce che vuoi misurare. Il numero si aggiorna da solo."
            if (texture.isAvailable) openTablet()
        } else {
            texture.visibility = View.INVISIBLE
            image.visibility = View.VISIBLE
            box.visibility = View.VISIBLE
            hint.text = "Metti una carta grigia o bianca nel riquadro al centro, sotto la luce della scena. " +
                "Il numero si aggiorna da solo."
            main.removeCallbacks(tick)
            main.post(tick)
        }
    }

    private fun stopAll() {
        main.removeCallbacks(tick)
        closeTablet()
    }

    private fun updatePill() {
        pill.text = when {
            hold -> "Valore bloccato"
            !gotReading -> "In attesa della misura…"
            approx -> "Stima approssimata"
            else -> "Misura in tempo reale"
        }
    }

    // ---------- risultato ----------

    private fun pushReading(k: Double, info: String) {
        infoText.text = info
        if (hold) return
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
            setAllBtn.isEnabled = true
            setAllBtn.alpha = 1f
            setOneBtn.isEnabled = true
            setOneBtn.alpha = 1f
            updatePill()
        } else {
            numSpring.to(target)
        }
        descText.text = describe(shownK)
        descText.setTextColor(kColor(target))
        setAllBtn.text = "Imposta le camere a $shownK K"
    }

    private fun status(text: String) {
        if (gotReading) return
        descText.text = text
        descText.setTextColor(Pal.MUTED)
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

    // ---------- misura dal live view di una camera Sony ----------

    private val tick = object : Runnable {
        override fun run() {
            if (!dlg.isShowing) return
            if (isCam(source)) measureCamera(source)
            main.postDelayed(this, 250L)
        }
    }

    private fun measureCamera(i: Int) {
        try {
            val bmp = host.frame(i)
            if (!host.isLive(i) || bmp == null) {
                status("${host.camLabel(i)} non è in live view: collegala per misurare")
                return
            }
            image.setImageBitmap(bmp)
            box.aspect = bmp.width.toFloat() / bmp.height
            box.invalidate()

            val k0 = host.wbKelvin(i)
            if (k0 == null) {
                modeBtn.visibility = View.VISIBLE
                status("Il WB della camera è su Auto: tocca \"Metti la camera in WB Kelvin\" per poter misurare")
                return
            }
            modeBtn.visibility = View.GONE

            val w = bmp.width
            val h = bmp.height
            val x0 = (w * 0.4f).toInt()
            val x1 = (w * 0.6f).toInt()
            val y0 = (h * 0.4f).toInt()
            val y1 = (h * 0.6f).toInt()
            var sr = 0.0
            var sb = 0.0
            var sum8 = 0.0
            var n = 0
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    val p = bmp.getPixel(x, y)
                    val r = (p shr 16) and 255
                    val g = (p shr 8) and 255
                    val b = p and 255
                    sr += lin[r]
                    sb += lin[b]
                    sum8 += (r + g + b) / 3.0
                    n++
                    x += 3
                }
                y += 3
            }
            if (n == 0) return
            val mean = sum8 / n
            if (mean < 25.0) { status("Carta troppo scura: inquadra una superficie più chiara"); return }
            if (mean > 240.0) { status("Carta troppo chiara (bruciata): riduci l'esposizione"); return }
            val rr = sr / n
            val bb = sb / n
            if (rr <= 0.0 || bb <= 0.0) return

            // La carta, vista con WB = k0, mostra quanto la luce reale si discosta da k0:
            // mired(luce) = mired(k0) + 173 * ln(R/B)
            val x = ln(rr / bb)
            val mired = 1e6 / k0 + 173.0 * x
            val k = if (mired <= 100.0) 10000.0 else (1e6 / mired).coerceIn(2000.0, 10000.0)
            val tint = when {
                x > 0.04 -> "tende al caldo"
                x < -0.04 -> "tende al freddo"
                else -> "neutra"
            }
            pushReading(k, "La camera è ora su $k0 K\nLa carta appare: $tint")
        } catch (_: Throwable) {
        }
    }

    // ---------- camera del tablet ----------

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

    private fun initTablet() {
        if (tabletInit) return
        tabletInit = true
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
            pickPreviewSize(it)
        }
        if (model == null) model = ColorModel.generic()
        approx = (chars?.let { buildModel(it) ?: findModelElsewhere() }) == null
    }

    private fun openTablet() {
        initTablet()
        relayout()
        val id = camId ?: run {
            status("Nessuna camera posteriore trovata su questo dispositivo")
            return
        }
        if (device != null || opening) return
        if (act.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            status("Serve il permesso camera")
            host.askCameraPermission { ok ->
                if (ok && source == TABLET) openTablet() else if (!ok) status("Permesso camera negato")
            }
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
                    if (camThread == null || !dlg.isShowing) { d.close(); return } // pannello chiuso nel frattempo
                    device = d
                    main.post { startTabletPreview() }
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
                    main.post { status("Errore camera: $error") }
                }
            }, camHandler)
        } catch (e: Exception) {
            opening = false
            status("Errore apertura camera: ${e.message}")
        }
    }

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
                if (gg > 0.0) kGains = m.estimate(doubleArrayOf(1.0 / g.red, 1.0 / gg, 1.0 / g.blue))
            }
            val k = kNeutral ?: kGains ?: return
            val info = "neutral point: ${kNeutral?.roundToInt() ?: "n/d"} K\n" +
                "guadagni AWB: ${kGains?.roundToInt() ?: "n/d"} K" +
                (if (approx) "\nmatrici colore non disponibili: modello generico" else "")
            main.post { if (source == TABLET) pushReading(k, info) }
        }
    }

    private fun startTabletPreview() {
        val d = device ?: return
        val st = texture.surfaceTexture ?: return
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        val surface = Surface(st)
        try {
            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            }
            @Suppress("DEPRECATION")
            d.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        s.setRepeatingRequest(req.build(), captureCb, camHandler)
                    } catch (e: Exception) {
                        main.post { status("Errore anteprima: ${e.message}") }
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    main.post { status("Configurazione camera fallita") }
                }
            }, camHandler)
        } catch (e: Exception) {
            status("Errore: ${e.message}")
        }
    }

    private fun closeTablet() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        opening = false
        camThread?.quitSafely()
        camThread = null
        camHandler = null
    }
}
