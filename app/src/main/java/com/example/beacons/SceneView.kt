package com.example.beacons

import android.content.Context
import android.graphics.Canvas
import android.view.Choreographer
import android.view.View

/**
 * The scene surface. A plain custom View, not Compose Canvas: the scene needs a frame-locked
 * Canvas with direct Paint/Shader/Path control and zero recomposition overhead, and the mockup
 * is already imperative Canvas2D code that ports one-to-one.
 *
 * The frame loop runs only between start() and stop() (onResume / onPause).
 */
class SceneView(context: Context, val scene: Scene) : View(context) {
    private val density = resources.displayMetrics.density
    private var running = false
    private var lastNs = 0L

    /** Called once per frame before drawing (feed heading / fix into the scene here). */
    var beforeFrame: (() -> Unit)? = null

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
}
