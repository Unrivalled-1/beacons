package com.example.beacons

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Click haptics via VibrationEffect.Composition primitives, with EFFECT_CLICK as the fallback.
 * Mapping from the mockup: clink (lock-on) = CLICK, tick = TICK, detent (tilt settled) = THUD.
 */
class Haptics(context: Context) {
    private val vib: Vibrator =
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator

    private fun supported(p: Int) = vib.areAllPrimitivesSupported(p)

    private fun prim(p: Int, scale: Float) {
        if (supported(p)) {
            vib.vibrate(VibrationEffect.startComposition().addPrimitive(p, scale).compose())
        } else {
            vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        }
    }

    fun clink() = prim(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.9f)
    fun tick() = prim(VibrationEffect.Composition.PRIMITIVE_TICK, 0.7f)
    fun detent() = prim(VibrationEffect.Composition.PRIMITIVE_THUD, 1.0f)
    fun drop() {
        if (supported(VibrationEffect.Composition.PRIMITIVE_CLICK) && supported(VibrationEffect.Composition.PRIMITIVE_THUD)) {
            vib.vibrate(
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.8f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1.0f, 30)
                    .compose()
            )
        } else vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
    }
}
