package com.scenacam.app

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.ExifInterface
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Controlează camera principală prin Camera2: previzualizare, analiză de cadre,
 * aplicarea setărilor alese de [SceneEngine] și salvarea fotografiilor.
 */
class CameraController(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onAnalysis(result: AnalysisResult)
        fun onPhotoSaved(uri: Uri?, plan: ShotPlan)
        fun onCameraError(message: String)
    }

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val main = Handler(Looper.getMainLooper())

    val cameraId: String
    private val chars: CameraCharacteristics
    val sensorOrientation: Int
    private val activeArray: Rect
    val previewSize: Size
    val jpegSize: Size
    private val analysisSize: Size
    val caps: DeviceCaps
    val hardwareLevel: String

    private val afContinuous: Boolean
    private val afAuto: Boolean
    private val maxAeRegions: Int
    private val maxAfRegions: Int
    private val oisAvailable: Boolean
    private val nrHighQuality: Boolean
    private val edgeHighQuality: Boolean
    private val lowFpsRange: Range<Int>?

    /** Orientarea telefonului: 0, 90, 180 sau 270 de grade. */
    @Volatile
    var deviceOrientation = 0

    /** Stilul de culoare aplicat fotografiilor salvate. */
    @Volatile
    var style = PhotoStyle.ORIGINAL

    /** Prelucrarea și salvarea fotografiilor, separat de firul camerei. */
    private val saveExecutor = Executors.newSingleThreadExecutor()

    // Ultimele valori alese de expunerea automată în previzualizare.
    @Volatile
    var aeIso = 0
        private set

    @Volatile
    var aeExposureNs = 0L
        private set

    @Volatile
    var aeAperture = 0f
        private set

    private val analyzer = SceneAnalyzer(
        rotationProvider = { sensorOrientation + deviceOrientation },
        onResult = { listener.onAnalysis(it) }
    )

    private var cameraThread: HandlerThread? = null
    private var analysisThread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var jpegReader: ImageReader? = null
    private var yuvReader: ImageReader? = null
    private var previewSurface: Surface? = null

    // Accesate doar de pe firul camerei.
    private var plan: ShotPlan = ShotPlan.DEFAULT
    private var shotPlan: ShotPlan = ShotPlan.DEFAULT
    private var tapRect: NormRect? = null
    private var capturing = false
    private val clearTap = Runnable {
        tapRect = null
        applyPreview()
    }

    init {
        val ids = manager.cameraIdList
        cameraId = ids.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraMetadata.LENS_FACING_BACK
        } ?: ids.first()
        chars = manager.getCameraCharacteristics(cameraId)
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 4000, 3000)

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
        val fourThree = jpegSizes.filter { abs(it.width.toFloat() / it.height - 4f / 3f) < 0.02f }
        jpegSize = (fourThree.ifEmpty { jpegSizes }).maxByOrNull { it.width.toLong() * it.height }
            ?: Size(1920, 1440)
        val aspect = jpegSize.width.toFloat() / jpegSize.height

        val previewSizes = map?.getOutputSizes(SurfaceHolder::class.java)?.toList() ?: emptyList()
        previewSize = previewSizes
            .filter { abs(it.width.toFloat() / it.height - aspect) < 0.02f && it.width <= 1920 && it.height <= 1080 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: Size(640, 480)

        val yuvSizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList() ?: emptyList()
        analysisSize = yuvSizes
            .filter { abs(it.width.toFloat() / it.height - aspect) < 0.02f && it.width >= 640 && it.width <= 1024 }
            .minByOrNull { it.width }
            ?: yuvSizes.filter { it.width <= 1024 }.maxByOrNull { it.width }
            ?: Size(640, 480)

        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val manualSensor = capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) &&
            isoRange != null && expRange != null

        val evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val evStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f

        val awbAvailable = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: IntArray(0)
        val awbModes = mutableSetOf(Awb.AUTO)
        if (awbAvailable.contains(CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT)) awbModes.add(Awb.DAYLIGHT)
        if (awbAvailable.contains(CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT)) awbModes.add(Awb.CLOUDY)

        val sceneModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES) ?: IntArray(0)

        caps = DeviceCaps(
            manualSensor = manualSensor,
            isoMin = isoRange?.lower ?: 0,
            isoMax = isoRange?.upper ?: 0,
            expMinNs = expRange?.lower ?: 0L,
            expMaxNs = expRange?.upper ?: 0L,
            evStep = evStep,
            evMinSteps = evRange?.lower ?: 0,
            evMaxSteps = evRange?.upper ?: 0,
            awbModes = awbModes,
            hdrSceneMode = sceneModes.contains(CameraMetadata.CONTROL_SCENE_MODE_HDR)
        )

        hardwareLevel = when (chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "?"
        }

        val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
        afContinuous = afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        afAuto = afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO)
        maxAeRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        maxAfRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        oisAvailable = (chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0))
            .contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
        nrHighQuality = (chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
            ?: IntArray(0)).contains(CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        edgeHighQuality = (chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: IntArray(0))
            .contains(CameraMetadata.EDGE_MODE_HIGH_QUALITY)

        // Pentru scene întunecate: intervalul de cadre cu cea mai mică limită inferioară.
        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        lowFpsRange = fpsRanges
            ?.filter { it.upper <= 30 && it.upper >= 24 }
            ?.minByOrNull { it.lower }
    }

    // ------------------------------------------------------------------ pornire / oprire

    @SuppressLint("MissingPermission")
    fun start(surface: Surface) {
        if (cameraThread != null) return
        previewSurface = surface
        val ct = HandlerThread("scenacam-camera").also { it.start() }
        val at = HandlerThread("scenacam-analysis").also { it.start() }
        cameraThread = ct
        analysisThread = at
        val h = Handler(ct.looper)
        handler = h

        val jr = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
        jr.setOnImageAvailableListener({ reader -> saveJpeg(reader) }, h)
        jpegReader = jr
        val yr = ImageReader.newInstance(analysisSize.width, analysisSize.height, ImageFormat.YUV_420_888, 4)
        yr.setOnImageAvailableListener(analyzer, Handler(at.looper))
        yuvReader = yr

        try {
            manager.openCamera(cameraId, deviceCallback, h)
        } catch (e: Exception) {
            fail("Camera nu a putut fi deschisă: ${e.message}")
        }
    }

    fun stop() {
        handler?.removeCallbacksAndMessages(null)
        try {
            session?.close()
        } catch (ignored: Exception) {
        }
        session = null
        try {
            device?.close()
        } catch (ignored: Exception) {
        }
        device = null
        cameraThread?.quitSafely()
        analysisThread?.quitSafely()
        cameraThread = null
        analysisThread = null
        handler = null
        try {
            jpegReader?.close()
        } catch (ignored: Exception) {
        }
        try {
            yuvReader?.close()
        } catch (ignored: Exception) {
        }
        jpegReader = null
        yuvReader = null
        previewSurface = null
        capturing = false
        aeIso = 0
        aeExposureNs = 0L
    }

    fun release() {
        stop()
        analyzer.close()
        saveExecutor.shutdown()
    }

    // ------------------------------------------------------------------ comenzi din interfață

    fun setPlan(newPlan: ShotPlan) {
        handler?.post {
            val changed = previewKey(newPlan) != previewKey(plan)
            plan = newPlan
            if (changed) applyPreview()
        }
    }

    /** Focalizare și măsurare a luminii în punctul atins (coordonate 0..1 pe previzualizare). */
    fun focusAt(xUpright: Float, yUpright: Float) {
        val h = handler ?: return
        h.post {
            val half = 0.08f
            tapRect = NormRect(xUpright - half, yUpright - half, xUpright + half, yUpright + half)
                .clamped()
                .uprightToSensor(sensorOrientation)
            h.removeCallbacks(clearTap)
            applyPreview()
            val dev = device
            val ses = session
            val surface = previewSurface
            if (afAuto && dev != null && ses != null && surface != null) {
                safely {
                    val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    b.addTarget(surface)
                    applyCommon(b, plan)
                    b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                    ses.capture(b.build(), null, h)
                }
            }
            h.postDelayed(clearTap, 5000)
        }
    }

    fun takePhoto() {
        val h = handler ?: return
        h.post {
            val dev = device ?: return@post
            val ses = session ?: return@post
            val reader = jpegReader ?: return@post
            if (capturing) return@post
            capturing = true
            val p = plan
            shotPlan = p
            try {
                val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                b.addTarget(reader.surface)
                applyCommon(b, p)
                b.set(CaptureRequest.JPEG_QUALITY, 97.toByte())
                b.set(CaptureRequest.JPEG_ORIENTATION, (sensorOrientation + deviceOrientation + 360) % 360)
                if (nrHighQuality) {
                    b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                }
                if (edgeHighQuality) {
                    b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
                }
                val iso = p.manualIso
                val exposure = p.manualExposureNs
                if (p.hdr) {
                    b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE)
                    b.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_HDR)
                } else if (iso != null && exposure != null) {
                    // Expunere manuală: ISO și timp calculate de SceneEngine.
                    b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
                    b.set(CaptureRequest.SENSOR_FRAME_DURATION, max(exposure, 33_333_333L))
                }
                ses.capture(b.build(), stillCallback, h)
            } catch (e: Exception) {
                capturing = false
                fail("Fotografia nu a putut fi făcută: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------ Camera2

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            device = camera
            createSession(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            device = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            device = null
            fail("Eroare cameră (cod $error)")
        }
    }

    @Suppress("DEPRECATION")
    private fun createSession(camera: CameraDevice) {
        val surface = previewSurface ?: return
        val jr = jpegReader ?: return
        val yr = yuvReader ?: return
        try {
            camera.createCaptureSession(
                listOf(surface, jr.surface, yr.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        applyPreview()
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        fail("Sesiunea camerei nu a putut fi configurată")
                    }
                },
                handler
            )
        } catch (e: Exception) {
            fail("Sesiunea camerei nu a putut fi creată: ${e.message}")
        }
    }

    private fun applyPreview() {
        val dev = device ?: return
        val ses = session ?: return
        val surface = previewSurface ?: return
        val yr = yuvReader ?: return
        safely {
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(surface)
            b.addTarget(yr.surface)
            applyCommon(b, plan)
            ses.setRepeatingRequest(b.build(), previewCallback, handler)
        }
    }

    /** Setările comune previzualizării și fotografiei. */
    private fun applyCommon(b: CaptureRequest.Builder, p: ShotPlan) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, p.evSteps)

        val tap = tapRect
        if (tap != null && afAuto) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
        } else if (afContinuous) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }

        b.set(
            CaptureRequest.CONTROL_AWB_MODE,
            when (p.awb) {
                Awb.DAYLIGHT -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
                Awb.CLOUDY -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
                Awb.AUTO -> CameraMetadata.CONTROL_AWB_MODE_AUTO
            }
        )

        val region = tap ?: p.meterRect
        if (region != null) {
            val rect = arrayOf(toMetering(region))
            if (maxAeRegions > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, rect)
            if (maxAfRegions > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, rect)
        }

        val fps = lowFpsRange
        if (p.lowFps && fps != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)

        if (oisAvailable) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }
    }

    private fun toMetering(sensorRect: NormRect): MeteringRectangle {
        val r = sensorRect.clamped()
        val w = activeArray.width()
        val h = activeArray.height()
        val x = (r.left * w).roundToInt().coerceIn(0, w - 2)
        val y = (r.top * h).roundToInt().coerceIn(0, h - 2)
        val rw = (r.width * w).roundToInt().coerceIn(1, w - x)
        val rh = (r.height * h).roundToInt().coerceIn(1, h - y)
        return MeteringRectangle(x, y, rw, rh, MeteringRectangle.METERING_WEIGHT_MAX)
    }

    /** Doar ce influențează previzualizarea; fața e rotunjită ca să nu retrimitem cererea la fiecare tremur. */
    private fun previewKey(p: ShotPlan): String {
        val m = p.meterRect
        val face = if (m == null) "-" else
            "${(m.left * 10).roundToInt()},${(m.top * 10).roundToInt()},${(m.right * 10).roundToInt()},${(m.bottom * 10).roundToInt()}"
        return "${p.evSteps}|${p.awb}|${p.lowFps}|$face"
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
            val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            if (iso != null && exposure != null && iso > 0 && exposure > 0) {
                val boost = result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST) ?: 100
                aeIso = if (boost > 100) (iso.toLong() * boost / 100).toInt() else iso
                aeExposureNs = exposure
            }
            result.get(CaptureResult.LENS_APERTURE)?.let { aeAperture = it }
        }
    }

    private val stillCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            capturing = false
        }

        override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            capturing = false
            fail("Captura a eșuat")
        }
    }

    // ------------------------------------------------------------------ salvare

    private fun saveJpeg(reader: ImageReader) {
        val image = try {
            reader.acquireNextImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return
        val bytes: ByteArray
        try {
            val buffer = image.planes[0].buffer
            bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
        } finally {
            image.close()
        }

        val used = shotPlan
        val tune = ColorTune.forShot(used.scene, style)
        try {
            saveExecutor.execute { writePhoto(bytes, used, tune) }
        } catch (e: Exception) {
            main.post { listener.onPhotoSaved(null, used) }
        }
    }

    private fun writePhoto(original: ByteArray, used: ShotPlan, tune: ColorTune) {
        // Dacă prelucrarea de culoare nu reușește, salvăm fotografia neschimbată.
        val bytes = stylize(original, tune) ?: original
        var uri: Uri? = null
        try {
            val name = "ScenaCam_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".jpg"
            val values = ContentValues()
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name)
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/ScenaCam")
            values.put(MediaStore.Images.Media.IS_PENDING, 1)
            val resolver = context.contentResolver
            val target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (target != null) {
                resolver.openOutputStream(target)?.use { it.write(bytes) }
                val done = ContentValues()
                done.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(target, done, null, null)
                uri = target
            }
        } catch (e: Exception) {
            uri = null
        }
        val saved = uri
        main.post { listener.onPhotoSaved(saved, used) }
    }

    /** Aplică reglajul de culoare. Întoarce null dacă nu e nimic de făcut sau dacă prelucrarea eșuează. */
    private fun stylize(bytes: ByteArray, tune: ColorTune): ByteArray? {
        if (tune.isIdentity) return null
        var src: Bitmap? = null
        var dst: Bitmap? = null
        try {
            val orientation = ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
            val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            src = source
            val swap = degrees == 90 || degrees == 270
            val w = if (swap) source.height else source.width
            val h = if (swap) source.width else source.height
            val target = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            dst = target

            // Rotim pixelii după orientarea din EXIF, fiindcă recodarea pierde eticheta de orientare.
            val transform = Matrix()
            transform.postRotate(degrees.toFloat(), source.width / 2f, source.height / 2f)
            transform.postTranslate((w - source.width) / 2f, (h - source.height) / 2f)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            paint.colorFilter = ColorMatrixColorFilter(ColorMatrix(tune.matrix()))
            Canvas(target).drawBitmap(source, transform, paint)

            val out = ByteArrayOutputStream(bytes.size)
            if (!target.compress(Bitmap.CompressFormat.JPEG, 95, out)) return null
            return out.toByteArray()
        } catch (t: Throwable) {
            // inclusiv lipsă de memorie la fotografii foarte mari
            return null
        } finally {
            src?.recycle()
            dst?.recycle()
        }
    }

    // ------------------------------------------------------------------ utilitare

    private fun fail(message: String) {
        main.post { listener.onCameraError(message) }
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: CameraAccessException) {
            // camera a fost închisă între timp
        } catch (e: IllegalStateException) {
            // sesiunea a fost închisă între timp
        } catch (e: IllegalArgumentException) {
            // suprafața nu mai e validă
        }
    }
}
