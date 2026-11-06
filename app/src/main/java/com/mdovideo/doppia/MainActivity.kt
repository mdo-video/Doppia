package com.mdovideo.doppia

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.Spannable
import android.text.SpannableString
import android.text.method.DigitsKeyListener
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Un riquadro con il live di una camera: immagine, nome, pillola del ritardo e stato. */
class Tile(ctx: Context, private val label: String, private val accent: Int) : FrameLayout(ctx) {
    private val img = ImageView(ctx)
    private val tag = TextView(ctx)
    private val lag = TextView(ctx)
    private val empty = TextView(ctx)

    init {
        background = roundBg(Pal.SURFACE, 22, ctx)
        clipToOutline = true
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        addView(img, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        empty.setTextColor(Pal.MUTED)
        empty.textSize = 15f
        empty.gravity = Gravity.CENTER
        empty.text = "Nessun segnale"
        addView(empty, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        tag.setTextColor(Pal.TEXT)
        tag.textSize = 13f
        tag.typeface = Typeface.DEFAULT_BOLD
        tag.setPadding(dp(10), dp(5), dp(10), dp(5))
        tag.background = roundBg(0xCC0B0F15.toInt(), 12, ctx)
        tag.text = label
        val tp = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tp.gravity = Gravity.TOP or Gravity.START
        tp.setMargins(dp(12), dp(12), 0, 0)
        addView(tag, tp)

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
    }

    fun setFrame(b: Bitmap, ms: Int) {
        img.setImageBitmap(b)
        empty.visibility = View.GONE
        lag.visibility = View.VISIBLE
        val s = SpannableString("● $ms ms")
        s.setSpan(ForegroundColorSpan(lagColor(ms)), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        lag.text = s
    }

    fun setState(st: CamState) {
        when (st) {
            CamState.IDLE -> clear("Nessun segnale")
            CamState.CONNECTING -> clear("Mi collego…")
            CamState.WAITING -> clear("In attesa del live view…")
            CamState.ERROR -> clear("Collegamento non riuscito, guarda il log")
            CamState.LIVE -> {}
        }
    }

    private fun clear(text: String) {
        img.setImageDrawable(null)
        lag.visibility = View.GONE
        empty.text = text
        empty.visibility = View.VISIBLE
    }
}

class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("doppia", MODE_PRIVATE) }
    private val cams = arrayOfNulls<SonyCam>(2)
    private lateinit var tiles: Array<Tile>
    private lateinit var fields: Array<EditText>
    private lateinit var buttons: Array<MaterialButton>
    private lateinit var status: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private val liveNow = BooleanArray(2)
    private val lines = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
    private var solo = -1
    private lateinit var tilesRow: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Pal.BG)
        root.fitsSystemWindows = true
        root.setPadding(dp(14), dp(10), dp(14), dp(12))

        // Barra in alto
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(this)
        title.text = "Doppia"
        title.setTextColor(Pal.TEXT)
        title.textSize = 22f
        title.typeface = Typeface.DEFAULT_BOLD
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        status = TextView(this)
        status.setTextColor(Pal.MUTED)
        status.textSize = 12f
        status.typeface = Typeface.MONOSPACE
        status.setPadding(dp(12), dp(5), dp(12), dp(5))
        status.background = roundBg(Pal.CARD, 14, this)
        bar.addView(status)
        val scanBtn = pillButton("Cerca camere")
        scanBtn.setOnClickListener { scan() }
        val logBtn = pillButton("Log")
        logBtn.setOnClickListener {
            logScroll.visibility = if (logScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        bar.addView(scanBtn, spaced(10))
        bar.addView(logBtn, spaced(6))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Area dei due riquadri, con il log sopra
        val stage = FrameLayout(this)
        tilesRow = LinearLayout(this)
        tilesRow.orientation = LinearLayout.HORIZONTAL
        tiles = arrayOf(Tile(this, "Camera A", Pal.COLD), Tile(this, "Camera B", Pal.HOT))
        for (i in 0..1) {
            val t = tiles[i]
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

        val sp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        sp.topMargin = dp(10)
        root.addView(stage, sp)

        // Righe di collegamento
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        fields = Array(2) { EditText(this) }
        buttons = Array(2) { pillButton("Connetti") }
        for (i in 0..1) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.background = rowBg(true, true, Pal.CARD)
            row.setPadding(dp(10), dp(8), dp(10), dp(8))

            val av = TextView(this)
            av.text = if (i == 0) "A" else "B"
            av.gravity = Gravity.CENTER
            av.setTextColor(Pal.BG)
            av.typeface = Typeface.DEFAULT_BOLD
            av.background = roundBg(if (i == 0) Pal.COLD else Pal.HOT, 20, this)
            row.addView(av, LinearLayout.LayoutParams(dp(40), dp(40)))

            val f = fields[i]
            f.hint = "indirizzo IP, es. 192.168.43.21"
            f.setHintTextColor(Pal.MUTED)
            f.setTextColor(Pal.TEXT)
            f.textSize = 14f
            f.typeface = Typeface.MONOSPACE
            f.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            f.keyListener = DigitsKeyListener.getInstance("0123456789.")
            f.setSingleLine(true)
            f.setBackgroundColor(0)
            f.setPadding(dp(12), dp(8), dp(12), dp(8))
            f.setText(prefs.getString("ip$i", "") ?: "")
            val flp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(f, flp)

            val b = buttons[i]
            b.setOnClickListener { toggle(i) }
            row.addView(b)

            val rlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i == 1) rlp.leftMargin = dp(10)
            bottom.addView(row, rlp)
        }
        val bp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        bp.topMargin = dp(10)
        root.addView(bottom, bp)

        setContentView(root)
        refreshStatus()
        addLog("Doppia 0.1: prova di collegamento. Le camere devono essere sulla stessa rete del tablet.")
        val mine = localIpv4()
        addLog(if (mine.isEmpty()) "Il tablet non è collegato a nessuna rete" else "Rete del tablet: ${mine.joinToString(", ")}")
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

    private fun spaced(left: Int): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        p.leftMargin = dp(left)
        return p
    }

    private fun applyLayout() {
        for (j in 0..1) tiles[j].visibility = if (solo == -1 || solo == j) View.VISIBLE else View.GONE
    }

    private fun refreshStatus() {
        val n = liveNow.count { it }
        status.text = if (n == 0) "nessuna camera live" else "$n di 2 live"
    }

    private fun toggle(i: Int) {
        val running = cams[i]
        if (running != null) {
            running.stop()
            cams[i] = null
            return
        }
        val ip = fields[i].text.toString().trim()
        if (!Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(ip)) {
            addLog("Camera ${if (i == 0) "A" else "B"}: scrivi un indirizzo IP valido")
            return
        }
        prefs.edit().putString("ip$i", ip).apply()
        val cam = SonyCam(
            ip, guid(), if (i == 0) "A" else "B",
            { s -> addLog(s) },
            { st -> runOnUiThread { onState(i, st) } },
            { bmp, ms -> runOnUiThread { tiles[i].setFrame(bmp, ms) } }
        )
        cams[i] = cam
        cam.start()
    }

    private fun onState(i: Int, st: CamState) {
        tiles[i].setState(st)
        liveNow[i] = st == CamState.LIVE
        buttons[i].text = when (st) {
            CamState.IDLE -> "Connetti"
            CamState.ERROR -> "Riprova"
            else -> "Stop"
        }
        if (st == CamState.ERROR) cams[i] = null
        refreshStatus()
    }

    private fun scan() {
        addLog("Cerco camere sulla rete…")
        Thread {
            val found = scanPtpHosts { s -> addLog(s) }
            runOnUiThread {
                if (found.isEmpty()) {
                    addLog("Nessuna camera trovata. Controlla che siano accese, con il Wi-Fi attivo e sulla stessa rete del tablet.")
                } else {
                    addLog("Trovate: ${found.joinToString(", ")}")
                    for (h in found) {
                        if (fields.any { it.text.toString().trim() == h }) continue
                        val empty = fields.firstOrNull { it.text.toString().trim().isEmpty() } ?: break
                        empty.setText(h)
                    }
                }
            }
        }.start()
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
            while (lines.size > 200) lines.removeFirst()
            logText.text = lines.joinToString("\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
