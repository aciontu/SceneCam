package com.scenacam.app

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build

/** Raport text cu tot ce oferă telefonul aplicațiilor de cameră. */
object Diagnostics {

    private val CAPABILITIES = mapOf(
        0 to "BACKWARD_COMPATIBLE", 1 to "MANUAL_SENSOR", 2 to "MANUAL_POST_PROCESSING", 3 to "RAW",
        4 to "PRIVATE_REPROCESSING", 5 to "READ_SENSOR_SETTINGS", 6 to "BURST_CAPTURE",
        7 to "YUV_REPROCESSING", 8 to "DEPTH_OUTPUT", 9 to "HIGH_SPEED_VIDEO", 10 to "MOTION_TRACKING",
        11 to "LOGICAL_MULTI_CAMERA", 12 to "MONOCHROME", 13 to "SECURE_IMAGE_DATA", 14 to "SYSTEM_CAMERA",
        15 to "OFFLINE_PROCESSING", 16 to "ULTRA_HIGH_RESOLUTION_SENSOR", 17 to "REMOSAIC_REPROCESSING",
        18 to "DYNAMIC_RANGE_TEN_BIT", 19 to "STREAM_USE_CASE", 20 to "COLOR_SPACE_PROFILES"
    )

    private val EXTENSIONS = mapOf(0 to "AUTO", 1 to "FACE_RETOUCH", 2 to "BOKEH", 3 to "HDR", 4 to "NIGHT")

    fun report(context: Context): String {
        val sb = StringBuilder()
        sb.append("ScenaCam diagnostic\n")
        sb.append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" · Android ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = try {
            manager.cameraIdList
        } catch (e: Exception) {
            sb.append("Nu pot lista camerele: ").append(e.message).append('\n')
            return sb.toString()
        }

        for (id in ids) {
            sb.append("\n=== Camera ").append(id).append(" ===\n")
            try {
                describe(manager, id, sb)
            } catch (e: Exception) {
                sb.append("eroare: ").append(e.message).append('\n')
            }
        }
        return sb.toString()
    }

    private fun describe(manager: CameraManager, id: String, sb: StringBuilder) {
        val c = manager.getCameraCharacteristics(id)

        val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
            CameraMetadata.LENS_FACING_BACK -> "spate"
            CameraMetadata.LENS_FACING_FRONT -> "față"
            else -> "extern"
        }
        val level = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "?"
        }
        sb.append("orientare: ").append(facing).append(" · nivel: ").append(level).append('\n')

        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        sb.append("capabilități: ")
            .append(caps.joinToString(", ") { CAPABILITIES[it] ?: it.toString() }).append('\n')
        sb.append("senzor manual: ").append(yesNo(caps.contains(1)))
            .append(" · RAW: ").append(yesNo(caps.contains(3)))
            .append(" · post-procesare manuală: ").append(yesNo(caps.contains(2)))
            .append(" · rafală: ").append(yesNo(caps.contains(6))).append('\n')

        sb.append("focale (mm): ")
            .append(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString() ?: "-")
            .append(" · diafragme: ")
            .append(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.joinToString() ?: "-").append('\n')
        sb.append("senzor (mm): ").append(c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: "-")
            .append(" · pixeli: ").append(c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: "-").append('\n')

        sb.append("ISO: ").append(c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: "-")
            .append(" · ISO analogic max: ").append(c.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY) ?: "-")
            .append('\n')
        val exp = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        sb.append("timp expunere: ")
        if (exp == null) {
            sb.append("-")
        } else {
            sb.append(SceneEngine.formatShutter(exp.lower)).append(" … ").append(SceneEngine.formatShutter(exp.upper))
                .append(" (").append(exp.lower).append(" … ").append(exp.upper).append(" ns)")
        }
        sb.append('\n')
        sb.append("compensare EV: ").append(c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: "-")
            .append(" pas ").append(c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP) ?: "-").append('\n')
        sb.append("intervale fps: ")
            .append(c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.joinToString() ?: "-")
            .append('\n')

        sb.append("regiuni AE/AF/AWB: ")
            .append(c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0).append('/')
            .append(c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0).append('/')
            .append(c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AWB) ?: 0).append('\n')
        sb.append("moduri AF: ").append(ints(c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)))
            .append(" · AWB: ").append(ints(c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)))
            .append(" · scene: ").append(ints(c.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)))
            .append('\n')
        sb.append("OIS: ").append(ints(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)))
            .append(" · reducere zgomot: ")
            .append(ints(c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)))
            .append(" · contur: ").append(ints(c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)))
            .append(" · tonemap: ").append(ints(c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)))
            .append('\n')

        if (Build.VERSION.SDK_INT >= 30) {
            sb.append("zoom: ").append(c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: "-").append('\n')
        }
        val physical = c.physicalCameraIds
        if (physical.isNotEmpty()) sb.append("camere fizice: ").append(physical.joinToString()).append('\n')

        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map != null) {
            val jpeg = map.getOutputSizes(ImageFormat.JPEG)
            sb.append("JPEG max: ").append(jpeg?.maxByOrNull { it.width.toLong() * it.height } ?: "-")
            val raw = try {
                map.getOutputSizes(ImageFormat.RAW_SENSOR)
            } catch (e: Exception) {
                null
            }
            sb.append(" · RAW max: ").append(raw?.maxByOrNull { it.width.toLong() * it.height } ?: "-")
            val yuv = map.getOutputSizes(ImageFormat.YUV_420_888)
            sb.append(" · YUV max: ").append(yuv?.maxByOrNull { it.width.toLong() * it.height } ?: "-").append('\n')
            if (Build.VERSION.SDK_INT >= 31) {
                val hi = try {
                    map.getHighResolutionOutputSizes(ImageFormat.JPEG)
                } catch (e: Exception) {
                    null
                }
                if (hi != null && hi.isNotEmpty()) {
                    sb.append("JPEG rezoluție mare: ").append(hi.maxByOrNull { it.width.toLong() * it.height })
                        .append('\n')
                }
            }
        }

        if (Build.VERSION.SDK_INT >= 31) {
            try {
                val ext = manager.getCameraExtensionCharacteristics(id)
                val supported = ext.supportedExtensions
                if (supported.isEmpty()) {
                    sb.append("extensii producător: niciuna\n")
                } else {
                    sb.append("extensii producător: ")
                        .append(supported.joinToString(", ") { EXTENSIONS[it] ?: it.toString() }).append('\n')
                    for (e in supported) {
                        val sizes = try {
                            ext.getExtensionSupportedSizes(e, ImageFormat.JPEG)
                        } catch (ex: Exception) {
                            emptyList()
                        }
                        sb.append("  ").append(EXTENSIONS[e] ?: e.toString()).append(" JPEG max: ")
                            .append(sizes.maxByOrNull { it.width.toLong() * it.height } ?: "-").append('\n')
                    }
                }
            } catch (e: Exception) {
                sb.append("extensii producător: eroare ").append(e.message).append('\n')
            }
        } else {
            sb.append("extensii producător: necesită Android 12+\n")
        }
    }

    private fun yesNo(value: Boolean): String = if (value) "DA" else "NU"

    private fun ints(values: IntArray?): String = values?.joinToString(",", "[", "]") ?: "-"
}
