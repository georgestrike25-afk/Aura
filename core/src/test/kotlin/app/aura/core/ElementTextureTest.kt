package app.aura.core

import app.aura.core.studio.ClockMaterial
import app.aura.core.studio.ElementTexture
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElementTextureTest {
    private val size = 128

    private fun coverage(tile: IntArray, color: Int) = tile.count { it == color } / tile.size.toFloat()

    @Test
    fun `every element produces an opaque-enough deterministic tile`() {
        ClockMaterial.entries.filter { it.isElement }.forEach { m ->
            val t = ElementTexture.generate(m, size)
            assertEquals(size * size, t.size)
            assertArrayEquals("$m determinista", t, ElementTexture.generate(m, size))
            val avgAlpha = t.map { it ushr 24 }.average()
            assertTrue("$m alfa $avgAlpha", avgAlpha > 100)
            assertTrue("$m tiene variación", t.toSet().size > 50)
        }
        assertEquals(0, ElementTexture.generate(ClockMaterial.METAL, size).size)
    }

    @Test
    fun `tiles repeat without seams`() {
        ClockMaterial.entries.filter { it.isElement }.forEach { m ->
            val t = ElementTexture.generate(m, size)
            fun lum(c: Int) = ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3.0
            // La diferencia entre el borde derecho y el izquierdo debe parecerse a la de dos columnas vecinas.
            val seam = (0 until size).map { y -> kotlin.math.abs(lum(t[y * size]) - lum(t[y * size + size - 1])) }.average()
            val inner = (0 until size).map { y -> kotlin.math.abs(lum(t[y * size + 10]) - lum(t[y * size + 11])) }.average()
            assertTrue("$m costura $seam vs $inner", seam < inner * 4 + 12)
        }
    }

    @Test
    fun `cracks and veins are thin lines`() {
        val ice = coverage(ElementTexture.generate(ClockMaterial.GLACIUS, size), 0xFFFFFFFF.toInt())
        val gold = ElementTexture.generate(ClockMaterial.KINTSUGI, size).count { (it and 0xFFFFFF) == 0xD4A64A || ((it shr 16 and 0xFF) > 0xD0 && (it and 0xFF) < 0xA0) } / (size * size).toFloat()
        println("GLACIUS grietas $ice, KINTSUGI oro $gold")
        assertTrue("grietas $ice", ice in 0.01f..0.15f)
        assertTrue("vetas $gold", gold in 0.01f..0.15f)
    }
}
