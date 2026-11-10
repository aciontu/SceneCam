package com.scenacam.app

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Creierul aplicației: primește ce "vede" telefonul (statistici de lumină, fețe, etichete AI,
 * valorile alese de expunerea automată, cât de stabil e ținut telefonul) și decide
 * scena + setările cu care se face fotografia.
 *
 * Fișierul nu depinde de Android, deci poate fi testat pe orice calculator.
 */

enum class Scene(val title: String, val icon: String) {
    AUTO("Standard", "✨"),
    PORTRAIT("Portret", "🙂"),
    NIGHT_PORTRAIT("Portret de noapte", "🌃"),
    BACKLIT("Contralumină", "🌤"),
    NIGHT("Noapte", "🌙"),
    LOW_LIGHT("Lumină slabă", "🕯"),
    LANDSCAPE("Peisaj", "🏞"),
    SUNSET("Apus / răsărit", "🌅"),
    FOOD("Mâncare", "🍽"),
    GREENERY("Flori și plante", "🌸"),
    SNOW("Zăpadă", "❄"),
    PET("Animale", "🐾")
}

enum class Awb { AUTO, DAYLIGHT, CLOUDY }

enum class Steadiness(val title: String) {
    TRIPOD("trepied"), STEADY("stabil"), NORMAL("din mână"), SHAKY("mișcat")
}

/** Dreptunghi în coordonate normalizate 0..1. */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = max(0f, width) * max(0f, height)

    fun clamped(): NormRect = NormRect(
        left.coerceIn(0f, 1f), top.coerceIn(0f, 1f), right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f)
    )

    /** Din imaginea "în picioare" (rotită cu [rotation] grade în sensul acelor de ceas) în coordonatele senzorului. */
    fun uprightToSensor(rotation: Int): NormRect = when (((rotation % 360) + 360) % 360) {
        90 -> NormRect(top, 1f - right, bottom, 1f - left)
        180 -> NormRect(1f - right, 1f - bottom, 1f - left, 1f - top)
        270 -> NormRect(1f - bottom, left, 1f - top, right)
        else -> this
    }

    /** Inversa lui [uprightToSensor]. */
    fun sensorToUpright(rotation: Int): NormRect = uprightToSensor(360 - (((rotation % 360) + 360) % 360))
}

/** Grilă mică de luminanță (0..255), în orientarea senzorului. */
class LumaGrid(val cols: Int, val rows: Int, val data: IntArray) {

    fun stats(): FrameStats {
        if (data.isEmpty()) return FrameStats(128f, 0f, 0f)
        var sum = 0L
        var shadows = 0
        var highlights = 0
        for (v in data) {
            sum += v
            if (v < 32) shadows++
            if (v > 232) highlights++
        }
        val n = data.size.toFloat()
        return FrameStats(sum / n, shadows / n, highlights / n)
    }

    fun meanIn(rect: NormRect): Float {
        val r = rect.clamped()
        val c0 = (r.left * cols).toInt().coerceIn(0, cols - 1)
        val c1 = (r.right * cols).toInt().coerceIn(c0, cols - 1)
        val r0 = (r.top * rows).toInt().coerceIn(0, rows - 1)
        val r1 = (r.bottom * rows).toInt().coerceIn(r0, rows - 1)
        var sum = 0L
        var count = 0
        for (y in r0..r1) for (x in c0..c1) {
            sum += data[y * cols + x]
            count++
        }
        return if (count == 0) 128f else sum.toFloat() / count
    }
}

data class FrameStats(val meanLuma: Float, val shadowFrac: Float, val highlightFrac: Float)

/** O față detectată: poziția în coordonatele senzorului și luminanța medie pe față. */
data class FaceBox(val sensorRect: NormRect, val luma: Float)

data class SceneInput(
    val stats: FrameStats,
    val faces: List<FaceBox>,
    val labels: Map<String, Float>,
    /** ISO efectiv ales de expunerea automată (0 = necunoscut). */
    val iso: Int,
    /** Timpul de expunere ales de expunerea automată, în nanosecunde (0 = necunoscut). */
    val exposureNs: Long,
    val aperture: Float,
    /** Viteza unghiulară netezită de la giroscop, rad/s. */
    val gyro: Float
) {
    val aeValid: Boolean get() = iso > 0 && exposureNs > 0
}

data class DeviceCaps(
    val manualSensor: Boolean,
    val isoMin: Int,
    val isoMax: Int,
    val expMinNs: Long,
    val expMaxNs: Long,
    val evStep: Float,
    val evMinSteps: Int,
    val evMaxSteps: Int,
    val awbModes: Set<Awb>,
    val hdrSceneMode: Boolean
)

data class ShotPlan(
    val scene: Scene,
    /** Compensarea de expunere, în pașii aparatului. */
    val evSteps: Int,
    /** Aceeași compensare, în trepte EV. */
    val evBias: Float,
    /** ISO manual pentru fotografie, sau null = expunere automată. */
    val manualIso: Int?,
    val manualExposureNs: Long?,
    val awb: Awb,
    val hdr: Boolean,
    /** Zona pe care se măsoară lumina și se focalizează (coordonate senzor), sau null. */
    val meterRect: NormRect?,
    /** Permite cadre mai lente în previzualizare ca expunerea automată să adune mai multă lumină. */
    val lowFps: Boolean,
    val steadiness: Steadiness,
    /** Câte cadre se fac și se combină (mod Noapte). 1 = o singură fotografie. */
    val burst: Int = 1
) {
    val isManual: Boolean get() = manualIso != null && manualExposureNs != null

    companion object {
        val DEFAULT = ShotPlan(Scene.AUTO, 0, 0f, null, null, Awb.AUTO, false, null, false, Steadiness.NORMAL)
    }
}

class SceneEngine {

    private var stable = Scene.AUTO
    private var candidate = Scene.AUTO
    private var votes = 0

    /** Clasifică scena și o stabilizează: schimbă scena doar după mai multe analize la rând identice. */
    fun update(input: SceneInput): Scene {
        val raw = classify(input)
        when (raw) {
            stable -> {
                candidate = raw
                votes = 0
            }
            candidate -> {
                votes++
                if (votes >= 2) {
                    stable = raw
                    votes = 0
                }
            }
            else -> {
                candidate = raw
                votes = 1
            }
        }
        return stable
    }

    fun classify(input: SceneInput): Scene {
        val ev = ev100(input)
        val s = input.stats
        val face = input.faces.maxByOrNull { it.sensorRect.area }

        if (face != null && face.sensorRect.area >= MIN_FACE_AREA) {
            if (ev != null && ev < 4f) return Scene.NIGHT_PORTRAIT
            if (ev == null && s.meanLuma < 40f) return Scene.NIGHT_PORTRAIT
            if (face.luma < s.meanLuma * 0.62f && s.highlightFrac > 0.06f) return Scene.BACKLIT
            return Scene.PORTRAIT
        }

        if (ev != null) {
            if (ev < 2.5f) return Scene.NIGHT
            if (ev < 5.5f) return Scene.LOW_LIGHT
        } else if (s.meanLuma < 30f) {
            return Scene.NIGHT
        }

        if (bestLabel(input.labels, SUNSET_LABELS) >= 0.6f) return Scene.SUNSET
        if (bestLabel(input.labels, FOOD_LABELS) >= 0.65f) return Scene.FOOD
        if (bestLabel(input.labels, PET_LABELS) >= 0.7f) return Scene.PET
        if (bestLabel(input.labels, SNOW_LABELS) >= 0.65f && (ev == null || ev >= 9f)) return Scene.SNOW
        if (s.shadowFrac > 0.28f && s.highlightFrac > 0.10f) return Scene.BACKLIT
        val landscape = bestLabel(input.labels, LANDSCAPE_LABELS)
        val greenery = bestLabel(input.labels, GREENERY_LABELS)
        if (greenery >= 0.65f && greenery >= landscape) return Scene.GREENERY
        if (landscape >= 0.6f && (ev == null || ev >= 8f)) return Scene.LANDSCAPE
        return Scene.AUTO
    }

    fun plan(scene: Scene, input: SceneInput, caps: DeviceCaps): ShotPlan {
        val s = input.stats
        val steadiness = steadiness(input.gyro)
        val faceScene = scene == Scene.PORTRAIT || scene == Scene.NIGHT_PORTRAIT || scene == Scene.BACKLIT
        val face = if (faceScene) input.faces.maxByOrNull { it.sensorRect.area } else null

        var bias = when (scene) {
            Scene.PORTRAIT -> 0.3f
            Scene.NIGHT_PORTRAIT -> 0.3f
            Scene.BACKLIT -> if (face != null) 0.5f else 0.3f
            Scene.NIGHT -> -0.3f
            Scene.LOW_LIGHT -> 0f
            Scene.LANDSCAPE -> -0.3f
            Scene.SUNSET -> -0.7f
            Scene.FOOD -> 0.3f
            // Zăpada păcălește expunerea automată și iese gri; o împingem spre alb.
            Scene.SNOW -> 0.7f
            Scene.GREENERY -> 0f
            Scene.PET -> 0f
            Scene.AUTO -> 0f
        }
        // Protejează zonele foarte luminoase (cer, becuri) când nu fotografiem o față.
        val protectHighlights = scene == Scene.AUTO || scene == Scene.LANDSCAPE ||
            scene == Scene.LOW_LIGHT || scene == Scene.FOOD || scene == Scene.GREENERY || scene == Scene.PET
        if (protectHighlights && s.highlightFrac > 0.15f) bias -= 0.3f

        val step = if (caps.evStep > 0f) caps.evStep else 1f
        val evSteps = (bias / step).roundToInt().coerceIn(caps.evMinSteps, caps.evMaxSteps)
        val evBias = evSteps * step

        val awb = if (scene == Scene.SUNSET && Awb.DAYLIGHT in caps.awbModes) Awb.DAYLIGHT else Awb.AUTO

        val hdr = caps.hdrSceneMode && (scene == Scene.BACKLIT ||
            (scene == Scene.LANDSCAPE && s.shadowFrac > 0.2f && s.highlightFrac > 0.08f))

        var manualIso: Int? = null
        var manualExp: Long? = null
        if (!hdr && caps.manualSensor && input.aeValid && caps.isoMin > 0 && caps.isoMax >= caps.isoMin) {
            val dark = scene == Scene.NIGHT || scene == Scene.LOW_LIGHT || scene == Scene.NIGHT_PORTRAIT
            val baseT = input.exposureNs / 1e9
            // Expunerea totală măsurată de aparat (ISO x secunde). Include deja compensarea EV din previzualizare.
            var target = input.iso * baseT
            var gain = 1.0
            if (dark && s.meanLuma < 35f) {
                // Expunerea automată a ajuns la limită și imaginea e încă prea întunecată.
                gain = (50.0 / max(s.meanLuma, 12f)).coerceIn(1.0, 2.5)
                target *= gain
            }
            val ev = ev100(input)
            val tMax = min(maxShutterSeconds(scene, steadiness, ev), caps.expMaxNs / 1e9)
            val tMin = caps.expMinNs / 1e9
            // Cel mai mic ISO posibil în limita timpului de expunere sigur pentru scenă.
            var t = min(tMax, target / caps.isoMin)
            if (t < tMin) t = tMin
            val iso = (target / t).coerceIn(caps.isoMin.toDouble(), caps.isoMax.toDouble())
            val stops = abs(log2(t / baseT))
            if (stops >= 0.4 || gain > 1.05) {
                manualIso = iso.roundToInt()
                manualExp = (t * 1e9).roundToLong().coerceIn(caps.expMinNs, caps.expMaxNs)
            }
        }

        val lowFps = scene == Scene.NIGHT || scene == Scene.LOW_LIGHT || scene == Scene.NIGHT_PORTRAIT

        return ShotPlan(
            scene = scene,
            evSteps = evSteps,
            evBias = evBias,
            manualIso = manualIso,
            manualExposureNs = manualExp,
            awb = awb,
            hdr = hdr,
            meterRect = face?.sensorRect,
            lowFps = lowFps,
            steadiness = steadiness,
            burst = if (hdr) 1 else when (scene) {
                Scene.NIGHT -> 6
                Scene.LOW_LIGHT, Scene.NIGHT_PORTRAIT -> 4
                else -> 1
            }
        )
    }

    companion object {
        const val MIN_FACE_AREA = 0.012f

        private val SUNSET_LABELS = setOf("Sunset", "Sunrise", "Dusk", "Dawn", "Afterglow")

        private val FOOD_LABELS = setOf(
            "Food", "Cuisine", "Dish", "Meal", "Fast food", "Vegetable", "Fruit", "Cake", "Pizza", "Bread",
            "Cookie", "Cheeseburger", "Hot dog", "Couscous", "Bento", "Juice", "Coffee", "Wine", "Pie",
            "Supper", "Lunch", "Sushi", "Pasta", "Salad", "Dessert", "Baked goods"
        )

        private val PET_LABELS = setOf("Dog", "Cat", "Pet", "Bird", "Horse", "Puppy", "Kitten")

        private val SNOW_LABELS = setOf("Snow", "Glacier", "Skiing", "Snowboarding", "Sledding", "Ski", "Ice")

        private val GREENERY_LABELS = setOf("Flower", "Petal", "Plant", "Leaf", "Bouquet", "Rose", "Garden")

        private val LANDSCAPE_LABELS = setOf(
            "Sky", "Mountain", "Cliff", "Rock", "Canyon", "Glacier", "Iceberg", "Beach", "Sea", "Ocean",
            "Lake", "River", "Waterfall", "Forest", "Jungle", "Field", "Prairie", "Meadow", "Desert", "Sand",
            "Snow", "Skyline", "Cityscape", "Skyscraper", "Building", "Tower", "Bridge", "Castle", "Palace",
            "Cathedral", "Church", "Temple", "Fort", "Lighthouse", "Pier", "Park", "Garden", "Road",
            "Infrastructure", "Nature", "Landscape", "Cloud", "Tree", "Hill", "Valley", "Vineyard", "Farm",
            "Town", "Village", "Plaza", "Monument", "Ruins", "Architecture", "Street"
        )

        fun bestLabel(labels: Map<String, Float>, wanted: Set<String>): Float {
            var best = 0f
            for ((name, score) in labels) if (name in wanted && score > best) best = score
            return best
        }

        /** Luminozitatea scenei (EV la ISO 100), dedusă din ce a ales expunerea automată. */
        fun ev100(input: SceneInput): Float? {
            if (!input.aeValid) return null
            val n = if (input.aperture > 0f) input.aperture.toDouble() else 1.8
            val t = input.exposureNs / 1e9
            return (log2(n * n / t) - log2(input.iso / 100.0)).toFloat()
        }

        fun steadiness(gyro: Float): Steadiness = when {
            gyro < 0.01f -> Steadiness.TRIPOD
            gyro < 0.05f -> Steadiness.STEADY
            gyro < 0.15f -> Steadiness.NORMAL
            else -> Steadiness.SHAKY
        }

        /** Cel mai lung timp de expunere acceptabil pentru scenă și pentru cât de stabil e telefonul. */
        fun maxShutterSeconds(scene: Scene, steadiness: Steadiness, ev: Float?): Double = when (scene) {
            Scene.NIGHT, Scene.LOW_LIGHT -> when (steadiness) {
                Steadiness.TRIPOD -> 1.0 / 2
                Steadiness.STEADY -> 1.0 / 8
                Steadiness.NORMAL -> 1.0 / 15
                Steadiness.SHAKY -> 1.0 / 30
            }
            Scene.NIGHT_PORTRAIT -> when (steadiness) {
                Steadiness.TRIPOD -> 1.0 / 15
                Steadiness.STEADY -> 1.0 / 20
                Steadiness.NORMAL -> 1.0 / 30
                Steadiness.SHAKY -> 1.0 / 40
            }
            Scene.PORTRAIT, Scene.BACKLIT -> if (ev != null && ev < 7f) 1.0 / 60 else 1.0 / 125
            Scene.LANDSCAPE, Scene.SUNSET -> when (steadiness) {
                Steadiness.TRIPOD -> 1.0 / 4
                Steadiness.STEADY -> 1.0 / 30
                else -> 1.0 / 60
            }
            // Animalele se mișcă: timp scurt, chiar cu prețul unui ISO mai mare.
            Scene.PET -> if (ev != null && ev < 7f) 1.0 / 125 else 1.0 / 500
            Scene.FOOD, Scene.GREENERY, Scene.SNOW, Scene.AUTO -> when (steadiness) {
                Steadiness.TRIPOD, Steadiness.STEADY -> 1.0 / 50
                else -> 1.0 / 80
            }
        }

        fun formatShutter(ns: Long): String {
            if (ns <= 0) return "–"
            val t = ns / 1e9
            return if (t >= 0.4) String.format("%.1f s", t) else "1/${(1.0 / t).roundToInt()} s"
        }
    }
}
