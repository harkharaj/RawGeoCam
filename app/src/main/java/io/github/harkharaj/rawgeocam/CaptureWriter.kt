package io.github.harkharaj.rawgeocam

import android.content.ContentValues
import android.content.Context
import android.graphics.Rect
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Writes the image (DNG straight from the RAW_SENSOR buffer, or the JPEG fallback) and a JSON sidecar
 * to Download/RawGeoCam/ via MediaStore. See README.md for every field and convention.
 */
object CaptureWriter {

    private const val FOLDER = "RawGeoCam"

    /** DngCreator writes DefaultCropOrigin = (8, 8) inside the ActiveArea (Adobe's demosaic margin). */
    private const val DNG_DEFAULT_CROP_MARGIN = 8

    class Input(
        val image: Image,
        val result: TotalCaptureResult,
        val chars: CameraCharacteristics,
        val cameraId: String,
        val isRaw: Boolean,
        val pose: PoseTracker.Snapshot,
        val focusMode: String,
        val captureWallMillis: Long,
    )

    class Output(val baseName: String, val intrinsicsSource: String)

    private class Geometry(
        val sensorOrientation: Int,
        val rDeviceFromCam: DoubleArray,
        val rWorldMagFromDevice: DoubleArray?,
        val rWorldFromDevice: DoubleArray?,
        val trueNorth: Boolean,
        val rWorldFromCamSensor: DoubleArray?,
        val rWorldFromCamDisplay: DoubleArray?,
        val sensorAngles: PoseMath.Angles?,
        val displayAngles: PoseMath.Angles?,
        val magneticHeadingDeg: Double?,
        val displayRotationCw: Int,
        val exifOrientation: Int,
    )

    fun write(context: Context, input: Input): Output {
        val base = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(input.captureWallMillis))
        val geo = geometry(input)
        val imageName = base + if (input.isRaw) ".dng" else ".jpg"

        if (input.isRaw) {
            save(context, imageName, "image/x-adobe-dng") { out ->
                val dng = DngCreator(input.chars, input.result)
                try {
                    dng.setOrientation(geo.exifOrientation)
                    input.pose.location?.let { loc ->
                        try {
                            dng.setLocation(loc)
                        } catch (_: IllegalArgumentException) {
                        }
                    }
                    dng.setDescription("RawGeoCam camera ${input.cameraId}")
                    dng.writeImage(out, input.image)
                } finally {
                    dng.close()
                }
            }
        } else {
            save(context, imageName, "image/jpeg") { out ->
                val buf = input.image.planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                out.write(bytes)
            }
        }

        val (json, kSource) = buildJson(input, imageName, geo)
        save(context, "$base.json", "application/json") { out ->
            out.write(json.toString(2).toByteArray(Charsets.UTF_8))
        }
        return Output(base, kSource)
    }

    // ---------------- geometry ----------------

    private fun geometry(input: Input): Geometry {
        val so = input.chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val rDc = PoseMath.deviceFromBackCamera(so)
        val rMag = input.pose.rWorldMagFromDevice
        if (rMag == null) {
            // No orientation data: assume an upright portrait shot for the DNG orientation tag.
            return Geometry(so, rDc, null, null, false, null, null, null, null, null, so,
                PoseMath.exifOrientationForRotation(so))
        }
        val decl = input.pose.declinationDeg
        val rWd = if (decl != null) PoseMath.mul(PoseMath.trueFromMagnetic(decl), rMag) else rMag
        val rWc = PoseMath.mul(rWd, rDc)
        val a = PoseMath.anglesOf(rWc)
        val rot = PoseMath.displayRotationForRoll(a.rollDeg)
        val rWcDisplay = PoseMath.mul(rWc, PoseMath.sensorCamFromDisplayCam(rot))
        return Geometry(
            sensorOrientation = so,
            rDeviceFromCam = rDc,
            rWorldMagFromDevice = rMag,
            rWorldFromDevice = rWd,
            trueNorth = decl != null,
            rWorldFromCamSensor = rWc,
            rWorldFromCamDisplay = rWcDisplay,
            sensorAngles = a,
            displayAngles = PoseMath.anglesOf(rWcDisplay),
            magneticHeadingDeg = PoseMath.anglesOf(PoseMath.mul(rMag, rDc)).headingDeg,
            displayRotationCw = rot,
            exifOrientation = PoseMath.exifOrientationForRotation(rot),
        )
    }

    // ---------------- JSON ----------------

    private fun buildJson(input: Input, imageName: String, geo: Geometry): Pair<JSONObject, String> {
        val c = input.chars
        val r = input.result
        val pose = input.pose

        val pre: Rect = c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)!!
        val act: Rect? = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val pix = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)!!
        val phys = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)!!
        val focal = r.get(CaptureResult.LENS_FOCAL_LENGTH)
            ?: c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
        val w = input.image.width
        val h = input.image.height

        // ---- intrinsics: Android convention, pre-correction active array, pixel centres at +0.5 ----
        fun valid(k: FloatArray?) = k != null && k.size >= 5 && k[0] > 0f && k[1] > 0f
        val kRes = r.get(CaptureResult.LENS_INTRINSIC_CALIBRATION)
        val kChr = c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        val k: DoubleArray
        val kSource: String
        when {
            valid(kRes) -> {
                k = DoubleArray(5) { kRes!![it].toDouble() }; kSource = "capture_result"
            }
            valid(kChr) -> {
                k = DoubleArray(5) { kChr!![it].toDouble() }; kSource = "camera_characteristics"
            }
            else -> {
                k = PoseMath.estimateIntrinsics(
                    focal.toDouble(), phys.width.toDouble(), phys.height.toDouble(),
                    pix.width, pix.height, pre.width(), pre.height(),
                )
                kSource = "estimated_from_focal_length_and_sensor_size"
            }
        }
        val (fx, fy, cx, cy) = k.toList()
        val skew = k[4]

        val dRes = r.get(CaptureResult.LENS_DISTORTION)
        val dChr = c.get(CameraCharacteristics.LENS_DISTORTION)
        val dist: FloatArray?
        val dSource: String
        when {
            dRes != null && dRes.size >= 5 -> { dist = dRes; dSource = "capture_result" }
            dChr != null && dChr.size >= 5 -> { dist = dChr; dSource = "camera_characteristics" }
            else -> { dist = null; dSource = "not_published" }
        }

        val intr = JSONObject()
        intr.put("source", kSource)
        intr.put("model", "pinhole + Brown-Conrady (OpenCV-compatible)")
        intr.put("android_fx_fy_cx_cy_s", arr(fx, fy, cx, cy, skew))
        intr.put("note_android", "Android convention: pixel coordinates in the pre-correction active array, pixel centres at (x+0.5, y+0.5)")
        if (input.isRaw) {
            val offX: Int
            val offY: Int
            if (w == pre.width() && h == pre.height()) { offX = 0; offY = 0 } else { offX = pre.left; offY = pre.top }
            intr.put("K_opencv_active_area", kOpenCv(fx, fy, cx, cy, skew, 0.0, 0.0))
            intr.put("K_opencv_active_area_size", JSONArray().put(pre.width()).put(pre.height()))
            intr.put("K_opencv_raw_buffer", kOpenCv(fx, fy, cx, cy, skew, offX.toDouble(), offY.toDouble()))
            intr.put("K_opencv_raw_buffer_size", JSONArray().put(w).put(h))
            intr.put("K_opencv_dng_default_crop", kOpenCv(fx, fy, cx, cy, skew,
                -DNG_DEFAULT_CROP_MARGIN.toDouble(), -DNG_DEFAULT_CROP_MARGIN.toDouble()))
            intr.put("K_opencv_dng_default_crop_size", JSONArray()
                .put(pre.width() - 2 * DNG_DEFAULT_CROP_MARGIN).put(pre.height() - 2 * DNG_DEFAULT_CROP_MARGIN))
            intr.put("note_opencv", "OpenCV convention (pixel centres on integers), in the SENSOR orientation (DNG pixels before the orientation tag). " +
                "Use K_opencv_active_area with rawpy raw_image_visible / postprocess(user_flip=0); " +
                "K_opencv_raw_buffer with the full raw_image; K_opencv_dng_default_crop with renderers that apply DefaultCrop.")
        } else {
            val sx = w.toDouble() / pre.width()
            val sy = h.toDouble() / pre.height()
            intr.put("K_opencv_image", JSONArray()
                .put(arr(fx * sx, skew * sx, cx * sx - 0.5))
                .put(arr(0.0, fy * sy, cy * sy - 0.5))
                .put(arr(0.0, 0.0, 1.0)))
            intr.put("K_opencv_image_size", JSONArray().put(w).put(h))
            intr.put("note_opencv", "APPROXIMATE: JPEG fallback (camera has no RAW). Assumes the JPEG covers the whole pre-correction " +
                "active array scaled to its size, in sensor orientation (JPEG_ORIENTATION=0).")
        }
        intr.put("distortion_source", dSource)
        if (dist != null) {
            intr.put("distortion_android_kappa1_5", arrF(dist))
            // Android: kappa_4/5 are the tangential terms in OpenCV's p1/p2 positions.
            intr.put("distortion_opencv_k1_k2_p1_p2_k3",
                arr(dist[0].toDouble(), dist[1].toDouble(), dist[3].toDouble(), dist[4].toDouble(), dist[2].toDouble()))
        } else {
            intr.put("distortion_android_kappa1_5", JSONObject.NULL)
            intr.put("distortion_opencv_k1_k2_p1_p2_k3", JSONObject.NULL)
        }
        intr.put("focal_length_mm", focal.toDouble())
        putF(intr, "aperture_f_number", r.get(CaptureResult.LENS_APERTURE))
        putF(intr, "focus_distance_diopters", r.get(CaptureResult.LENS_FOCUS_DISTANCE))
        intr.put("focus_mode", input.focusMode)
        intr.put("focus_distance_calibration", when (c.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)) {
            CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED -> "calibrated"
            CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE -> "approximate"
            else -> "uncalibrated"
        })
        intr.put("optical_stabilization", when (r.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)) {
            CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF -> "off"
            CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON -> "on"
            else -> "unknown"
        })
        intr.put("distortion_correction_mode", when (r.get(CaptureResult.DISTORTION_CORRECTION_MODE)) {
            CameraMetadata.DISTORTION_CORRECTION_MODE_OFF -> "off"
            null -> "unsupported"
            else -> "on (affects processed outputs only, never RAW)"
        })
        val poseRot = r.get(CaptureResult.LENS_POSE_ROTATION) ?: c.get(CameraCharacteristics.LENS_POSE_ROTATION)
        val poseTr = r.get(CaptureResult.LENS_POSE_TRANSLATION) ?: c.get(CameraCharacteristics.LENS_POSE_TRANSLATION)
        intr.put("lens_pose_rotation_xyzw", poseRot?.let { arrF(it) } ?: JSONObject.NULL)
        intr.put("lens_pose_translation_m", poseTr?.let { arrF(it) } ?: JSONObject.NULL)
        intr.put("lens_pose_reference", when (c.get(CameraCharacteristics.LENS_POSE_REFERENCE)) {
            CameraMetadata.LENS_POSE_REFERENCE_PRIMARY_CAMERA -> "primary_camera"
            CameraMetadata.LENS_POSE_REFERENCE_GYROSCOPE -> "gyroscope"
            CameraMetadata.LENS_POSE_REFERENCE_UNDEFINED -> "undefined"
            null -> "not_published"
            else -> "other"
        })

        // ---- image / sensor geometry ----
        val img = JSONObject()
        img.put("file", imageName)
        img.put("format", if (input.isRaw) "DNG from RAW_SENSOR: unprocessed Bayer mosaic (no demosaic, denoise, sharpening, tone mapping or distortion correction applied)"
            else "JPEG fallback (this camera does not offer RAW); noise reduction / edge enhancement / aberration correction off where allowed")
        img.put("width", w)
        img.put("height", h)
        img.put("sensor_orientation_deg", geo.sensorOrientation)
        img.put("pixel_array_size", JSONArray().put(pix.width).put(pix.height))
        img.put("pre_correction_active_array", rectJson(pre))
        act?.let { img.put("active_array", rectJson(it)) }
        img.put("physical_size_mm", arr(phys.width.toDouble(), phys.height.toDouble()))
        if (input.isRaw) {
            img.put("dng_active_area_origin_in_buffer",
                if (w == pre.width() && h == pre.height()) JSONArray().put(0).put(0) else JSONArray().put(pre.left).put(pre.top))
            img.put("dng_default_crop_margin_px", DNG_DEFAULT_CROP_MARGIN)
            img.put("dng_note", "DngCreator also stores the lens-shading GainMap and a WarpRectilinear distortion opcode as METADATA. " +
                "Adobe/Lightroom apply them when rendering (undistorting the image); rawpy/LibRaw do not.")
        }
        img.put("display_rotation_cw_deg", geo.displayRotationCw)
        img.put("exif_orientation", geo.exifOrientation)

        // ---- exposure ----
        val exp = JSONObject()
        putL(exp, "exposure_time_ns", r.get(CaptureResult.SENSOR_EXPOSURE_TIME))
        putI(exp, "iso", r.get(CaptureResult.SENSOR_SENSITIVITY))
        putL(exp, "frame_duration_ns", r.get(CaptureResult.SENSOR_FRAME_DURATION))
        putL(exp, "rolling_shutter_skew_ns", r.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW))
        putL(exp, "sensor_timestamp_ns", r.get(CaptureResult.SENSOR_TIMESTAMP))

        // ---- location ----
        val loc = pose.location
        val locJson: Any = if (loc == null) JSONObject.NULL else JSONObject().apply {
            put("latitude", loc.latitude)
            put("longitude", loc.longitude)
            put("altitude_wgs84_ellipsoid_m", if (loc.hasAltitude()) loc.altitude else JSONObject.NULL)
            if (Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude()) put("altitude_msl_m", loc.mslAltitudeMeters)
            put("horizontal_accuracy_m", if (loc.hasAccuracy()) loc.accuracy.toDouble() else JSONObject.NULL)
            put("vertical_accuracy_m", if (loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters.toDouble() else JSONObject.NULL)
            put("speed_mps", if (loc.hasSpeed()) loc.speed.toDouble() else JSONObject.NULL)
            put("provider", loc.provider ?: "unknown")
            put("fix_age_s", pose.locationAgeS ?: JSONObject.NULL)
            put("fix_time_utc", iso(loc.time))
        }

        // ---- orientation ----
        val ori = JSONObject()
        ori.put("world_frame", if (geo.trueNorth) "ENU: x = east, y = true north, z = up"
            else "ENU: x = east, y = MAGNETIC north, z = up (no location, so declination unknown)")
        ori.put("camera_frame", "x = increasing pixel column, y = increasing pixel row, z = optical axis into the scene")
        ori.put("angle_definitions", "heading = bearing of the optical axis, deg clockwise from north; " +
            "pitch = elevation of the optical axis, + up; roll = rotation about the optical axis from level, + when image +x tips toward the ground")
        ori.put("magnetic_declination_deg", pose.declinationDeg ?: JSONObject.NULL)
        val sa = geo.sensorAngles
        val da = geo.displayAngles
        if (sa != null && da != null && geo.rWorldFromCamSensor != null && geo.rWorldFromCamDisplay != null) {
            ori.put("heading_true_deg", if (geo.trueNorth) sa.headingDeg else JSONObject.NULL)
            ori.put("heading_magnetic_deg", geo.magneticHeadingDeg ?: JSONObject.NULL)
            ori.put("near_vertical", sa.nearVertical)
            ori.put("sensor_frame", frameJson(sa, geo.rWorldFromCamSensor, 0, 1))
            ori.put("display_frame", frameJson(da, geo.rWorldFromCamDisplay, geo.displayRotationCw, geo.exifOrientation))
            val dev = JSONObject()
            dev.put("R_world_from_device", mat3(geo.rWorldFromDevice!!))
            dev.put("R_device_from_camera_sensor", mat3(geo.rDeviceFromCam))
            val o = FloatArray(3)
            SensorManager.getOrientation(FloatArray(9) { geo.rWorldMagFromDevice!![it].toFloat() }, o)
            dev.put("android_getOrientation_magnetic_deg", JSONObject()
                .put("azimuth", Math.toDegrees(o[0].toDouble()))
                .put("pitch", Math.toDegrees(o[1].toDouble()))
                .put("roll", Math.toDegrees(o[2].toDouble())))
            ori.put("device", dev)
        } else {
            ori.put("heading_true_deg", JSONObject.NULL)
            ori.put("heading_magnetic_deg", JSONObject.NULL)
            ori.put("error", "no orientation sensor data")
        }
        val sample = JSONObject()
        sample.put("source", pose.orientationSource)
        sample.put("time_reference", pose.timeReference)
        sample.put("sample_offset_ms", pose.sampleOffsetMs ?: JSONObject.NULL)
        sample.put("heading_accuracy_deg_reported", pose.headingAccuracyDeg ?: JSONObject.NULL)
        sample.put("rotation_vector_accuracy", PoseTracker.accuracyName(pose.rotationAccuracyStatus))
        sample.put("magnetometer_calibration", PoseTracker.accuracyName(pose.magnetometerAccuracyStatus))
        sample.put("angular_speed_dps", pose.angularSpeedDps ?: JSONObject.NULL)
        ori.put("sample", sample)

        // ---- summary first, then details ----
        val root = JSONObject()
        root.put("schema", "rawgeocam/1")
        root.put("image_file", imageName)
        root.put("capture_time_utc", iso(input.captureWallMillis))
        root.put("summary", JSONObject().apply {
            put("latitude", loc?.latitude ?: JSONObject.NULL)
            put("longitude", loc?.longitude ?: JSONObject.NULL)
            put("gps_accuracy_m", if (loc != null && loc.hasAccuracy()) loc.accuracy.toDouble() else JSONObject.NULL)
            put("heading_deg", sa?.headingDeg ?: JSONObject.NULL)
            put("heading_reference", if (geo.trueNorth) "true_north" else "magnetic_north")
            put("pitch_deg", sa?.pitchDeg ?: JSONObject.NULL)
            put("roll_deg_display_frame", da?.rollDeg ?: JSONObject.NULL)
            put("roll_deg_sensor_frame", sa?.rollDeg ?: JSONObject.NULL)
            if (input.isRaw) {
                put("fx_fy_cx_cy_opencv_sensor_frame", arr(fx, fy, cx - 0.5, cy - 0.5))
            } else {
                val sx = w.toDouble() / pre.width()
                val sy = h.toDouble() / pre.height()
                put("fx_fy_cx_cy_opencv_sensor_frame", arr(fx * sx, fy * sy, cx * sx - 0.5, cy * sy - 0.5))
            }
            put("intrinsics_source", kSource)
        })
        root.put("device", JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("android_sdk", Build.VERSION.SDK_INT))
        root.put("camera", JSONObject().apply {
            put("id", input.cameraId)
            put("active_physical_id", r.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) ?: JSONObject.NULL)
            put("timestamp_source", if (c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) "realtime" else "unknown")
        })
        root.put("image", img)
        root.put("intrinsics", intr)
        root.put("location", locJson)
        root.put("orientation", ori)
        root.put("exposure", exp)
        return root to kSource
    }

    private fun frameJson(a: PoseMath.Angles, rWc: DoubleArray, rotCw: Int, exif: Int): JSONObject =
        JSONObject()
            .put("rotation_cw_from_sensor_deg", rotCw)
            .put("exif_orientation", exif)
            .put("heading_deg", a.headingDeg)
            .put("pitch_deg", a.pitchDeg)
            .put("roll_deg", a.rollDeg)
            .put("R_world_from_camera", mat3(rWc))
            .put("quaternion_wxyz_world_from_camera", arr(*PoseMath.quaternionWxyz(rWc)))

    // ---------------- helpers ----------------

    private fun kOpenCv(fx: Double, fy: Double, cx: Double, cy: Double, s: Double, offX: Double, offY: Double) =
        JSONArray()
            .put(arr(fx, s, cx + offX - 0.5))
            .put(arr(0.0, fy, cy + offY - 0.5))
            .put(arr(0.0, 0.0, 1.0))

    private fun arr(vararg v: Double) = JSONArray().apply { v.forEach { put(it) } }

    private fun arrF(v: FloatArray) = JSONArray().apply { v.forEach { put(it.toDouble()) } }

    private fun mat3(m: DoubleArray) = JSONArray().apply {
        for (row in 0..2) put(arr(m[3 * row], m[3 * row + 1], m[3 * row + 2]))
    }

    private fun rectJson(r: Rect) = JSONObject()
        .put("left", r.left).put("top", r.top).put("width", r.width()).put("height", r.height())

    private fun putF(o: JSONObject, key: String, v: Float?) { o.put(key, v?.toDouble() ?: JSONObject.NULL) }
    private fun putL(o: JSONObject, key: String, v: Long?) { o.put(key, v ?: JSONObject.NULL) }
    private fun putI(o: JSONObject, key: String, v: Int?) { o.put(key, v ?: JSONObject.NULL) }

    private fun iso(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(millis))

    private fun save(context: Context, name: String, mime: String, body: (OutputStream) -> Unit): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("could not create $name")
        try {
            val os = resolver.openOutputStream(uri) ?: throw IOException("could not open $name")
            BufferedOutputStream(os, 1 shl 20).use { body(it) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }
}
