package com.example.beacons

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * True-north heading of the direction the phone is "facing", stable from held-upright (camera forward)
 * through tilted down toward flat on a table (top edge forward).
 *
 * Upright pose: rotation matrix remapped with remapCoordinateSystem(AXIS_X, AXIS_Z), azimuth from getOrientation.
 * Flat pose: azimuth of the device's top edge (unmapped getOrientation).
 * Transition: both headings are unit vectors weighted by how much horizontal projection each axis still has
 * (sqrt(1 - Rzz^2) for the camera axis, sqrt(1 - Ryz^2) for the top edge). Each singular pose has zero weight
 * exactly where its azimuth is undefined, so there is no switch point and no jump.
 *
 * Register in onResume, unregister in onPause. Nothing runs in between.
 */
class HeadingSensor(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val r = FloatArray(9)
    private val rr = FloatArray(9)
    private val ori = FloatArray(3)

    val available get() = sensor != null

    /** Smoothed true heading in degrees [0,360). */
    @Volatile var trueHeading = 0.0
    @Volatile var magHeading = 0.0
    @Volatile var declination = 0.0
    @Volatile var accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    /** Sensor-estimated heading error in degrees, or -1 if the sensor does not report one. */
    @Volatile var estAccDeg = -1.0
    /** Degrees away from upright: 0 = held upright, 90 = flat. */
    @Volatile var tiltFromUpright = 0.0
    @Volatile var hasSample = false
    // diagnostics
    @Volatile var diagRemapAz = 0.0      // getOrientation azimuth from the remapped matrix
    @Volatile var diagVectorFwd = 0.0    // direct world heading of the camera axis, atan2(-R[2], -R[5])
    @Volatile var diagFlatAz = 0.0       // top-edge azimuth
    @Volatile var hz = 0.0
    @Volatile var jitter5s = 0.0         // circular std-dev of smoothed heading over last 5 s, degrees

    /** Added to the remapped azimuth to get the camera-forward direction. Measured on device, see diagnostics. */
    var uprightOffsetDeg = 0.0

    private var sx = 0.0
    private var sy = 0.0
    private var lastNs = 0L
    private var hzCount = 0
    private var hzStart = 0L
    private val hist = ArrayDeque<Pair<Long, DoubleArray>>()

    private var declLat = Double.NaN
    private var declLon = Double.NaN

    fun start() {
        hasSample = false; lastNs = 0L
        sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() = sm.unregisterListener(this)

    /** Recompute magnetic declination when the fix has moved meaningfully (it varies over hundreds of km). */
    fun setLocation(lat: Double, lon: Double, alt: Double) {
        if (!declLat.isNaN() && abs(lat - declLat) < 0.25 && abs(lon - declLon) < 0.25) return
        declLat = lat; declLon = lon
        declination = GeomagneticField(lat.toFloat(), lon.toFloat(), alt.toFloat(), System.currentTimeMillis())
            .declination.toDouble()
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) { accuracy = a }

    override fun onSensorChanged(ev: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(r, ev.values)
        if (ev.values.size >= 5 && ev.values[4] >= 0f) estAccDeg = Math.toDegrees(ev.values[4].toDouble())
        accuracy = ev.accuracy

        // upright (camera forward) via remap
        SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_X, SensorManager.AXIS_Z, rr)
        SensorManager.getOrientation(rr, ori)
        val remapAz = Math.toDegrees(ori[0].toDouble())
        // flat (top edge forward), unmapped
        SensorManager.getOrientation(r, ori)
        val flatAz = Math.toDegrees(ori[0].toDouble())

        val wUp = sqrt(max(0.0, 1.0 - r[8].toDouble() * r[8]))   // horizontal reach of the camera axis
        val wFlat = sqrt(max(0.0, 1.0 - r[7].toDouble() * r[7])) // horizontal reach of the top edge
        val upAz = remapAz + uprightOffsetDeg
        val e = wUp * sin(upAz * D2R) + wFlat * sin(flatAz * D2R)
        val n = wUp * cos(upAz * D2R) + wFlat * cos(flatAz * D2R)
        val mag = wrap(Math.toDegrees(atan2(e, n)))
        magHeading = mag
        val tru = wrap(mag + declination)

        diagRemapAz = wrap(remapAz); diagFlatAz = wrap(flatAz)
        diagVectorFwd = wrap(Math.toDegrees(atan2(-r[2].toDouble(), -r[5].toDouble())))
        tiltFromUpright = Math.toDegrees(asin(clamp(abs(r[8].toDouble()), 0.0, 1.0)))

        // light circular low-pass (tau 0.1 s) on top of the fused rotation vector
        val now = ev.timestamp
        val dt = if (lastNs == 0L) 0.0 else (now - lastNs) / 1e9
        lastNs = now
        val a = if (!hasSample) 1.0 else 1 - exp(-dt / 0.10)
        sx += a * (sin(tru * D2R) - sx); sy += a * (cos(tru * D2R) - sy)
        trueHeading = wrap(Math.toDegrees(atan2(sx, sy)))
        hasSample = true

        // diagnostics: rate and 5 s jitter
        if (hzStart == 0L) hzStart = now
        hzCount++
        if (now - hzStart > 1_000_000_000L) { hz = hzCount * 1e9 / (now - hzStart); hzCount = 0; hzStart = now }
        hist.addLast(now to doubleArrayOf(sin(trueHeading * D2R), cos(trueHeading * D2R)))
        while (hist.isNotEmpty() && now - hist.first().first > 5_000_000_000L) hist.removeFirst()
        if (hist.size > 5) {
            var mx = 0.0; var my = 0.0
            for (h in hist) { mx += h.second[0]; my += h.second[1] }
            mx /= hist.size; my /= hist.size
            val rbar = sqrt(mx * mx + my * my).coerceIn(1e-9, 1.0)
            jitter5s = Math.toDegrees(sqrt(-2 * kotlin.math.ln(rbar)))
        }
    }

    /** True when the compass should not be trusted: low sensor accuracy or a large reported error. */
    val unreliable: Boolean
        get() = accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW || (estAccDeg >= 0 && estAccDeg > 20)
}
