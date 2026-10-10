package com.example.beacons

import android.content.Context
import android.graphics.Canvas
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * The scene surface. A plain custom View, not Compose Canvas: the scene needs a frame-locked
 * Canvas with direct Paint/Shader/Path control and zero recomposition overhead, and the mockup
 * is already imperative Canvas2D code that ports one-to-one.
 *
 * The frame loop runs only between start() and stop() (onResume / onPause).
 *
 * Gestures are the mockup's, single finger: vertical drag tilts the camera (spring-settled),
 * horizontal drag turns only when [debugTurn] is on (real heading drives the scene otherwise),
 * a short tap selects/clears a beacon target, a double tap on empty space opens the colour ring
 * (hold, slide, release drops a beacon), a double tap on a beam opens its edit sheet.
 */
class SceneView(context: Context, val scene: Scene) : View(context) {
    private val density = resources.displayMetrics.density
    private var running = false
    private var lastNs = 0L

    /** Called once per frame before drawing (feed heading / fix into the scene here). */
    var beforeFrame: (() -> Unit)? = null

    /** Debug override: horizontal drag turns the scene instead of the compass. */
    var debugTurn = false

    /** If a sheet is open, the first touch just closes it (mockup behaviour). Return true if it consumed the touch. */
    var sheetOpen: (() -> Boolean)? = null
    var closeSheets: (() -> Unit)? = null
    var onDrop: ((colorIndex: Int) -> Unit)? = null
    var onEdit: ((id: Int) -> Unit)? = null

    private enum class Mode { PENDING, TILT, TURN, IGNORE, RING, EDITPRESS }

    private class Gesture(val x0: Double, val y0: Double, val t0: Long, val startP: Double) {
        var x = x0; var y = y0; var tl = t0
        var mode = Mode.PENDING
        var moved = 0.0
        var pv = 0.0
        var hit = -1
    }

    private class LastTap(val x: Double, val y: Double, val t: Long, val toggled: Boolean, val prev: Int)

    private var g: Gesture? = null
    private var lastTap: LastTap? = null

    private val cb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        if (running) return
        running = true; lastNs = 0L
        Choreographer.getInstance().postFrameCallback(cb)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(cb)
        g = null; scene.tilting = false; scene.dragH = false; scene.ring.on = false
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        scene.W = w / density.toDouble(); scene.H = h / density.toDouble()
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0.0 else minOf(0.05, (now - lastNs) / 1e9)
        lastNs = now
        beforeFrame?.invoke()
        canvas.save()
        canvas.scale(density, density)
        scene.frame(canvas, dt)
        canvas.restore()
    }

    // ---------- touch ----------
    private fun pick(px: Double, py: Double): Int {
        var best = -1; var bd = 1e9
        for (h in scene.hits) {
            val d = distSeg(px, py, h.ax, h.ay, h.bx, h.by)
            if (d < h.r && d < bd) { bd = d; best = h.id }
        }
        return best
    }

    private fun distSeg(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
        val t = if (l2 > 0) clamp(((px - ax) * dx + (py - ay) * dy) / l2, 0.0, 1.0) else 0.0
        return hypot(px - (ax + dx * t), py - (ay + dy * t))
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x / density.toDouble(); val y = e.y / density.toDouble()
        val now = e.eventTime
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (sheetOpen?.invoke() == true) { closeSheets?.invoke(); return true }
                val G = Gesture(x, y, now, scene.p)
                g = G
                scene.vh = 0.0; scene.turn = null
                val lt = lastTap
                val dbl = lt != null && now - lt.t < Constants.DOUBLE_TAP_MS &&
                    hypot(x - lt.x, y - lt.y) < Constants.DOUBLE_TAP_SLOP_PX
                if (dbl && lt != null) {
                    val hid = pick(x, y)
                    if (lt.toggled) scene.targetId = lt.prev
                    lastTap = null
                    if (hid != -1) { G.mode = Mode.EDITPRESS; G.hit = hid }
                    else { G.mode = Mode.RING; scene.openRing(x, y) }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val G = g ?: return true
                val dts = max(0.001, (now - G.tl) / 1000.0)
                G.moved = max(G.moved, abs(x - G.x0) + abs(y - G.y0))
                if (G.mode == Mode.PENDING && G.moved > Constants.MOVE_SLOP_PX) {
                    if (abs(y - G.y0) > abs(x - G.x0) * Constants.VERTICAL_BIAS) {
                        G.mode = Mode.TILT; scene.tilting = true; scene.pv = 0.0
                    } else if (debugTurn) {
                        G.mode = Mode.TURN; scene.dragH = true
                    } else G.mode = Mode.IGNORE
                }
                when (G.mode) {
                    Mode.TILT -> {
                        val np = clamp(G.startP + (G.y0 - y) / (scene.H * Constants.TILT_DRAG_H), Constants.TILT_MIN, Constants.TILT_MAX)
                        G.pv = lerp(G.pv, (np - scene.p) / dts, 0.5); scene.p = np
                    }
                    Mode.TURN -> {
                        val dh = -(x - G.x) * Constants.TURN_DRAG_GAIN
                        scene.hdg = wrap(scene.hdg + dh); scene.turn = null
                        scene.vh = clamp(scene.vh * 0.5 + (dh / dts) * 0.5, -Constants.TURN_VEL_MAX, Constants.TURN_VEL_MAX)
                    }
                    Mode.RING -> scene.ringMove(x, y)
                    else -> {}
                }
                G.x = x; G.y = y; G.tl = now
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val G = g ?: return true
                g = null
                scene.dragH = false; scene.tilting = false
                val up = e.actionMasked == MotionEvent.ACTION_UP
                when (G.mode) {
                    Mode.TILT -> {
                        scene.pv = G.pv
                        scene.pt = if (scene.p + G.pv * Constants.SPRING_FLING_LOOKAHEAD > 0.5) 1.0 else 0.0
                        scene.moving = true
                    }
                    Mode.TURN -> { if ((now - G.tl) > 90) scene.vh = 0.0 }
                    Mode.RING -> {
                        val ci = if (up) scene.closeRing(x, y) else { scene.ring.on = false; -1 }
                        if (ci >= 0) onDrop?.invoke(ci)
                    }
                    Mode.EDITPRESS -> { if (up && G.moved < 12) onEdit?.invoke(G.hit) }
                    Mode.PENDING -> {
                        if (up && now - G.t0 < Constants.TAP_MAX_MS && G.moved < Constants.TAP_SLOP_PX) {
                            val id = pick(x, y)
                            val prev = scene.targetId
                            var tog = false
                            if (id != -1) { scene.targetId = if (prev == id) -1 else id; tog = true }
                            lastTap = LastTap(x, y, now, tog, prev)
                        }
                    }
                    else -> {}
                }
            }
        }
        return true
    }
}
