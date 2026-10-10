package com.example.beacons

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.beacons.Constants.NATO
import com.example.beacons.Constants.PAL
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : Activity() {
    private lateinit var scene: Scene
    private lateinit var view: SceneView
    private lateinit var heading: HeadingSensor
    private lateinit var loc: LocationSource
    private lateinit var haptics: Haptics
    private lateinit var store: BeaconStore
    private lateinit var ui: Typeface
    private lateinit var mono: Typeface

    private lateinit var hud: TextView
    private lateinit var panel: LinearLayout
    private lateinit var latF: EditText
    private lateinit var lonF: EditText
    private lateinit var turnBtn: Button
    private lateinit var editSheet: LinearLayout
    private lateinit var eDot: View
    private lateinit var eName: EditText
    private lateinit var swatches: LinearLayout
    private lateinit var toast: LinearLayout
    private lateinit var toastMsg: TextView
    private lateinit var toastBtn: Button

    private var natoI = 0
    private var nextId = 1
    private var editId = -1
    private var sheetOpen = false
    private var lastLogMs = 0L
    private var started = false
    private val toastHide = Runnable { toast.visibility = View.GONE; toastFn = null }
    private var toastFn: (() -> Unit)? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pill(fillColor: Int, strokeColor: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(999).toFloat(); setColor(fillColor); setStroke(dp(1), strokeColor)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = resources.getFont(R.font.chakra_petch_medium)
        mono = resources.getFont(R.font.share_tech_mono)
        scene = Scene(ui, mono)
        view = SceneView(this, scene)
        heading = HeadingSensor(this)
        loc = LocationSource(this)
        haptics = Haptics(this)
        store = BeaconStore(this)
        scene.onLock = { haptics.clink() }
        scene.onDetent = { haptics.detent() }
        scene.onTick = { haptics.tick() }

        val snap = store.load()
        natoI = snap.natoI; scene.dropN = snap.dropN; nextId = snap.nextId
        scene.beacons.addAll(snap.beacons)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.addView(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        buildDebug(root)
        buildGear(root)
        buildEditSheet(root)
        buildToast(root)
        setContentView(root)

        view.beforeFrame = { tick() }
        view.sheetOpen = { sheetOpen }
        view.closeSheets = { closeSheets() }
        view.onDrop = { ci -> dropBeacon(ci) }
        view.onEdit = { id -> openEdit(id) }
    }

    // ---------- UI building ----------
    private fun buildGear(root: FrameLayout) {
        val gear = FrameLayout(this)
        val dot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            alpha = 0.3f
        }
        gear.addView(dot, FrameLayout.LayoutParams(dp(5), dp(5), Gravity.CENTER))
        gear.contentDescription = "Settings"
        // Until the Step 5 settings sheet exists, the gear toggles the debug HUD.
        gear.setOnClickListener {
            val show = panel.visibility != View.VISIBLE
            panel.visibility = if (show) View.VISIBLE else View.GONE
            hud.visibility = if (show) View.VISIBLE else View.GONE
        }
        val lp = FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END)
        lp.topMargin = dp(30); lp.rightMargin = dp(6)
        root.addView(gear, lp)
    }

    private fun buildDebug(root: FrameLayout) {
        hud = TextView(this).apply {
            typeface = mono; textSize = 10f; setTextColor(Color.argb(190, 255, 255, 255))
            setPadding(dp(8), dp(36), dp(8), 0); visibility = View.GONE
        }
        root.addView(hud, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(6), dp(4), dp(6), dp(10)); gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        fun field(hint: String) = EditText(this).apply {
            this.hint = hint; typeface = mono; textSize = 11f; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setSingleLine(); imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
        }
        fun btn(t: String) = Button(this).apply {
            text = t; typeface = mono; textSize = 10f; setAllCaps(false); minWidth = 0; minimumWidth = 0; setPadding(dp(8), 0, dp(8), 0)
        }
        latF = field("lat"); lonF = field("lon")
        val set = btn("ADD").apply { setOnClickListener { addAtField() } }
        val ahead = btn("100M AHEAD").apply { setOnClickListener { placeAhead(100.0) } }
        turnBtn = btn("COMPASS").apply { setOnClickListener { setDebugTurn(!view.debugTurn) } }
        panel.addView(latF, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(lonF, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(set); panel.addView(ahead); panel.addView(turnBtn)
        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    private fun buildEditSheet(root: FrameLayout) {
        editSheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(242, 0, 0, 0))
            setPadding(dp(16), dp(22), dp(16), dp(20))
            visibility = View.INVISIBLE
        }
        val topLine = View(this).apply { setBackgroundColor(Color.argb(41, 255, 255, 255)) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        eDot = View(this)
        row.addView(eDot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(14) })
        eName = EditText(this).apply {
            typeface = ui; textSize = 22f; setTextColor(Color.WHITE); background = null
            letterSpacing = 0.14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(18))
            setSingleLine(); imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_FULLSCREEN
            setPadding(0, dp(6), 0, dp(6))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val b = scene.byId(editId) ?: return
                    b.name = s.toString().trim().ifEmpty { "Beacon" }
                    persist()
                }
            })
        }
        row.addView(eName, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        swatches = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in PAL.indices) {
            val c = Color.rgb(rgbOf(i)[0], rgbOf(i)[1], rgbOf(i)[2])
            val v = View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
                contentDescription = PAL[i].name
                setOnClickListener {
                    val b = scene.byId(editId) ?: return@setOnClickListener
                    b.ci = i; syncEdit(b); haptics.tick(); persist()
                }
            }
            swatches.addView(v, LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(10) })
        }

        val pair = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun pillBtn(label: String, solid: Boolean, warn: Boolean) = Button(this).apply {
            text = label.uppercase(); typeface = ui; textSize = 13f; letterSpacing = 0.16f
            setTextColor(if (solid) Color.BLACK else if (warn) Color.rgb(255, 61, 20) else Color.WHITE)
            background = pill(if (solid) Color.WHITE else Color.TRANSPARENT,
                if (solid) Color.WHITE else if (warn) Color.argb(128, 255, 61, 20) else Color.argb(41, 255, 255, 255))
            stateListAnimator = null
        }
        val down = pillBtn("Take down", false, true).apply { setOnClickListener { takeDown() } }
        val done = pillBtn("Done", true, false).apply { setOnClickListener { closeSheets() } }
        pair.addView(down, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(5) })
        pair.addView(done, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(5) })

        // thin top border, as the mockup's sheet
        editSheet.addView(topLine, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = -dp(22); bottomMargin = dp(21); leftMargin = -dp(16); rightMargin = -dp(16)
        })
        editSheet.addView(row)
        editSheet.addView(swatches, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(20); bottomMargin = dp(18)
        })
        editSheet.addView(pair)
        root.addView(editSheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    private fun buildToast(root: FrameLayout) {
        toast = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(8), dp(8), dp(8))
            background = pill(Color.argb(240, 0, 0, 0), Color.argb(41, 255, 255, 255))
            visibility = View.GONE
        }
        toastMsg = TextView(this).apply {
            typeface = ui; textSize = 12f; letterSpacing = 0.16f; setTextColor(Color.WHITE); maxLines = 1
        }
        toastBtn = Button(this).apply {
            typeface = ui; textSize = 12f; letterSpacing = 0.16f; setTextColor(Color.BLACK)
            background = pill(Color.WHITE, Color.WHITE); stateListAnimator = null; minWidth = 0; minimumWidth = 0
            setPadding(dp(16), 0, dp(16), 0)
            setOnClickListener {
                val f = toastFn
                removeCallbacks(toastHide); toast.visibility = View.GONE; toastFn = null
                f?.invoke()
            }
        }
        toast.addView(toastMsg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(14) })
        toast.addView(toastBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)))
        root.addView(toast, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(22) })
    }

    private fun showToast(msg: String, label: String, fn: (() -> Unit)?) {
        toastMsg.text = msg.uppercase(); toastBtn.text = label.uppercase(); toastFn = fn
        toast.visibility = View.VISIBLE
        toast.removeCallbacks(toastHide); toast.postDelayed(toastHide, 5500)
    }

    private fun rgbOf(i: Int) = hslRgb(PAL[i].h, PAL[i].l)

    // ---------- beacon actions ----------
    private fun persist() = store.save(scene.beacons, natoI, scene.dropN)

    private fun fixAgeS(l: Location) = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1e9

    /** Drop a beacon at the current fix. Refuses (with a clear toast) when there is no fresh fix. */
    private fun dropBeacon(ci: Int) {
        val l = loc.last
        if (l == null || fixAgeS(l) > 15) {
            showToast(if (l == null) "No fix yet" else "Fix is stale, wait a moment", "Okay", null)
            return
        }
        val name = NATO[natoI % NATO.size]
        natoI++; scene.dropN++
        val b = Beacon(nextId++, name, ci, 0.0, 0.0, scene.t)
        b.lat = l.latitude; b.lon = l.longitude
        scene.beacons.add(b)
        persist()
        haptics.drop()
        showToast("$name placed", "Undo") { scene.beacons.remove(b); persist() }
    }

    private fun openEdit(id: Int) {
        val b = scene.byId(id) ?: return
        editId = id
        eName.setText(b.name)
        syncEdit(b)
        sheetOpen = true
        editSheet.visibility = View.VISIBLE
        editSheet.translationY = editSheet.height.toFloat().coerceAtLeast(dp(260).toFloat())
        editSheet.animate().translationY(0f).setDuration(360).start()
    }

    private fun syncEdit(b: Beacon) {
        val c = rgbOf(b.ci)
        (eDot.background as? GradientDrawable)?.setColor(Color.rgb(c[0], c[1], c[2])) ?: run {
            eDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(c[0], c[1], c[2])) }
        }
        for (i in 0 until swatches.childCount) {
            val v = swatches.getChildAt(i)
            val d = v.background as GradientDrawable
            d.setStroke(if (i == b.ci) dp(2) else 0, Color.WHITE)
        }
    }

    private fun closeSheets() {
        if (!sheetOpen) return
        sheetOpen = false
        eName.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(eName.windowToken, 0)
        editSheet.animate().translationY(editSheet.height.toFloat()).setDuration(300)
            .withEndAction { if (!sheetOpen) editSheet.visibility = View.INVISIBLE }.start()
        editId = -1
    }

    private fun takeDown() {
        val b = scene.byId(editId) ?: return
        val idx = scene.beacons.indexOf(b)
        if (scene.targetId == b.id) scene.targetId = -1
        scene.beacons.remove(b)
        closeSheets()
        persist()
        showToast("${b.name} taken down", "Undo") {
            b.born = scene.t
            scene.beacons.add(minOf(idx, scene.beacons.size), b)
            persist()
        }
    }

    // ---------- debug ----------
    /** Debug only: true = scene follows finger drag instead of the compass. Never persists across resumes. */
    private fun setDebugTurn(on: Boolean) {
        view.debugTurn = on
        turnBtn.text = if (on) "DRAG (DEBUG)" else "COMPASS"
    }

    private fun addAtField() {
        val la = latF.text.toString().toDoubleOrNull() ?: return
        val lo = lonF.text.toString().toDoubleOrNull() ?: return
        addAt(la, lo)
    }

    private fun addAt(lat: Double, lon: Double) {
        val name = NATO[natoI % NATO.size]; natoI++
        val b = Beacon(nextId++, name, 4, 0.0, 0.0, scene.t)
        b.lat = lat; b.lon = lon
        scene.beacons.add(b); persist()
    }

    private fun placeAhead(m: Double) {
        val l = loc.last ?: return
        val brg = heading.trueHeading * D2R
        val dLat = m * cos(brg) / 111_320.0
        val dLon = m * sin(brg) / (111_320.0 * cos(l.latitude * D2R))
        addAt(l.latitude + dLat, l.longitude + dLon)
    }

    // ---------- per frame ----------
    private fun tick() {
        if (!view.debugTurn) scene.hdg = heading.trueHeading
        val l = loc.last
        scene.caption = if (heading.hasSample && heading.unreliable) "COMPASS LOW  WAVE PHONE IN A FIGURE 8" else null
        if (l != null) {
            heading.setLocation(l.latitude, l.longitude, l.altitude)
            val res = FloatArray(2)
            for (b in scene.beacons) {
                if (b.lat.isNaN()) continue
                Location.distanceBetween(l.latitude, l.longitude, b.lat, b.lon, res)
                val d = res[0].toDouble(); val brg = res[1] * D2R
                b.x = d * sin(brg); b.y = d * cos(brg)
            }
        }
        val now = System.currentTimeMillis()
        if (hud.visibility == View.VISIBLE && now - lastLogMs >= 250) {
            lastLogMs = now
            val age = l?.let { fixAgeS(it) }
            hud.text = buildString {
                append("TRUE %05.1f  MAG %05.1f  DECL %+.1f\n".format(heading.trueHeading, heading.magHeading, heading.declination))
                append("ACC %d  EST %s  JIT5s %.2f  %.0fHz\n".format(heading.accuracy, if (heading.estAccDeg >= 0) "%.1f".format(heading.estAccDeg) else "n/a", heading.jitter5s, heading.hz))
                append("TILT %.0f  remap %05.1f  vec %05.1f  flat %05.1f\n".format(heading.tiltFromUpright, heading.diagRemapAz, heading.diagVectorFwd, heading.diagFlatAz))
                append(if (l != null) "FIX acc %.0f m  age %.0f s  n=%d\n".format(l.accuracy, age, loc.updates) else "FIX none\n")
                append("BEACONS %d".format(scene.beacons.size))
            }
            Log.i("BeaconsHeading", "true=%.1f mag=%.1f decl=%.1f acc=%d est=%.1f jit=%.2f tilt=%.0f remap=%.1f vec=%.1f flat=%.1f hz=%.0f".format(
                heading.trueHeading, heading.magHeading, heading.declination, heading.accuracy, heading.estAccDeg, heading.jitter5s,
                heading.tiltFromUpright, heading.diagRemapAz, heading.diagVectorFwd, heading.diagFlatAz, heading.hz))
        }
    }

    // ---------- lifecycle ----------
    override fun onResume() {
        super.onResume()
        setDebugTurn(false)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        } else startSensors()
        view.start()
    }

    override fun onPause() {
        view.stop()
        stopSensors()
        persist()
        super.onPause()
    }

    private fun startSensors() {
        if (started) return
        started = true
        heading.start(); loc.start()
    }

    private fun stopSensors() {
        if (!started) return
        started = false
        heading.stop(); loc.stop()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED && !isFinishing) startSensors()
    }
}
