package com.example.beacons

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.example.beacons.Constants.DMAX
import com.example.beacons.Constants.DMIN
import com.example.beacons.Constants.HB
import com.example.beacons.Constants.NEAR
import com.example.beacons.Constants.PAL
import com.example.beacons.Constants.R0
import com.example.beacons.Constants.R1
import com.example.beacons.Constants.RC
import com.example.beacons.Constants.RHO
import com.example.beacons.Constants.SIGNAL
import com.example.beacons.Constants.ZB
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

class Beacon(val id: Int, var name: String, var ci: Int, var x: Double, var y: Double, var born: Double = -9.0)

/** A scene-space beacon with its per-frame derived values (mockup's L[] items). */
private class Item(val b: Beacon, val d: Double, val rel: Double, val r: Double, val sx: Double, val sz: Double) {
    var vis = false
    var dr: Dr? = null
}

private class Dr(val ax: Double, val ay: Double, val ux: Double, val uy: Double, val wpx: Double, val dd: Double)
private class Vx { var x = 0.0; var y = 0.0; var d = 0.0 }
class Hit(val id: Int, val ax: Double, val ay: Double, val bx: Double, val by: Double, val r: Double)

/**
 * Direct port of the mockup's scene: projection, draw order, spring, lock-on.
 * Coordinates are in dp (the view scales the canvas by density), so 1 unit == 1 CSS px of the mockup.
 * Note: as in the mockup, proj(x, y, z) has y = height and z = forward distance.
 */
class Scene(private val ui: Typeface, private val mono: Typeface) {
    var W = 1.0
    var H = 1.0

    // state (mockup's S)
    var x = 0.0; var y = 0.0
    var hdg = 0.0                     // true heading, degrees
    var vh = 0.0
    var turn: Double? = null
    var p = 0.0; var pt = 0.0; var pv = 0.0
    var moving = false; var tilting = false; var dragH = false
    var targetId = -1; var lockId = -1
    var flash = 0.0; var t = 0.0
    var metric = false
    var ringA = 30.0; var ringT = 30.0
    var reduce = false
    var walk = 0.0
    /** Shown as a one-line caption when the compass reports low accuracy. */
    var caption: String? = null

    val beacons = ArrayList<Beacon>()
    val hits = ArrayList<Hit>()

    var onLock: (() -> Unit)? = null
    var onDetent: (() -> Unit)? = null

    // camera
    private var F = 1.0; private var th = 0.0; private var e = 1.0; private var zc = 0.0
    private var cx = 0.0; private var cy = 0.0; private var sT = 0.0; private var cT = 1.0
    private val PA = Vx(); private val PB = Vx(); private val P2 = Vx(); private val P3 = Vx()
    private var segClip = false

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tUi = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = ui }
    private val tMono = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = mono }
    private val path = Path()
    private val oval = RectF()

    private fun col(c: IntArray, a: Double) = Color.argb((clamp(a, 0.0, 1.0) * 255).roundToInt(), c[0], c[1], c[2])
    private fun white(a: Double) = Color.argb((clamp(a, 0.0, 1.0) * 255).roundToInt(), 255, 255, 255)

    // ---------- helpers ----------
    fun warp(d: Double): Double {
        if (d < DMIN) return R0 * d / DMIN
        return R0 + (R1 - R0) * min(1.0, ln(d / DMIN) / ln(DMAX / DMIN))
    }

    fun byId(id: Int) = beacons.firstOrNull { it.id == id }

    fun fmt(d: Double): String {
        if (d < Constants.HERE_DIST) return "HERE"
        if (metric) {
            if (d < 1000) return "${((if (d < 100) Math.round(d / 5) * 5 else Math.round(d / 10) * 10))} M"
            return String.format("%.${if (d < 10000) 2 else 1}f KM", d / 1000)
        }
        val ft = d * 3.28084
        if (ft < 1000) return "${(if (ft < 100) Math.round(ft / 5) * 5 else Math.round(ft / 10) * 10)} FT"
        val mi = d / 1609.344
        return String.format("%.${if (mi < 10) 2 else 1}f MI", mi)
    }

    private fun ringName(m: Double, metricNames: Boolean): String {
        val i = Constants.RING_METERS.indexOfFirst { it == m }
        val ft = arrayOf("100 ft", "300 ft", "600 ft", "1/4 mi", "1/2 mi", "1 mi", "2 mi")
        val me = arrayOf("30 m", "90 m", "180 m", "400 m", "800 m", "1.6 km", "3.2 km")
        return (if (metricNames) me[i] else ft[i]).uppercase()
    }

    // ---------- camera ----------
    private fun setCam() {
        val pp = clamp(p, 0.0, 1.0)
        val th0 = Constants.CAM_PITCH_DEG * D2R
        F = W * Constants.CAM_FOCAL
        th = lerp(th0, Math.PI / 2, pp)
        val e0 = Constants.CAM_E0
        val e1 = F * RC / (W * Constants.CAM_E1_REF)
        e = e0 * Math.pow(e1 / e0, pp)
        zc = -ZB * (1 - pp)
        cx = W / 2
        cy = H * lerp(Constants.CAM_CY0, Constants.CAM_CY1, pp)
        sT = sin(th); cT = cos(th)
    }

    private fun proj(x: Double, y: Double, z: Double, o: Vx): Boolean {
        val py = y - e
        val pz = z - zc
        val d = -py * sT + pz * cT
        o.d = d
        val k = F / max(d, 0.05)
        o.x = cx + x * k
        o.y = cy - (py * cT + pz * sT) * k
        return d >= NEAR - 1e-4
    }

    private fun seg(x0i: Double, y0i: Double, z0i: Double, x1i: Double, y1i: Double, z1i: Double, A: Vx, Q: Vx): Boolean {
        var x0 = x0i; var y0 = y0i; var z0 = z0i; var x1 = x1i; var y1 = y1i; var z1 = z1i
        val d0 = -(y0 - e) * sT + (z0 - zc) * cT
        val d1 = -(y1 - e) * sT + (z1 - zc) * cT
        segClip = false
        if (d0 < NEAR && d1 < NEAR) return false
        if (d0 < NEAR) {
            val tt = (NEAR - d0) / (d1 - d0); x0 += (x1 - x0) * tt; y0 += (y1 - y0) * tt; z0 += (z1 - z0) * tt; segClip = true
        } else if (d1 < NEAR) {
            val u = (NEAR - d1) / (d0 - d1); x1 += (x0 - x1) * u; y1 += (y0 - y1) * u; z1 += (z0 - z1) * u
        }
        proj(x0, y0, z0, A); proj(x1, y1, z1, Q)
        return true
    }

    private fun ringPath(r: Double, yy: Double) {
        var open = false
        val n = 120
        path.reset()
        for (i in 0..n) {
            val a = i.toDouble() / n * TAU
            if (proj(r * sin(a), yy, r * cos(a), PA)) {
                if (open) path.lineTo(PA.x.toFloat(), PA.y.toFloat()) else { path.moveTo(PA.x.toFloat(), PA.y.toFloat()); open = true }
            } else open = false
        }
    }

    private fun ls(p: Paint, px: Double) { p.letterSpacing = (px / p.textSize).toFloat() }
    private fun textMid(c: Canvas, s: String, x: Double, yy: Double, p: Paint) {
        c.drawText(s, x.toFloat(), (yy - (p.ascent() + p.descent()) / 2).toFloat(), p)
    }

    // ---------- update ----------
    fun info(b: Beacon): Triple<Double, Double, Double> {
        val dx = b.x - x; val dy = b.y - y
        val d = hypot(dx, dy)
        val brg = wrap(atan2(dx, dy) / D2R)
        return Triple(d, brg, norm(brg - hdg))
    }

    private fun step(dt: Double) {
        t += dt; flash = max(0.0, flash - dt * 3)
        if (!tilting) {
            if (reduce) { p = pt; pv = 0.0 } else {
                val kk = Constants.SPRING_K
                val cc = 2 * sqrt(kk) * Constants.SPRING_DAMP_RATIO
                pv += (kk * (pt - p) - cc * pv) * dt; p += pv * dt
            }
            if (abs(pt - p) < 0.002 && abs(pv) < 0.02) {
                p = pt; pv = 0.0
                if (moving) { moving = false; onDetent?.invoke() }
            } else moving = true
        }
        val tn = turn
        if (tn != null && !dragH) {
            val dd = norm(tn - hdg)
            hdg = wrap(hdg + dd * min(1.0, dt * Constants.TURN_FOLLOW_RATE))
            if (abs(dd) < 0.08) { hdg = wrap(tn); turn = null }
        } else if (!dragH) {
            if (abs(vh) > 0.2) hdg = wrap(hdg + vh * dt)
            vh *= Math.exp(-Constants.TURN_FRICTION * dt)
            if (abs(vh) < 0.2) vh = 0.0
        }
        ringA = norm(ringA + norm(ringT - ringA) * min(1.0, dt * Constants.RING_ANGLE_RATE))
    }

    private fun updateRingAngle(L: List<Item>) {
        fun clear(a: Double): Double {
            var m = 999.0
            for (it in L) { if (it.d < 3) continue; m = min(m, abs(norm(a - it.rel))) }
            return m
        }
        if (clear(ringT) >= Constants.RING_LABEL_CLEAR_DEG) return
        val cands = if (p < 0.5) doubleArrayOf(30.0, -30.0, 16.0, -16.0)
        else doubleArrayOf(30.0, -30.0, 60.0, -60.0, 90.0, -90.0, 120.0, -120.0, 150.0, -150.0, 180.0, 15.0, -15.0)
        var best = ringT; var bm = -1.0
        for (c in cands) { val m = clear(c); if (m > bm) { bm = m; best = c } }
        ringT = best
    }

    /** Advance simulation and draw one frame. */
    fun frame(c: Canvas, dt: Double) {
        step(dt)
        setCam()
        val L = ArrayList<Item>()
        var ref = Double.POSITIVE_INFINITY
        for (b in beacons) {
            val (d, _, rel) = info(b)
            val r = warp(d); val a = rel * D2R
            val it = Item(b, d, rel, r, r * sin(a), r * cos(a))
            it.vis = proj(it.sx, 0.0, it.sz, PA) && PA.x > -40 && PA.x < W + 40
            L.add(it)
            if (targetId == b.id) ref = d
        }
        if (ref.isInfinite()) for (it in L) ref = min(ref, it.d)
        var lk: Item? = null; var cur: Item? = null
        for (it in L) {
            val ar = abs(it.rel)
            if (it.b.id == lockId && ar < Constants.LOCK_KEEP_DEG) cur = it
            if (ar < Constants.LOCK_ACQUIRE_DEG && it.d > Constants.LOCK_MIN_DIST && (lk == null || ar < abs(lk.rel))) lk = it
        }
        val nid = cur?.b?.id ?: lk?.b?.id ?: -1
        if (nid != lockId) { lockId = nid; if (nid != -1) { flash = 1.0; onLock?.invoke() } }

        updateRingAngle(L)
        c.drawColor(Color.BLACK)
        hits.clear()
        var lockRgb: IntArray? = null
        for (it in L) if (it.b.id == lockId) lockRgb = PAL[it.b.ci].rgb()
        drawHorizon(c, lockRgb ?: SIGNAL)
        drawGround(c)
        drawCompass(c)
        drawYou(c)
        drawBeams(c, L)
        drawLockLine(c, L)
        drawEdges(c, L)
        caption?.let { drawCaption(c, it) }
    }

    private fun Constants.Swatch.rgb(): IntArray = rgbCache.getOrPut(this) { hslRgb(h, l) }
    private val rgbCache = HashMap<Constants.Swatch, IntArray>()

    // ---------- scene pieces ----------
    private fun drawHorizon(c: Canvas, color: IntArray) {
        val a = 1 - clamp(clamp(p, 0.0, 1.0) * 1.5, 0.0, 1.0)
        if (a < 0.01) return
        val hy = cy - F * tan(min(th, 1.5))
        if (hy < -60 || hy > H + 60) return
        fill.shader = LinearGradient(0f, (hy - 70).toFloat(), 0f, (hy + 22).toFloat(),
            intArrayOf(col(color, 0.0), col(color, 0.2 * a), col(color, 0.0)), floatArrayOf(0f, 0.76f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, (hy - 70).toFloat(), W.toFloat(), (hy + 22).toFloat(), fill)
        fill.shader = LinearGradient(0f, 0f, W.toFloat(), 0f,
            intArrayOf(white(0.0), white(0.42 * a), white(0.0)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, hy.toFloat(), W.toFloat(), (hy + 1).toFloat(), fill)
        fill.shader = null
    }

    private fun drawGround(c: Canvas) {
        stroke.strokeWidth = 1f
        val n = Constants.RING_METERS.size
        for (i in 0 until n) {
            stroke.color = white(0.07 + 0.17 * (1 - i.toDouble() / n))
            ringPath(warp(Constants.RING_METERS[i]), 0.0)
            c.drawPath(path, stroke)
        }
        var b = 0
        while (b < 360) {
            val rel = (b - hdg) * D2R; val sn = sin(rel); val cs = cos(rel)
            if (seg(sn * 3, 0.0, cs * 3, sn * RC, 0.0, cs * RC, PA, PB)) {
                stroke.shader = LinearGradient(PA.x.toFloat(), PA.y.toFloat(), PB.x.toFloat(), PB.y.toFloat(),
                    white(if (b % 90 == 0) 0.24 else 0.1), white(0.0), Shader.TileMode.CLAMP)
                c.drawLine(PA.x.toFloat(), PA.y.toFloat(), PB.x.toFloat(), PB.y.toFloat(), stroke)
                stroke.shader = null
            }
            b += Constants.SPOKE_STEP
        }
        val sa = ringA * D2R
        var lastY = Double.NaN
        tMono.textSize = 10f; tMono.textAlign = Paint.Align.LEFT; tMono.typeface = mono; ls(tMono, 0.6)
        for (i in 0 until n) {
            val r = warp(Constants.RING_METERS[i])
            if (proj(r * sin(sa), 0.0, r * cos(sa), PA) && PA.x > 0 && PA.x < W - 30 && (lastY.isNaN() || abs(PA.y - lastY) >= 13)) {
                lastY = PA.y
                tMono.color = white(0.3 + 0.2 * (1 - i.toDouble() / n))
                textMid(c, ringName(Constants.RING_METERS[i], metric), PA.x + 5, PA.y, tMono)
            }
        }
        ls(tMono, 0.0)
    }

    private fun drawCompass(c: Canvas) {
        val sp = sm(p)
        val ht = 20 * (1 - 0.9 * sp)
        stroke.color = white(0.16); stroke.strokeWidth = 1f
        ringPath(RC, ht); c.drawPath(path, stroke)
        tUi.textAlign = Paint.Align.CENTER; tMono.textAlign = Paint.Align.CENTER
        var b = 0
        while (b < 360) {
            val rel = (b - hdg) * D2R; val sn = sin(rel); val cs = cos(rel)
            val card = b % 90 == 0; val maj = b % 30 == 0; val m15 = b % 15 == 0
            val tl = (if (card) 4.2 else if (maj) 2.8 else if (m15) 1.9 else 1.0) * (1 - 0.45 * sp)
            val ar = abs(norm(b - hdg))
            val fade = lerp(1 - clamp((ar - 24) / 120, 0.0, 0.82), 1.0, sp)
            if (seg(sn * RC, ht - tl, cs * RC, sn * RC, ht, cs * RC, PA, PB)) {
                stroke.color = if (b == 0) col(SIGNAL, 0.95 * fade)
                else white((if (card) 0.9 else if (maj) 0.6 else if (m15) 0.38 else 0.2) * fade)
                stroke.strokeWidth = if (card) 1.6f else 1f
                c.drawLine(PA.x.toFloat(), PA.y.toFloat(), PB.x.toFloat(), PB.y.toFloat(), stroke)
            }
            if (maj && proj(sn * RC, ht + (if (card) 3.4 else 2.6), cs * RC, PA)) {
                if (card) {
                    tUi.textSize = 17f; tUi.typeface = ui; ls(tUi, 0.0)
                    tUi.color = if (b == 0) col(SIGNAL, fade) else white(fade)
                    textMid(c, mapOf(0 to "N", 90 to "E", 180 to "S", 270 to "W")[b]!!, PA.x, PA.y, tUi)
                } else {
                    tMono.textSize = 10f; ls(tMono, 0.6)
                    tMono.color = white(0.5 * fade)
                    textMid(c, ("00$b").takeLast(3), PA.x, PA.y, tMono)
                    ls(tMono, 0.0)
                }
            }
            b += Constants.COMPASS_TICK_STEP
        }
        stroke.strokeWidth = 1f
    }

    private fun drawYou(c: Canvas) {
        val sp = sm(p)
        val kc = lerp(1.0, 4.0, sp)
        val pts = arrayOf(doubleArrayOf(0.0, 1.9), doubleArrayOf(-1.0, -0.8), doubleArrayOf(0.0, -0.1), doubleArrayOf(1.0, -0.8))
        val ox = DoubleArray(4); val oy = DoubleArray(4)
        for (i in 0 until 4) {
            if (!proj(pts[i][0] * kc, 0.0, pts[i][1] * kc, PA)) return
            ox[i] = PA.x; oy[i] = PA.y
        }
        stroke.color = white(0.26); stroke.strokeWidth = 1f
        ringPath(2.6 * kc, 0.0); c.drawPath(path, stroke)
        fill.color = white(0.94)
        path.reset()
        path.moveTo(ox[0].toFloat(), oy[0].toFloat()); path.lineTo(ox[1].toFloat(), oy[1].toFloat())
        path.lineTo(ox[2].toFloat(), oy[2].toFloat()); path.lineTo(ox[3].toFloat(), oy[3].toFloat()); path.close()
        c.drawPath(path, fill)
    }

    private fun additive(on: Boolean) {
        val m = if (on) BlendMode.PLUS else BlendMode.SRC_OVER
        stroke.blendMode = m; fill.blendMode = m
    }

    private fun drawBeams(c: Canvas, L: List<Item>) {
        val sp = sm(p)
        val order = L.sortedByDescending { it.r }
        val labels = ArrayList<Item>()
        additive(true)
        for ((i, it) in order.withIndex()) {
            val b = it.b; val cc = PAL[b.ci].rgb()
            it.dr = null
            val gr = if (b.born < -5) 1.0 else easeOut((t - b.born) / Constants.BIRTH_S)
            val hb = max(HB * (1 - 0.955 * sp) * gr, 0.01)
            if (!seg(it.sx, 0.0, it.sz, it.sx, hb, it.sz, PA, PB)) continue
            val ax = PA.x; val ay = PA.y; val dd = max(PA.d, 1.0); val clipped = segClip
            val bx = PB.x; val by = PB.y
            val wpx = clamp(F * 0.55 / dd, 1.8, 13.0)
            val dx = bx - ax; val dy = by - ay; val len = hypot(dx, dy)
            val ux = if (len > 0.01) dx / len else 0.0
            val uy = if (len > 0.01) dy / len else -1.0
            val lg = if (len > 0.5) min(len, clamp(F * 60 / dd, 120.0, H * 0.62)) else 0.0
            val ex = ax + ux * lg; val ey = ay + uy * lg
            val lock = lockId == b.id; val tg = targetId == b.id

            if (!clipped && proj(it.sx + RHO, 0.0, it.sz, P2) && proj(it.sx, 0.0, it.sz + RHO, P3)) {
                val rx = max(abs(P2.x - ax), wpx * 1.6); val ry = max(abs(P3.y - ay), wpx * 0.6)
                c.save(); c.translate(ax.toFloat(), ay.toFloat()); c.scale(1f, (ry / rx).toFloat())
                fill.shader = RadialGradient(0f, 0f, (rx * 2.6).toFloat(), col(cc, 0.5), col(cc, 0.0), Shader.TileMode.CLAMP)
                c.drawCircle(0f, 0f, (rx * 2.6).toFloat(), fill); fill.shader = null
                c.restore()
                if (!reduce) {
                    val ph = (t * 0.5 + i * 0.27) % 1
                    stroke.color = col(cc, (1 - ph) * 0.5); stroke.strokeWidth = 1f
                    ellipse(c, ax, ay, rx * (1 + ph * 1.7), ry * (1 + ph * 1.7))
                }
                stroke.color = col(cc, 0.95); stroke.strokeWidth = 1.5f
                ellipse(c, ax, ay, rx, ry)
                if (tg) { stroke.color = white(0.85); stroke.strokeWidth = 1f; ellipse(c, ax, ay, rx * 1.4, ry * 1.4) }
                if (lock) {
                    stroke.color = col(SIGNAL, 0.95); stroke.strokeWidth = (1.5 + flash * 2).toFloat()
                    ellipse(c, ax, ay, rx * (1.9 + flash * 0.5), ry * (1.9 + flash * 0.5))
                }
            }

            if (lg > 1) {
                stroke.strokeCap = Paint.Cap.BUTT
                stroke.shader = LinearGradient(ax.toFloat(), ay.toFloat(), ex.toFloat(), ey.toFloat(),
                    intArrayOf(col(cc, 1.0), col(cc, 0.55), col(cc, 0.0)), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
                stroke.color = Color.WHITE
                stroke.alpha = (0.09 * 255).toInt(); stroke.strokeWidth = (wpx * 16).toFloat(); beamLine(c, ax, ay, ex, ey)
                stroke.alpha = (0.3 * 255).toInt(); stroke.strokeWidth = (wpx * 6).toFloat(); beamLine(c, ax, ay, ex, ey)
                stroke.alpha = 255; stroke.strokeWidth = wpx.toFloat(); beamLine(c, ax, ay, ex, ey)
                stroke.shader = LinearGradient(ax.toFloat(), ay.toFloat(), ex.toFloat(), ey.toFloat(),
                    intArrayOf(white(0.95), white(0.25), white(0.0)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                stroke.strokeWidth = max(1.0, wpx * 0.3).toFloat(); beamLine(c, ax, ay, ex, ey)
                stroke.shader = null
            }
            if (sp > 0.4) {
                val ma = sm((sp - 0.4) / 0.4)
                fill.shader = RadialGradient(ax.toFloat(), ay.toFloat(), 18f, col(cc, 0.8 * ma), col(cc, 0.0), Shader.TileMode.CLAMP)
                c.drawCircle(ax.toFloat(), ay.toFloat(), 18f, fill); fill.shader = null
                fill.color = white(ma); c.drawCircle(ax.toFloat(), ay.toFloat(), 3.2f, fill)
                if (tg || lock) {
                    stroke.color = if (lock) col(SIGNAL, ma) else white(0.85 * ma); stroke.strokeWidth = 1.5f
                    c.drawCircle(ax.toFloat(), ay.toFloat(), (10 + (if (lock) flash * 5 else 0.0)).toFloat(), stroke)
                }
            }
            if (sp > 0.6) hits.add(Hit(b.id, ax, ay, ax, ay, 30.0))
            else hits.add(Hit(b.id, ax, ay, ex, ey, max(26.0, wpx * 2.5)))
            it.dr = Dr(ax, ay, ux, uy, wpx, dd)
            labels.add(it)
        }
        additive(false)
        stroke.strokeWidth = 1f; stroke.alpha = 255

        tUi.textAlign = Paint.Align.LEFT
        for (q in labels) {
            val dr = q.dr!!
            val lk = lockId == q.b.id; val tgt = targetId == q.b.id
            val fs = if (lk) 17.0 else if (tgt) 15.0 else 13.0
            val off = lerp(30.0, 0.0, sp)
            val lx = lerp(0.0, dr.wpx / 2 + 11, sp); val ly = lerp(dr.wpx / 2 + 6 + fs * 0.8, fs * 0.36, sp)
            val al = clamp(1.12 - dr.dd / 150, 0.5, 1.0)
            c.save(); c.translate((dr.ax + dr.ux * off).toFloat(), (dr.ay + dr.uy * off).toFloat())
            c.rotate((-90 * (1 - sp)).toFloat())
            tUi.textSize = fs.toFloat(); tUi.typeface = ui; ls(tUi, fs * 0.14)
            val nm = q.b.name.ifEmpty { "BEACON" }.uppercase()
            tUi.color = if (lk) Color.WHITE else white(al * 0.92)
            c.drawText(nm, lx.toFloat(), (ly).toFloat(), tUi)
            val mw = tUi.measureText(nm)
            tMono.textSize = (fs - 2).toFloat(); tMono.textAlign = Paint.Align.LEFT; ls(tMono, 0.4)
            tMono.color = if (lk) col(SIGNAL, 1.0) else white(al * 0.62)
            c.drawText(fmt(q.d), (lx + mw + 12).toFloat(), ly.toFloat(), tMono)
            ls(tMono, 0.0)
            c.restore()
        }
    }

    private fun beamLine(c: Canvas, ax: Double, ay: Double, ex: Double, ey: Double) =
        c.drawLine(ax.toFloat(), ay.toFloat(), ex.toFloat(), ey.toFloat(), stroke)

    private fun ellipse(c: Canvas, x: Double, y: Double, rx: Double, ry: Double) {
        oval.set((x - rx).toFloat(), (y - ry).toFloat(), (x + rx).toFloat(), (y + ry).toFloat())
        c.drawOval(oval, stroke)
    }

    private fun drawEdges(c: Canvas, L: List<Item>) {
        val w = 1 - sm((p - 0.08) / 0.45)
        if (w < 0.02) return
        val hy = cy - F * tan(min(th, 1.5))
        val y0 = clamp(hy + 70, H * 0.34, H * 0.66)
        val sides = arrayOf(ArrayList<Item>(), ArrayList<Item>())
        for (it in L) if (!it.vis) sides[if (it.rel >= 0) 1 else 0].add(it)
        additive(true)
        for (s in 0..1) {
            val arr = sides[s]; val ex = if (s == 1) W else 0.0
            arr.sortBy { it.d }
            for (k in 0 until min(arr.size, 4)) {
                val it = arr[k]; val cc = PAL[it.b.ci].rgb(); val y = y0 + k * 54; val tg = targetId == it.b.id
                val pulse = if (reduce) 1.0 else 0.75 + 0.25 * sin(t * 3 + k)
                fill.shader = RadialGradient(ex.toFloat(), y.toFloat(), 64f, col(cc, min(1.0, 0.75 * pulse + (if (tg) 0.25 else 0.0)) * w), col(cc, 0.0), Shader.TileMode.CLAMP)
                c.drawRect((ex - 64).toFloat(), (y - 64).toFloat(), (ex + 64).toFloat(), (y + 64).toFloat(), fill)
                fill.shader = null
                fill.color = col(cc, 0.95 * w)
                val rx = if (s == 1) (W - 3).toFloat() else 0f
                c.drawRect(rx, (y - 16).toFloat(), rx + 3f, (y + 16).toFloat(), fill)
                hits.add(Hit(it.b.id, ex, y - 24, ex, y + 24, 44.0))
            }
        }
        additive(false)
    }

    private fun drawLockLine(c: Canvas, L: List<Item>) {
        val w = 1 - sm((p - 0.1) / 0.5)
        if (lockId == -1 || w < 0.02) return
        for (it in L) {
            val dr = it.dr
            if (it.b.id == lockId && dr != null) {
                val a = (0.2 + flash * 0.5) * w
                stroke.shader = LinearGradient(0f, 0f, 0f, dr.ay.toFloat(), col(SIGNAL, 0.0), col(SIGNAL, a), Shader.TileMode.CLAMP)
                stroke.strokeWidth = 1f
                c.drawLine(dr.ax.toFloat(), 0f, dr.ax.toFloat(), dr.ay.toFloat(), stroke)
                stroke.shader = null
            }
        }
    }

    /** Same style as the mockup's intro captions. */
    private fun drawCaption(c: Canvas, s: String) {
        tMono.textSize = 11f; tMono.textAlign = Paint.Align.CENTER; ls(tMono, 2.4)
        tMono.color = white(0.75)
        textMid(c, s, W / 2, H * 0.12, tMono)
        ls(tMono, 0.0)
    }
}
