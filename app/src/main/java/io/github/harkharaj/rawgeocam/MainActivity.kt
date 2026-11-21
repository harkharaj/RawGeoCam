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
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity(), CameraController.Listener {

    private lateinit var preview: TextureView
    private lateinit var previewContainer: View
    private lateinit var status: TextView
    private lateinit var shutter: Button
    private lateinit var lensButton: Button
    private lateinit var modeButton: Button
    private lateinit var focusButton: Button
    private lateinit var manualPanel: View
    private lateinit var lblShutter: TextView
    private lateinit var lblIso: TextView
    private lateinit var lblFocus: TextView
    private lateinit var seekShutter: SeekBar
    private lateinit var seekIso: SeekBar
    private lateinit var seekFocus: SeekBar

    private lateinit var prefs: Prefs
    private lateinit var pose: PoseTracker
    private lateinit var camera: CameraController

    private var lenses: List<CameraController.Lens> = emptyList()
    private var lensIndex = 0
    private var info: CameraController.CameraInfo? = null
    private var savedCount = 0
    private var lastSaved: String? = null
    private var lastIntrinsicsSource: String? = null
    private var lastIssues: List<String> = emptyList()
    private var message: String? = null
    private var isResumedNow = false

    // Manual-mode state for the current lens.
    private var shutterStops = LongArray(0)
    private var isoStops = IntArray(0)
    private var manualExposureNs = 10_000_000L
    private var manualIso = 100
    private var manualFocusD = 0f

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
        lensButton = findViewById(R.id.btnLens)
        modeButton = findViewById(R.id.btnMode)
        focusButton = findViewById(R.id.btnFocus)
        manualPanel = findViewById(R.id.manualPanel)
        lblShutter = findViewById(R.id.lblShutter)
        lblIso = findViewById(R.id.lblIso)
        lblFocus = findViewById(R.id.lblFocus)
        seekShutter = findViewById(R.id.seekShutter)
        seekIso = findViewById(R.id.seekIso)
        seekFocus = findViewById(R.id.seekFocus)

        prefs = Prefs(this)
        pose = PoseTracker(this)
        camera = CameraController(this, preview, pose, this)
        camera.focusAtInfinity = prefs.focusAtInfinity

        shutter.setOnClickListener {
            if (camera.capture()) setShutterEnabled(false)
        }
        lensButton.setOnClickListener { switchLens() }
        modeButton.setOnClickListener {
            prefs.manualMode = !prefs.manualMode
            applyMode()
        }
        focusButton.setOnClickListener {
            camera.focusAtInfinity = !camera.focusAtInfinity
            prefs.focusAtInfinity = camera.focusAtInfinity
            updateButtons()
        }
        val sliderListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) onSliderMoved(bar, progress)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        }
        seekShutter.setOnSeekBarChangeListener(sliderListener)
        seekIso.setOnSeekBarChangeListener(sliderListener)
        seekFocus.setOnSeekBarChangeListener(sliderListener)
        seekFocus.max = FOCUS_STEPS

        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) = openCameraIfReady()
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        // Showing / hiding the manual panel changes the space for the preview: refit it.
        previewContainer.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) fitPreview()
        }
        manualPanel.visibility = if (prefs.manualMode) View.VISIBLE else View.GONE
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

    private fun currentLens(): CameraController.Lens? = lenses.getOrNull(lensIndex)

    private fun openCameraIfReady() {
        if (!isResumedNow || !granted(Manifest.permission.CAMERA) || !preview.isAvailable) return
        if (lenses.isEmpty()) {
            lenses = try {
                camera.listLenses()
            } catch (e: Exception) {
                emptyList()
            }
            val saved = prefs.lensKey
            lensIndex = lenses.indexOfFirst { it.key == saved }.let { if (it >= 0) it else defaultLensIndex() }
        }
        val lens = currentLens()
        if (lens == null) {
            message = "No back-facing camera is exposed to apps on this phone."
            return
        }
        loadManualValues(lens, null)
        camera.manual = manualSettingsOrNull()
        camera.open(lens)
    }

    private fun defaultLensIndex(): Int = lenses.indexOfFirst { it.physicalId == null }.coerceAtLeast(0)

    private fun switchLens() {
        if (lenses.size < 2) {
            toast("This phone exposes only one back lens to apps")
            return
        }
        lensIndex = (lensIndex + 1) % lenses.size
        prefs.lensKey = currentLens()?.key
        camera.close()
        info = null
        lastIntrinsicsSource = null
        setShutterEnabled(true)
        openCameraIfReady()
    }

    override fun onCameraReady(info: CameraController.CameraInfo) {
        this.info = info
        fitPreview()
        setupManualControls(info)
        if (!info.raw) toast("This lens does not offer RAW. Saving JPEG with processing reduced instead.")
        updateButtons()
    }

    /** Fit the preview inside its container at the sensor's aspect ratio, upright for portrait. */
    private fun fitPreview() {
        val i = info ?: return
        val p = i.previewSize
        val upright = i.sensorOrientation % 180 == 90
        val ratio = if (upright) p.width.toDouble() / p.height else p.height.toDouble() / p.width // height / width
        val cw = previewContainer.width
        val ch = previewContainer.height
        if (cw <= 0 || ch <= 0) return
        var w = cw
        var h = (cw * ratio).toInt()
        if (h > ch) {
            h = ch
            w = (ch / ratio).toInt()
        }
        val lp = preview.layoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            preview.layoutParams = lp
        }
    }

    override fun onCaptureSaved(baseName: String, intrinsicsSource: String, qualityIssues: List<String>) {
        savedCount++
        lastSaved = baseName
        lastIntrinsicsSource = intrinsicsSource
        lastIssues = qualityIssues
        message = null
        setShutterEnabled(true)
        if (qualityIssues.isEmpty()) toast("Saved $baseName")
        else toast("Saved $baseName  ⚠ ${qualityIssues.joinToString(", ")}")
    }

    override fun onCameraError(message: String) {
        this.message = message
        setShutterEnabled(true)
        toast(message)
    }

    // ---------------- manual mode ----------------

    /**
     * Restores this lens's saved shutter / ISO / focus. Without saved values, starts from what auto exposure
     * is currently using (so the first switch to manual is not black) and focus at infinity.
     */
    private fun loadManualValues(lens: CameraController.Lens, i: CameraController.CameraInfo?) {
        val live = camera.live
        manualExposureNs = prefs.exposureNs(lens.key) ?: live?.exposureNs ?: 10_000_000L
        manualIso = prefs.iso(lens.key) ?: live?.iso ?: 100
        manualFocusD = prefs.focusDiopters(lens.key) ?: 0f
        if (i != null) {
            if (i.manualSensor) {
                manualExposureNs = manualExposureNs.coerceIn(i.exposureMinNs, i.exposureMaxNs)
                manualIso = manualIso.coerceIn(i.isoMin, i.isoMax)
            }
            manualFocusD = manualFocusD.coerceIn(0f, i.minFocusDiopters)
        }
    }

    private fun setupManualControls(i: CameraController.CameraInfo) {
        loadManualValues(i.lens, i)
        if (i.manualSensor) {
            shutterStops = ManualControls.shutterStops(i.exposureMinNs, i.exposureMaxNs)
            isoStops = ManualControls.isoStops(i.isoMin, i.isoMax)
            seekShutter.max = shutterStops.size - 1
            seekIso.max = isoStops.size - 1
            // The stored value is kept exactly; the slider only shows the nearest stop until it is moved.
            seekShutter.progress = ManualControls.nearestIndex(shutterStops, manualExposureNs)
            seekIso.progress = ManualControls.nearestIndex(isoStops, manualIso)
        }
        seekShutter.isEnabled = i.manualSensor
        seekIso.isEnabled = i.manualSensor
        seekFocus.isEnabled = i.manualFocus
        seekFocus.progress = ManualControls.progressForDiopters(manualFocusD, FOCUS_STEPS, i.minFocusDiopters)
        updateManualLabels()
        camera.manual = manualSettingsOrNull()
    }

    private fun onSliderMoved(bar: SeekBar, progress: Int) {
        val i = info ?: return
        when (bar) {
            seekShutter -> shutterStops.getOrNull(progress)?.let { manualExposureNs = it }
            seekIso -> isoStops.getOrNull(progress)?.let { manualIso = it }
            seekFocus -> manualFocusD = ManualControls.dioptersForProgress(progress, FOCUS_STEPS, i.minFocusDiopters)
        }
        prefs.saveManual(i.lens.key, manualExposureNs, manualIso, manualFocusD)
        updateManualLabels()
        camera.manual = manualSettingsOrNull()
    }

    private fun manualSettingsOrNull(): CameraController.ManualSettings? =
        if (prefs.manualMode) CameraController.ManualSettings(manualExposureNs, manualIso, manualFocusD) else null

    private fun applyMode() {
        manualPanel.visibility = if (prefs.manualMode) View.VISIBLE else View.GONE
        val lens = currentLens()
        if (lens != null) loadManualValues(lens, info)
        val i = info
        if (i != null) {
            setupManualControls(i)
            // Pin the starting values the first time, so the next launch restores exactly these.
            if (prefs.manualMode) prefs.saveManual(i.lens.key, manualExposureNs, manualIso, manualFocusD)
        }
        camera.manual = manualSettingsOrNull()
        updateButtons()
    }

    private fun updateManualLabels() {
        val i = info
        lblShutter.text = if (i?.manualSensor == false) "Shutter: this lens has no manual exposure"
        else "Shutter  " + ManualControls.shutterLabel(manualExposureNs)
        lblIso.text = if (i?.manualSensor == false) "ISO: this lens has no manual exposure" else "ISO  $manualIso"
        lblFocus.text = when {
            i == null -> "Focus"
            i.fixedFocus -> "Focus: fixed-focus lens"
            !i.manualFocus -> "Focus: no manual focus on this lens"
            else -> "Focus  " + ManualControls.focusLabel(manualFocusD, i.focusCalibrated)
        }
    }

    // ---------------- UI ----------------

    private fun setShutterEnabled(enabled: Boolean) {
        shutter.isEnabled = enabled
        shutter.alpha = if (enabled) 1f else 0.4f
        shutter.text = if (enabled) "Capture" else "Saving…"
    }

    private fun updateButtons() {
        val i = info
        val lens = currentLens()
        lensButton.text = when {
            lens == null -> "Lens"
            lens.label.startsWith("Ultrawide") -> "UW"
            lens.label.startsWith("Tele") -> "Tele"
            lens.physicalId == null -> "Cam ${lens.openId}"
            else -> "Lens"
        }
        modeButton.text = if (prefs.manualMode) "Manual" else "Auto"
        focusButton.isEnabled = !prefs.manualMode && i?.manualFocus == true
        focusButton.text = when {
            i == null -> "Focus"
            i.fixedFocus -> "Fixed focus"
            prefs.manualMode -> "Focus: slider"
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
        currentLens()?.let { sb.append("Lens: ").append(it.label).append('\n') }
        if (i != null) {
            sb.append(if (i.raw) "RAW DNG ${i.stillSize}" else "NO RAW -> JPEG ${i.stillSize}")
            if (i.lens.physicalId != null) sb.append(if (i.previewOnPhysical) "  preview: this lens" else "  preview: logical cam")
            sb.append('\n')
            val live = camera.live
            val k = live?.intrinsics
            if (live != null && k != null && k.size >= 4 && k[0] > 0f) {
                sb.append(String.format(Locale.US, "K fx %.1f fy %.1f cx %.1f cy %.1f%s\n",
                    k[0], k[1], k[2], k[3],
                    if (i.lens.physicalId != null && !live.fromPhysicalResult) " (logical!)" else " (per frame)"))
            } else {
                val kText = lastIntrinsicsSource ?: if (i.staticIntrinsics) "static factory value" else "not published (estimate)"
                sb.append("K: ").append(kText).append('\n')
            }
            if (live != null) {
                sb.append("Exp ").append(live.exposureNs?.let { ManualControls.shutterLabel(it) } ?: "?")
                sb.append("  ISO ").append(live.iso ?: "?")
                live.focusDiopters?.let { sb.append("  focus ").append(String.format(Locale.US, "%.3f D", it)) }
                if (live.lensState == android.hardware.camera2.CameraMetadata.LENS_STATE_MOVING) sb.append("  lens moving")
                sb.append('\n')
            }
            sb.append("Distortion: ").append(if (i.staticDistortion) "published" else "not published")
            sb.append("   OIS: ").append(if (i.oisOffSupported) "off" else "no control").append('\n')
        }
        sb.append("Saved: ").append(savedCount)
        lastSaved?.let { sb.append("  last ").append(it) }
        if (lastSaved != null) sb.append(if (lastIssues.isEmpty()) "  quality OK" else "  ⚠ ${lastIssues.size} issue(s)")
        message?.let { sb.append('\n').append(it) }
        status.text = sb
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQUEST_PERMISSIONS = 1
        private const val FOCUS_STEPS = 1000
    }
}
