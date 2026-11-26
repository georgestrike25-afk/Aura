package app.aura.core

import app.aura.core.studio.AutoDesign
import app.aura.core.studio.ClockInformationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDesignTest {
    private val width = 72
    private val height = 156
    private val pixels = IntArray(width * height) { 0xFF202326.toInt() }

    @Test
    fun threeDistinctEditableDesignsAndNewAlternatives() {
        val first = AutoDesign.propose(pixels, width, height, null, 0)
        val next = AutoDesign.propose(pixels, width, height, null, 1)
        assertEquals(3, first.map { it.design }.distinct().size)
        assertNotEquals(first, next)
        assertTrue(first.all { it.design.artistic.enabled && it.design.informationMode == ClockInformationMode.ESSENTIAL })
        assertFalse(first.any { it.design.behindSubject })
    }

    @Test
    fun centralMaskSupportsSeparatedGroups() {
        val mask = IntArray(pixels.size) { i ->
            if (i % width in 30..42 && i / width >= 38) 0xFF000000.toInt() else 0
        }
        val depth = AutoDesign.propose(pixels, width, height, mask, 0).single { it.design.behindSubject }.design
        assertTrue(depth.spread > 0f)
        assertFalse(depth.showColon)
    }
}
