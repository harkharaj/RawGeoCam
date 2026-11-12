# RawGeoCam

An Android camera app for geometric work. Each shot saves:

- `IMG_<time>.dng`: the **unprocessed RAW_SENSOR Bayer mosaic** (no demosaic, denoise, sharpening, tone mapping, HDR merge or distortion correction applied to the pixels).
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

**Without Android Studio:** push this folder to a GitHub repository. `.github/workflows/build-apk.yml` builds the APK on every push; download it from the run's **Artifacts** section in the repository's **Actions** tab.

## Using it

The top panel updates live:

| Line | Meaning |
|---|---|
| `HDG 123.4° true  PITCH +2.1°  ROLL -0.4°` | Where the camera is pointing, for the upright image. `mag` instead of `true` means no location fix yet, so no declination correction. |
| `Compass: HIGH` | Magnetometer calibration. If it says LOW/UNRELIABLE, wave the phone in a figure-8 for a few seconds. |
| `GPS lat, lon ±4 m gps 1s old` | Current fix, its accuracy and age. Wait for a `gps` fix of a few metres before shooting. |
| `Cam 0  RAW DNG 4080x3072` | Camera ID and whether it delivers RAW. `NO RAW -> JPEG` means this camera only offers processed output (see Limitations). |
| `Intrinsics: …` | Whether the phone publishes a factory calibration (after the first shot, shows where the values came from). |

Buttons: **Cam** cycles through the back cameras the phone exposes to apps; **Capture**; **Focus ∞ / Focus AF** toggles between focus locked at infinity (default: keeps the focal length fixed for outdoor scenes) and continuous autofocus.

What the app switches off for every shot, where the camera allows it: optical image stabilisation (it shifts the principal point), video stabilisation, distortion correction, noise reduction, edge enhancement, hot-pixel correction and chromatic-aberration correction. The last four only affect processed outputs anyway; RAW never gets them.

---

## The JSON sidecar

`summary` at the top has the headline numbers. The details:

| Key | Contents |
|---|---|
| `intrinsics.android_fx_fy_cx_cy_s` | Camera2 `LENS_INTRINSIC_CALIBRATION` exactly as reported (Android pixel convention, see below). |
| `intrinsics.source` | `capture_result` (per-frame value from the camera), `camera_characteristics` (static factory value), or `estimated_from_focal_length_and_sensor_size` when the phone publishes neither. |
| `intrinsics.K_opencv_active_area` | 3×3 K in OpenCV convention for the DNG's active area. **This is the one to use** with rawpy (see below). |
| `intrinsics.K_opencv_raw_buffer` / `…_dng_default_crop` | The same K shifted for the full raw buffer, or for renderers that apply the DNG DefaultCrop (8 px margin). |
| `intrinsics.distortion_opencv_k1_k2_p1_p2_k3` | Brown–Conrady coefficients in OpenCV order, ready for `cv2.undistort`. `null` if the phone does not publish them. |
| `intrinsics.focus_mode`, `focus_distance_diopters`, `optical_stabilization` | What the lens was actually doing for this frame. |
| `location` | `latitude`, `longitude`, `altitude_wgs84_ellipsoid_m`, `altitude_msl_m` (Android 14+), `horizontal_accuracy_m`, `vertical_accuracy_m`, `provider`, `fix_age_s`. |
| `orientation.heading_true_deg` / `heading_magnetic_deg` | Bearing of the optical axis. |
| `orientation.sensor_frame` | heading / pitch / roll, `R_world_from_camera` and quaternion for the DNG pixel grid as stored. |
| `orientation.display_frame` | The same for the upright image (after the DNG orientation tag); roll is near 0 for a level shot. |
| `orientation.device` | Phone-body rotation matrix and Android's own `getOrientation()` azimuth/pitch/roll, for reference. |
| `orientation.sample` | Which sensor sample was used and how far it was from mid-exposure (`sample_offset_ms`), the phone's own heading-accuracy estimate, compass calibration status, and angular speed at the time (motion blur check). |
| `image` | Sensor geometry (pixel array, pre-correction active array, physical size), sensor orientation, display rotation, EXIF orientation. |
| `exposure` | Exposure time, ISO, frame duration, rolling-shutter skew, sensor timestamp. |

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

---

## Accuracy and limitations

- **Heading is the weak link.** Phone compasses are typically good to a few degrees when calibrated and much worse near cars, metal poles, rebar or electrical cabinets, which is exactly where street cameras are mounted. Check `orientation.sample.magnetometer_calibration` and `heading_accuracy_deg_reported`, recalibrate (figure-8) often, and step away from metal when you can. Pitch and roll are referenced to gravity and are much better (typically around a degree or better when the phone is held still).
- **GPS** is typically 3–10 m in the open and worse between tall buildings. `horizontal_accuracy_m` is the phone's 68% estimate.
- **Factory intrinsics vary by phone.** Many phones publish `LENS_INTRINSIC_CALIBRATION` and `LENS_DISTORTION`, some publish only one, some publish neither (then the JSON says `estimated_from_focal_length_and_sensor_size` and distortion is `null`). Even published values are a factory calibration, so if precision matters, verify against a checkerboard calibration (`cv2.calibrateCamera`) on DNGs from this app with the same focus setting.
- **Focus changes the intrinsics** slightly (focus breathing). Keep focus locked at ∞ for scenes beyond a few metres, which is the default.
- **OIS**: if the phone does not let apps turn optical stabilisation off, the JSON says `optical_stabilization: on` and the principal point can move by a few pixels between shots.
- **Multi-camera phones**: the `Cam` button only cycles through cameras the manufacturer exposes to third-party apps. Some logical cameras switch physical lenses internally; `camera.active_physical_id` records which one took the shot.
- **No RAW**: on a camera without RAW support the app saves a JPEG in the sensor orientation with as much processing off as the camera allows. That is still processed, and its K is an approximation (`K_opencv_image`).
- **Altitude** from the phone is relative to the WGS84 ellipsoid; Android 14+ also gives mean-sea-level altitude.
