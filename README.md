# Beacons

**An offline beacon finder for Android.** Drop a beacon where you stand (car, tent, trailhead, anything) and
follow a column of colored light back to it. Point the phone and the beams line up with the real places they mark.

Pure-black screen. Colored light beams standing on a ground grid. A compass ring in the sky. No map, no account,
no network.

> Status: **v0.3.0, Phase 1 preview.** The core loop works on a real device. The home-screen widget and the display
> settings (labels, ring spacing, zoom, units) are not built yet. See [Status](#status).

## How it works

- **Point and see.** The scene is a 3D view from your feet. Each beacon is a beam rising from its real position, and
  the compass ring and beams track the phone's true-north heading.
- **Swipe up** for a top-down radar view; swipe down to return. The beams grow and shrink with the camera.
- **Double tap** empty space to drop a beacon at your current location. Hold the second tap, slide to a color,
  release. Beacons are auto-named with the NATO alphabet (Alpha, Bravo, Charlie...).
- **Double tap a beam** to rename it, recolor it, or take it down (with undo).
- **Lock-on.** Line a beacon up with the center and you feel a click.
- Beacons are stored on the device as real latitude/longitude, so they survive restarts.

## Principles

| | |
|---|---|
| **Offline** | Works with zero signal. GPS and the compass need no network. |
| **Private** | No `INTERNET` permission in the manifest. No analytics, no crash SDKs, no accounts. Data never leaves the phone. Backups are disabled. |
| **Battery-conscious** | No foreground service, no background location. The compass and GPS run only while the scene is on screen (registered in `onResume`, released in `onPause`). |
| **Honest** | See [Verification](#verification). Nothing is claimed that was not observed on a device. |

## Install

Download `beacons-0.3.0.apk` from the [Releases](../../releases) page and sideload it (you may need to allow
installs from your browser or file manager). Android 10 (API 29) or newer. On first launch, grant **precise
location** while using the app.

The APK is signed with a project key. Check the SHA-256 listed in the release notes against your download.

## Build from source

Requirements: JDK 17, Android SDK (compileSdk 34).

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # signed only if ~/.beacons-signing/signing.properties exists
```

Location uses Google Play Services' fused provider (`play-services-location`), which is on-device and works with
no data connection.

## Status

Built in steps, each one committed:

| Step | Scope | State |
|---|---|---|
| 0 | Project, constants, bundled fonts | done |
| 1 | Real heading (rotation vector, remapped, true north via declination) + one beacon | done, with caveats below |
| 2 | Full scene: rings, compass ring, beams, edge glows, labels, you-chevron, lock-on, swipe tilt | done |
| 3 | Drop / color ring / edit / undo / persistence | done |
| 4 | Home-screen widget: one-tap drop | **not built** |
| 5 | Display controls (labels, rings, linear/log scale, pinch zoom, units) | **not built** |

Debug readouts (heading, accuracy, tilt, fix) are behind the dot at the top-right of the scene.

## Verification

Observed on a OnePlus 12R (Android 16):

- Heading stable within about 1 degree from upright to roughly 50 degrees of tilt, at 50 Hz, sensor accuracy "high".
- Rendering matches the reference prototype; swipe tilt and turn follow the compass.
- Drop, edit, undo and persistence flows work (hand-tested).
- Release APK is signature-verified and requests only location and vibration.

**Known issues / not yet verified**

- **Flat-phone heading.** Tilting from upright all the way to flat showed about 20 degrees of drift in one test. The
  cause (hand rotation vs. magnetic interference vs. fusion) was not isolated. Held upright, the compass is solid.
- **Absolute heading accuracy** against a known bearing has not been measured. Only stability has.
- **Battery** (1 h screen-on plus 8 h overnight idle, `dumpsys batterystats`) has **not** been measured yet.
- Lock-on and settle haptics are implemented (composition primitives, `EFFECT_CLICK` fallback); feel is
  unconfirmed beyond a quick hand test.
- Tested on one device only.

## Project layout

```
app/src/main/java/com/example/beacons/
  Constants.kt       tuned numbers from the prototype, same names
  Scene.kt           projection, draw order, spring, lock-on, colour ring
  SceneView.kt       custom View, frame loop (visible only), gestures
  HeadingSensor.kt   rotation vector -> true heading
  LocationSource.kt  fused location, visible only
  BeaconStore.kt     local persistence
  Haptics.kt         click / tick / thud
reference/beacons_mockup.html   the visual and interaction spec (JS canvas prototype)
```

`CLAUDE.md` holds the project rules (battery, privacy, offline, honest verification) and the build order.

## Credits

- Fonts: [Chakra Petch](https://fonts.google.com/specimen/Chakra+Petch) and
  [Share Tech Mono](https://fonts.google.com/specimen/Share+Tech+Mono), both under the SIL Open Font License
  (see `third_party/fonts/`).
- The application ID `com.example.beacons` and the name "Beacons" are placeholders.

## License

No license has been chosen yet. All rights reserved until one is added.
