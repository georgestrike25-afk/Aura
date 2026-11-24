package app.aura.core

import app.aura.core.studio.ClockMaterial
import app.aura.core.studio.Fusion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionTest {
    private val w = 64
    private val h = 128

    /** Imagen sintética: cielo [top] arriba, suelo [bottom] abajo y un sol claro en la esquina [sunRight]. */
    private fun image(top: Int, bottom: Int, sunRight: Boolean = true) = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        when {
            sunRight && x > w * 0.8 && y < h * 0.12 -> 0xFFFFFFF0.toInt()
            !sunRight && x < w * 0.2 && y < h * 0.12 -> 0xFFFFFFF0.toInt()
            y < h / 2 -> top
            else -> bottom
        }
    }

    @Test
    fun `dominant colours and light direction are detected`() {
        val t = Fusion.analyze(image(0xFF1E5AA8.toInt(), 0xFF2F7F3A.toInt()), w, h)
        assertTrue(t.palette.isNotEmpty())
        assertEquals(1f, t.weights.sum(), 0.001f)
        // El sol arriba a la derecha queda arriba-derecha del reloj: ángulo entre 270 y 360.
        assertTrue("ángulo ${t.lightAngle}", t.lightAngle in 271..359)
        val left = Fusion.analyze(image(0xFF1E5AA8.toInt(), 0xFF2F7F3A.toInt(), sunRight = false), w, h)
        assertTrue("ángulo ${left.lightAngle}", left.lightAngle in 181..269)
    }

    @Test
    fun `harmony picks material by mood and readable colour`() {
        val sea = Fusion.harmonize(Fusion.analyze(image(0xFF1FA3C8.toInt(), 0xFF0B6E8C.toInt()), w, h))
        assertEquals(ClockMaterial.AQUA, sea.material)
        val night = Fusion.harmonize(Fusion.analyze(image(0xFF1A0E33.toInt(), 0xFF2A1450.toInt(), sunRight = false), w, h))
        assertEquals(ClockMaterial.AETHER, night.material)
        // Fondo oscuro detrás del reloj → números claros con buen contraste.
        assertTrue(Fusion.luminance(night.color) > 0.5f)
        val snow = Fusion.analyze(image(0xFFE4EEF8.toInt(), 0xFFF2F6FA.toInt()), w, h)
        val bright = Fusion.harmonize(snow)
        assertTrue("contraste ${Fusion.contrastRatio(Fusion.luminance(bright.color), snow.regionBrightness)}",
            Fusion.contrastRatio(Fusion.luminance(bright.color), snow.regionBrightness) >= 3f)
        assertTrue(bright.reasons.isNotEmpty())
    }
}
