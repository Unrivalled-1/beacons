package com.example.beacons

/**
 * Every tuned number from reference/beacons_mockup.html, under the mockup's own names.
 * Do not retune here without re-approving the look. Units: scene units unless noted.
 */
object Constants {
    // --- distance warp (log scale). Real metres in, scene units out. ---
    const val DMIN = 20.0       // below this distance the warp is linear
    const val DMAX = 4000.0     // distance that lands on R1
    const val R0 = 5.0          // scene radius at DMIN
    const val R1 = 58.0         // scene radius at DMAX
    const val RC = 64.0         // compass ring radius
    const val HB = 42.0         // beam height
    const val ZB = 10.0         // camera z offset that is removed as the camera rises (zc = -ZB * (1 - p))
    const val NEAR = 0.6        // near clip distance along the view axis
    const val RHO = 1.5         // beacon base ring radius (ground units)

    // --- camera ---
    const val CAM_PITCH_DEG = 21.0      // th0: chase-view pitch at p = 0
    const val CAM_FOCAL = 0.56          // F = W * CAM_FOCAL
    const val CAM_E0 = 9.0              // camera distance behind origin at p = 0
    const val CAM_E1_REF = 0.41         // e1 = F * RC / (W * CAM_E1_REF)
    const val CAM_CY0 = 0.56            // horizon-centre y fraction at p = 0
    const val CAM_CY1 = 0.50            // at p = 1
    const val TILT_MIN = -0.05
    const val TILT_MAX = 1.05
    const val TILT_DRAG_H = 0.5         // vertical drag of H * 0.5 spans p 0..1

    // --- tilt spring (p chases pt) ---
    const val SPRING_K = 150.0
    const val SPRING_DAMP_RATIO = 0.86  // c = 2 * sqrt(K) * ratio
    const val SPRING_FLING_LOOKAHEAD = 0.18

    // --- turn / heading ---
    const val TURN_DRAG_GAIN = 0.22     // degrees per px (debug drag-to-turn only once real heading lands)
    const val TURN_FOLLOW_RATE = 7.0
    const val TURN_FRICTION = 3.4
    const val TURN_VEL_MAX = 420.0

    // --- lock-on ---
    const val LOCK_ACQUIRE_DEG = 1.4
    const val LOCK_KEEP_DEG = 2.6
    const val LOCK_MIN_DIST = 3.0
    const val HERE_DIST = 3.0           // fmt() prints HERE under this

    // --- compass / ground ---
    const val COMPASS_TICK_STEP = 5
    const val SPOKE_STEP = 15
    const val RING_LABEL_CLEAR_DEG = 16.0
    const val RING_ANGLE_RATE = 6.0

    // --- colour ring (double tap, hold, slide) ---
    const val RR = 74.0
    const val RING_CANCEL_PX = 170.0
    const val RING_DEADZONE_PX = 22.0

    // --- gestures (ms / px) ---
    const val DOUBLE_TAP_MS = 330L
    const val DOUBLE_TAP_SLOP_PX = 36.0
    const val HOLD_WALK_MS = 380L       // debug walk, not shipped as a feature
    const val TAP_MAX_MS = 300L
    const val MOVE_SLOP_PX = 9.0
    const val TAP_SLOP_PX = 10.0
    const val VERTICAL_BIAS = 1.15

    // --- beam birth ---
    const val BIRTH_S = 0.8

    // --- palette: HSL (h deg, l) + name, in mockup order ---
    class Swatch(val h: Double, val l: Double, val name: String)

    val PAL = listOf(
        Swatch(12.0, 0.55, "Ember"), Swatch(40.0, 0.50, "Amber"), Swatch(84.0, 0.50, "Lime"), Swatch(150.0, 0.50, "Mint"),
        Swatch(186.0, 0.50, "Cyan"), Swatch(218.0, 0.60, "Blue"), Swatch(264.0, 0.64, "Violet"), Swatch(322.0, 0.58, "Magenta"),
    )
    val NATO = listOf(
        "Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel", "India", "Juliet", "Kilo", "Lima",
    )

    /** Signal red used for north tick, lock-on, and the lock line. */
    val SIGNAL = intArrayOf(255, 61, 20)

    // --- Linear/Log ring set from the mockup (metres). Display strings live with unit formatting later. ---
    val RING_METERS = doubleArrayOf(30.48, 91.44, 182.9, 402.3, 804.7, 1609.3, 3218.7)
}
