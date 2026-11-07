package com.mdovideo.doppia

import android.app.Dialog
import android.content.ClipData
import android.content.ContentValues
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.Spannable
import android.text.SpannableString
import android.text.method.DigitsKeyListener
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Toast
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pulsante REC stile Sony: anello bianco con pallino rosso; quadrato rosso mentre registra. */
class RecButton(ctx: Context) : View(ctx) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = ctx.dp(2).toFloat()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() }
    var recording = false
        set(v) { field = v; invalidate() }
    var pending = false
        set(v) { field = v; alpha = if (v) 0.45f else 1f }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = width / 2f - ring.strokeWidth
        c.drawCircle(cx, cy, r, ring)
        if (recording) {
            val h = r * 0.5f
            c.drawRoundRect(cx - h, cy - h, cx + h, cy + h, h * 0.25f, h * 0.25f, fill)
        } else {
            c.drawCircle(cx, cy, r * 0.68f, fill)
        }
    }
}

/** Istogramma della luminanza (64 barre). */
class HistView(ctx: Context) : View(ctx) {
    var bins: IntArray? = null
        set(v) { field = v; invalidate() }
    private val barP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCE8EDF3.toInt() }
    private val bgP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x990B0F15.toInt() }

    override fun onDraw(c: Canvas) {
        val b = bins ?: return
        val r = dp(8).toFloat()
        c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, bgP)
        var mx = 1
        for (v in b) if (v > mx) mx = v
        val pad = dp(5).toFloat()
        val bw = (width - 2 * pad) / b.size
        val hh = height - 2 * pad
        for (i in b.indices) {
            val f = Math.sqrt(b[i].toDouble() / mx).toFloat()
            val x = pad + i * bw
            c.drawRect(x, pad + hh * (1f - f), x + bw * 0.85f, pad + hh, barP)
        }
    }
}

/** Esposimetro come il "M.M." Sony: scala da -3 a +3 EV con indicatore (stimato dall'immagine). */
class MeterView(ctx: Context) : View(ctx) {
    var ev = 0f
        set(v) { field = v; invalidate() }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.MUTED; strokeWidth = ctx.dp(1).toFloat() }
    private val zero = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = ctx.dp(2).toFloat() }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.TEXT
        textSize = ctx.dp(12).toFloat()
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.MUTED
        textSize = ctx.dp(9).toFloat()
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = dp(14).toFloat()
        val x0 = pad
        val x1 = w - pad
        val yl = h * 0.60f
        for (k in -9..9) {
            val x = x0 + (k + 9) / 18f * (x1 - x0)
            val big = k % 3 == 0
            val th = dp(if (big) 8 else 4).toFloat()
            c.drawLine(x, yl - th, x, yl, if (k == 0) zero else tick)
        }
        c.drawText("-3", x0, yl + dp(11), label)
        c.drawText("0", (x0 + x1) / 2f, yl + dp(11), label)
        c.drawText("+3", x1, yl + dp(11), label)
        val e = ev.coerceIn(-3f, 3f)
        val xm = x0 + (e + 3f) / 6f * (x1 - x0)
        mark.color = if (Math.abs(ev) > 2f) 0xFFE53935.toInt() else Pal.MID
        val p = Path()
        p.moveTo(xm, yl + dp(1))
        p.lineTo(xm - dp(5), yl - dp(9))
        p.lineTo(xm + dp(5), yl - dp(9))
        p.close()
        c.drawPath(p, mark)
        c.drawText(String.format(Locale.ROOT, "%+.1f EV", ev), w / 2f, dp(11).toFloat(), txt)
    }
}

/** Griglia dei terzi, allineata all'immagine (non al riquadro). */
class GridView(ctx: Context) : View(ctx) {
    var aspect = 16f / 9f
    private val p = Paint().apply { color = 0x99FFFFFF.toInt(); strokeWidth = ctx.dp(1).toFloat() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        var iw = w
        var ih = w / aspect
        if (ih > h) { ih = h; iw = h * aspect }
        val l = (w - iw) / 2f
        val t = (h - ih) / 2f
        for (k in 1..2) {
            c.drawLine(l + iw * k / 3f, t, l + iw * k / 3f, t + ih, p)
            c.drawLine(l, t + ih * k / 3f, l + iw, t + ih * k / 3f, p)
        }
    }
}

/** Guide di inquadratura per i social: ritagli centrati sull'immagine, ognuno col suo colore e nome. */
class GuidesView(ctx: Context) : View(ctx) {
    var aspect = 16f / 9f
    var mask = 0
    var safe = false
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp(2).toFloat()
    }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp(1).toFloat()
        color = 0xCCFFFFFF.toInt()
        pathEffect = DashPathEffect(floatArrayOf(ctx.dp(8).toFloat(), ctx.dp(6).toFloat()), 0f)
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = ctx.dp(12).toFloat()
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(ctx.dp(2).toFloat(), 0f, 0f, 0xFF000000.toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        var iw = w
        var ih = w / aspect
        if (ih > h) { ih = h; iw = h * aspect }
        val l = (w - iw) / 2f
        val t = (h - ih) / 2f
        for (k in Guides.names.indices) {
            if ((mask and (1 shl k)) == 0) continue
            val a = Guides.ratios[k]
            var gw = iw
            var gh = iw / a
            if (gh > ih) { gh = ih; gw = ih * a }
            val gl = l + (iw - gw) / 2f
            val gt = t + (ih - gh) / 2f
            line.color = Guides.colors[k]
            c.drawRect(gl, gt, gl + gw, gt + gh, line)
            txt.color = Guides.colors[k]
            c.drawText(Guides.names[k], gl + dp(6), gt + dp(16), txt)
        }
        if (safe) {
            // zona sicura 9:16: lontano da titoli in alto, didascalie in basso e pulsanti a destra (valori approssimati)
            val a = Guides.ratios[0]
            var gw = iw
            var gh = iw / a
            if (gh > ih) { gh = ih; gw = ih * a }
            val gl = l + (iw - gw) / 2f
            val gt = t + (ih - gh) / 2f
            val sl = gl + gw * 0.06f
            val sr = gl + gw * 0.86f
            val st = gt + gh * 0.12f
            val sb = gt + gh * 0.78f
            c.drawRect(sl, st, sr, sb, dash)
            txt.color = 0xCCFFFFFF.toInt()
            c.drawText("zona sicura", sl + dp(6), st + dp(16), txt)
        }
    }
}

/** Pulsante tondo con icona disegnata: fotocamera (screenshot), condividi, livelli (sovrapposizione). */
class IconButton(ctx: Context) : View(ctx) {
    enum class Kind { CAMERA, SHARE, LAYERS }

    var kind = Kind.CAMERA
        set(v) { field = v; invalidate() }
    var active = false
        set(v) { field = v; invalidate() }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp(2).toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        bg.color = if (active) 0xF2FFC107.toInt() else 0xCC0B0F15.toInt()
        c.drawCircle(w / 2f, h / 2f, w / 2f, bg)
        val ink = if (active) Pal.BG else Color.WHITE
        stroke.color = ink
        fill.color = ink
        when (kind) {
            Kind.CAMERA -> {
                c.drawRoundRect(w * 0.26f, h * 0.36f, w * 0.74f, h * 0.68f, w * 0.06f, w * 0.06f, stroke)
                c.drawLine(w * 0.40f, h * 0.36f, w * 0.44f, h * 0.29f, stroke)
                c.drawLine(w * 0.44f, h * 0.29f, w * 0.56f, h * 0.29f, stroke)
                c.drawLine(w * 0.56f, h * 0.29f, w * 0.60f, h * 0.36f, stroke)
                c.drawCircle(w / 2f, h * 0.52f, w * 0.08f, stroke)
            }
            Kind.SHARE -> {
                val ax = w * 0.70f; val ay = h * 0.30f
                val bx = w * 0.30f; val by = h * 0.50f
                val cx = w * 0.70f; val cy = h * 0.70f
                c.drawLine(bx, by, ax, ay, stroke)
                c.drawLine(bx, by, cx, cy, stroke)
                val r = w * 0.075f
                c.drawCircle(ax, ay, r, fill)
                c.drawCircle(bx, by, r, fill)
                c.drawCircle(cx, cy, r, fill)
            }
            Kind.LAYERS -> {
                c.drawRoundRect(w * 0.28f, h * 0.26f, w * 0.62f, h * 0.58f, w * 0.05f, w * 0.05f, stroke)
                c.drawRoundRect(w * 0.38f, h * 0.42f, w * 0.72f, h * 0.74f, w * 0.05f, w * 0.05f, stroke)
            }
        }
    }
}

/** Un riquadro con il live di una camera, in stile monitor Sony: REC, otturatore / diaframma / ISO, esposimetro, istogramma. */
class Tile(ctx: Context, private val label: String, private val accent: Int) : FrameLayout(ctx) {
    private val img = ImageView(ctx)
    private val zebraImg = ImageView(ctx)
    private val peakImg = ImageView(ctx)
    private val gridV = GridView(ctx)
    private val guideV = GuidesView(ctx)
    private val tag = TextView(ctx)
    private val lag = TextView(ctx)
    private val empty = TextView(ctx)
    private val msgBox = LinearLayout(ctx)
    private val connectBtn = MaterialButton(ctx)

    private val recPill = TextView(ctx)
    private val batt = TextView(ctx)
    private val recBtn = RecButton(ctx)
    private val bar = LinearLayout(ctx)
    private val meterBox = LinearLayout(ctx)
    private val meterV = MeterView(ctx)
    private val histV = HistView(ctx)
    private val picker = HorizontalScrollView(ctx)
    private val pickerRow = LinearLayout(ctx)
    private val chipBox = HashMap<Int, LinearLayout>()
    private val chipVal = HashMap<Int, TextView>()
    private val chipCodes = intArrayOf(SonyProps.SHUTTER, SonyProps.APERTURE, SonyProps.ISO)
    private val pickerItems = ArrayList<Pair<Long, TextView>>()
    private var props: Map<Int, Prop> = emptyMap()
    private var pickerCode = 0
    private var recStart = 0L
    private var labelText = label
    private var camState = CamState.IDLE

    // Screenshot / condividi e sovrapposizione
    private val overlayImg = ImageView(ctx)
    private val side = LinearLayout(ctx)
    private val shotBtn = IconButton(ctx)
    private val ovlBtn = IconButton(ctx)
    private val ovlPanel = LinearLayout(ctx)
    private val ovlLabel = TextView(ctx)
    private val ovlSeek = SeekBar(ctx)
    private var lastFrame: Bitmap? = null
    private var overlayBmp: Bitmap? = null
    private var hasFrame = false
    private val shotRevert = Runnable {
        shotBtn.kind = IconButton.Kind.CAMERA
        shotBtn.active = false
    }
    var onShot: (() -> Unit)? = null
    var onShare: (() -> Unit)? = null
    var onOvlLast: (() -> Unit)? = null
    var onOvlPick: (() -> Unit)? = null
    var onOvlClear: (() -> Unit)? = null
    var onOvlOpacity: ((Int) -> Unit)? = null
    private var evSmooth = 0f
    private val borderRed = GradientDrawable().apply {
        setColor(0)
        setStroke(ctx.dp(4), 0xFFE53935.toInt())
        cornerRadius = ctx.dp(22).toFloat()
    }

    /** True se la camera sta registrando (letto dalla camera, mai ipotizzato). */
    var recording = false
        private set
    var onRec: (() -> Unit)? = null
    /** Chiamata quando la camera inizia (true) o finisce (false) di registrare. */
    var onRecChange: ((Boolean) -> Unit)? = null
    var onConnect: (() -> Unit)? = null
    var onPick: ((Prop, Long) -> Unit)? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (!recording) return
            val sec = ((SystemClock.elapsedRealtime() - recStart) / 1000).toInt()
            val dot = if (sec % 2 == 0) "●" else "○"
            recPill.text = String.format(Locale.ROOT, "%s REC  %02d:%02d:%02d", dot, sec / 3600, (sec / 60) % 60, sec % 60)
            postDelayed(this, 500)
        }
    }

    private fun full() = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private fun divider(): View {
        val v = View(context)
        v.setBackgroundColor(Pal.LINE)
        val lp = LinearLayout.LayoutParams(dp(1), dp(30))
        lp.setMargins(dp(6), 0, dp(6), 0)
        v.layoutParams = lp
        return v
    }

    init {
        background = roundBg(Pal.SURFACE, 22, ctx)
        clipToOutline = true
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        addView(img, full())
        overlayImg.scaleType = ImageView.ScaleType.FIT_CENTER
        overlayImg.alpha = 0.5f
        overlayImg.visibility = View.GONE
        addView(overlayImg, full())
        zebraImg.scaleType = ImageView.ScaleType.FIT_CENTER
        zebraImg.visibility = View.GONE
        addView(zebraImg, full())
        peakImg.scaleType = ImageView.ScaleType.FIT_CENTER
        peakImg.visibility = View.GONE
        addView(peakImg, full())
        gridV.visibility = View.GONE
        addView(gridV, full())
        guideV.visibility = View.GONE
        addView(guideV, full())

        // Al centro: tasto Connetti / Riprova / Annulla e, sotto, il messaggio di stato
        msgBox.orientation = LinearLayout.VERTICAL
        msgBox.gravity = Gravity.CENTER
        connectBtn.isAllCaps = false
        connectBtn.textSize = 16f
        connectBtn.setTextColor(Pal.BG)
        connectBtn.backgroundTintList = ColorStateList.valueOf(accent)
        connectBtn.cornerRadius = dp(26)
        connectBtn.insetTop = 0
        connectBtn.insetBottom = 0
        connectBtn.minimumWidth = dp(160)
        connectBtn.minimumHeight = dp(52)
        connectBtn.text = "Connetti"
        connectBtn.setOnClickListener { onConnect?.invoke() }
        Springs.pressable(connectBtn)
        msgBox.addView(connectBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        empty.setTextColor(Pal.MUTED)
        empty.textSize = 15f
        empty.gravity = Gravity.CENTER
        empty.text = "Nessun segnale"
        empty.setPadding(dp(24), dp(14), dp(24), 0)
        msgBox.addView(empty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(msgBox, full())

        tag.setTextColor(Pal.TEXT)
        tag.textSize = 13f
        tag.typeface = Typeface.DEFAULT_BOLD
        tag.setPadding(dp(10), dp(5), dp(10), dp(5))
        tag.background = roundBg(0xCC0B0F15.toInt(), 12, ctx)
        updateTag()
        val tp = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tp.gravity = Gravity.TOP or Gravity.START
        tp.setMargins(dp(12), dp(12), 0, 0)
        addView(tag, tp)

        // Batteria e memoria residua, sotto il nome
        batt.textSize = 15f
        batt.typeface = Typeface.DEFAULT_BOLD
        batt.setPadding(dp(10), dp(4), dp(10), dp(4))
        batt.background = roundBg(0xCC0B0F15.toInt(), 12, ctx)
        batt.visibility = View.GONE
        val bp = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        bp.gravity = Gravity.TOP or Gravity.START
        bp.setMargins(dp(12), dp(52), 0, 0)
        addView(batt, bp)

        lag.setTextColor(Pal.TEXT)
        lag.textSize = 12f
        lag.typeface = Typeface.MONOSPACE
        lag.setPadding(dp(10), dp(4), dp(10), dp(4))
        lag.background = roundBg(0xCC0B0F15.toInt(), 14, ctx)
        lag.visibility = View.GONE
        val lp = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.TOP or Gravity.END
        lp.setMargins(0, dp(12), dp(12), 0)
        addView(lag, lp)

        // Pillola REC con il tempo, in alto al centro
        recPill.setTextColor(Color.WHITE)
        recPill.textSize = 14f
        recPill.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        recPill.setPadding(dp(14), dp(6), dp(14), dp(6))
        recPill.background = roundBg(0xE6C62828.toInt(), 16, ctx)
        recPill.visibility = View.GONE
        val rp = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        rp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        rp.topMargin = dp(12)
        addView(recPill, rp)

        // Istogramma in basso a sinistra
        histV.visibility = View.GONE
        val hp = LayoutParams(dp(120), dp(60))
        hp.gravity = Gravity.BOTTOM or Gravity.START
        hp.setMargins(dp(12), 0, 0, dp(12))
        addView(histV, hp)

        // Barra monitor in basso: REC | otturatore diaframma ISO | esposimetro
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.background = roundBg(0xD90B0F15.toInt(), 18, ctx)
        bar.setPadding(dp(8), dp(5), dp(8), dp(5))
        bar.visibility = View.GONE

        recBtn.setOnClickListener {
            recBtn.pending = true
            postDelayed({ recBtn.pending = false }, 7000)
            onRec?.invoke()
        }
        bar.addView(recBtn, LinearLayout.LayoutParams(dp(46), dp(46)))
        bar.addView(divider())

        val titles = arrayOf("SHUTTER", "IRIS", "ISO")
        for (k in chipCodes.indices) {
            val code = chipCodes[k]
            val box = LinearLayout(ctx)
            box.orientation = LinearLayout.VERTICAL
            box.gravity = Gravity.CENTER
            box.setPadding(dp(12), dp(3), dp(12), dp(3))
            val t = TextView(ctx)
            t.text = titles[k]
            t.textSize = 9f
            t.setTextColor(Pal.MUTED)
            t.gravity = Gravity.CENTER
            val v = TextView(ctx)
            v.text = "—"
            v.textSize = 19f
            v.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            v.setTextColor(Pal.MUTED)
            v.gravity = Gravity.CENTER
            box.addView(t)
            box.addView(v)
            box.setOnClickListener { togglePicker(code) }
            bar.addView(box)
            chipBox[code] = box
            chipVal[code] = v
        }

        meterBox.orientation = LinearLayout.HORIZONTAL
        meterBox.gravity = Gravity.CENTER_VERTICAL
        meterBox.visibility = View.GONE
        meterBox.addView(divider())
        meterBox.addView(meterV, LinearLayout.LayoutParams(dp(150), dp(38)))
        bar.addView(meterBox)

        val barLp = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        barLp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        barLp.bottomMargin = dp(12)
        addView(bar, barLp)

        // Selettore valori sopra la barra (scorre in orizzontale, tocca per scegliere)
        pickerRow.orientation = LinearLayout.HORIZONTAL
        pickerRow.setPadding(dp(8), dp(4), dp(8), dp(4))
        picker.isHorizontalScrollBarEnabled = false
        picker.addView(pickerRow)
        picker.background = roundBg(0xE60B0F15.toInt(), 16, ctx)
        picker.visibility = View.GONE
        val pkLp = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        pkLp.gravity = Gravity.BOTTOM
        pkLp.setMargins(dp(12), 0, dp(12), dp(124))
        addView(picker, pkLp)

        // Pulsanti piccoli sotto il live view, sopra la barra REC: screenshot (poi condividi) e sovrapposizione
        side.orientation = LinearLayout.HORIZONTAL
        side.gravity = Gravity.CENTER_VERTICAL
        side.visibility = View.GONE
        shotBtn.setOnClickListener {
            if (shotBtn.kind == IconButton.Kind.SHARE) {
                removeCallbacks(shotRevert)
                shotRevert.run()
                onShare?.invoke()
            } else {
                onShot?.invoke()
            }
        }
        ovlBtn.kind = IconButton.Kind.LAYERS
        ovlBtn.setOnClickListener {
            ovlPanel.visibility = if (ovlPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        side.addView(shotBtn, LinearLayout.LayoutParams(dp(34), dp(34)))
        val ovlLp = LinearLayout.LayoutParams(dp(34), dp(34))
        ovlLp.leftMargin = dp(8)
        side.addView(ovlBtn, ovlLp)
        val sdLp = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        sdLp.gravity = Gravity.BOTTOM or Gravity.END
        sdLp.setMargins(dp(12), 0, dp(12), dp(78))
        addView(side, sdLp)

        // Pannello della sovrapposizione: cursore opacita' (50% di partenza) e scelta dell'immagine
        ovlPanel.orientation = LinearLayout.VERTICAL
        ovlPanel.background = roundBg(0xEE0B0F15.toInt(), 16, ctx)
        ovlPanel.setPadding(dp(14), dp(10), dp(14), dp(12))
        ovlPanel.isClickable = true
        ovlPanel.visibility = View.GONE
        ovlLabel.setTextColor(Pal.TEXT)
        ovlLabel.textSize = 13f
        ovlLabel.typeface = Typeface.DEFAULT_BOLD
        ovlLabel.text = "Sovrapposizione  50%"
        ovlPanel.addView(ovlLabel)
        ovlSeek.max = 100
        ovlSeek.progress = 50
        ovlSeek.progressTintList = ColorStateList.valueOf(Pal.MID)
        ovlSeek.thumbTintList = ColorStateList.valueOf(Pal.MID)
        ovlSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                ovlLabel.text = "Sovrapposizione  $p%"
                overlayImg.alpha = p / 100f
                if (fromUser) onOvlOpacity?.invoke(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        ovlPanel.addView(ovlSeek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val prow = LinearLayout(ctx)
        prow.orientation = LinearLayout.HORIZONTAL
        fun ovlPill(text: String, action: () -> Unit, first: Boolean) {
            val t = TextView(ctx)
            t.text = text
            t.textSize = 13f
            t.typeface = Typeface.DEFAULT_BOLD
            t.setTextColor(Pal.TEXT)
            t.background = roundBg(Pal.LINE, 14, ctx)
            t.setPadding(dp(12), dp(7), dp(12), dp(7))
            t.setOnClickListener { action() }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (!first) lp.leftMargin = dp(6)
            prow.addView(t, lp)
        }
        ovlPill("Ultimo", { onOvlLast?.invoke() }, true)
        ovlPill("Galleria", { onOvlPick?.invoke() }, false)
        ovlPill("Togli", { onOvlClear?.invoke() }, false)
        val prLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        prLp.topMargin = dp(6)
        ovlPanel.addView(prow, prLp)
        val note = TextView(ctx)
        note.text = "Ultimo = l'ultimo screenshot di questa camera"
        note.textSize = 11f
        note.setTextColor(Pal.MUTED)
        note.setPadding(0, dp(6), 0, 0)
        ovlPanel.addView(note)
        val pnLp = LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT)
        pnLp.gravity = Gravity.BOTTOM or Gravity.END
        pnLp.setMargins(dp(12), 0, dp(12), dp(78 + 34 + 8))
        addView(ovlPanel, pnLp)
    }

    /** Pulsanti screenshot e sovrapposizione dal lato esterno: a destra per il riquadro di destra, a sinistra per quello di sinistra. */
    fun setSide(right: Boolean) {
        val g = Gravity.BOTTOM or (if (right) Gravity.END else Gravity.START)
        val a = side.layoutParams as LayoutParams
        a.gravity = g
        side.layoutParams = a
        val b = ovlPanel.layoutParams as LayoutParams
        b.gravity = g
        ovlPanel.layoutParams = b
    }

    /** Ultimo fotogramma ricevuto (senza strumenti sopra), per lo screenshot. */
    fun frame(): Bitmap? = lastFrame

    /** Screenshot salvato: il pulsante diventa Condividi (torna fotocamera dopo la condivisione o 20 secondi). */
    fun shotReady() {
        shotBtn.kind = IconButton.Kind.SHARE
        shotBtn.active = true
        removeCallbacks(shotRevert)
        postDelayed(shotRevert, 20000)
    }

    /** Immagine di confronto sopra il live; null per toglierla. */
    fun setOverlay(b: Bitmap?, opacity: Int) {
        overlayBmp = b
        overlayImg.setImageBitmap(b)
        ovlSeek.progress = opacity
        overlayImg.alpha = opacity / 100f
        ovlLabel.text = "Sovrapposizione  $opacity%"
        ovlBtn.active = b != null
        overlayImg.visibility = if (b != null && hasFrame) View.VISIBLE else View.GONE
    }

    fun setFrame(b: Bitmap, ms: Int, a: Analysis?) {
        img.setImageBitmap(b)
        lastFrame = b
        hasFrame = true
        side.visibility = View.VISIBLE
        overlayImg.visibility = if (overlayBmp != null) View.VISIBLE else View.GONE
        msgBox.visibility = View.GONE
        lag.visibility = View.VISIBLE
        val s = SpannableString("● $ms ms")
        s.setSpan(ForegroundColorSpan(lagColor(ms)), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        lag.text = s

        gridV.aspect = b.width.toFloat() / b.height
        gridV.visibility = if (Monitor.grid) View.VISIBLE else View.GONE
        gridV.invalidate()
        guideV.aspect = b.width.toFloat() / b.height
        guideV.mask = Monitor.guides
        guideV.safe = Monitor.safe
        guideV.visibility = if (Monitor.guides != 0 || Monitor.safe) View.VISIBLE else View.GONE
        guideV.invalidate()

        val z = a?.zebra
        zebraImg.setImageBitmap(z)
        zebraImg.visibility = if (z != null) View.VISIBLE else View.GONE
        val pk = a?.peaking
        peakImg.setImageBitmap(pk)
        peakImg.visibility = if (pk != null) View.VISIBLE else View.GONE

        histV.bins = a?.hist
        histV.visibility = if (a?.hist != null) View.VISIBLE else View.GONE

        if (a != null && Monitor.meter) {
            evSmooth = evSmooth * 0.8f + a.ev * 0.2f
            meterV.ev = evSmooth
            meterBox.visibility = View.VISIBLE
        } else {
            meterBox.visibility = View.GONE
        }
    }

    /** Secondi di registrazione (contati dall'app da quando rileva il REC), -1 se non registra. */
    fun recSeconds(): Int =
        if (!recording) -1 else ((SystemClock.elapsedRealtime() - recStart) / 1000).toInt()

    /** Millisecondi di registrazione dal momento in cui l'app ha rilevato il REC, -1 se non registra. */
    fun recElapsedMs(): Long =
        if (!recording) -1L else SystemClock.elapsedRealtime() - recStart

    /** Tally grande: cornice rossa spessa e pillola REC piu' grande, visibili da lontano. */
    fun setBigTally(on: Boolean) {
        borderRed.setStroke(dp(if (on) 12 else 4), 0xFFE53935.toInt())
        recPill.textSize = if (on) 22f else 14f
    }

    fun setLabel(text: String) {
        labelText = text
        updateTag()
    }

    /** Nome della camera con il puntino dello stato: grigio spenta, giallo in collegamento, verde live, rosso errore. */
    private fun updateTag() {
        val c = when (camState) {
            CamState.IDLE -> Pal.MUTED
            CamState.CONNECTING, CamState.WAITING -> Pal.MID
            CamState.LIVE -> Pal.OK
            CamState.ERROR -> 0xFFE53935.toInt()
        }
        val sp = SpannableString("● $labelText")
        sp.setSpan(ForegroundColorSpan(c), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        tag.text = sp
    }

    /** Cambia solo il messaggio sotto il tasto (es. IP mancante). */
    fun showMessage(text: String) {
        empty.text = text
    }

    fun setState(st: CamState) {
        camState = st
        updateTag()
        when (st) {
            CamState.IDLE -> clear("Nessun segnale", "Connetti")
            CamState.CONNECTING -> clear("Mi collego…", "Annulla")
            CamState.WAITING -> clear("In attesa del live view…", "Annulla")
            CamState.ERROR -> clear("Collegamento non riuscito\n(il log è in Impostazioni)", "Riprova")
            CamState.LIVE -> {}
        }
    }

    /** Aggiorna valori, stato REC e pulsanti con la tabella proprieta' appena letta dalla camera. */
    fun setProps(m: Map<Int, Prop>) {
        props = m
        bar.visibility = View.VISIBLE
        for (code in chipCodes) {
            val p = m[code]
            val tv = chipVal[code]!!
            tv.text = if (p == null) "—" else SonyProps.fmt(code, p.current)
            tv.setTextColor(if (p != null && p.settable) Pal.TEXT else Pal.MUTED)
            chipBox[code]!!.background = if (pickerCode == code) roundBg(0x33FFC107.toInt(), 12, context) else null
        }
        if (pickerCode != 0) {
            val p = m[pickerCode]
            if (p == null || !p.settable) closePicker() else highlight(p.current, false)
        }
        val bl = SonyProps.batteryPercent(m)
        val mem = SonyProps.mediaSeconds(m)
        val parts = ArrayList<String>()
        parts.add(if (bl != null) "🔋 $bl%" else "🔋 —")
        val valid = mem.filterNotNull()
        if (valid.isEmpty()) parts.add("💾 —")
        else parts.add("💾 " + mem.mapIndexedNotNull { i, s -> if (s == null) null else (if (mem.size > 1 && valid.size > 1) "${i + 1}: " else "") + SonyProps.fmtRemain(s) }.joinToString("  "))
        batt.text = parts.joinToString("   ")
        val lowBat = bl != null && bl <= 10
        val lowMem = valid.isNotEmpty() && valid.minOrNull()!! < 600
        val warn = (bl != null && bl <= 25) || (valid.isNotEmpty() && valid.minOrNull()!! < 1200)
        batt.setTextColor(if (lowBat || lowMem) 0xFFEF5350.toInt() else if (warn) Pal.MID else Pal.TEXT)
        batt.visibility = View.VISIBLE
        val r = (m[SonyProps.REC_STATE]?.current ?: 0L) != 0L
        if (r != recording) {
            recording = r
            recBtn.pending = false
            recBtn.recording = r
            foreground = if (r) borderRed else null
            if (r) {
                recStart = SystemClock.elapsedRealtime()
                recPill.visibility = View.VISIBLE
                removeCallbacks(ticker)
                post(ticker)
            } else {
                recPill.visibility = View.GONE
                removeCallbacks(ticker)
            }
            onRecChange?.invoke(r)
        }
    }

    private fun togglePicker(code: Int) {
        if (pickerCode == code) { closePicker(); return }
        val p = props[code] ?: return
        if (!p.settable || p.options.isEmpty()) return
        pickerCode = code
        pickerRow.removeAllViews()
        pickerItems.clear()
        for (v in p.options) {
            val t = TextView(context)
            t.text = SonyProps.fmt(code, v)
            t.textSize = 17f
            t.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            t.setPadding(dp(14), dp(8), dp(14), dp(8))
            t.setOnClickListener { onPick?.invoke(p, v) }
            pickerRow.addView(t)
            pickerItems.add(v to t)
        }
        picker.visibility = View.VISIBLE
        for (c in chipCodes) chipBox[c]?.background = if (c == code) roundBg(0x33FFC107.toInt(), 12, context) else null
        highlight(p.current, true)
    }

    private fun closePicker() {
        pickerCode = 0
        picker.visibility = View.GONE
        for (c in chipCodes) chipBox[c]?.background = null
    }

    private fun highlight(cur: Long, scroll: Boolean) {
        var curView: TextView? = null
        for ((v, t) in pickerItems) {
            val on = v == cur
            t.setTextColor(if (on) Pal.MID else Pal.TEXT)
            t.background = if (on) roundBg(0x33FFC107.toInt(), 10, context) else null
            if (on) curView = t
        }
        if (scroll && curView != null) {
            val t = curView
            picker.post { picker.scrollTo(maxOf(0, t.left - picker.width / 2 + t.width / 2), 0) }
        }
    }

    private fun clear(text: String, btn: String) {
        img.setImageDrawable(null)
        lastFrame = null
        hasFrame = false
        side.visibility = View.GONE
        overlayImg.visibility = View.GONE
        ovlPanel.visibility = View.GONE
        removeCallbacks(shotRevert)
        shotRevert.run()
        zebraImg.visibility = View.GONE
        peakImg.visibility = View.GONE
        gridV.visibility = View.GONE
        guideV.visibility = View.GONE
        histV.visibility = View.GONE
        meterBox.visibility = View.GONE
        lag.visibility = View.GONE
        empty.text = text
        connectBtn.text = btn
        msgBox.visibility = View.VISIBLE
        closePicker()
        bar.visibility = View.GONE
        recPill.visibility = View.GONE
        foreground = null
        batt.visibility = View.GONE
        val was = recording
        recording = false
        recBtn.recording = false
        recBtn.pending = false
        removeCallbacks(ticker)
        if (was) onRecChange?.invoke(false)
    }
}

class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("doppia", MODE_PRIVATE) }
    private val cams = arrayOfNulls<SonyCam>(2)
    /** Modalita' test (camere finte): sempre spenta all'avvio, si accende in Impostazioni con il PIN. */
    private var simOn = false
    private lateinit var tiles: Array<Tile>
    private lateinit var status: TextView
    private lateinit var logClose: MaterialButton
    private lateinit var recAllBtn: MaterialButton
    private lateinit var syncBtn: MaterialButton
    private var syncOn = false
    private lateinit var updBtn: MaterialButton
    private var pendingUpdate: Updater.Release? = null
    @Volatile private var updCancel = false
    private var pickFor = -1
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val i = pickFor
        if (uri != null && i in 0..1) importOverlay(i, uri)
    }
    private lateinit var rootView: LinearLayout
    private val stopBtns = arrayOfNulls<MaterialButton>(2)
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private val liveNow = BooleanArray(2)
    private val lines = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
    private var solo = -1
    private lateinit var tilesRow: LinearLayout
    private var settingsDlg: Dialog? = null
    private var settingsParams: LinearLayout? = null
    private var settingsSig = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Monitor.load(prefs)
        syncOn = prefs.getBoolean("sync", false)
        Session.load(prefs)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Pal.BG)
        root.fitsSystemWindows = true
        root.setPadding(dp(14), dp(10), dp(14), dp(12))
        rootView = root

        // Barra in alto: titolo + indicatore camere a sinistra, REC A+B e Impostazioni a destra
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(this)
        title.text = "Doppia"
        title.setTextColor(Pal.TEXT)
        title.textSize = 22f
        title.typeface = Typeface.DEFAULT_BOLD
        bar.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        status = TextView(this)
        status.setTextColor(Pal.MUTED)
        status.textSize = 12f
        status.typeface = Typeface.MONOSPACE
        status.setPadding(dp(12), dp(5), dp(12), dp(5))
        status.background = roundBg(Pal.CARD, 14, this)
        bar.addView(status, spaced(12))
        updBtn = pillButton("⬆ Aggiorna")
        updBtn.backgroundTintList = ColorStateList.valueOf(Pal.MID)
        updBtn.setTextColor(Pal.BG)
        updBtn.visibility = View.GONE
        updBtn.setOnClickListener { pendingUpdate?.let { showUpdateDialog(it) } }
        bar.addView(updBtn, spaced(8))
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        recAllBtn = pillButton("REC A+B")
        recAllBtn.backgroundTintList = ColorStateList.valueOf(0xFFB71C1C.toInt())
        recAllBtn.setOnClickListener { recBoth() }
        val setBtn = pillButton("Impostazioni")
        setBtn.setOnClickListener { showSettings() }
        syncBtn = pillButton("Sync")
        syncBtn.setOnClickListener {
            syncOn = !syncOn
            prefs.edit().putBoolean("sync", syncOn).apply()
            paintSync()
            val msg = if (syncOn) "Sync attiva: le modifiche vanno su entrambe le camere" else "Sync spenta: ogni camera per conto suo"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            addLog(msg)
        }
        paintSync()
        val goodBtn = pillButton("⚑ Buona")
        goodBtn.backgroundTintList = ColorStateList.valueOf(MarkCat.colors[MarkCat.GOOD])
        goodBtn.setTextColor(Pal.BG)
        goodBtn.setOnClickListener { addMark(MarkCat.GOOD, "") }
        val badBtn = pillButton("⚑ Scarto")
        badBtn.backgroundTintList = ColorStateList.valueOf(MarkCat.colors[MarkCat.BAD])
        badBtn.setTextColor(Pal.BG)
        badBtn.setOnClickListener { addMark(MarkCat.BAD, "") }
        val noteBtn = pillButton("⚑ Nota")
        noteBtn.backgroundTintList = ColorStateList.valueOf(MarkCat.colors[MarkCat.NOTE])
        noteBtn.setTextColor(Pal.BG)
        noteBtn.setOnClickListener { noteDialog() }
        bar.addView(syncBtn, spaced(10))
        bar.addView(goodBtn, spaced(6))
        bar.addView(badBtn, spaced(6))
        bar.addView(noteBtn, spaced(6))
        bar.addView(recAllBtn, spaced(6))
        bar.addView(setBtn, spaced(6))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Area dei due riquadri, con il log sopra
        val stage = FrameLayout(this)
        tilesRow = LinearLayout(this)
        tilesRow.orientation = LinearLayout.HORIZONTAL
        tiles = arrayOf(Tile(this, camName(0), Pal.COLD), Tile(this, camName(1), Pal.HOT))
        for (i in 0..1) {
            val t = tiles[i]
            t.onConnect = { toggle(i) }
            t.onShot = { takeShot(i) }
            t.onShare = { shareShot(i) }
            t.onOvlLast = { overlayFromLast(i) }
            t.onOvlPick = { pickFor = i; pickImage.launch("image/*") }
            t.onOvlClear = { overlayClear(i) }
            t.onOvlOpacity = { pct -> prefs.edit().putInt("ovlop$i", pct).apply() }
            t.onRec = { cams[i]?.toggleRec() }
            t.onRecChange = { r -> onRecChange(i, r) }
            t.onPick = { p, v -> applyProp(i, p, v) }
            Springs.pressable(t, 0.985f)
            t.setOnClickListener {
                solo = if (solo == i) -1 else i
                applyLayout()
            }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            if (i == 1) lp.leftMargin = dp(10)
            tilesRow.addView(t, lp)
        }
        stage.addView(tilesRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tiles[0].setSide(false)
        tiles[1].setSide(true)
        for (i in 0..1) {
            val f = overlayFile(i)
            val bmp = if (f.exists()) BitmapFactory.decodeFile(f.path) else null
            tiles[i].setOverlay(bmp, prefs.getInt("ovlop$i", 50))
        }

        logText = TextView(this)
        logText.setTextColor(Pal.TEXT)
        logText.textSize = 11.5f
        logText.typeface = Typeface.MONOSPACE
        logText.setPadding(dp(14), dp(12), dp(14), dp(12))
        logScroll = ScrollView(this)
        logScroll.background = roundBg(0xE60B0F15.toInt(), 22, this)
        logScroll.addView(logText)
        logScroll.visibility = View.GONE
        stage.addView(logScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        logClose = pillButton("Chiudi log")
        logClose.visibility = View.GONE
        logClose.setOnClickListener { showLog(false) }
        val lcp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lcp.gravity = Gravity.TOP or Gravity.END
        lcp.setMargins(0, dp(10), dp(10), 0)
        stage.addView(logClose, lcp)

        val sp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        sp.topMargin = dp(10)
        root.addView(stage, sp)

        setContentView(root)
        applyTally()
        refreshStatus()
        addLog("Doppia 1.2: live, REC, esposizione, segni e take per Premiere, tally grande.")
        val mine = localIpv4()
        addLog(if (mine.isEmpty()) "Il tablet non è collegato a nessuna rete" else "Rete del tablet: ${mine.joinToString(", ")}")
        Thread { try { File(cacheDir, "update").deleteRecursively() } catch (_: Throwable) {} }.start()
        if (prefs.getBoolean("autoupd", true) && System.currentTimeMillis() - prefs.getLong("lastupd", 0L) > 6 * 3600_000L) {
            checkUpdate(false)
        }
        if (prefs.getBoolean("auto", true)) {
            for (i in 0..1) {
                val ip = (prefs.getString("ip$i", "") ?: "").trim()
                if (ip.isNotEmpty() && cams[i] == null) toggle(i)
            }
        }
    }

    override fun onDestroy() {
        for (i in 0..1) cams[i]?.stop()
        super.onDestroy()
    }

    private fun pillButton(text: String): MaterialButton {
        val b = MaterialButton(this)
        b.text = text
        b.isAllCaps = false
        b.textSize = 13f
        b.setTextColor(Pal.TEXT)
        b.backgroundTintList = ColorStateList.valueOf(Pal.LINE)
        b.cornerRadius = dp(20)
        b.insetTop = 0
        b.insetBottom = 0
        Springs.pressable(b)
        return b
    }

    // ---------- Screenshot e sovrapposizione ----------

    private fun shotDir(): File = File(filesDir, "shots").also { it.mkdirs() }

    private fun shotFile(i: Int): File? {
        val n = prefs.getString("shot$i", "") ?: ""
        if (n.isEmpty()) return null
        val f = File(shotDir(), n)
        return if (f.exists()) f else null
    }

    private fun overlayFile(i: Int): File = File(filesDir, "overlay$i.jpg")

    /** Screenshot del live di una camera (senza strumenti sopra): copia nell'app e, da Android 10, in Immagini/Doppia. */
    private fun takeShot(i: Int) {
        val src = tiles[i].frame()
        val bmp = src?.copy(Bitmap.Config.ARGB_8888, false)
        if (bmp == null) {
            Toast.makeText(this, "Nessuna immagine da salvare", Toast.LENGTH_SHORT).show()
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val name = "Doppia_" + camName(i).replace(Regex("[^A-Za-z0-9_-]+"), "_") + "_" + stamp + ".jpg"
        Thread {
            try {
                val old = prefs.getString("shot$i", "") ?: ""
                if (old.isNotEmpty()) File(shotDir(), old).delete()
                FileOutputStream(File(shotDir(), name)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                prefs.edit().putString("shot$i", name).apply()
                var where = "nell'app"
                if (Build.VERSION.SDK_INT >= 29) {
                    val cv = ContentValues()
                    cv.put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Doppia")
                    cv.put(MediaStore.Images.Media.IS_PENDING, 1)
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                        cv.clear()
                        cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                        contentResolver.update(uri, cv, null, null)
                        where = "in Immagini/Doppia"
                    }
                }
                addLog("Screenshot ${camName(i)}: $name ($where)")
                runOnUiThread {
                    tiles[i].shotReady()
                    Toast.makeText(this, "Screenshot salvato $where", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Throwable) {
                addLog("Screenshot: ${e.message ?: e.javaClass.simpleName}")
                runOnUiThread { Toast.makeText(this, "Screenshot non riuscito", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    /** Apre il menu di condivisione (WhatsApp ecc.) con l'ultimo screenshot della camera. */
    private fun shareShot(i: Int) {
        val f = shotFile(i)
        if (f == null) {
            Toast.makeText(this, "Nessuno screenshot da condividere", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val send = Intent(Intent.ACTION_SEND)
            send.type = "image/jpeg"
            send.putExtra(Intent.EXTRA_STREAM, uri)
            send.clipData = ClipData.newRawUri("", uri)
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "Condividi screenshot"))
        } catch (e: Throwable) {
            addLog("Condividi: ${e.message ?: e.javaClass.simpleName}")
            Toast.makeText(this, "Condivisione non riuscita", Toast.LENGTH_LONG).show()
        }
    }

    /** Legge un'immagine riducendola (lato lungo tra maxDim e 2 x maxDim) per non occupare troppa memoria. */
    private fun decodeScaled(open: () -> java.io.InputStream?, maxDim: Int): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        open()?.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = sample
        return open()?.use { BitmapFactory.decodeStream(it, null, o2) }
    }

    private fun setOverlayFrom(i: Int, bmp: Bitmap) {
        FileOutputStream(overlayFile(i)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        val op = prefs.getInt("ovlop$i", 50)
        runOnUiThread {
            tiles[i].setOverlay(bmp, op)
            Toast.makeText(this, "Sovrapposizione impostata su ${camName(i)}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importOverlay(i: Int, uri: Uri) {
        Thread {
            try {
                val bmp = decodeScaled({ contentResolver.openInputStream(uri) }, 1920)
                if (bmp == null) runOnUiThread { Toast.makeText(this, "Immagine non leggibile", Toast.LENGTH_LONG).show() }
                else setOverlayFrom(i, bmp)
            } catch (e: Throwable) {
                addLog("Sovrapposizione: ${e.message ?: e.javaClass.simpleName}")
                runOnUiThread { Toast.makeText(this, "Sovrapposizione non riuscita", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun overlayFromLast(i: Int) {
        val f = shotFile(i)
        if (f == null) {
            Toast.makeText(this, "Nessuno screenshot di ${camName(i)}: toccane uno prima", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            try {
                val bmp = decodeScaled({ java.io.FileInputStream(f) }, 1920)
                if (bmp != null) setOverlayFrom(i, bmp)
            } catch (e: Throwable) {
                addLog("Sovrapposizione: ${e.message ?: e.javaClass.simpleName}")
            }
        }.start()
    }

    private fun overlayClear(i: Int) {
        overlayFile(i).delete()
        tiles[i].setOverlay(null, prefs.getInt("ovlop$i", 50))
    }

    // ---------- Aggiornamenti ----------

    private fun installedName(): String =
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (_: Throwable) { "?" }

    @Suppress("DEPRECATION")
    private fun installedCode(): Long {
        val pi = packageManager.getPackageInfo(packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    }

    /** Cerca l'ultima release su GitHub. Automatico: silenzioso, mostra solo il tasto giallo. Manuale: risponde sempre. */
    private fun checkUpdate(manual: Boolean) {
        if (manual) Toast.makeText(this, "Cerco aggiornamenti…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val r = Updater.latest(BuildConfig.UPDATE_REPO)
                prefs.edit().putLong("lastupd", System.currentTimeMillis()).apply()
                runOnUiThread {
                    if (r != null && r.build + 1L > installedCode()) {
                        pendingUpdate = r
                        updBtn.visibility = View.VISIBLE
                        addLog("Aggiornamento disponibile: ${r.name} (installata ${installedName()})")
                        if (manual) showUpdateDialog(r)
                    } else {
                        pendingUpdate = null
                        updBtn.visibility = View.GONE
                        addLog("Nessun aggiornamento (versione ${installedName()})")
                        if (manual) Toast.makeText(this, "Hai già l'ultima versione (${installedName()})", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Throwable) {
                val msg = e.message ?: e.javaClass.simpleName
                addLog("Aggiornamenti: $msg")
                if (manual) runOnUiThread { Toast.makeText(this, "Aggiornamenti: $msg", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun showUpdateDialog(r: Updater.Release) {
        val mb = if (r.size > 0) String.format(Locale.ROOT, " (%.1f MB)", r.size / 1048576.0) else ""
        val notes = r.notes.trim().take(500)
        AlertDialog.Builder(this)
            .setTitle("Nuova versione disponibile")
            .setMessage("Installata: ${installedName()}\nNuova: ${r.name}$mb" + (if (notes.isNotEmpty()) "\n\n$notes" else ""))
            .setPositiveButton("Aggiorna") { _, _ -> startUpdate(r) }
            .setNegativeButton("Più tardi", null)
            .show()
    }

    private fun startUpdate(r: Updater.Release) {
        if (tiles.any { it.recording }) {
            Toast.makeText(this, "Una camera sta registrando: ferma il REC prima di aggiornare", Toast.LENGTH_LONG).show()
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setMessage("Per installare l'aggiornamento devi permettere a Doppia di installare app. Nella schermata che si apre attiva l'interruttore, poi torna qui e tocca di nuovo Aggiorna.")
                .setPositiveButton("Apri impostazioni") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Annulla", null)
                .show()
            return
        }
        val dir = File(cacheDir, "update")
        dir.mkdirs()
        val dest = File(dir, "Doppia.apk")
        updCancel = false
        val dlg = AlertDialog.Builder(this)
            .setTitle("Scarico ${r.name}")
            .setMessage("0%")
            .setCancelable(false)
            .setNegativeButton("Annulla") { _, _ -> updCancel = true }
            .create()
        dlg.show()
        Thread {
            try {
                Updater.download(r.apkUrl, dest, r.size, { updCancel }) { pct -> runOnUiThread { dlg.setMessage("$pct%") } }
                runOnUiThread { dlg.dismiss(); installApk(dest) }
            } catch (e: Throwable) {
                val msg = e.message ?: e.javaClass.simpleName
                addLog("Download aggiornamento: $msg")
                runOnUiThread {
                    dlg.dismiss()
                    if (!updCancel) Toast.makeText(this, "Download non riuscito: $msg", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun installApk(f: File) {
        try {
            val info = packageManager.getPackageArchiveInfo(f.path, 0)
            if (info == null || info.packageName != packageName) {
                Toast.makeText(this, "Il file scaricato non è valido", Toast.LENGTH_LONG).show()
                return
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, "application/vnd.android.package-archive")
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Throwable) {
            addLog("Installazione: ${e.message ?: e.javaClass.simpleName}")
            Toast.makeText(this, "Installazione non riuscita", Toast.LENGTH_LONG).show()
        }
    }

    private fun paintSync() {
        syncBtn.backgroundTintList = ColorStateList.valueOf(if (syncOn) Pal.MID else Pal.LINE)
        syncBtn.setTextColor(if (syncOn) Pal.BG else Pal.TEXT)
    }

    private fun propLabel(code: Int): String = when (code) {
        SonyProps.SHUTTER -> "otturatore"
        SonyProps.APERTURE -> "diaframma"
        SonyProps.ISO -> "ISO"
        else -> paramNames[code]?.lowercase(Locale.ROOT) ?: "parametro"
    }

    /** Cambia un parametro sulla camera i e, con Sync accesa, sull'altra camera se live e se accetta quel valore. */
    private fun applyProp(i: Int, p: Prop, v: Long) {
        cams[i]?.setProp(p, v)
        if (!syncOn) return
        val j = 1 - i
        val other = cams[j]
        if (other == null || !liveNow[j]) return
        val op = other.props[p.code]
        if (op != null && op.settable && op.options.contains(v)) {
            other.setProp(op, v)
        } else {
            val msg = "Sync: ${camName(j)} non accetta ${SonyProps.fmt(p.code, v)} per ${propLabel(p.code)}"
            addLog(msg)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun tc(sec: Int): String = String.format(Locale.ROOT, "%02d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60)

    private fun onRecChange(i: Int, r: Boolean) {
        val lag = prefs.getInt("reclag", 300)
        val now = System.currentTimeMillis()
        if (r) {
            val t = Session.startTake(i, now - lag)
            addLog("${camName(i)}: take ${t.num} avviato")
        } else {
            val t = Session.openTake(i)
            Session.closeTake(i, now)
            if (t != null) addLog("${camName(i)}: take ${t.num} chiuso")
        }
        Session.save(prefs)
        applyTally()
    }

    /** Tally grande: con almeno una camera in REC tutto il bordo dell'app diventa rosso. */
    private fun applyTally() {
        val big = prefs.getBoolean("tally", true)
        for (t in tiles) t.setBigTally(big)
        val any = tiles.any { it.recording }
        rootView.setBackgroundColor(if (any && big) 0xFFC62828.toInt() else Pal.BG)
    }

    /** Segna un momento sul take in corso di ogni camera che registra (tempo dall'inizio del take). */
    private fun addMark(cat: Int, note: String) {
        val lag = prefs.getInt("reclag", 300)
        val refs = ArrayList<Ref>()
        val sb = StringBuilder(MarkCat.names[cat])
        if (note.isNotEmpty()) sb.append(": ").append(note)
        for (i in 0..1) {
            val ms = tiles[i].recElapsedMs()
            val tk = Session.openTake(i)
            if (ms < 0L || tk == null) continue
            val off = ms + lag
            refs.add(Ref(i, tk.num, off))
            sb.append("   ").append(camName(i)).append(' ').append(tc((off / 1000L).toInt()))
        }
        if (refs.isEmpty()) sb.append("   (nessuna camera in REC)")
        Session.addMark(Mark(System.currentTimeMillis(), cat, note, refs))
        Session.save(prefs)
        addLog("Segno: $sb")
        Toast.makeText(this, "⚑ $sb", Toast.LENGTH_SHORT).show()
    }

    /** Nota: frasi rapide a un tocco oppure testo libero (anche con il microfono della tastiera). */
    private fun noteDialog() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(22), dp(18), dp(22), dp(18))
        val head = TextView(this)
        head.text = "Nota"
        head.setTextColor(Pal.TEXT)
        head.textSize = 20f
        head.typeface = Typeface.DEFAULT_BOLD
        col.addView(head)

        val dlg = Dialog(this)
        fun done(text: String) {
            dlg.dismiss()
            addMark(MarkCat.NOTE, text.trim())
        }

        val chips = LinearLayout(this)
        chips.orientation = LinearLayout.HORIZONTAL
        for (q in listOf("Audio sporco", "Fuori fuoco", "Rifare", "Taglio qui", "Ottima")) {
            val c = TextView(this)
            c.text = q
            c.setTextColor(Pal.TEXT)
            c.textSize = 15f
            c.typeface = Typeface.DEFAULT_BOLD
            c.setPadding(dp(14), dp(9), dp(14), dp(9))
            c.background = roundBg(Pal.LINE, 16, this)
            c.setOnClickListener { done(q) }
            chips.addView(c, spaced(8))
        }
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.addView(chips)
        val hlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        hlp.topMargin = dp(12)
        col.addView(hs, hlp)

        val input = EditText(this)
        input.hint = "Scrivi o detta una nota"
        input.setTextColor(Pal.TEXT)
        input.setHintTextColor(Pal.MUTED)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.imeOptions = EditorInfo.IME_ACTION_DONE
        input.setSingleLine(true)
        input.setOnEditorActionListener { _, _, _ -> done(input.text.toString()); true }
        val ilp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        ilp.topMargin = dp(14)
        col.addView(input, ilp)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.END
        val cancel = pillButton("Annulla")
        cancel.setOnClickListener { dlg.dismiss() }
        val ok = pillButton("Segna")
        ok.backgroundTintList = ColorStateList.valueOf(MarkCat.colors[MarkCat.NOTE])
        ok.setTextColor(Pal.BG)
        ok.setOnClickListener { done(input.text.toString()) }
        row.addView(cancel)
        row.addView(ok, spaced(8))
        val rlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        rlp.topMargin = dp(16)
        col.addView(row, rlp)

        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dlg.setContentView(col)
        dlg.window?.setBackgroundDrawable(roundBg(Pal.SURFACE, 24, this))
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dlg.show()
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.55f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun camNames(): List<String> = listOf(camName(0), camName(1))

    /** Scrive XML (Premiere), JSON (plugin) e CSV (take list) e apre il menu di condivisione. */
    private fun exportSession() {
        if (Session.takes.isEmpty()) {
            Toast.makeText(this, "Nessun take da esportare: registra prima con REC", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val names = camNames()
            val fi = prefs.getInt("xmlfps", 2)
            val dir = File(filesDir, "export")
            dir.deleteRecursively()
            dir.mkdirs()
            val base = "Doppia_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(Date())
            val xml = File(dir, "$base.xml")
            xml.writeText(Session.xmeml(names, fi, base), Charsets.UTF_8)
            val json = File(dir, "$base.json")
            json.writeText(Session.json(names, fi), Charsets.UTF_8)
            val csv = File(dir, "$base.csv")
            csv.writeText(Session.csv(names), Charsets.UTF_8)
            val uris = ArrayList<Uri>()
            for (f in listOf(xml, json, csv)) uris.add(FileProvider.getUriForFile(this, "$packageName.fileprovider", f))
            val it2 = Intent(Intent.ACTION_SEND_MULTIPLE)
            it2.type = "*/*"
            it2.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            it2.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(it2, "Esporta per Premiere"))
            addLog("Esportati $base .xml .json .csv")
        } catch (e: Throwable) {
            addLog("Esportazione: ${e.message ?: e.javaClass.simpleName}")
            Toast.makeText(this, "Esportazione non riuscita", Toast.LENGTH_LONG).show()
        }
    }

    private fun camName(i: Int): String {
        val n = (prefs.getString("name$i", "") ?: "").trim()
        return if (n.isEmpty()) (if (i == 0) "Camera A" else "Camera B") else n
    }

    private fun showLog(on: Boolean) {
        logScroll.visibility = if (on) View.VISIBLE else View.GONE
        logClose.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun spaced(left: Int): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        p.leftMargin = dp(left)
        return p
    }

    /** Avvia o ferma la registrazione su tutte le camere live: se una registra, ferma quelle che registrano. */
    private fun recBoth() {
        val live = (0..1).filter { cams[it] != null && liveNow[it] }
        if (live.isEmpty()) {
            addLog("REC: nessuna camera live")
            return
        }
        val anyRec = live.any { tiles[it].recording }
        for (i in live) if (tiles[i].recording == anyRec) cams[i]?.toggleRec()
    }

    private fun applyLayout() {
        for (j in 0..1) tiles[j].visibility = if (solo == -1 || solo == j) View.VISIBLE else View.GONE
    }

    private fun refreshStatus() {
        val n = liveNow.count { it }
        status.text = if (n == 0) "nessuna camera live" else "$n di 2 live"
        recAllBtn.isEnabled = n > 0
        recAllBtn.alpha = if (n > 0) 1f else 0.4f
    }

    // ---------- Impostazioni ----------

    private fun showSettings() {
        settingsDlg?.dismiss()
        val scroll = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(22), dp(18), dp(22), dp(18))
        scroll.addView(col)

        val head = TextView(this)
        head.text = "Impostazioni"
        head.setTextColor(Pal.TEXT)
        head.textSize = 20f
        head.typeface = Typeface.DEFAULT_BOLD
        col.addView(head)

        col.addView(sectionTitle("Camere"))
        for (i in 0..1) col.addView(cameraRow(i))

        col.addView(toggleRow("Collega all'avvio le camere con IP salvato", prefs.getBoolean("auto", true)) {
            prefs.edit().putBoolean("auto", it).apply()
        })

        col.addView(sectionTitle("Monitor"))
        col.addView(toggleRow("Zebra", Monitor.zebra) { Monitor.zebra = it; Monitor.save(prefs) })
        val zl = listOf(70, 80, 90, 95, 100)
        col.addView(chooserRow("Livello zebra (%)", zl.map { it.toString() }, zl.indexOf(Monitor.zebraLevel).coerceAtLeast(0)) {
            Monitor.zebraLevel = zl[it]; Monitor.save(prefs)
        })
        col.addView(toggleRow("Focus peaking", Monitor.peaking) { Monitor.peaking = it; Monitor.save(prefs) })
        val pc = listOf(0xFFFF1744.toInt(), 0xFFFFEB3B.toInt(), 0xFFFFFFFF.toInt(), 0xFF69F0AE.toInt())
        col.addView(chooserRow("Colore peaking", listOf("Rosso", "Giallo", "Bianco", "Verde"), pc.indexOf(Monitor.peakColor).coerceAtLeast(0)) {
            Monitor.peakColor = pc[it]; Monitor.save(prefs)
        })
        col.addView(toggleRow("Griglia dei terzi", Monitor.grid) { Monitor.grid = it; Monitor.save(prefs) })
        col.addView(multiRow("Guide inquadratura (tocca per accendere)", Guides.names.toList(), Monitor.guides) {
            Monitor.guides = it; Monitor.save(prefs)
        })
        col.addView(toggleRow("Zona sicura 9:16 (approssimata)", Monitor.safe) { Monitor.safe = it; Monitor.save(prefs) })
        col.addView(toggleRow("Istogramma", Monitor.histogram) { Monitor.histogram = it; Monitor.save(prefs) })
        col.addView(toggleRow("Esposimetro (stimato dall'immagine)", Monitor.meter) { Monitor.meter = it; Monitor.save(prefs) })

        val params = LinearLayout(this)
        params.orientation = LinearLayout.VERTICAL
        col.addView(params)
        settingsParams = params
        fillParams(params)
        settingsSig = paramSig()

        val markTitle = sectionTitle("Take e segni")
        col.addView(markTitle)
        val markText = TextView(this)
        markText.setTextColor(Pal.TEXT)
        markText.textSize = 12.5f
        markText.typeface = Typeface.MONOSPACE
        fun paintSummary() {
            val sm = Session.summary(camNames())
            markText.text = if (sm.isEmpty()) "Nessun take. Registra con REC e usa ⚑ Buona, Scarto, Nota in alto." else sm
        }
        paintSummary()
        col.addView(markText)

        val fpsBtn = pillButton("")
        fun paintFps() { fpsBtn.text = "Fotogrammi dell'XML: " + Session.fpsNames[prefs.getInt("xmlfps", 2).coerceIn(0, Session.fpsNames.size - 1)] + "  ▸" }
        paintFps()
        fpsBtn.setOnClickListener {
            val n = (prefs.getInt("xmlfps", 2) + 1) % Session.fpsNames.size
            prefs.edit().putInt("xmlfps", n).apply()
            paintFps()
        }
        val flp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        flp.topMargin = dp(8)
        col.addView(fpsBtn, flp)
        val lags = listOf(0, 300, 600)
        col.addView(chooserRow("Ritardo rilevamento REC (ms)", lags.map { it.toString() }, lags.indexOf(prefs.getInt("reclag", 300)).coerceAtLeast(0)) {
            prefs.edit().putInt("reclag", lags[it]).apply()
        })
        col.addView(toggleRow("Tally grande (bordo rosso su tutto lo schermo in REC)", prefs.getBoolean("tally", true)) {
            prefs.edit().putBoolean("tally", it).apply()
            applyTally()
        })

        val mrow = LinearLayout(this)
        mrow.orientation = LinearLayout.HORIZONTAL
        val mexp = pillButton("Esporta per Premiere")
        mexp.backgroundTintList = ColorStateList.valueOf(Pal.MID)
        mexp.setTextColor(Pal.BG)
        mexp.setOnClickListener { exportSession() }
        val mcopy = pillButton("Copia elenco")
        mcopy.setOnClickListener {
            val sm = Session.summary(camNames())
            if (sm.isEmpty()) return@setOnClickListener
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Take e segni", sm))
            Toast.makeText(this, "Elenco copiato", Toast.LENGTH_SHORT).show()
        }
        val mclear = pillButton("Azzera sessione")
        mclear.setOnClickListener {
            if (Session.takes.isEmpty() && Session.marks.isEmpty()) return@setOnClickListener
            AlertDialog.Builder(this)
                .setMessage("Cancellare tutti i take e i segni?")
                .setPositiveButton("Cancella") { _, _ ->
                    Session.reset()
                    Session.save(prefs)
                    paintSummary()
                }
                .setNegativeButton("Annulla", null)
                .show()
        }
        mrow.addView(mexp)
        mrow.addView(mcopy, spaced(6))
        mrow.addView(mclear, spaced(6))
        val mlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        mlp.topMargin = dp(8)
        col.addView(mrow, mlp)

        col.addView(sectionTitle("Aggiornamenti"))
        col.addView(note("Versione installata: ${installedName()}"))
        col.addView(toggleRow("Cerca aggiornamenti all'avvio", prefs.getBoolean("autoupd", true)) {
            prefs.edit().putBoolean("autoupd", it).apply()
        })
        val updCheck = pillButton("Cerca aggiornamenti")
        updCheck.setOnClickListener { checkUpdate(true) }
        val ulp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        ulp.topMargin = dp(6)
        col.addView(updCheck, ulp)

        col.addView(sectionTitle("Modalità test"))
        val testBtn = pillButton(if (simOn) "Camere simulate: ACCESE  ▸" else "Camere simulate (PIN)  ▸")
        testBtn.setOnClickListener {
            if (simOn) {
                setSim(false)
                testBtn.text = "Camere simulate (PIN)  ▸"
            } else {
                askPin { ok -> if (ok) { setSim(true); testBtn.text = "Camere simulate: ACCESE  ▸" } }
            }
        }
        col.addView(testBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val testHint = TextView(this)
        testHint.text = "Due camere finte con immagine, REC, valori, batteria e memoria che scendono in fretta. Si spegne da sola alla riapertura dell'app."
        testHint.setTextColor(Pal.MUTED)
        testHint.textSize = 13f
        testHint.setPadding(0, dp(6), 0, 0)
        col.addView(testHint)

        col.addView(sectionTitle("Diagnostica"))
        val copy = pillButton("Copia log e proprietà delle camere")
        copy.setOnClickListener { copyDiagnostics() }
        col.addView(copy, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val logBtn = pillButton("Apri il log")
        val lbp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lbp.topMargin = dp(8)
        col.addView(logBtn, lbp)

        val close = pillButton("Chiudi")
        val cl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        cl.topMargin = dp(18)
        cl.gravity = Gravity.END
        col.addView(close, cl)

        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dlg.setContentView(scroll)
        dlg.window?.setBackgroundDrawable(roundBg(Pal.SURFACE, 24, this))
        dlg.setOnDismissListener { settingsDlg = null; settingsParams = null; stopBtns[0] = null; stopBtns[1] = null }
        close.setOnClickListener { dlg.dismiss() }
        logBtn.setOnClickListener { dlg.dismiss(); showLog(true) }
        settingsDlg = dlg
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dlg.show()
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.6f).toInt(), (resources.displayMetrics.heightPixels * 0.85f).toInt())
    }

    private val paramCodes = intArrayOf(SonyProps.EXP_MODE, SonyProps.EXP_COMP, SonyProps.METERING, SonyProps.WB)
    private val paramNames = mapOf(
        SonyProps.EXP_MODE to "Modo esposizione",
        SonyProps.EXP_COMP to "Compensazione EV",
        SonyProps.METERING to "Misurazione luce",
        SonyProps.WB to "Bilanciamento bianco"
    )

    private fun paramSig(): String {
        val sb = StringBuilder()
        for (i in 0..1) {
            sb.append(if (liveNow[i]) 'L' else '-')
            val m = cams[i]?.props ?: continue
            for (c in paramCodes) sb.append(m[c]?.current).append(',').append(m[c]?.settable).append(';')
        }
        return sb.toString()
    }

    /** Una camera nelle impostazioni: nome (al posto di A/B), indirizzo IP e tasto Scollega. */
    private fun cameraRow(i: Int): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = rowBg(true, true, Pal.CARD)
        row.setPadding(dp(10), dp(8), dp(10), dp(8))
        val rlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        rlp.bottomMargin = dp(8)
        row.layoutParams = rlp

        val dot = View(this)
        dot.background = roundBg(if (i == 0) Pal.COLD else Pal.HOT, 10, this)
        row.addView(dot, LinearLayout.LayoutParams(dp(20), dp(20)))

        val name = EditText(this)
        name.hint = if (i == 0) "Camera A" else "Camera B"
        name.setHintTextColor(Pal.MUTED)
        name.setTextColor(Pal.TEXT)
        name.textSize = 15f
        name.typeface = Typeface.DEFAULT_BOLD
        name.setSingleLine(true)
        name.setBackgroundColor(0)
        name.setPadding(dp(12), dp(8), dp(12), dp(8))
        name.setText(prefs.getString("name$i", "") ?: "")
        name.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                prefs.edit().putString("name$i", e?.toString()?.trim() ?: "").apply()
                tiles[i].setLabel(camName(i))
            }
        })
        row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val ip = EditText(this)
        ip.hint = "indirizzo IP, es. 192.168.43.21"
        ip.setHintTextColor(Pal.MUTED)
        ip.setTextColor(Pal.TEXT)
        ip.textSize = 14f
        ip.typeface = Typeface.MONOSPACE
        ip.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        ip.keyListener = DigitsKeyListener.getInstance("0123456789.")
        ip.setSingleLine(true)
        ip.setBackgroundColor(0)
        ip.setPadding(dp(12), dp(8), dp(12), dp(8))
        ip.setText(prefs.getString("ip$i", "") ?: "")
        ip.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                prefs.edit().putString("ip$i", e?.toString()?.trim() ?: "").apply()
            }
        })
        row.addView(ip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f))

        val stop = pillButton("Scollega")
        stop.visibility = if (cams[i] != null) View.VISIBLE else View.GONE
        stop.setOnClickListener { toggle(i) }
        stopBtns[i] = stop
        row.addView(stop)
        return row
    }

    private fun refreshSettings() {
        for (i in 0..1) stopBtns[i]?.visibility = if (cams[i] != null) View.VISIBLE else View.GONE
        val params = settingsParams ?: return
        val sig = paramSig()
        if (sig == settingsSig) return
        settingsSig = sig
        params.removeAllViews()
        fillParams(params)
    }

    private fun fillParams(container: LinearLayout) {
        var any = false
        for (i in 0..1) {
            val cam = cams[i]
            if (cam == null || !liveNow[i]) continue
            any = true
            container.addView(sectionTitle(camName(i)))
            val m = cam.props
            var rows = 0
            for (code in paramCodes) {
                val p = m[code] ?: continue
                container.addView(stepperRow(paramNames[code] ?: "", i, cam, p))
                rows++
            }
            if (rows == 0) container.addView(note("La camera non espone parametri modificabili in questa modalità."))
        }
        if (!any) {
            container.addView(sectionTitle("Camere"))
            container.addView(note("Collega una camera per cambiare i suoi parametri."))
        }
    }

    private fun sectionTitle(text: String): TextView {
        val t = TextView(this)
        t.text = text.uppercase(Locale.ROOT)
        t.setTextColor(Pal.MID)
        t.textSize = 12f
        t.typeface = Typeface.DEFAULT_BOLD
        t.setPadding(0, dp(18), 0, dp(6))
        return t
    }

    private fun note(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(Pal.MUTED)
        t.textSize = 13f
        t.setPadding(0, dp(4), 0, dp(4))
        return t
    }

    private fun toggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val sw = SwitchCompat(this)
        sw.text = label
        sw.setTextColor(Pal.TEXT)
        sw.textSize = 15f
        sw.isChecked = checked
        sw.setPadding(0, dp(8), 0, dp(8))
        sw.setOnCheckedChangeListener { _, v -> onChange(v) }
        return sw
    }

    /** Riga di pulsanti accendibili in piu' alla volta: il valore e' una maschera di bit. */
    private fun multiRow(label: String, options: List<String>, mask: Int, onChange: (Int) -> Unit): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, dp(6), 0, dp(6))
        val l = TextView(this)
        l.text = label
        l.setTextColor(Pal.TEXT)
        l.textSize = 15f
        box.addView(l)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val pills = ArrayList<TextView>()
        var cur = mask
        fun paint() {
            for ((k, t) in pills.withIndex()) {
                val on = (cur and (1 shl k)) != 0
                t.setTextColor(if (on) Pal.MID else Pal.TEXT)
                t.background = roundBg(if (on) 0x33FFC107 else Pal.LINE, 14, this)
            }
        }
        for ((k, o) in options.withIndex()) {
            val t = TextView(this)
            t.text = o
            t.textSize = 14f
            t.typeface = Typeface.DEFAULT_BOLD
            t.setPadding(dp(14), dp(7), dp(14), dp(7))
            t.setOnClickListener { cur = cur xor (1 shl k); paint(); onChange(cur) }
            pills.add(t)
            row.addView(t, if (k == 0) LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) else spaced(6))
        }
        paint()
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.addView(row)
        val hlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        hlp.topMargin = dp(6)
        box.addView(hs, hlp)
        return box
    }

    private fun chooserRow(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(6), 0, dp(6))
        val l = TextView(this)
        l.text = label
        l.setTextColor(Pal.TEXT)
        l.textSize = 15f
        row.addView(l, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val pills = ArrayList<TextView>()
        fun paint(sel: Int) {
            for ((k, t) in pills.withIndex()) {
                val on = k == sel
                t.setTextColor(if (on) Pal.MID else Pal.TEXT)
                t.background = roundBg(if (on) 0x33FFC107 else Pal.LINE, 14, this)
            }
        }
        for ((k, o) in options.withIndex()) {
            val t = TextView(this)
            t.text = o
            t.textSize = 14f
            t.typeface = Typeface.DEFAULT_BOLD
            t.setPadding(dp(14), dp(7), dp(14), dp(7))
            t.setOnClickListener { paint(k); onSelect(k) }
            pills.add(t)
            row.addView(t, spaced(6))
        }
        paint(selected)
        return row
    }

    private fun stepperRow(label: String, i: Int, cam: SonyCam, p: Prop): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(6), 0, dp(6))
        val l = TextView(this)
        l.text = label
        l.setTextColor(Pal.TEXT)
        l.textSize = 15f
        row.addView(l, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val can = p.settable && p.options.isNotEmpty()
        val idx = p.options.indexOf(p.current)
        fun step(d: Int) {
            if (!can) return
            val n = (if (idx < 0) 0 else idx + d).coerceIn(0, p.options.size - 1)
            applyProp(i, p, p.options[n])
        }
        val minus = pillButton("‹")
        val plus = pillButton("›")
        minus.setOnClickListener { step(-1) }
        plus.setOnClickListener { step(1) }
        minus.isEnabled = can
        plus.isEnabled = can
        val v = TextView(this)
        v.text = SonyProps.fmt(p.code, p.current)
        v.setTextColor(if (can) Pal.TEXT else Pal.MUTED)
        v.textSize = 16f
        v.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        v.gravity = Gravity.CENTER
        row.addView(minus)
        row.addView(v, LinearLayout.LayoutParams(dp(130), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(plus)
        return row
    }

    private fun copyDiagnostics() {
        val sb = StringBuilder()
        for (i in 0..1) {
            val m = cams[i]?.props ?: continue
            sb.append("== Camera ").append(if (i == 0) "A" else "B").append(" (").append(m.size).append(" proprietà)\n")
            for (p in m.values) {
                sb.append(String.format(Locale.ROOT, "%04X t=%X gs=%d en=%d cur=%X opts=%d",
                    p.code, p.type, p.getSet, p.enabledFlag, p.current, p.options.size))
                if (p.options.isNotEmpty() && p.options.size <= 40) {
                    sb.append(" [").append(p.options.joinToString(",") { java.lang.Long.toHexString(it) }).append(']')
                }
                sb.append('\n')
            }
        }
        sb.append("== Log\n").append(lines.joinToString("\n"))
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Doppia diagnostica", sb.toString()))
        addLog("Diagnostica copiata negli appunti (${sb.length} caratteri)")
    }

    // ---------- Collegamento ----------

    /** Accende o spegne le camere finte: ferma le camere in corso e le ricollega nel nuovo modo. */
    private fun setSim(on: Boolean) {
        if (on == simOn) return
        for (i in 0..1) { cams[i]?.stop(); cams[i] = null }
        simOn = on
        addLog(if (on) "MODALITA' TEST accesa: camere simulate" else "Modalità test spenta")
        Toast.makeText(this, if (on) "Modalità test: tocca Connetti sui riquadri" else "Modalità test spenta", Toast.LENGTH_LONG).show()
    }

    /** Chiede il PIN (la prima volta lo si sceglie, 4 cifre) e chiama done(true) se e' giusto. */
    private fun askPin(done: (Boolean) -> Unit) {
        val saved = prefs.getString("testpin", null)
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        et.filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        et.setTextColor(Pal.TEXT)
        et.textSize = 24f
        et.gravity = Gravity.CENTER
        et.hint = "····"
        et.setHintTextColor(Pal.MUTED)
        val dlg = android.app.AlertDialog.Builder(this)
            .setTitle(if (saved == null) "Scegli un PIN di 4 cifre" else "Inserisci il PIN")
            .setView(et)
            .setPositiveButton("OK") { _, _ ->
                val v = et.text.toString()
                if (saved == null) {
                    if (v.length == 4) { prefs.edit().putString("testpin", v).apply(); done(true) }
                    else { Toast.makeText(this, "Servono 4 cifre", Toast.LENGTH_SHORT).show(); done(false) }
                } else if (v == saved) {
                    done(true)
                } else {
                    Toast.makeText(this, "PIN sbagliato", Toast.LENGTH_SHORT).show(); done(false)
                }
            }
            .setNegativeButton("Annulla") { _, _ -> done(false) }
            .create()
        dlg.show()
    }

    private fun toggle(i: Int) {
        val running = cams[i]
        if (running != null) {
            running.stop()
            cams[i] = null
            return
        }
        val ip = if (simOn) "sim$i" else (prefs.getString("ip$i", "") ?: "").trim()
        if (!simOn && !Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(ip)) {
            addLog("${camName(i)}: scrivi un indirizzo IP valido")
            tiles[i].showMessage("Inserisci l'indirizzo IP in Impostazioni")
            return
        }
        val cam = SonyCam(
            ip, guid(), camName(i),
            { s -> addLog(s) },
            { st -> runOnUiThread { onState(i, st) } },
            { bmp, ms ->
                val a = Monitor.analyze(bmp)
                runOnUiThread { tiles[i].setFrame(bmp, ms, a) }
            },
            { m -> runOnUiThread { tiles[i].setProps(m); refreshSettings() } },
            simOn
        )
        cams[i] = cam
        cam.start()
    }

    private fun onState(i: Int, st: CamState) {
        tiles[i].setState(st)
        liveNow[i] = st == CamState.LIVE
        if (st == CamState.ERROR) cams[i] = null
        refreshStatus()
        refreshSettings()
    }

    private fun guid(): ByteArray {
        var hex = prefs.getString("guid", null)
        if (hex == null || hex.length != 32) {
            val b = ByteArray(16)
            java.security.SecureRandom().nextBytes(b)
            hex = b.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
            prefs.edit().putString("guid", hex).apply()
        }
        return ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun addLog(s: String) {
        runOnUiThread {
            lines.addLast(clock.format(Date()) + "  " + s)
            while (lines.size > 300) lines.removeFirst()
            logText.text = lines.joinToString("\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
