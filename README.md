# RawGeoCam

An Android camera app for geometric work. Each shot saves:

- `IMG_<time>.dng`: the **RAW_SENSOR Bayer mosaic** from a single frame: no demosaic, denoise, sharpening, tone mapping, HDR merge or distortion correction. (Camera2 calls RAW_SENSOR *minimally processed*: some phones still apply sensor-level defect / hot-pixel correction, which does not move pixels.)
- `IMG_<time>.json`: the camera **intrinsics and distortion**, **latitude / longitude / altitude**, the camera's **heading, pitch and roll**, the full rotation matrix, and everything needed to interpret them.

Files go to **Download/RawGeoCam/** on the phone.

---

## Build and install

1. Open the `RawGeoCam` folder in Android Studio (Ladybug or newer). It syncs with Gradle 8.9, AGP 8.7.3 and Kotlin 2.0.21; there are no third-party libraries.
2. Turn on **Developer options → USB debugging** on the phone, plug it in, press **Run**.
   Or **Build → Build App Bundle(s)/APK(s) → Build APK(s)** and copy `app/build/outputs/apk/debug/app-debug.apk` to the phone.
   From a terminal with the Android SDK installed: `./gradlew assembleDebug` (set `sdk.dir` in `local.properties` or `ANDROID_HOME`).
3. Allow **Camera** and **Precise location** on first launch.

Requires Android 10 (API 29) or newer.

**Without Android Studio:** GitHub Actions (`.github/workflows/build-apk.yml`) builds the APK and runs the unit tests on every push. Download the APK from the run's **Artifacts** section in the **Actions** tab.

## Using it

The top panel updates live:

| Line | Meaning |
|---|---|
| `HDG 123.4° true  PITCH +2.1°  ROLL -0.4°` | Where the camera is pointing, for the upright image. `mag` instead of `true` means no location fix yet, so no declination correction. |
| `Compass: HIGH` | Magnetometer calibration. If it says LOW/UNRELIABLE, wave the phone in a figure-8 for a few seconds. |
| `GPS lat, lon ±4 m gps 1s old` | Current fix, its accuracy and age. Wait for a `gps` fix of a few metres before shooting. |
| `Lens: Ultrawide 1.95mm (phys 3 of cam 0)` | Selected lens. Hidden physical lenses (e.g. the Pixel ultrawide) show which logical camera they belong to. |
| `RAW DNG 4080x3072` | Whether this lens delivers RAW. `NO RAW -> JPEG` means only processed output (see Limitations). |
| `K fx 3245.1 fy 3245.3 cx … (per frame)` | The intrinsics the camera reports for the **current** preview frame, live. Watch `fx` while moving the focus slider: that is focus breathing. `(logical!)` means the phone did not report values for the physical lens itself. |
| `Exp 1/250  ISO 100  focus 0.000 D` | What the sensor is actually doing (also in manual mode, so you can see the camera honoured your settings). `lens moving` = focus still travelling. |

Buttons:
- **Lens** cycles through every back lens, including lenses hidden inside a logical multi-camera (see *Lenses and the Pixel ultrawide*).
- **Auto / Manual** switches exposure mode (see *Manual mode*).
- **Capture**. With a fixed focus it waits (up to 1.5 s) until the lens has stopped moving.
- **Focus ∞ / Focus AF** (auto mode): focus locked at infinity (default) or continuous autofocus. In manual mode focus comes from the slider.

### Manual mode

Tap **Auto** to switch to **Manual**. Three sliders appear:

| Slider | Range | Notes |
|---|---|---|
| Shutter | the lens's exposure range, in third stops | Auto exposure is switched off. Frame duration is set to at least the exposure. |
| ISO | the lens's sensitivity range | |
| Focus | ∞ (left) to the closest focus distance (right), in diopters (1/m) | Labelled `(uncalibrated)` when the phone's focus scale is only approximate: then the number is a lens position, not a true distance. |

**Everything is saved per lens and restored after the app is closed**, including the mode itself, the selected lens, and the exact focus value (stored in diopters, not as a slider position). The first time you enter manual mode on a lens, it starts from what auto exposure was using.

Why the focus slider matters for geometry: a phone lens has one fixed focal length, but moving the focus changes the effective focal length in pixels (`fx`, `fy`) by up to a few percent (focus breathing). Locking focus at one value keeps `K` constant between shots and sessions; it is what "fix the focal length" means on a phone. The JSON records the focus value and the per-frame `K` for every shot, so check `summary.focus_distance_diopters` is identical across a dataset.

### Lenses and the Pixel ultrawide

Many phones (all recent Pixels, including the 7a) list only **one** back camera to apps: a *logical* camera that contains the main and ultrawide sensors. Zooming below 1× in other apps switches to the ultrawide, but Android forbids a logical camera's RAW stream from switching to a sensor of a different size, so a RAW app can never reach the ultrawide by zooming.

RawGeoCam therefore lists each hidden physical lens separately and opens it as a **physical stream** of the logical camera. The DNG and the intrinsics then use that lens's own characteristics and its own per-frame metadata (`camera.metadata_source: physical_camera_result`). If the phone will not stream RAW from a lens, the app says so. The Pixel 7a ultrawide is fixed-focus, so the focus slider is disabled for it.

What the app switches off for every shot, where the camera allows it: optical image stabilisation (it shifts the principal point), video stabilisation, distortion correction, noise reduction, edge enhancement, hot-pixel correction and chromatic-aberration correction. Distortion correction, noise reduction, edge enhancement and aberration correction are defined for processed outputs only; Camera2 does not guarantee that a phone does no defect correction on RAW.

---

## The JSON sidecar

`summary` at the top has the headline numbers, including **`quality_ok`** and **`quality_issues`**: every reason not to trust a shot blindly. Filter your dataset on it.

| Issue | Meaning |
|---|---|
| `no_location`, `location_fix_older_than_30s`, `location_accuracy_worse_than_20m`, `location_not_from_gps` | Position missing, stale (e.g. an old last-known fix), poor, or from Wi-Fi/cell (Android's "approximate location" is deliberately blurred by kilometres). |
| `heading_is_magnetic_not_true`, `compass_calibration_low`, `heading_accuracy_worse_than_10deg`, `near_vertical_heading_unstable` | Heading problems. |
| `phone_rotating_during_capture`, `pose_not_time_aligned` | Motion blur risk; the pose sample could not be matched to the exposure time. |
| `intrinsics_estimated_not_calibrated`, `distortion_not_published`, `intrinsics_check_warning` | No factory calibration, or it failed a sanity check (see `intrinsics.checks.warnings`). |
| `ois_on_principal_point_may_shift`, `lens_moving`, `jpeg_not_raw` | |

The details:

| Key | Contents |
|---|---|
| `intrinsics.android_fx_fy_cx_cy_s` | Camera2 `LENS_INTRINSIC_CALIBRATION` exactly as reported (Android pixel convention, see below). |
| `intrinsics.source` | `capture_result` (per-frame value from the camera), `camera_characteristics` (static factory value), or `estimated_from_focal_length_and_sensor_size` when the phone publishes neither. |
| `intrinsics.K_opencv_active_area` | 3×3 K in OpenCV convention for the DNG's active area. **This is the one to use** with rawpy (see below). |
| `intrinsics.K_opencv_raw_buffer` / `…_dng_default_crop` | The same K shifted for the full raw buffer, or for renderers that apply the DNG DefaultCrop (8 px margin). |
| `intrinsics.distortion_opencv_k1_k2_p1_p2_k3` | Brown–Conrady coefficients in OpenCV order, ready for `cv2.undistort`. `null` if the phone does not publish them. |
| `intrinsics.checks` | Sanity checks: `fx` against focal length × pixel pitch (catches intrinsics that belong to a binned/full-resolution grid or another lens), `fy/fx`, principal-point offset from the centre, and `warnings`. |
| `intrinsics.focus_mode`, `focus_distance_diopters`, `optical_stabilization` | What the lens was actually doing for this frame. |
| `camera` | `id` (opened camera), `physical_id` (hidden lens, or null), `lens`, `metadata_source`, `preview_stream`, `active_physical_id`. |
| `location` | `latitude`, `longitude`, `altitude_wgs84_ellipsoid_m`, `altitude_msl_m` (Android 14+), `horizontal_accuracy_m`, `vertical_accuracy_m`, `provider`, `fix_age_s`. |
| `orientation.heading_true_deg` / `heading_magnetic_deg` | Bearing of the optical axis. |
| `orientation.sensor_frame` | heading / pitch / roll, `R_world_from_camera` and quaternion for the DNG pixel grid as stored. |
| `orientation.display_frame` | The same for the upright image (after the DNG orientation tag); roll is near 0 for a level shot. |
| `orientation.device` | Phone-body rotation matrix and Android's own `getOrientation()` azimuth/pitch/roll, for reference. |
| `orientation.sample` | Which sensor sample was used and how far it was from mid-exposure (`sample_offset_ms`), the phone's own heading-accuracy estimate, compass calibration status, and angular speed at the time (motion blur check). |
| `image` | Sensor geometry (pixel array, pre-correction active array, physical size), sensor orientation, display rotation, EXIF orientation. |
| `exposure` | `mode` (auto/manual), requested vs actual exposure time and ISO, frame duration, rolling-shutter skew, sensor timestamp. |

## Conventions

**World frame**: ENU, x = east, y = north, z = up. North is true north (magnetic declination from the WGS84 field model at the GPS position). Without a location fix it falls back to magnetic north and says so in `world_frame`.

**Camera frame**: x = increasing pixel column, y = increasing pixel row, z = optical axis into the scene (OpenCV / pinhole convention). `R_world_from_camera` has the camera's x, y, z axes as its columns, so a pixel ray `d_cam = K⁻¹ [u, v, 1]ᵀ` points along `R · d_cam` in ENU.

**Angles**:
- **heading**: bearing of the optical axis, degrees clockwise from north, 0–360.
- **pitch**: elevation of the optical axis, degrees, positive looking up.
- **roll**: rotation about the optical axis from level, degrees, positive when the image's +x axis tips toward the ground (the scene then appears rotated counter-clockwise).

These three determine `R_world_from_camera` uniquely (`R_from_angles` in `tools/rawgeocam.py`); heading and roll become unstable within about 5° of straight up or down (`near_vertical`).

**Pose timing**: the orientation sample is the one nearest the middle of the exposure (sensor timestamp + exposure/2 + rolling-shutter skew/2), when the camera clock is comparable with the sensor clock (`timestamp_source: realtime`, true on most phones).

## Pixel grids

Camera2 defines the intrinsics in the **pre-correction active array** with **pixel centres at (x + 0.5, y + 0.5)**. OpenCV puts pixel centres on integers, so every `K_opencv_*` matrix has 0.5 subtracted from cx and cy.

The DNG stores the raw buffer, which is either exactly the active array or the full pixel array with the active array at an offset (`image.dng_active_area_origin_in_buffer`, also written as the DNG `ActiveArea` tag). rawpy/LibRaw crop to `ActiveArea`, so:

| What you load | Intrinsics |
|---|---|
| `raw.raw_image_visible`, or `raw.postprocess(user_flip=0)` | `K_opencv_active_area` |
| `raw.raw_image` (full buffer) | `K_opencv_raw_buffer` |
| A renderer that also applies the DNG DefaultCrop | `K_opencv_dng_default_crop` |
| Any of the above rotated upright | rotate with `rotate_intrinsics()` |

**Do not judge geometry from Lightroom / Adobe Camera Raw renders.** The DNG carries the lens-shading map and a `WarpRectilinear` distortion opcode as metadata; Adobe software applies them, which undistorts the image so it no longer matches the distortion coefficients. rawpy/LibRaw leave the geometry as captured.

## Reading captures in Python

```bash
pip install numpy rawpy opencv-python
python tools/rawgeocam.py --selftest            # checks the rotation/projection maths
python tools/rawgeocam.py IMG_20261112_154800_123.json
```

```python
import sys; sys.path.insert(0, "tools")
import cv2, numpy as np
from rawgeocam import Capture

cap = Capture("IMG_20261112_154800_123.json")
rgb, K, dist = cap.image(frame="display", output_bps=16)   # upright, intrinsics to match
pose = cap.pose(frame="display")
R = pose["R_world_from_camera"]                             # ENU <- camera
lat, lon = pose["latitude"], pose["longitude"]

undistorted = cv2.undistort(rgb, K, dist)                   # if you want a pinhole image

# Project an ENU point (metres, relative to the phone) into the image:
p_cam = R.T @ np.array([10.0, 50.0, 2.0])
u, v = (K @ (p_cam / p_cam[2]))[:2]                         # add distortion with cv2.projectPoints

bayer, K_raw, dist = cap.raw_mosaic()                       # untouched CFA data, sensor frame
```

`ecef_from_enu_rotation(lat, lon)` converts the rotation into Earth-centred coordinates if you need to place several captures in one frame.

`Capture.quality_ok` / `quality_issues` mirror the JSON flags. If the phone published no distortion, the reader warns instead of silently assuming a perfect pinhole.

## Verifying the intrinsics

The factory calibration is the phone maker's, not yours. To check it for one lens and one focus setting:

1. Print a checkerboard (e.g. 10×7 squares = 9×6 inner corners) and tape it to something flat.
2. In **Manual** mode, pick the lens and a focus value (∞ is fine beyond ~2 m; for a board at 0.5 m use a matching focus), and shoot 15–30 captures with the board in **all parts of the frame**, especially the corners, some of them tilted. Hold still (`quality_issues` should not say `phone_rotating_during_capture`).
3. Run:

```bash
python tools/verify_intrinsics.py captures/IMG_*.json --board 9x6 --square 0.025
```

It calibrates the lens from your shots (dropping mis-detected views), then reports how well the **phone's** K and distortion explain the same corners and the largest disagreement between the two models across the frame, ending with `PHONE INTRINSICS LOOK GOOD` or `… DO NOT MATCH`. A full report goes to `verify_intrinsics_report.json`. If they do not match, use your calibration for that lens and focus value. On synthetic data the tool recovers the true focal length within 0.05% and flags a 2% focal error.

---

## Accuracy and limitations

- **Heading is the weak link.** Phone compasses are typically good to a few degrees when calibrated and much worse near cars, metal poles, rebar or electrical cabinets, which is exactly where street cameras are mounted. Check `orientation.sample.magnetometer_calibration` and `heading_accuracy_deg_reported`, recalibrate (figure-8) often, and step away from metal when you can. Pitch and roll are referenced to gravity and are much better (typically around a degree or better when the phone is held still).
- **GPS** is typically 3–10 m in the open and worse between tall buildings. `horizontal_accuracy_m` is the phone's 68% estimate.
- **Factory intrinsics vary by phone.** Many phones publish `LENS_INTRINSIC_CALIBRATION` and `LENS_DISTORTION`, some publish only one, some publish neither (then the JSON says `estimated_from_focal_length_and_sensor_size` and distortion is `null`). Even published values are a factory calibration, so if precision matters, verify against a checkerboard calibration (`cv2.calibrateCamera`) on DNGs from this app with the same focus setting.
- **Focus changes the intrinsics** slightly (focus breathing). Keep focus locked at ∞ for scenes beyond a few metres, which is the default.
- **OIS**: if the phone does not let apps turn optical stabilisation off, the JSON says `optical_stabilization: on` and the principal point can move by a few pixels between shots.
- **Multi-camera phones**: the `Lens` button offers every back camera the phone lists plus the hidden physical lenses of a logical camera that support RAW. Physical streams are optional for manufacturers: if a phone refuses, the app reports it.
- **Ultrawide lenses** have strong distortion. Always use the distortion coefficients (or your own calibration); never assume a pinhole for them.
- **Manual settings on physical lenses**: the app sets them on the physical lens where the phone allows it. Check `exposure.exposure_time_ns` / `iso` against `requested_*` in the JSON to confirm they were honoured.
- **No RAW**: on a camera without RAW support the app saves a JPEG in the sensor orientation with as much processing off as the camera allows. That is still processed, and its K is an approximation (`K_opencv_image`).
- **Altitude** from the phone is relative to the WGS84 ellipsoid; Android 14+ also gives mean-sea-level altitude.
