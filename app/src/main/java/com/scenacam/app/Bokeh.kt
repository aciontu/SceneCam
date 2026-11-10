package com.scenacam.app

import android.annotation.TargetApi
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraExtensionSession
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ExtensionSessionConfiguration
import android.hardware.camera2.params.OutputConfiguration
import android.os.Build
import android.os.Handler
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor
import kotlin.math.abs

/** Dimensiunile cu care lucrează extensia Bokeh a producătorului. */
data class BokehInfo(val previewSize: Size, val jpegSize: Size)

@TargetApi(31)
object BokehSupport {

    const val EXTENSION = CameraExtensionCharacteristics.EXTENSION_BOKEH

    /** Întoarce null dacă telefonul nu oferă Bokeh aplicațiilor terțe. */
    fun query(manager: CameraManager, cameraId: String): BokehInfo? {
        if (Build.VERSION.SDK_INT < 31) return null
        return try {
            val ext = manager.getCameraExtensionCharacteristics(cameraId)
            if (!ext.supportedExtensions.contains(EXTENSION)) return null
            val previews = ext.getExtensionSupportedSizes(EXTENSION, SurfaceTexture::class.java)
            val jpegs = ext.getExtensionSupportedSizes(EXTENSION, ImageFormat.JPEG)
            if (previews.isEmpty() || jpegs.isEmpty()) return null

            val fourThree = jpegs.filter { abs(it.width.toFloat() / it.height - 4f / 3f) < 0.02f }
            val jpeg = (fourThree.ifEmpty { jpegs }).maxByOrNull { it.width.toLong() * it.height } ?: return null
            val aspect = jpeg.width.toFloat() / jpeg.height
            val preview = previews
                .filter { abs(it.width.toFloat() / it.height - aspect) < 0.02f && it.width <= 1920 && it.height <= 1440 }
                .maxByOrNull { it.width.toLong() * it.height }
                ?: previews.filter { it.width <= 1920 }.maxByOrNull { it.width.toLong() * it.height }
                ?: previews.minByOrNull { it.width.toLong() * it.height }
                ?: return null
            BokehInfo(preview, jpeg)
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Sesiune de cameră care folosește procesarea Bokeh a producătorului (Camera2 Extensions):
 * previzualizarea și fotografia ies cu fundalul estompat de telefon, nu de aplicație.
 *
 * Toate metodele se apelează de pe firul camerei.
 */
@TargetApi(31)
class BokehSession(
    manager: CameraManager,
    cameraId: String,
    private val handler: Handler,
    private val events: Events
) {

    interface Events {
        fun onBokehReady()
        fun onBokehFailed(message: String)

        /** Fotografia nu a putut fi făcută. Reușita se vede prin sosirea imaginii JPEG. */
        fun onBokehCaptureFailed()
    }

    private val executor = Executor { handler.post(it) }
    private var session: CameraExtensionSession? = null

    /** Setările pe care extensia le acceptă; restul sunt hotărâte de telefon. */
    private val keys: Set<CaptureRequest.Key<*>> = try {
        if (Build.VERSION.SDK_INT >= 33) {
            manager.getCameraExtensionCharacteristics(cameraId).getAvailableCaptureRequestKeys(BokehSupport.EXTENSION)
        } else {
            emptySet()
        }
    } catch (e: Exception) {
        emptySet()
    }

    fun open(device: CameraDevice, preview: Surface, jpeg: Surface) {
        try {
            val config = ExtensionSessionConfiguration(
                BokehSupport.EXTENSION,
                listOf(OutputConfiguration(preview), OutputConfiguration(jpeg)),
                executor,
                object : CameraExtensionSession.StateCallback() {
                    override fun onConfigured(s: CameraExtensionSession) {
                        session = s
                        events.onBokehReady()
                    }

                    override fun onConfigureFailed(s: CameraExtensionSession) {
                        events.onBokehFailed("Modul Bokeh nu a putut fi pornit")
                    }
                }
            )
            device.createExtensionSession(config)
        } catch (e: Exception) {
            events.onBokehFailed("Modul Bokeh nu a putut fi pornit: ${e.message}")
        }
    }

    fun applyPreview(device: CameraDevice, preview: Surface, zoom: Float) {
        val s = session ?: return
        try {
            val b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(preview)
            setZoom(b, zoom)
            s.setRepeatingRequest(b.build(), executor, object : CameraExtensionSession.ExtensionCaptureCallback() {})
        } catch (e: Exception) {
            // sesiunea a fost închisă între timp
        }
    }

    fun capture(device: CameraDevice, jpeg: Surface, zoom: Float, jpegOrientation: Int) {
        val s = session ?: throw IllegalStateException("sesiunea Bokeh nu e pregătită")
        val b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        b.addTarget(jpeg)
        b.set(CaptureRequest.JPEG_QUALITY, 97.toByte())
        b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
        setZoom(b, zoom)
        s.capture(b.build(), executor, object : CameraExtensionSession.ExtensionCaptureCallback() {
            override fun onCaptureFailed(session: CameraExtensionSession, request: CaptureRequest) {
                events.onBokehCaptureFailed()
            }

            override fun onCaptureSequenceAborted(session: CameraExtensionSession, sequenceId: Int) {
                events.onBokehCaptureFailed()
            }
        })
    }

    fun close() {
        try {
            session?.close()
        } catch (ignored: Exception) {
        }
        session = null
    }

    private fun setZoom(b: CaptureRequest.Builder, zoom: Float) {
        if (Build.VERSION.SDK_INT >= 30 && keys.contains(CaptureRequest.CONTROL_ZOOM_RATIO)) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        }
    }
}
