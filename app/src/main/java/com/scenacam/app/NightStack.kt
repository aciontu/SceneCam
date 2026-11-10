package com.scenacam.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Sursă de pixeli ARGB citită pe fâșii de rânduri, ca să nu ținem imagini întregi în memorie de două ori. */
interface PixelSource {
    val width: Int
    val height: Int
    fun readRows(y: Int, rows: Int, out: IntArray)
}

/**
 * Mod Noapte din mai multe cadre: aliniază fiecare cadru la primul (deplasare pe orizontală și
 * verticală), le mediază ca să scadă zgomotul și apoi deschide umbrele.
 *
 * Unde un cadru diferă mult de primul (ceva s-a mișcat în scenă), pixelul lui este ignorat,
 * ca să nu apară dubluri.
 *
 * Nu depinde de Android, deci poate fi testat pe orice calculator.
 */
class FrameStacker(val width: Int, val height: Int) {

    private val n = width * height
    private var sumR = ShortArray(n)
    private var sumG = ShortArray(n)
    private var sumB = ShortArray(n)
    private var count = ByteArray(n)

    private val w4 = width / 4
    private val h4 = height / 4
    private val w16 = w4 / 4
    private val h16 = h4 / 4
    private var refGray = ByteArray(0)
    private var ref4 = ByteArray(0)
    private var ref16 = ByteArray(0)

    private var lut: IntArray? = null

    /** Câte cadre au fost combinate. */
    var merged = 0
        private set

    /** Deplasarea găsită pentru ultimul cadru adăugat (dx, dy), în pixeli. */
    var lastShiftX = 0
        private set
    var lastShiftY = 0
        private set

    /** Cât au fost deschise umbrele la final (1 = deloc). */
    var lift = 1f
        private set

    /** Adaugă un cadru. Întoarce false dacă a fost respins (nu s-a putut alinia). */
    fun add(src: PixelSource): Boolean {
        if (merged >= MAX_FRAMES || src.width != width || src.height != height) return false
        val gray = grayOf(src)
        if (merged == 0) {
            refGray = gray
            ref4 = box4(gray, width, height)
            ref16 = box4(ref4, w4, h4)
            accumulate(src, gray, 0, 0, true)
            merged = 1
            return true
        }

        val cur4 = box4(gray, width, height)
        val cur16 = box4(cur4, w4, h4)
        // De la grosier la fin: 1/16, apoi 1/4, apoi rezoluția întreagă.
        val coarse = search(ref16, cur16, w16, h16, 0, 0, 6, 1)
        val middle = search(ref4, cur4, w4, h4, coarse[0] * 4, coarse[1] * 4, 3, 2)
        if (middle[2] > REJECT_MEAN_DIFF * 1000) return false
        val fine = search(refGray, gray, width, height, middle[0] * 4, middle[1] * 4, 3, 5)

        lastShiftX = fine[0]
        lastShiftY = fine[1]
        accumulate(src, gray, fine[0], fine[1], false)
        merged++
        return true
    }

    /** Eliberează memoria folosită la aliniere și pregătește curba de luminozitate. */
    fun finish() {
        refGray = ByteArray(0)
        ref4 = ByteArray(0)
        ref16 = ByteArray(0)

        var total = 0L
        var samples = 0
        var i = 0
        while (i < n) {
            val c = count[i].toInt()
            if (c > 0) {
                total += (sumR[i] * 77 + sumG[i] * 150 + sumB[i] * 29) / (c * 256)
                samples++
            }
            i += 7
        }
        val mean = if (samples == 0) 128f else total.toFloat() / samples
        // Factorul care aduce luminanța medie la țintă, pe o curbă care lasă albul pe loc.
        lift = if (mean >= LIFT_BELOW) {
            1f
        } else {
            val m = max(mean, 10f)
            (LIFT_TARGET * (1f - m / 255f) / (m * (1f - LIFT_TARGET / 255f))).coerceIn(1f, MAX_LIFT)
        }

        // Tabel de amplificare în funcție de luminanță (x1024). Aceeași amplificare se aplică pe
        // toate cele trei culori ale pixelului, ca imaginea să nu își piardă culoarea când e deschisă.
        val g = lift
        val table = IntArray(1021)
        for (k in 0..1020) {
            val v = k / 4f
            val gain = if (v < 0.5f) g else (v * g / (1f + (g - 1f) * v / 255f)) / v
            table[k] = (gain * 1024f).roundToInt()
        }
        lut = table
    }

    /** Scrie rezultatul ca pixeli ARGB. Se apelează după [finish]. */
    fun writeRows(y: Int, rows: Int, out: IntArray) {
        val table = lut ?: return
        val sat = ((1f - 0.07f * (lift - 1f)) * 256f).roundToInt().coerceIn(160, 256)
        var idx = y * width
        val end = idx + rows * width
        var o = 0
        while (idx < end) {
            val c = max(1, count[idx].toInt())
            val r4 = sumR[idx] * 4 / c
            val g4 = sumG[idx] * 4 / c
            val b4 = sumB[idx] * 4 / c
            val gain = table[min(1020, (r4 * 77 + g4 * 150 + b4 * 29) shr 8)]
            var r = min(255, (r4 * gain + 2048) shr 12)
            var g = min(255, (g4 * gain + 2048) shr 12)
            var b = min(255, (b4 * gain + 2048) shr 12)
            if (sat < 256) {
                // Umbrele deschise mult ies prea colorate; le temperăm proporțional cu deschiderea.
                val l = (r * 77 + g * 150 + b * 29) shr 8
                r = (l + (((r - l) * sat) shr 8)).coerceIn(0, 255)
                g = (l + (((g - l) * sat) shr 8)).coerceIn(0, 255)
                b = (l + (((b - l) * sat) shr 8)).coerceIn(0, 255)
            }
            out[o] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            idx++
            o++
        }
    }

    fun release() {
        sumR = ShortArray(0)
        sumG = ShortArray(0)
        sumB = ShortArray(0)
        count = ByteArray(0)
        lut = null
    }

    // ------------------------------------------------------------------ intern

    private fun grayOf(src: PixelSource): ByteArray {
        val gray = ByteArray(n)
        val buf = IntArray(width * STRIP)
        var y0 = 0
        while (y0 < height) {
            val rows = min(STRIP, height - y0)
            src.readRows(y0, rows, buf)
            var g = y0 * width
            val total = rows * width
            for (i in 0 until total) {
                val p = buf[i]
                gray[g++] = ((((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8).toByte()
            }
            y0 += rows
        }
        return gray
    }

    private fun accumulate(src: PixelSource, gray: ByteArray, dx: Int, dy: Int, first: Boolean) {
        val buf = IntArray(width * STRIP)
        val xStart = max(0, -dx)
        val xEnd = min(width, width - dx)
        var y0 = 0
        while (y0 < height) {
            val rows = min(STRIP, height - y0)
            src.readRows(y0, rows, buf)
            for (r in 0 until rows) {
                val fy = y0 + r
                val y = fy - dy
                if (y < 0 || y >= height) continue
                var idx = y * width + xStart
                var fidx = r * width + xStart + dx
                var gidx = fy * width + xStart + dx
                for (x in xStart until xEnd) {
                    val ok = first ||
                        abs((gray[gidx].toInt() and 0xFF) - (refGray[idx].toInt() and 0xFF)) <= GHOST_THRESHOLD
                    if (ok) {
                        val p = buf[fidx]
                        sumR[idx] = (sumR[idx] + ((p shr 16) and 0xFF)).toShort()
                        sumG[idx] = (sumG[idx] + ((p shr 8) and 0xFF)).toShort()
                        sumB[idx] = (sumB[idx] + (p and 0xFF)).toShort()
                        count[idx] = (count[idx] + 1).toByte()
                    }
                    idx++
                    fidx++
                    gidx++
                }
            }
            y0 += rows
        }
    }

    /**
     * Caută deplasarea (dx, dy) în jurul centrului dat pentru care cele două imagini se potrivesc cel mai bine.
     * Întoarce [dx, dy, diferența medie x 1000].
     */
    private fun search(a: ByteArray, b: ByteArray, w: Int, h: Int, cx: Int, cy: Int, radius: Int, step: Int): IntArray {
        val margin = max(abs(cx), abs(cy)) + radius + 1
        if (w - 2 * margin < 8 || h - 2 * margin < 8) return intArrayOf(cx, cy, 0)
        var bestDx = cx
        var bestDy = cy
        var best = Long.MAX_VALUE
        var samples = 1L
        for (dy in cy - radius..cy + radius) {
            for (dx in cx - radius..cx + radius) {
                var sum = 0L
                var cnt = 0L
                var y = margin
                while (y < h - margin) {
                    var ia = y * w + margin
                    var ib = (y + dy) * w + margin + dx
                    var x = margin
                    while (x < w - margin) {
                        sum += abs((a[ia].toInt() and 0xFF) - (b[ib].toInt() and 0xFF))
                        cnt++
                        ia += step
                        ib += step
                        x += step
                    }
                    y += step
                }
                // La egalitate preferăm deplasarea mai mică.
                val closer = abs(dx) + abs(dy) < abs(bestDx) + abs(bestDy)
                if (sum < best || (sum == best && closer)) {
                    best = sum
                    bestDx = dx
                    bestDy = dy
                    samples = max(1L, cnt)
                }
            }
        }
        return intArrayOf(bestDx, bestDy, (best * 1000 / samples).toInt())
    }

    /** Micșorează de 4 ori pe fiecare latură, mediind blocuri de 4x4 (reduce și zgomotul). */
    private fun box4(src: ByteArray, w: Int, h: Int): ByteArray {
        val ow = w / 4
        val oh = h / 4
        val out = ByteArray(ow * oh)
        for (oy in 0 until oh) {
            for (ox in 0 until ow) {
                var sum = 0
                val base = oy * 4 * w + ox * 4
                for (j in 0 until 4) {
                    val row = base + j * w
                    sum += (src[row].toInt() and 0xFF) + (src[row + 1].toInt() and 0xFF) +
                        (src[row + 2].toInt() and 0xFF) + (src[row + 3].toInt() and 0xFF)
                }
                out[oy * ow + ox] = (sum shr 4).toByte()
            }
        }
        return out
    }

    companion object {
        const val MAX_FRAMES = 8
        private const val STRIP = 64

        /** Diferență de luminanță peste care pixelul unui cadru e considerat "s-a mișcat ceva". */
        private const val GHOST_THRESHOLD = 40

        /** Diferență medie (la 1/4 din rezoluție) peste care cadrul întreg e respins. */
        private const val REJECT_MEAN_DIFF = 24

        /** Sub această luminanță medie imaginea e deschisă. */
        private const val LIFT_BELOW = 80f

        /** Luminanța medie spre care e adusă o imagine întunecată. */
        private const val LIFT_TARGET = 88f
        private const val MAX_LIFT = 4.5f
    }
}
