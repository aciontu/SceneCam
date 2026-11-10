package com.scenacam.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), CameraController.Listener, SurfaceHolder.Callback {

    private lateinit var surfaceView: AutoFitSurfaceView
    private lateinit var overlay: OverlayView
    private lateinit var flash: View
    private lateinit var sceneText: TextView
    private lateinit var settingsText: TextView
    private lateinit var detailText: TextView
    private lateinit var modeButton: TextView
    private lateinit var galleryButton: TextView
    private lateinit var styleButton: TextView
    private lateinit var infoButton: TextView
    private var style = PhotoStyle.ORIGINAL
    private lateinit var zoomText: TextView
    private lateinit var scaleDetector: ScaleGestureDetector
    private var zoom = 1f
    private var multiTouch = false
    private lateinit var nightButton: TextView
    private var nightEnabled = true
    private var lastPlan: ShotPlan = ShotPlan.DEFAULT
    private lateinit var shutter: View

    private var controller: CameraController? = null
    private lateinit var steadiness: SteadinessMonitor
    private lateinit var orientationListener: OrientationEventListener
    private val engine = SceneEngine()

    /** null = AI alege scena; altfel scena e forțată de utilizator. */
    private var forcedScene: Scene? = null
    private var surfaceReady = false
    private var resumed = false
    private var lastPhoto: Uri? = null
    private val clearTapRing = Runnable { overlay.clearTap() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surface)
        overlay = findViewById(R.id.overlay)
        flash = findViewById(R.id.flash)
        sceneText = findViewById(R.id.scene_text)
        settingsText = findViewById(R.id.settings_text)
        detailText = findViewById(R.id.detail_text)
        modeButton = findViewById(R.id.mode_button)
        galleryButton = findViewById(R.id.gallery_button)
        shutter = findViewById(R.id.shutter)
        styleButton = findViewById(R.id.style_button)
        infoButton = findViewById(R.id.info_button)
        zoomText = findViewById(R.id.zoom_text)
        nightButton = findViewById(R.id.night_button)
        nightEnabled = getPreferences(Context.MODE_PRIVATE).getBoolean(PREF_NIGHT, true)
        nightButton.setOnClickListener {
            nightEnabled = !nightEnabled
            controller?.nightBurst = nightEnabled
            getPreferences(Context.MODE_PRIVATE).edit().putBoolean(PREF_NIGHT, nightEnabled).apply()
            renderNight()
        }

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                applyZoom(zoom * detector.scaleFactor)
                return true
            }
        })
        zoomText.setOnClickListener { applyZoom(1f) }

        val savedStyle = getPreferences(Context.MODE_PRIVATE).getString(PREF_STYLE, null)
        style = PhotoStyle.values().firstOrNull { it.name == savedStyle } ?: PhotoStyle.ORIGINAL

        steadiness = SteadinessMonitor(this)
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                controller?.deviceOrientation = ((orientation + 45) / 90 * 90) % 360
            }
        }

        try {
            val c = CameraController(this, this)
            controller = c
            c.style = style
            c.nightBurst = nightEnabled
            surfaceView.setAspect(c.previewSize.width, c.previewSize.height)
            surfaceView.holder.setFixedSize(c.previewSize.width, c.previewSize.height)
            val manual = if (c.caps.manualSensor) getString(R.string.manual_yes) else getString(R.string.manual_no)
            detailText.text = getString(
                R.string.camera_info, c.hardwareLevel, manual, c.jpegSize.width, c.jpegSize.height
            )
        } catch (e: Exception) {
            sceneText.text = getString(R.string.no_camera)
        }
        surfaceView.holder.addCallback(this)

        shutter.setOnClickListener {
            if (nightEnabled && lastPlan.burst > 1 && controller != null) {
                Toast.makeText(this, getString(R.string.hold_still, lastPlan.burst), Toast.LENGTH_SHORT).show()
            }
            controller?.takePhoto()
            flash.alpha = 0.7f
            flash.animate().alpha(0f).setDuration(250).start()
        }

        modeButton.setOnClickListener {
            val scenes = Scene.values()
            val current = forcedScene
            forcedScene = when {
                current == null -> scenes[0]
                current.ordinal == scenes.size - 1 -> null
                else -> scenes[current.ordinal + 1]
            }
            renderMode()
        }

        styleButton.setOnClickListener {
            val all = PhotoStyle.values()
            style = all[(style.ordinal + 1) % all.size]
            controller?.style = style
            getPreferences(Context.MODE_PRIVATE).edit().putString(PREF_STYLE, style.name).apply()
            renderStyle()
        }

        infoButton.setOnClickListener { showDiagnostics() }

        galleryButton.setOnClickListener {
            val uri = lastPhoto
            if (uri == null) {
                Toast.makeText(this, R.string.no_photo_yet, Toast.LENGTH_SHORT).show()
            } else {
                try {
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.setDataAndType(uri, "image/jpeg")
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, R.string.no_gallery, Toast.LENGTH_SHORT).show()
                }
            }
        }

        overlay.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> multiTouch = false
                MotionEvent.ACTION_POINTER_DOWN -> multiTouch = true
            }
            // O atingere simplă focalizează; un gest cu două degete face doar zoom.
            if (event.actionMasked == MotionEvent.ACTION_UP && !multiTouch && v.width > 0 && v.height > 0) {
                val x = (event.x / v.width).coerceIn(0f, 1f)
                val y = (event.y / v.height).coerceIn(0f, 1f)
                controller?.focusAt(x, y)
                overlay.setTap(x, y)
                overlay.removeCallbacks(clearTapRing)
                overlay.postDelayed(clearTapRing, 5000)
                v.performClick()
            }
            true
        }

        renderMode()
        renderStyle()
        renderNight()
        applyZoom(1f)
    }

    private fun renderNight() {
        nightButton.text = getString(if (nightEnabled) R.string.night_on else R.string.night_off)
        nightButton.setTextColor(if (nightEnabled) 0xFFFFD54F.toInt() else 0xFF9E9E9E.toInt())
    }

    private fun applyZoom(ratio: Float) {
        val c = controller ?: return
        zoom = ratio.coerceIn(c.zoomMin, c.zoomMax)
        c.setZoom(zoom)
        zoomText.text = String.format("%.1f×", zoom)
    }

    private fun renderStyle() {
        styleButton.text = getString(R.string.style_label, style.title)
    }

    private fun showDiagnostics() {
        val report = try {
            Diagnostics.report(this)
        } catch (e: Exception) {
            "Eroare: " + e.message
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val text = TextView(this)
        text.text = report
        text.textSize = 12f
        text.typeface = Typeface.MONOSPACE
        text.setTextIsSelectable(true)
        text.setPadding(pad, pad, pad, pad)
        val scroll = ScrollView(this)
        scroll.addView(text)
        AlertDialog.Builder(this)
            .setTitle(R.string.diagnostic_title)
            .setView(scroll)
            .setPositiveButton(R.string.copy) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("ScenaCam diagnostic", report))
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        steadiness.start()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        } else {
            startCameraIfReady()
        }
    }

    override fun onPause() {
        resumed = false
        steadiness.stop()
        orientationListener.disable()
        controller?.stop()
        super.onPause()
    }

    override fun onDestroy() {
        controller?.release()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCameraIfReady()
        } else {
            sceneText.text = getString(R.string.permission_needed)
        }
    }

    private fun startCameraIfReady() {
        val c = controller ?: return
        if (!resumed || !surfaceReady) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        c.start(surfaceView.holder.surface)
    }

    // ------------------------------------------------------------------ SurfaceHolder.Callback

    override fun surfaceCreated(holder: SurfaceHolder) {
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val c = controller ?: return
        // Așteptăm ca suprafața să aibă exact dimensiunea cerută pentru previzualizare.
        if (width == c.previewSize.width && height == c.previewSize.height) {
            surfaceReady = true
            startCameraIfReady()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        controller?.stop()
    }

    // ------------------------------------------------------------------ CameraController.Listener

    override fun onAnalysis(result: AnalysisResult) {
        val c = controller ?: return
        val input = SceneInput(
            stats = result.stats,
            faces = result.faces,
            labels = result.labels,
            iso = c.aeIso,
            exposureNs = c.aeExposureNs,
            aperture = c.aeAperture,
            gyro = steadiness.value
        )
        val detected = engine.update(input)
        val scene = forcedScene ?: detected
        val plan = engine.plan(scene, input, c.caps)
        c.setPlan(plan)
        lastPlan = plan

        overlay.setFaces(result.faces.map { it.sensorRect.sensorToUpright(c.sensorOrientation) })
        render(plan, input, detected)
    }

    override fun onPhotoSaved(uri: Uri?, plan: ShotPlan, merged: Int) {
        if (uri == null) {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show()
            return
        }
        lastPhoto = uri
        val message = if (merged > 1) {
            getString(R.string.saved_merged, plan.scene.title, merged)
        } else {
            getString(R.string.saved, plan.scene.title)
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onCameraError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------ afișare

    private fun renderMode() {
        val forced = forcedScene
        modeButton.text = if (forced == null) getString(R.string.mode_ai) else forced.icon + " " + forced.title
    }

    private fun render(plan: ShotPlan, input: SceneInput, detected: Scene) {
        val forced = forcedScene
        sceneText.text = if (forced == null) {
            plan.scene.icon + "  " + plan.scene.title
        } else {
            plan.scene.icon + "  " + plan.scene.title + "  " + getString(R.string.forced_suffix, detected.title)
        }

        val sb = StringBuilder()
        val iso = plan.manualIso
        val exposure = plan.manualExposureNs
        if (plan.hdr) {
            sb.append("HDR")
        } else if (iso != null && exposure != null) {
            sb.append("ISO ").append(iso).append(" · ").append(SceneEngine.formatShutter(exposure))
            sb.append(" · ").append(getString(R.string.manual_tag))
        } else if (input.aeValid) {
            sb.append("ISO ").append(input.iso).append(" · ").append(SceneEngine.formatShutter(input.exposureNs))
            sb.append(" · ").append(getString(R.string.auto_tag))
        } else {
            sb.append(getString(R.string.auto_tag))
        }
        if (plan.evSteps != 0) sb.append(" · EV ").append(String.format("%+.1f", plan.evBias))
        when (plan.awb) {
            Awb.DAYLIGHT -> sb.append(" · WB ").append(getString(R.string.wb_daylight))
            Awb.CLOUDY -> sb.append(" · WB ").append(getString(R.string.wb_cloudy))
            Awb.AUTO -> {
            }
        }
        if (plan.meterRect != null) sb.append(" · ").append(getString(R.string.face_metering))
        if (nightEnabled && plan.burst > 1) sb.append(" · ").append(getString(R.string.frames, plan.burst))
        if (style != PhotoStyle.ORIGINAL) sb.append(" · ").append(style.title)
        settingsText.text = sb.toString()

        val top = input.labels.entries.sortedByDescending { it.value }.take(3)
            .joinToString(", ") { it.key + " " + (it.value * 100).toInt() + "%" }
        val ev = SceneEngine.ev100(input)
        val light = if (ev == null) "" else String.format("EV %.1f · ", ev)
        detailText.text = light + plan.steadiness.title + (if (top.isEmpty()) "" else " · $top")
    }

    companion object {
        private const val REQUEST_CAMERA = 1
        private const val PREF_STYLE = "style"
        private const val PREF_NIGHT = "night"
    }
}
