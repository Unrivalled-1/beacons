package com.example.beacons

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

const val TAU = Math.PI * 2
const val D2R = Math.PI / 180

fun clamp(v: Double, a: Double, b: Double) = max(a, min(b, v))
fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
fun sm(t0: Double): Double { val t = clamp(t0, 0.0, 1.0); return t * t * (3 - 2 * t) }
fun wrap(a: Double) = ((a % 360) + 360) % 360
fun norm(a: Double): Double { val w = wrap(a); return if (w > 180) w - 360 else w }
fun easeOut(t0: Double): Double { val t = clamp(t0, 0.0, 1.0); return 1 - (1 - t).pow(3) }
fun eob(t: Double): Double { val c1 = 1.70158; val c3 = c1 + 1; return 1 + c3 * (t - 1).pow(3) + c1 * (t - 1).pow(2) }

fun hslRgb(h: Double, l: Double): IntArray {
    val a = min(l, 1 - l)
    fun f(n: Int): Int {
        val k = (n + h / 30) % 12
        return (255 * (l - a * max(-1.0, min(min(k - 3, 9 - k), 1.0)))).roundToInt()
    }
    return intArrayOf(f(0), f(8), f(4))
}
