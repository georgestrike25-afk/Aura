package app.aura.core

import app.aura.core.studio.ClockMaterial
import app.aura.core.studio.ElementTexture
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElementTextureTest {
    private val size = 128

    @Test
    fun `every material produces a deterministic surface`() {
        ClockMaterial.entries.forEach { m ->
            val t = ElementTexture.generate(m, size)
            assertEquals(size * size, t.size)
            assertArrayEquals("$m determinista", t, ElementTexture.generate(m, size))
            val avgAlpha = t.map { it ushr 24 }.average()
            if (m.isElement) assertTrue("$m alfa $avgAlpha", avgAlpha > 100)
            assertTrue("$m tiene variación", t.toSet().size > 10)
        }
    }

    @Test
    fun `tiles repeat without seams`() {
        ClockMaterial.entries.forEach { m ->
            val t = ElementTexture.generate(m, size)
            fun lum(c: Int) = ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3.0
            // La diferencia entre el borde derecho y el izquierdo debe parecerse a la de dos columnas vecinas.
            val seam = (0 until size).map { y -> kotlin.math.abs(lum(t[y * size]) - lum(t[y * size + size - 1])) }.average()
            val inner = (0 until size).map { y -> kotlin.math.abs(lum(t[y * size + 10]) - lum(t[y * size + 11])) }.average()
            assertTrue("$m costura horizontal $seam vs $inner", seam < inner * 4 + 12)
            // Ignis se ajusta a la altura del glifo y tiene una base distinta de la punta.
            if (m != ClockMaterial.IGNIS) {
                val top = (0 until size).map { x -> kotlin.math.abs(lum(t[x]) - lum(t[(size - 1) * size + x])) }.average()
                val nearby = (0 until size).map { x -> kotlin.math.abs(lum(t[10 * size + x]) - lum(t[11 * size + x])) }.average()
                assertTrue("$m costura vertical $top vs $nearby", top < nearby * 4 + 12)
            }
        }
    }

    @Test
    fun `fire glows from below and water passes light through`() {
        val fire = ElementTexture.generate(ClockMaterial.IGNIS, size)
        fun red(from: Int, until: Int) = fire.sliceArray(from until until).map { it ushr 16 and 255 }.average()
        assertTrue(red(fire.size * 3 / 4, fire.size) > red(0, fire.size / 4) + 50)
        val water = ElementTexture.generate(ClockMaterial.AQUA, size)
        assertTrue(water.any { (it ushr 24) < 180 } && water.any { (it ushr 24) > 210 })
    }

    @Test
    fun `classic surfaces have grain and glass stays translucent`() {
        for (material in listOf(ClockMaterial.METAL, ClockMaterial.GLASS, ClockMaterial.STONE)) {
            val tile = ElementTexture.generate(material, size)
            assertEquals(size * size, tile.size)
            assertTrue(tile.toSet().size > 30)
        }
        assertTrue(ElementTexture.generate(ClockMaterial.GLASS, size).all { (it ushr 24) < 90 })
    }

    @Test
    fun `cracks and veins are thin lines`() {
        val ice = ElementTexture.generate(ClockMaterial.GLACIUS, size)
        val gold = ElementTexture.generate(ClockMaterial.KINTSUGI, size)
        val frostFraction = ice.count { (it ushr 16 and 0xFF) > 215 } / ice.size.toFloat()
        val goldFraction = gold.count { (it ushr 16 and 0xFF) - (it and 0xFF) > 70 } / gold.size.toFloat()
        assertTrue("grietas $frostFraction", frostFraction in 0.005f..0.20f)
        assertTrue("vetas $goldFraction", goldFraction in 0.005f..0.20f)
    }

    @Test
    fun `mist has translucent openings and the matte grain is subtle`() {
        val mist = ElementTexture.generate(ClockMaterial.VENTUS, size)
        assertTrue(mist.any { it ushr 24 < 90 } && mist.any { it ushr 24 > 170 })
        val matte = ElementTexture.generate(ClockMaterial.MINIMAL, size)
        assertTrue(matte.all { it ushr 24 < 12 })
    }
}
