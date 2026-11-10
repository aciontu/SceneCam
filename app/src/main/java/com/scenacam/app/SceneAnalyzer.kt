package com.scenacam.app

import android.media.Image
import android.media.ImageReader
import android.os.SystemClock
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions

data class AnalysisResult(
    val stats: FrameStats,
    val faces: List<FaceBox>,
    val labels: Map<String, Float>
)

/**
 * Analizează cadrele din previzualizare direct pe telefon (fără internet):
 * - recunoaștere de obiecte/scene (ML Kit Image Labeling, model inclus în aplicație)
 * - detecție de fețe (ML Kit Face Detection, model inclus în aplicație)
 * - statistici de luminanță (umbre, zone arse)
 */
class SceneAnalyzer(
    /** Cu câte grade trebuie rotit cadrul ca să fie "în picioare". */
    private val rotationProvider: () -> Int,
    /** Apelat pe firul principal. */
    private val onResult: (AnalysisResult) -> Unit
) : ImageReader.OnImageAvailableListener {

    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder().setConfidenceThreshold(0.55f).build()
    )
    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.08f)
            .build()
    )

    @Volatile
    private var busy = false

    @Volatile
    private var closed = false
    private var lastRun = 0L

    override fun onImageAvailable(reader: ImageReader) {
        val image: Image = try {
            reader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return

        val now = SystemClock.elapsedRealtime()
        if (closed || busy || now - lastRun < INTERVAL_MS) {
            image.close()
            return
        }
        busy = true
        lastRun = now

        try {
            val rotation = ((rotationProvider() % 360) + 360) % 360
            val grid = lumaGrid(image)
            val width = image.width
            val height = image.height
            val uprightW = if (rotation == 90 || rotation == 270) height else width
            val uprightH = if (rotation == 90 || rotation == 270) width else height

            val input = InputImage.fromMediaImage(image, rotation)
            val labelsTask = labeler.process(input)
            val facesTask = faceDetector.process(input)

            Tasks.whenAllComplete(labelsTask, facesTask).addOnCompleteListener {
                image.close()
                val labels = if (labelsTask.isSuccessful) {
                    labelsTask.result.associate { it.text to it.confidence }
                } else {
                    emptyMap()
                }
                val faces = if (facesTask.isSuccessful) {
                    facesTask.result.map { face ->
                        val b = face.boundingBox
                        val upright = NormRect(
                            b.left.toFloat() / uprightW,
                            b.top.toFloat() / uprightH,
                            b.right.toFloat() / uprightW,
                            b.bottom.toFloat() / uprightH
                        ).clamped()
                        val sensorRect = upright.uprightToSensor(rotation)
                        FaceBox(sensorRect, grid.meanIn(sensorRect))
                    }
                } else {
                    emptyList()
                }
                busy = false
                if (!closed) onResult(AnalysisResult(grid.stats(), faces, labels))
            }
        } catch (e: Exception) {
            try {
                image.close()
            } catch (ignored: Exception) {
            }
            busy = false
        }
    }

    fun close() {
        closed = true
        try {
            labeler.close()
        } catch (ignored: Exception) {
        }
        try {
            faceDetector.close()
        } catch (ignored: Exception) {
        }
    }

    private fun lumaGrid(image: Image): LumaGrid {
        val cols = 64
        val rows = 48
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = image.width
        val h = image.height
        val limit = buffer.limit()
        val data = IntArray(cols * rows)
        var k = 0
        for (r in 0 until rows) {
            val y = (r * h / rows + h / (2 * rows)).coerceAtMost(h - 1)
            for (c in 0 until cols) {
                val x = (c * w / cols + w / (2 * cols)).coerceAtMost(w - 1)
                val index = y * rowStride + x * pixelStride
                data[k++] = if (index < limit) buffer.get(index).toInt() and 0xFF else 0
            }
        }
        return LumaGrid(cols, rows, data)
    }

    companion object {
        private const val INTERVAL_MS = 450L
    }
}
