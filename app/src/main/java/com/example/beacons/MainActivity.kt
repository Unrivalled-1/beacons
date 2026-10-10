package com.example.beacons

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.location.Location
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.sin

/** Step 1 spike host: scene + debug HUD + one debug beacon. */
class MainActivity : Activity() {
    private lateinit var scene: Scene
    private lateinit var view: SceneView
    private lateinit var heading: HeadingSensor
    private lateinit var loc: LocationSource
    private lateinit var haptics: Haptics
    private lateinit var hud: TextView
    private lateinit var latF: EditText
    private lateinit var lonF: EditText
    private var beacon: Beacon? = null
    private var lastLogMs = 0L
    private var started = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = resources.getFont(R.font.chakra_petch_medium)
        val mono = resources.getFont(R.font.share_tech_mono)
        scene = Scene(ui, mono)
        view = SceneView(this, scene)
        heading = HeadingSensor(this)
        loc = LocationSource(this)
        haptics = Haptics(this)
        scene.onLock = { haptics.clink() }
        scene.onDetent = { haptics.detent() }

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.addView(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        hud = TextView(this).apply {
            typeface = mono; textSize = 10f; setTextColor(Color.argb(190, 255, 255, 255)); setLineSpacing(0f, 1.0f)
            setPadding(dp(8), dp(36), dp(8), 0)
        }
        root.addView(hud, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val panel = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(6), dp(4), dp(6), dp(10)); gravity = Gravity.CENTER_VERTICAL }
        fun field(hint: String) = EditText(this).apply {
            this.hint = hint; typeface = mono; textSize = 11f; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setSingleLine(); imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
        }
        fun btn(t: String) = Button(this).apply { text = t; typeface = mono; textSize = 10f; setAllCaps(false); minWidth = 0; minimumWidth = 0; setPadding(dp(8), 0, dp(8), 0) }
        latF = field("lat"); lonF = field("lon")
        val set = btn("SET").apply { setOnClickListener { applyField() } }
        val here = btn("HERE").apply { setOnClickListener { loc.last?.let { l -> latF.setText("%.6f".format(l.latitude)); lonF.setText("%.6f".format(l.longitude)); applyField() } } }
        val ahead = btn("100M AHEAD").apply { setOnClickListener { placeAhead(100.0) } }
        panel.addView(latF, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(lonF, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        turnBtn = btn("COMPASS").apply {
            setOnClickListener { setDebugTurn(!view.debugTurn) }
        }
        panel.addView(set); panel.addView(here); panel.addView(ahead); panel.addView(turnBtn)
        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)

        val sp = getSharedPreferences("spike", MODE_PRIVATE)
        if (sp.contains("lat")) {
            latF.setText(sp.getString("lat", "")); lonF.setText(sp.getString("lon", ""))
            applyField()
        }

        view.beforeFrame = { tick() }
    }

    private var beaconLat = Double.NaN
    private var beaconLon = Double.NaN

    private fun applyField() {
        val la = latF.text.toString().toDoubleOrNull(); val lo = lonF.text.toString().toDoubleOrNull()
        if (la == null || lo == null) return
        beaconLat = la; beaconLon = lo
        getSharedPreferences("spike", MODE_PRIVATE).edit().putString("lat", latF.text.toString()).putString("lon", lonF.text.toString()).apply()
        if (beacon == null) { beacon = Beacon(1, "Debug", 4, 0.0, 0.0); scene.beacons.add(beacon!!) }
    }

    private lateinit var turnBtn: Button

    /** Debug only: true = scene follows finger drag instead of the compass. Never persists across resumes. */
    private fun setDebugTurn(on: Boolean) {
        view.debugTurn = on
        turnBtn.text = if (on) "DRAG (DEBUG)" else "COMPASS"
    }

    private fun placeAhead(m: Double) {
        val l = loc.last ?: return
        val brg = heading.trueHeading * D2R
        val dLat = m * cos(brg) / 111_320.0
        val dLon = m * sin(brg) / (111_320.0 * cos(l.latitude * D2R))
        latF.setText("%.6f".format(l.latitude + dLat)); lonF.setText("%.6f".format(l.longitude + dLon))
        applyField()
    }

    private fun tick() {
        if (!view.debugTurn) scene.hdg = heading.trueHeading
        val l = loc.last
        scene.caption = if (heading.hasSample && heading.unreliable) "COMPASS LOW  WAVE PHONE IN A FIGURE 8" else null
        var info = "NO FIX"
        if (l != null) {
            heading.setLocation(l.latitude, l.longitude, l.altitude)
            val b = beacon
            if (b != null && !beaconLat.isNaN()) {
                val res = FloatArray(2)
                Location.distanceBetween(l.latitude, l.longitude, beaconLat, beaconLon, res)
                val d = res[0].toDouble(); val brg = res[1] * D2R
                b.x = d * sin(brg); b.y = d * cos(brg)
                info = "BEACON %.1f m  brg %.1f".format(d, wrap(res[1].toDouble()))
            }
        }
        val now = System.currentTimeMillis()
        if (now - lastLogMs >= 250) {
            lastLogMs = now
            val age = l?.let { (android.os.SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1e9 }
            hud.text = buildString {
                append("TRUE %05.1f  MAG %05.1f  DECL %+.1f\n".format(heading.trueHeading, heading.magHeading, heading.declination))
                append("ACC %d  EST %s  JIT5s %.2f  %.0fHz\n".format(heading.accuracy, if (heading.estAccDeg >= 0) "%.1f".format(heading.estAccDeg) else "n/a", heading.jitter5s, heading.hz))
                append("TILT %.0f  remap %05.1f  vec %05.1f  flat %05.1f\n".format(heading.tiltFromUpright, heading.diagRemapAz, heading.diagVectorFwd, heading.diagFlatAz))
                append(if (l != null) "FIX acc %.0f m  age %.0f s  n=%d\n".format(l.accuracy, age, loc.updates) else "FIX none\n")
                append(info)
            }
            Log.i("BeaconsHeading", "true=%.1f mag=%.1f decl=%.1f acc=%d est=%.1f jit=%.2f tilt=%.0f remap=%.1f vec=%.1f flat=%.1f hz=%.0f".format(
                heading.trueHeading, heading.magHeading, heading.declination, heading.accuracy, heading.estAccDeg, heading.jitter5s,
                heading.tiltFromUpright, heading.diagRemapAz, heading.diagVectorFwd, heading.diagFlatAz, heading.hz))
        }
    }

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
