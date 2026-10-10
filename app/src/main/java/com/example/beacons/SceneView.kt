package com.example.beacons

import android.content.Context
import android.graphics.Canvas
import android.view.View

/**
 * The scene surface. A plain custom View, not Compose Canvas: the scene needs a frame-locked
 * Canvas with direct Paint/Shader/Path control and zero recomposition overhead, and the mockup
 * is already imperative Canvas2D code that ports one-to-one.
 */
class SceneView(context: Context) : View(context) {
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF000000.toInt())
    }
}
