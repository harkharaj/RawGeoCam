package io.github.harkharaj.rawgeocam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Camera2 wrapper: a preview stream plus one still stream (RAW_SENSOR when the camera supports it,
 * otherwise JPEG). Geometry-changing features are switched off where the camera allows it
 * (optical/video stabilisation, distortion correction) and focus can be locked at infinity so the
 * intrinsics do not drift with focus breathing.
 */
class CameraController(
    private val context: Context,
    private val textureView: TextureView,
    private val pose: PoseTracker,
    private val listener: Listener,
) {

    interface Listener {
        fun onCameraReady(info: CameraInfo)
        fun onCaptureSaved(baseName: String, intrinsicsSource: String)
        fun onCameraError(message: String)
    }

    data class CameraInfo(
        val id: String,
        val raw: Boolean,
        val stillSize: Size,
        val previewSize: Size,
        val sensorOrientation: Int,
        val focalLengthMm: Float?,
        val staticIntrinsics: Boolean,
        val staticDistortion: Boolean,
        val manualFocus: Boolean,
        val fixedFocus: Boolean,
        val oisOffSupported: Boolean,
        val realtimeTimestamps: Boolean,
    )

    private val manager = context.getSystemService(CameraManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()

    private var bgThread: HandlerThread? = null
    @Volatile private var bg: Handler? = null
    private val bgExecutor = Executor { r -> bg?.post(r) }

    @Volatile private var device: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    @Volatile private var opening = false
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var chars: CameraCharacteristics? = null
    private var info: CameraInfo? = null

    /** Lock focus at infinity (when the lens supports manual focus) instead of continuous AF. */
    @Volatile var focusAtInfinity = true
        set(value) {
            field = value
            bg?.post { startPreview() }
        }

    // One capture in flight at a time. Fields below are only touched on the camera thread.
    private val busy = AtomicBoolean(false)
    @Volatile private var token = 0
    private var pendingImage: Image? = null
    private var pendingResult: TotalCaptureResult? = null
    private var pendingPose: PoseTracker.Snapshot? = null
    private var pendingWallMs = 0L
    private var pendingStartRealtime = 0L

    fun backCameraIds(): List<String> = manager.cameraIdList.filter { id ->
        manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
            CameraMetadata.LENS_FACING_BACK
    }

    // ---------------- open / close ----------------

    @SuppressLint("MissingPermission")
    fun open(cameraId: String) {
        if (opening || device != null) return
        val st = textureView.surfaceTexture ?: return
        startThread()
        try {
            val c = manager.getCameraCharacteristics(cameraId)
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw IllegalStateException("no stream configuration map")
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            val rawSizes: Array<Size>? = map.getOutputSizes(ImageFormat.RAW_SENSOR)
            val raw = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps && !rawSizes.isNullOrEmpty()
            val format = if (raw) ImageFormat.RAW_SENSOR else ImageFormat.JPEG
            val stillSizes: Array<Size> = if (raw) rawSizes!! else map.getOutputSizes(ImageFormat.JPEG)
            val stillSize = stillSizes.maxBy { it.width.toLong() * it.height }

            val pre = c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
            val aspect = if (pre != null) pre.width().toDouble() / pre.height()
            else stillSize.width.toDouble() / stillSize.height
            val previewSize = choosePreviewSize(map.getOutputSizes(SurfaceTexture::class.java), aspect)
            st.setDefaultBufferSize(previewSize.width, previewSize.height)
            previewSurface = Surface(st)

            val minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            val afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
            val ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0)
            val k = c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
            val dist = c.get(CameraCharacteristics.LENS_DISTORTION)

            chars = c
            info = CameraInfo(
                id = cameraId,
                raw = raw,
                stillSize = stillSize,
                previewSize = previewSize,
                sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                focalLengthMm = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull(),
                staticIntrinsics = k != null && k.size >= 5 && k[0] > 0f,
                staticDistortion = dist != null && dist.size >= 5,
                manualFocus = minFocus > 0f && CameraMetadata.CONTROL_AF_MODE_OFF in afModes,
                fixedFocus = minFocus == 0f,
                oisOffSupported = CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF in ois,
                realtimeTimestamps = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                    CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
            )

            reader = ImageReader.newInstance(stillSize.width, stillSize.height, format, 2).apply {
                setOnImageAvailableListener({ onImageAvailable(it) }, bg)
            }
            opening = true
            manager.openCamera(cameraId, deviceCallback, bg)
        } catch (e: Exception) {
            opening = false
            reportError("Cannot open camera $cameraId: ${e.message}")
        }
    }

    fun close() {
        // Let a save that is already running finish while its Image is still valid.
        try {
            writer.submit {}.get(3, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
        try {
            device?.close()
        } catch (_: Exception) {
        }
        device = null
        opening = false
        val t = bgThread
        bgThread = null
        bg = null
        t?.quitSafely()
        try {
            t?.join(1000)
        } catch (_: InterruptedException) {
        }
        token++
        pendingImage?.close()
        pendingImage = null
        pendingResult = null
        pendingPose = null
        reader?.close()
        reader = null
        previewSurface?.release()
        previewSurface = null
        busy.set(false)
    }

    fun release() {
        writer.shutdown()
    }

    private fun startThread() {
        if (bgThread != null) return
        val t = HandlerThread("RawGeoCam-camera").apply { start() }
        bgThread = t
        bg = Handler(t.looper)
    }

    private fun choosePreviewSize(sizes: Array<Size>, aspect: Double): Size {
        val small = sizes.filter { it.width <= 1920 && it.height <= 1440 }
        val same = small.filter { abs(it.width.toDouble() / it.height - aspect) < 0.02 }
        return same.ifEmpty { small }.ifEmpty { sizes.toList() }.maxBy { it.width.toLong() * it.height }
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            opening = false
            device = camera
            createSession(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            opening = false
            camera.close()
            if (device == camera) device = null
        }

        override fun onError(camera: CameraDevice, code: Int) {
            opening = false
            camera.close()
            if (device == camera) device = null
            reportError("Camera error $code (another app may be using the camera)")
        }
    }

    private fun createSession(d: CameraDevice) {
        val ps = previewSurface ?: return
        val rd = reader ?: return
        val outputs = listOf(OutputConfiguration(ps), OutputConfiguration(rd.surface))
        val cfg = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR, outputs, bgExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    startPreview()
                    val i = info
                    if (i != null) main.post { listener.onCameraReady(i) }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    reportError("Camera rejected the preview + still stream configuration")
                }
            },
        )
        try {
            d.createCaptureSession(cfg)
        } catch (e: Exception) {
            reportError("Cannot create capture session: ${e.message}")
        }
    }

    // ---------------- requests ----------------

    private fun startPreview() {
        val d = device ?: return
        val s = session ?: return
        val ps = previewSurface ?: return
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(ps)
            applyCommon(b)
            s.setRepeatingRequest(b.build(), null, bg)
        } catch (e: Exception) {
            reportError("Preview failed: ${e.message}")
        }
    }

    /** Settings shared by preview and still: these change the geometry, so they must match. */
    private fun applyCommon(b: CaptureRequest.Builder) {
        val c = chars ?: return
        val i = info ?: return
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)

        val afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
        if (focusAtInfinity && i.manualFocus) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f) // 0 diopters = infinity
        } else if (CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }

        if (i.oisOffSupported) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        }
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        val dc = c.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES) ?: IntArray(0)
        if (CameraMetadata.DISTORTION_CORRECTION_MODE_OFF in dc) {
            b.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CameraMetadata.DISTORTION_CORRECTION_MODE_OFF)
        }
    }

    /** Still-only settings: turn off every optional processing block the camera lets us turn off. */
    private fun applyStill(b: CaptureRequest.Builder) {
        val c = chars ?: return
        val i = info ?: return
        fun offIfAvailable(key: CaptureRequest.Key<Int>, modes: IntArray?, off: Int) {
            if (modes != null && off in modes) b.set(key, off)
        }
        offIfAvailable(
            CaptureRequest.NOISE_REDUCTION_MODE,
            c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES),
            CameraMetadata.NOISE_REDUCTION_MODE_OFF,
        )
        offIfAvailable(
            CaptureRequest.EDGE_MODE,
            c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES),
            CameraMetadata.EDGE_MODE_OFF,
        )
        offIfAvailable(
            CaptureRequest.HOT_PIXEL_MODE,
            c.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES),
            CameraMetadata.HOT_PIXEL_MODE_OFF,
        )
        offIfAvailable(
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
            c.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES),
            CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF,
        )
        if (i.raw) {
            // The lens-shading map is stored in the DNG as metadata (GainMap opcode); pixels stay untouched.
            offIfAvailable(
                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES),
                CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON,
            )
        } else {
            b.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            b.set(CaptureRequest.JPEG_ORIENTATION, 0) // keep the sensor pixel grid; rotation goes in the JSON
        }
    }

    // ---------------- capture ----------------

    /** Returns false if the camera is not ready or a capture is already in progress. */
    fun capture(): Boolean {
        if (session == null || device == null) return false
        if (!busy.compareAndSet(false, true)) return false
        val h = bg
        if (h == null) {
            busy.set(false)
            return false
        }
        h.post { submitCapture() }
        return true
    }

    private fun submitCapture() {
        val d = device
        val s = session
        val rd = reader
        val ps = previewSurface
        if (d == null || s == null || rd == null || ps == null) {
            fail("Camera not ready")
            return
        }
        val myToken = ++token
        pendingImage?.close()
        pendingImage = null
        pendingResult = null
        pendingPose = null
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(rd.surface)
            b.addTarget(ps)
            applyCommon(b)
            applyStill(b)
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(
                    session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long,
                ) {
                    pendingStartRealtime = SystemClock.elapsedRealtimeNanos()
                }

                override fun onCaptureCompleted(
                    session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult,
                ) {
                    if (myToken != token) return
                    pendingResult = result
                    snapshotFor(result)
                    tryWrite(myToken)
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure,
                ) {
                    if (myToken == token) fail("Capture failed (reason ${failure.reason})")
                }
            }, bg)
            bg?.postDelayed({ if (myToken == token && busy.get()) fail("Capture timed out") }, 8000)
        } catch (e: Exception) {
            fail("Capture request failed: ${e.message}")
        }
    }

    /** Pose at mid-exposure when the camera clock is comparable with the sensor clock. */
    private fun snapshotFor(result: TotalCaptureResult) {
        val ts = result.get(CaptureResult.SENSOR_TIMESTAMP)
        val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
        val skew = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW) ?: 0L
        val nowWall = System.currentTimeMillis()
        val nowRt = SystemClock.elapsedRealtimeNanos()
        if (info?.realtimeTimestamps == true && ts != null) {
            val mid = ts + exposure / 2 + skew / 2
            pendingPose = pose.snapshotAt(mid, "mid_exposure_from_sensor_timestamp")
            pendingWallMs = nowWall - (nowRt - mid) / 1_000_000
        } else {
            val mid = pendingStartRealtime + exposure / 2
            pendingPose = pose.snapshotAt(mid, "capture_started_callback_plus_half_exposure")
            pendingWallMs = nowWall - (nowRt - mid) / 1_000_000
        }
    }

    private fun onImageAvailable(r: ImageReader) {
        val img = try {
            r.acquireNextImage()
        } catch (_: Exception) {
            null
        } ?: return
        if (!busy.get() || pendingImage != null) {
            img.close()
            return
        }
        pendingImage = img
        tryWrite(token)
    }

    private fun tryWrite(t: Int) {
        if (t != token) return
        val img = pendingImage ?: return
        val res = pendingResult ?: return
        val p = pendingPose ?: return
        val c = chars ?: return
        val i = info ?: return
        pendingImage = null
        pendingResult = null
        pendingPose = null
        token++ // disarms the timeout
        val input = CaptureWriter.Input(
            image = img,
            result = res,
            chars = c,
            cameraId = i.id,
            isRaw = i.raw,
            pose = p,
            focusMode = focusLabel(res, i),
            captureWallMillis = pendingWallMs,
        )
        writer.execute {
            try {
                val out = CaptureWriter.write(context.applicationContext, input)
                main.post { listener.onCaptureSaved(out.baseName, out.intrinsicsSource) }
            } catch (e: Exception) {
                main.post { listener.onCameraError("Save failed: ${e.message}") }
            } finally {
                img.close()
                busy.set(false)
            }
        }
    }

    private fun focusLabel(res: TotalCaptureResult, i: CameraInfo): String {
        val af = res.get(CaptureResult.CONTROL_AF_MODE)
        return when {
            i.fixedFocus -> "fixed_focus_lens"
            af == CameraMetadata.CONTROL_AF_MODE_OFF -> "manual_locked_at_infinity"
            af == CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "continuous_autofocus"
            else -> "af_mode_$af"
        }
    }

    private fun fail(message: String) {
        token++
        pendingImage?.close()
        pendingImage = null
        pendingResult = null
        pendingPose = null
        busy.set(false)
        main.post { listener.onCameraError(message) }
    }

    private fun reportError(message: String) {
        main.post { listener.onCameraError(message) }
    }
}
