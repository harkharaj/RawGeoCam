package io.github.harkharaj.rawgeocam

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity(), CameraController.Listener {

    private lateinit var preview: TextureView
    private lateinit var previewContainer: View
    private lateinit var status: TextView
    private lateinit var shutter: Button
    private lateinit var cameraButton: Button
    private lateinit var focusButton: Button

    private lateinit var pose: PoseTracker
    private lateinit var camera: CameraController

    private var cameraIds: List<String> = emptyList()
    private var cameraIndex = 0
    private var info: CameraController.CameraInfo? = null
    private var savedCount = 0
    private var lastSaved: String? = null
    private var lastIntrinsicsSource: String? = null
    private var message: String? = null
    private var isResumedNow = false

    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            ui.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        preview = findViewById(R.id.preview)
        previewContainer = findViewById(R.id.previewContainer)
        status = findViewById(R.id.status)
        shutter = findViewById(R.id.btnShutter)
        cameraButton = findViewById(R.id.btnCamera)
        focusButton = findViewById(R.id.btnFocus)

        pose = PoseTracker(this)
        camera = CameraController(this, preview, pose, this)

        shutter.setOnClickListener {
            if (camera.capture()) setShutterEnabled(false)
        }
        cameraButton.setOnClickListener { switchCamera() }
        focusButton.setOnClickListener {
            camera.focusAtInfinity = !camera.focusAtInfinity
            updateButtons()
        }
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) = openCameraIfReady()
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        requestMissingPermissions()
    }

    override fun onResume() {
        super.onResume()
        isResumedNow = true
        pose.start()
        openCameraIfReady()
        ui.post(ticker)
    }

    override fun onPause() {
        isResumedNow = false
        ui.removeCallbacks(ticker)
        camera.close()
        pose.stop()
        setShutterEnabled(true)
        super.onPause()
    }

    override fun onDestroy() {
        camera.release()
        super.onDestroy()
    }

    // ---------------- permissions ----------------

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun requestMissingPermissions() {
        val needed = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ).filterNot { granted(it) }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        message = when {
            !granted(Manifest.permission.CAMERA) -> "Camera permission is required."
            !granted(Manifest.permission.ACCESS_FINE_LOCATION) -> "Precise location not granted: GPS will be missing or coarse."
            else -> null
        }
        if (isResumedNow) {
            pose.stop()
            pose.start()
            openCameraIfReady()
        }
    }

    // ---------------- camera ----------------

    private fun openCameraIfReady() {
        if (!isResumedNow || !granted(Manifest.permission.CAMERA) || !preview.isAvailable) return
        if (cameraIds.isEmpty()) {
            cameraIds = try {
                camera.backCameraIds()
            } catch (e: Exception) {
                emptyList()
            }
        }
        if (cameraIds.isEmpty()) {
            message = "No back-facing camera is exposed to apps on this phone."
            return
        }
        camera.open(cameraIds[cameraIndex % cameraIds.size])
    }

    private fun switchCamera() {
        if (cameraIds.size < 2) {
            toast("This phone exposes only one back camera to apps")
            return
        }
        cameraIndex = (cameraIndex + 1) % cameraIds.size
        camera.close()
        info = null
        lastIntrinsicsSource = null
        setShutterEnabled(true)
        openCameraIfReady()
    }

    override fun onCameraReady(info: CameraController.CameraInfo) {
        this.info = info
        // Fit the preview inside its container at the sensor's aspect ratio, upright for portrait.
        val p = info.previewSize
        val upright = info.sensorOrientation % 180 == 90
        val ratio = if (upright) p.width.toDouble() / p.height else p.height.toDouble() / p.width // height / width
        val cw = previewContainer.width
        val ch = previewContainer.height
        if (cw > 0 && ch > 0) {
            var w = cw
            var h = (cw * ratio).toInt()
            if (h > ch) {
                h = ch
                w = (ch / ratio).toInt()
            }
            preview.layoutParams = preview.layoutParams.apply {
                width = w
                height = h
            }
        }
        if (!info.raw) toast("This camera does not offer RAW. Saving JPEG with processing reduced instead.")
        updateButtons()
    }

    override fun onCaptureSaved(baseName: String, intrinsicsSource: String) {
        savedCount++
        lastSaved = baseName
        lastIntrinsicsSource = intrinsicsSource
        message = null
        setShutterEnabled(true)
        toast("Saved $baseName")
    }

    override fun onCameraError(message: String) {
        this.message = message
        setShutterEnabled(true)
        toast(message)
    }

    // ---------------- UI ----------------

    private fun setShutterEnabled(enabled: Boolean) {
        shutter.isEnabled = enabled
        shutter.alpha = if (enabled) 1f else 0.4f
        shutter.text = if (enabled) "Capture" else "Saving…"
    }

    private fun updateButtons() {
        val i = info
        cameraButton.text = if (i != null) "Cam ${i.id}" else "Cam"
        focusButton.isEnabled = i?.manualFocus == true
        focusButton.text = when {
            i == null -> "Focus"
            i.fixedFocus -> "Fixed focus"
            !i.manualFocus -> "AF only"
            camera.focusAtInfinity -> "Focus ∞"
            else -> "Focus AF"
        }
    }

    private fun refreshStatus() {
        val sb = StringBuilder()
        val i = info
        val loc = pose.currentLocation()
        val decl = loc?.let { pose.declinationAt(it) }
        val rMag = pose.latestWorldMagFromDevice()
        if (rMag != null) {
            val rDc = PoseMath.deviceFromBackCamera(i?.sensorOrientation ?: 90)
            val rW = if (decl != null) PoseMath.mul(PoseMath.trueFromMagnetic(decl), rMag) else rMag
            val rWc = PoseMath.mul(rW, rDc)
            val a = PoseMath.anglesOf(rWc)
            val rot = PoseMath.displayRotationForRoll(a.rollDeg)
            val d = PoseMath.anglesOf(PoseMath.mul(rWc, PoseMath.sensorCamFromDisplayCam(rot)))
            sb.append(String.format(Locale.US, "HDG %5.1f° %s  PITCH %+5.1f°  ROLL %+5.1f°\n",
                d.headingDeg, if (decl != null) "true" else "mag ", d.pitchDeg, d.rollDeg))
        } else {
            sb.append("Orientation: waiting for sensors\n")
        }
        val magAcc = pose.magnetometerAccuracy
        sb.append("Compass: ").append(PoseTracker.accuracyName(magAcc))
        if (magAcc <= SensorManager.SENSOR_STATUS_ACCURACY_LOW) sb.append("  ← wave phone in a figure-8")
        sb.append('\n')
        if (loc != null) {
            sb.append(String.format(Locale.US, "GPS %.6f, %.6f  ±%.0f m  %s %.0fs old\n",
                loc.latitude, loc.longitude, loc.accuracy, loc.provider, pose.locationAgeSeconds(loc)))
        } else {
            sb.append("GPS: waiting for a fix\n")
        }
        if (i != null) {
            sb.append(if (i.raw) "Cam ${i.id}  RAW DNG ${i.stillSize}" else "Cam ${i.id}  NO RAW -> JPEG ${i.stillSize}")
            i.focalLengthMm?.let { sb.append(String.format(Locale.US, "  f=%.2fmm", it)) }
            sb.append('\n')
            val kText = lastIntrinsicsSource ?: if (i.staticIntrinsics) "published by camera" else "not in static metadata (checked per shot)"
            sb.append("Intrinsics: ").append(kText).append('\n')
            sb.append("Distortion: ").append(if (i.staticDistortion) "published" else "not published")
            sb.append("   OIS: ").append(if (i.oisOffSupported) "off" else "no control").append('\n')
        }
        sb.append("Saved: ").append(savedCount)
        lastSaved?.let { sb.append("  last ").append(it) }
        sb.append("  → Download/RawGeoCam")
        message?.let { sb.append('\n').append(it) }
        status.text = sb
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQUEST_PERMISSIONS = 1
    }
}
