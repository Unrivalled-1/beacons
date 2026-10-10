# Beacons (working name) - Phase 1

Offline beacon finder for Android. Pure-black screen, colored light beams on a ground grid, a compass ring in the sky.
Point the phone and the beams line up with real places. The one job it must do perfectly: drop a beacon where you
stand and guide you back to it. A home-screen widget drops a beacon in one tap.

## The spec
`reference/beacons_mockup.html` is a working JS canvas prototype and IS the spec (copied from the brief's original).
Port its projection math, gesture logic, spring, and draw order. Do NOT redesign, restyle, or "improve" it.
Visuals, gestures, and the swipe tilt animation (beams growing as the camera tilts) are approved as drawn.
All tuned numbers live in `app/src/main/java/com/example/beacons/Constants.kt` under the mockup's names.

## Hard rules (every session keeps these)
1. **Battery.** No foreground service. No background location. Sensors and location run only while the scene is on
   screen: register in onResume, unregister in onPause. Rotation vector sensor at SENSOR_DELAY_GAME. Fused location only
   while the scene is visible, at a modest interval. The widget and the drop action use a single fix with a timeout.
   After Step 1, measure 1 h screen-on plus 8 h overnight idle, read `adb shell dumpsys batterystats --charged`, and
   report this app's share. It must not appear among the top overnight drains.
2. **Privacy.** No INTERNET permission in the manifest in phase 1. No analytics, no crash SDKs. All data stays local.
   Do not build hooks for online maps or AI.
3. **Works with zero signal.**
4. **Honest verification.** If it cannot be tested on the physical phone, mark heading, GPS, haptics, widget, and
   battery as UNVERIFIED. Never claim a sensor result that was not observed. If something fails, report it. Do not work
   around it silently.

## Build order (stop and report after each step, one commit per step)
0. Repo, CLAUDE.md, empty app that launches, constants file, fonts.
1. SPIKE: real heading + ONE hardcoded beacon from a debug field. Mockup projection, bearing/distance from current fix.
   GeomagneticField declination so heading is true north. Heading within 2 deg upright and tilting toward flat.
   Rotation vector + remapCoordinateSystem for upright. Report the upright/flat transition and low-accuracy handling.
   Report real numbers, then do the battery measurement.
2. Full scene port (rings, compass ring, beams, edge glows, labels, you-chevron, lock-on haptics, swipe tilt spring,
   beams growing as camera tilts, turn). Real heading replaces drag-to-turn; keep drag-to-turn as debug override only.
3. Beacons: double-tap empty = drop at current fix (hold second tap, slide to color, release). NATO names. Double-tap
   a beam = edit sheet (rename, recolor, take down with undo toast). Persist locally.
4. Widget: one tap drops a beacon. Single fix, 10 s timeout, last-known only if under 2 min old, else clear failed
   state. Haptic on success. Tapping widget body opens app locked on newest beacon.
5. Display controls + pinch zoom: labels (Off / Distance / Name+distance), rings On/Off, label frequency
   (every / 2nd / 4th / only one / none), scale Linear (exactly equal steps) or Log (mockup warp), pinch zoom
   (1-2-5 series in Linear, far range in Log; single-finger tilt/turn unaffected), units ft/mi or m/km. Persisted.

## Out of scope
Friends/sharing, maps, AI, accounts, backups, iOS, any network code, any visual change to the approved look.

## Conventions
- Native Kotlin, Android only. Scene on a custom View (Canvas); sheets/settings may be Compose.
- Placeholder applicationId `com.example.beacons`. Display name only in `res/values/strings.xml` (`app_name`).
- Fonts bundled (OFL): Chakra Petch, Share Tech Mono in `res/font`; licenses in `third_party/fonts`.
- Test device: OnePlus 12R (CPH2611), Android 16. adb: `%LOCALAPPDATA%\Android\Sdk\platform-tools`.
- Each step ends with a CLAUDE_BRIEF_SUMMARY block: files changed, what changed, verification (observed vs
  UNVERIFIED), measured numbers, open questions, commit hash.
