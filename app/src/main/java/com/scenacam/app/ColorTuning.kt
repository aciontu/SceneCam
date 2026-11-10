package com.scenacam.app

import kotlin.math.abs

/** Stilul de culoare ales de utilizator, aplicat fotografiei salvate. */
enum class PhotoStyle(val title: String) {
    /** Exact ce produce camera, fără nicio prelucrare. */
    ORIGINAL("Original"),

    /** Culori mai saturate și contrast mai mare. */
    VIVID("Viu"),

    /** Imagine mai luminoasă și mai blândă, potrivită pentru oameni. */
    BRIGHT("Luminos")
}

/**
 * Reglaj de culoare: saturație, contrast, luminozitate (toate 1 = neschimbat)
 * și căldură (0 = neschimbat, pozitiv = mai cald).
 */
data class ColorTune(
    val saturation: Float,
    val contrast: Float,
    val brightness: Float,
    val warmth: Float
) {
    val isIdentity: Boolean
        get() = abs(saturation - 1f) < 0.005f && abs(contrast - 1f) < 0.005f &&
            abs(brightness - 1f) < 0.005f && abs(warmth) < 0.002f

    /** Matrice 4x5 în formatul android.graphics.ColorMatrix (decalaje pe scara 0..255). */
    fun matrix(): FloatArray {
        val lr = 0.213f
        val lg = 0.715f
        val lb = 0.072f
        val s = saturation
        // Saturație
        val m = arrayOf(
            floatArrayOf(lr * (1 - s) + s, lg * (1 - s), lb * (1 - s)),
            floatArrayOf(lr * (1 - s), lg * (1 - s) + s, lb * (1 - s)),
            floatArrayOf(lr * (1 - s), lg * (1 - s), lb * (1 - s) + s)
        )
        val o = floatArrayOf(0f, 0f, 0f)
        // Contrast în jurul griului mediu, apoi luminozitate
        for (r in 0..2) {
            for (c in 0..2) m[r][c] *= contrast * brightness
            o[r] = 128f * (1f - contrast) * brightness
        }
        // Căldură: mai mult roșu, mai puțin albastru
        val gains = floatArrayOf(1f + warmth, 1f, 1f - warmth)
        for (r in 0..2) {
            for (c in 0..2) m[r][c] *= gains[r]
            o[r] *= gains[r]
        }
        return floatArrayOf(
            m[0][0], m[0][1], m[0][2], 0f, o[0],
            m[1][0], m[1][1], m[1][2], 0f, o[1],
            m[2][0], m[2][1], m[2][2], 0f, o[2],
            0f, 0f, 0f, 1f, 0f
        )
    }

    companion object {
        val IDENTITY = ColorTune(1f, 1f, 1f, 0f)

        /** Reglajul pentru o fotografie: ce cere scena, combinat cu stilul ales. */
        fun forShot(scene: Scene, style: PhotoStyle): ColorTune {
            if (style == PhotoStyle.ORIGINAL) return IDENTITY

            val people = scene == Scene.PORTRAIT || scene == Scene.NIGHT_PORTRAIT || scene == Scene.BACKLIT

            // Ce cere scena.
            val base = when (scene) {
                Scene.LANDSCAPE -> ColorTune(1.12f, 1.06f, 1f, 0f)
                Scene.SUNSET -> ColorTune(1.15f, 1.05f, 1f, 0.04f)
                Scene.FOOD -> ColorTune(1.12f, 1.02f, 1.03f, 0.03f)
                Scene.GREENERY -> ColorTune(1.15f, 1.04f, 1f, 0f)
                Scene.SNOW -> ColorTune(1.04f, 1.05f, 1.02f, -0.02f)
                Scene.PET -> ColorTune(1.05f, 1.04f, 1f, 0f)
                Scene.NIGHT, Scene.LOW_LIGHT -> ColorTune(1.05f, 1.05f, 1f, 0f)
                Scene.PORTRAIT, Scene.NIGHT_PORTRAIT, Scene.BACKLIT -> ColorTune(1f, 1f, 1.02f, 0.01f)
                Scene.AUTO -> IDENTITY
            }

            // Ce adaugă stilul. La oameni saturația crește mai puțin, ca pielea să rămână naturală.
            return when (style) {
                PhotoStyle.VIVID -> ColorTune(
                    base.saturation * (if (people) 1.08f else 1.18f),
                    base.contrast * 1.08f,
                    base.brightness,
                    base.warmth
                )
                PhotoStyle.BRIGHT -> ColorTune(
                    base.saturation * 1.05f,
                    base.contrast * 0.97f,
                    base.brightness * 1.07f,
                    base.warmth + 0.01f
                )
                PhotoStyle.ORIGINAL -> IDENTITY
            }
        }
    }
}
