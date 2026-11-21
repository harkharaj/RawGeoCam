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
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

/**
 * Camera2 wrapper: a preview stream plus one still stream (RAW_SENSOR when the lens supports it,
 * otherwise JPEG).
 *
 * Lenses: every back camera the phone lists, plus the hidden physical lenses inside a logical
 * multi-camera. On Pixels the ultrawide only exists as a physical lens of camera 0; a logical RAW stream
 * is not allowed to switch to a sub-camera with a different sensor size, so the ultrawide is opened as a
 * PHYSICAL stream (OutputConfiguration.setPhysicalCameraId) and its own characteristics and per-lens
 * capture results are used for the DNG and the intrinsics.
 *
 * Geometry-changing features are switched off where the camera allows it (optical/video stabilisation,
 * distortion correction), and focus can be locked so the intrinsics do not drift with focus breathing.
 */
class CameraController(
    private val context: Context,
    private val textureView: TextureView,
    private val pose: PoseTracker,
    private val listener: Listener,
) {

    interface Listener {
        fun onCameraReady(info: CameraInfo)
        fun onCaptureSaved(baseName: String, intrinsicsSource: String, qualityIssues: List<String>)
        fun onCameraError(message: String)
    }

    /** A selectable lens: [openId] is what gets opened; [physicalId] picks a hidden sub-camera of it. */
    data class Lens(
        val openId: String,
        val physicalId: String?,
        val focalMm: Float,
        val label: String,
        /** True for the widest lens of a logical multi-camera (used to steer the logical zoom ratio). */
        val widestOfGroup: Boolean,
    ) {
        val key: String get() = if (physicalId == null) openId else "$openId/$physicalId"
    }

    data class CameraInfo(
        val lens: Lens,
        val raw: Boolean,
        val stillSize: Size,
        val previewSize: Size,
        val sensorOrientation: Int,
        val focalLengthMm: Float?,
        val staticIntrinsics: Boolean,
        val staticDistortion: Boolean,
        val manualFocus: Boolean,
        val fixedFocus: Boolean,
        /** Closest focus, diopters (0 for fixed focus). */
        val minFocusDiopters: Float,
        val focusCalibrated: Boolean,
        val manualSensor: Boolean,
        val exposureMinNs: Long,
        val exposureMaxNs: Long,
        val isoMin: Int,
        val isoMax: Int,
        val minFrameDurationNs: Long,
        val oisOffSupported: Boolean,
        val realtimeTimestamps: Boolean,
        /** Logical zoom ratio requested alongside a physical lens, or null. */
        val zoomRatio: Float?,
        /** Whether the preview comes from the physical lens itself (false: logical camera preview). */
        val previewOnPhysical: Boolean,
    )

    /** Manual exposure and focus. Values are clamped to the lens's ranges when applied. */
    data class ManualSettings(val exposureNs: Long, val iso: Int, val focusDiopters: Float)

    /** Latest preview metadata of the selected lens, for the live status display. */
    class Live(
        val intrinsics: FloatArray?,
        val fromPhysicalResult: Boolean,
        val focusDiopters: Float?,
        val exposureNs: Long?,
        val iso: Int?,
        val lensState: Int?,
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
    /** Characteristics of the lens that produces the frames (the physical lens when one is selected). */
    private var chars: CameraCharacteristics? = null
    private var logicalChars: CameraCharacteristics? = null
    private var physicalRequestKeys: Set<CaptureRequest.Key<*>> = emptySet()
    @Volatile private var info: CameraInfo? = null
    @Volatile private var lens: Lens? = null
    /** 0: preview and still both on the physical lens; 1: logical preview + physical still. */
    private var sessionVariant = 0

    /** Auto mode: lock focus at infinity (when the lens supports manual focus) instead of continuous AF. */
    @Volatile var focusAtInfinity = true
        set(value) {
            field = value
            bg?.post { startPreview() }
        }

    /** Non-null = manual mode (AE off, fixed shutter / ISO, fixed focus). Null = auto exposure. */
    @Volatile var manual: ManualSettings? = null
        set(value) {
            field = value
            bg?.post { startPreview() }
        }

    @Volatile var live: Live? = null
        private set

    // One capture in flight at a time. Fields below are only touched on the camera thread.
    private val busy = AtomicBoolean(false)
    @Volatile private var token = 0
    /** Bumped by close(), so a save that finishes after a re-open cannot unlock the new session's shutter. */
    @Volatile private var generation = 0
    private var pendingImage: Image? = null
    private var pendingTotal: TotalCaptureResult? = null
    private var pendingMeta: CaptureResult? = null
    private var pendingMetaSource = ""
    private var pendingPose: PoseTracker.Snapshot? = null
    private var pendingWallMs = 0L
    private var pendingStartRealtime = 0L
    private var pendingLensMoving = false

    // ---------------- lenses ----------------

    /** Back lenses, widest first. */
    fun listLenses(): List<Lens> {
        val found = mutableListOf<Lens>()
        for (id in manager.cameraIdList) {
            val c = try {
                manager.getCameraCharacteristics(id)
            } catch (_: Exception) {
                continue
            }
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) continue
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            val pre = c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
            val group = mutableListOf<Lens>()
            var logicalFocal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f

            if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps) {
                for (pid in c.physicalCameraIds) {
                    val pc = try {
                        manager.getCameraCharacteristics(pid)
                    } catch (_: Exception) {
                        continue
                    }
                    val ppre = pc.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
                    val pf = pc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
                    if (pre != null && ppre != null && ppre.width() == pre.width() && ppre.height() == pre.height()) {
                        // Same sensor as the logical camera's own RAW stream: that is the default lens.
                        logicalFocal = pf
                        continue
                    }
                    if (!hasRaw(pc)) continue
                    group += Lens(id, pid, pf, "", false)
                }
            }
            group += Lens(id, null, logicalFocal, "", false)
            val widest = group.minByOrNull { it.focalMm }
            for (l in group) found += l.copy(widestOfGroup = group.size > 1 && l === widest)
        }
        val mainFocal = found.firstOrNull { it.physicalId == null }?.focalMm ?: 0f
        return found.sortedBy { it.focalMm }.map { l ->
            val name = when {
                l.physicalId == null && found.count { it.physicalId == null } == 1 -> "Main"
                l.physicalId == null -> "Cam ${l.openId}"
                mainFocal > 0f && l.focalMm < mainFocal * 0.8f -> "Ultrawide"
                mainFocal > 0f && l.focalMm > mainFocal * 1.25f -> "Tele"
                else -> "Lens ${l.physicalId}"
            }
            val where = if (l.physicalId == null) "cam ${l.openId}" else "phys ${l.physicalId} of cam ${l.openId}"
            l.copy(label = String.format(java.util.Locale.US, "%s %.2fmm (%s)", name, l.focalMm, where))
        }
    }

    private fun hasRaw(c: CameraCharacteristics): Boolean {
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
        return CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps &&
            !map.getOutputSizes(ImageFormat.RAW_SENSOR).isNullOrEmpty()
    }

    // ---------------- open / close ----------------

    @SuppressLint("MissingPermission")
    fun open(target: Lens) {
        if (opening || device != null) return
        val st = textureView.surfaceTexture ?: return
        startThread()
        try {
            val logical = manager.getCameraCharacteristics(target.openId)
            val pid = target.physicalId
            val c = if (pid != null) manager.getCameraCharacteristics(pid) else logical
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw IllegalStateException("no stream configuration map")
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            val raw = hasRaw(c)
            if (pid != null && !raw) throw IllegalStateException("${target.label} offers no RAW stream")
            val format = if (raw) ImageFormat.RAW_SENSOR else ImageFormat.JPEG

            val pre = c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
            val pix = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val stillSize: Size = if (raw) {
                val sizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)
                val idx = PoseMath.preferredRawIndex(
                    IntArray(sizes.size) { sizes[it].width }, IntArray(sizes.size) { sizes[it].height },
                    pix?.width ?: -1, pix?.height ?: -1, pre?.width() ?: -1, pre?.height() ?: -1,
                )
                sizes[idx]
            } else {
                map.getOutputSizes(ImageFormat.JPEG).maxBy { it.width.toLong() * it.height }
            }

            val aspect = if (pre != null) pre.width().toDouble() / pre.height()
            else stillSize.width.toDouble() / stillSize.height
            var previewChoices = map.getOutputSizes(SurfaceTexture::class.java).toList()
            if (pid != null) {
                // Prefer sizes the logical camera can also stream, so the logical-preview fallback works.
                val logicalSizes = logical.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(SurfaceTexture::class.java)?.toSet() ?: emptySet()
                val both = previewChoices.filter { it in logicalSizes }
                if (both.isNotEmpty()) previewChoices = both
            }
            val previewSize = choosePreviewSize(previewChoices, aspect)
            st.setDefaultBufferSize(previewSize.width, previewSize.height)
            previewSurface = Surface(st)

            val minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            val afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
            val ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0)
            val k = c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
            val dist = c.get(CameraCharacteristics.LENS_DISTORTION)
            val maxFrame = c.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION) ?: 1_000_000_000L
            val expRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val minFrame = try {
                map.getOutputMinFrameDuration(format, stillSize)
            } catch (_: Exception) {
                0L
            }

            var zoom: Float? = null
            if (pid != null && target.widestOfGroup && Build.VERSION.SDK_INT >= 30) {
                // Points the logical camera at the ultrawide, so the HAL keeps that sensor streaming.
                // Zoom never crops RAW, so this does not affect the geometry of the saved image.
                zoom = logical.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.lower
            }

            chars = c
            logicalChars = logical
            physicalRequestKeys = if (pid != null) logical.availablePhysicalCameraRequestKeys?.toSet() ?: emptySet() else emptySet()
            lens = target
            sessionVariant = 0
            info = CameraInfo(
                lens = target,
                raw = raw,
                stillSize = stillSize,
                previewSize = previewSize,
                sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                focalLengthMm = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull(),
                staticIntrinsics = k != null && k.size >= 5 && k[0] > 0f,
                staticDistortion = dist != null && dist.size >= 5,
                manualFocus = minFocus > 0f && CameraMetadata.CONTROL_AF_MODE_OFF in afModes,
                fixedFocus = minFocus == 0f,
                minFocusDiopters = minFocus,
                focusCalibrated = c.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION) ==
                    CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED,
                manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps &&
                    expRange != null && isoRange != null,
                exposureMinNs = expRange?.lower ?: 0L,
                exposureMaxNs = minOf(expRange?.upper ?: 0L, maxFrame),
                isoMin = isoRange?.lower ?: 0,
                isoMax = isoRange?.upper ?: 0,
                minFrameDurationNs = minFrame,
                oisOffSupported = CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF in ois,
                realtimeTimestamps = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                    CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
                zoomRatio = zoom,
                previewOnPhysical = pid != null,
            )

            reader = ImageReader.newInstance(stillSize.width, stillSize.height, format, 2).apply {
                setOnImageAvailableListener({ onImageAvailable(it) }, bg)
            }
            opening = true
            manager.openCamera(target.openId, deviceCallback, bg)
        } catch (e: Exception) {
            opening = false
            reportError("Cannot open ${target.label}: ${e.message}")
        }
    }

    /**
     * Never blocks the UI thread. An image that is still being written keeps its ImageReader alive:
     * the reader is closed on the writer thread, after any save already queued there.
     */
    fun close() {
        generation++
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
            t?.join(500)
        } catch (_: InterruptedException) {
        }
        token++
        pendingImage?.close()
        pendingImage = null
        pendingTotal = null
        pendingMeta = null
        pendingPose = null
        val r = reader
        reader = null
        if (r != null) {
            try {
                writer.execute { closeQuietly(r) }
            } catch (_: RejectedExecutionException) {
                closeQuietly(r)
            }
        }
        previewSurface?.release()
        previewSurface = null
        live = null
        busy.set(false)
    }

    private fun closeQuietly(r: ImageReader) {
        try {
            r.close()
        } catch (_: Exception) {
        }
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

    private fun choosePreviewSize(sizes: List<Size>, aspect: Double): Size {
        val small = sizes.filter { it.width <= 1920 && it.height <= 1440 }
        val same = small.filter { abs(it.width.toDouble() / it.height - aspect) < 0.02 }
        return same.ifEmpty { small }.ifEmpty { sizes }.maxBy { it.width.toLong() * it.height }
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
        val pid = lens?.physicalId
        val previewPhysical = pid != null && sessionVariant == 0
        val previewCfg = OutputConfiguration(ps)
        val stillCfg = OutputConfiguration(rd.surface)
        if (pid != null) {
            stillCfg.setPhysicalCameraId(pid)
            if (previewPhysical) previewCfg.setPhysicalCameraId(pid)
        }
        val cfg = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR, listOf(previewCfg, stillCfg), bgExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    val i = info?.copy(previewOnPhysical = previewPhysical)
                    info = i
                    startPreview()
                    if (i != null) main.post { listener.onCameraReady(i) }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (pid != null && sessionVariant == 0) {
                        sessionVariant = 1 // retry: logical preview, physical still
                        bg?.post { device?.let { createSession(it) } }
                    } else if (pid != null) {
                        reportError("This phone will not stream RAW from ${lens?.label}. Use another lens.")
                    } else {
                        reportError("Camera rejected the preview + still stream configuration")
                    }
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

    private fun newRequest(d: CameraDevice, template: Int): CaptureRequest.Builder {
        val pid = lens?.physicalId
        return if (pid != null) d.createCaptureRequest(template, setOf(pid)) else d.createCaptureRequest(template)
    }

    /** Sets a key for the logical camera and, where the HAL accepts it, for the selected physical lens. */
    private fun <T> put(b: CaptureRequest.Builder, key: CaptureRequest.Key<T>, value: T) {
        b.set(key, value)
        val pid = lens?.physicalId
        if (pid != null && key in physicalRequestKeys) b.setPhysicalCameraKey(key, value, pid)
    }

    private fun startPreview() {
        val d = device ?: return
        val s = session ?: return
        val ps = previewSurface ?: return
        try {
            val b = newRequest(d, CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(ps)
            applyCommon(b)
            s.setRepeatingRequest(b.build(), previewCallback, bg)
        } catch (e: Exception) {
            reportError("Preview failed: ${e.message}")
        }
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val phys = lens?.physicalId?.let { physicalResult(result, it) }
            val r: CaptureResult = phys ?: result
            live = Live(
                intrinsics = r.get(CaptureResult.LENS_INTRINSIC_CALIBRATION),
                fromPhysicalResult = phys != null,
                focusDiopters = r.get(CaptureResult.LENS_FOCUS_DISTANCE),
                exposureNs = r.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                iso = r.get(CaptureResult.SENSOR_SENSITIVITY),
                lensState = r.get(CaptureResult.LENS_STATE),
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun physicalResult(total: TotalCaptureResult, pid: String): CaptureResult? =
        if (Build.VERSION.SDK_INT >= 31) total.physicalCameraTotalResults[pid] else total.physicalCameraResults[pid]

    /** True when focus is commanded to a fixed distance (so the lens must be stationary before a shot). */
    private fun focusLocked(i: CameraInfo): Boolean = i.manualFocus && (manual != null || focusAtInfinity)

    /** Settings shared by preview and still: these change the geometry or exposure, so they must match. */
    private fun applyCommon(b: CaptureRequest.Builder) {
        val c = chars ?: return
        val i = info ?: return
        put(b, CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        put(b, CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)

        val m = manual
        if (m != null && i.manualSensor) {
            val exposure = m.exposureNs.coerceIn(i.exposureMinNs, i.exposureMaxNs)
            put(b, CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            put(b, CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
            put(b, CaptureRequest.SENSOR_SENSITIVITY, m.iso.coerceIn(i.isoMin, i.isoMax))
            put(b, CaptureRequest.SENSOR_FRAME_DURATION, max(exposure, i.minFrameDurationNs))
        } else {
            put(b, CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        }

        val afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
        val lockedDiopters: Float? = when {
            !i.manualFocus -> null
            m != null -> m.focusDiopters.coerceIn(0f, i.minFocusDiopters)
            focusAtInfinity -> 0f
            else -> null
        }
        if (lockedDiopters != null) {
            put(b, CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            put(b, CaptureRequest.LENS_FOCUS_DISTANCE, lockedDiopters) // 0 diopters = infinity
        } else if (CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes) {
            put(b, CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }

        if (i.oisOffSupported) {
            put(b, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        }
        put(b, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        val dc = c.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES) ?: IntArray(0)
        if (CameraMetadata.DISTORTION_CORRECTION_MODE_OFF in dc) {
            put(b, CaptureRequest.DISTORTION_CORRECTION_MODE, CameraMetadata.DISTORTION_CORRECTION_MODE_OFF)
        }
        val z = i.zoomRatio
        if (z != null && Build.VERSION.SDK_INT >= 30) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, z)
    }

    /** Still-only settings: turn off every optional processing block the camera lets us turn off. */
    private fun applyStill(b: CaptureRequest.Builder) {
        val c = chars ?: return
        val i = info ?: return
        fun offIfAvailable(key: CaptureRequest.Key<Int>, modes: IntArray?, off: Int) {
            if (modes != null && off in modes) put(b, key, off)
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
        h.post { submitCapture(0) }
        return true
    }

    private fun submitCapture(waitedMs: Int) {
        val d = device
        val s = session
        val rd = reader
        val ps = previewSurface
        val i = info
        if (d == null || s == null || rd == null || ps == null || i == null) {
            fail("Camera not ready")
            return
        }
        // After a focus change the lens needs a few frames to get there; shoot only once it has stopped.
        val moving = focusLocked(i) && live?.lensState == CameraMetadata.LENS_STATE_MOVING
        if (moving && waitedMs < 1500) {
            bg?.postDelayed({ submitCapture(waitedMs + 50) }, 50)
            return
        }
        val myToken = ++token
        pendingImage?.close()
        pendingImage = null
        pendingTotal = null
        pendingMeta = null
        pendingPose = null
        pendingLensMoving = moving
        try {
            val b = newRequest(d, CameraDevice.TEMPLATE_STILL_CAPTURE)
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
                    val pid = lens?.physicalId
                    val phys = pid?.let { physicalResult(result, it) }
                    pendingTotal = result
                    pendingMeta = phys ?: result
                    pendingMetaSource = when {
                        pid == null -> "logical_camera_result"
                        phys != null -> "physical_camera_result"
                        else -> "logical_result_fallback"
                    }
                    snapshotFor(phys ?: result)
                    tryWrite(myToken)
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure,
                ) {
                    if (myToken == token) fail("Capture failed (reason ${failure.reason})")
                }
            }, bg)
            val exposureMs = ((manual?.exposureNs ?: 0L) / 1_000_000L).coerceAtMost(60_000L)
            bg?.postDelayed({ if (myToken == token && busy.get()) fail("Capture timed out") }, 8000L + 2 * exposureMs)
        } catch (e: Exception) {
            fail("Capture request failed: ${e.message}")
        }
    }

    /** Pose at mid-exposure when the camera clock is comparable with the sensor clock. */
    private fun snapshotFor(result: CaptureResult) {
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
        val total = pendingTotal ?: return
        val meta = pendingMeta ?: return
        val p = pendingPose ?: return
        val c = chars ?: return
        val i = info ?: return
        pendingImage = null
        pendingTotal = null
        pendingMeta = null
        pendingPose = null
        token++ // disarms the timeout
        val gen = generation
        val m = manual
        val input = CaptureWriter.Input(
            image = img,
            result = meta,
            totalResult = total,
            chars = c,
            lens = i.lens,
            metadataSource = pendingMetaSource,
            isRaw = i.raw,
            pose = p,
            focusMode = focusLabel(meta, i),
            exposureMode = if (m != null && i.manualSensor) "manual" else "auto",
            requestedExposureNs = if (m != null && i.manualSensor) m.exposureNs.coerceIn(i.exposureMinNs, i.exposureMaxNs) else null,
            requestedIso = if (m != null && i.manualSensor) m.iso.coerceIn(i.isoMin, i.isoMax) else null,
            zoomRatio = i.zoomRatio,
            previewOnPhysical = i.previewOnPhysical,
            lensWasMoving = pendingLensMoving,
            captureWallMillis = pendingWallMs,
        )
        try {
            writer.execute {
                try {
                    val out = CaptureWriter.write(context.applicationContext, input)
                    main.post { listener.onCaptureSaved(out.baseName, out.intrinsicsSource, out.qualityIssues) }
                } catch (e: Exception) {
                    main.post { listener.onCameraError("Save failed: ${e.message}") }
                } finally {
                    img.close()
                    if (gen == generation) busy.set(false)
                }
            }
        } catch (e: RejectedExecutionException) {
            img.close()
            busy.set(false)
        }
    }

    private fun focusLabel(res: CaptureResult, i: CameraInfo): String {
        val af = res.get(CaptureResult.CONTROL_AF_MODE)
        return when {
            i.fixedFocus -> "fixed_focus_lens"
            af == CameraMetadata.CONTROL_AF_MODE_OFF && manual != null -> "manual_fixed_distance"
            af == CameraMetadata.CONTROL_AF_MODE_OFF -> "manual_locked_at_infinity"
            af == CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "continuous_autofocus"
            else -> "af_mode_$af"
        }
    }

    private fun fail(message: String) {
        token++
        pendingImage?.close()
        pendingImage = null
        pendingTotal = null
        pendingMeta = null
        pendingPose = null
        busy.set(false)
        main.post { listener.onCameraError(message) }
    }

    private fun reportError(message: String) {
        main.post { listener.onCameraError(message) }
    }
}
