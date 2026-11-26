package app.aura.core

import app.aura.core.studio.AutoDesign
import app.aura.core.studio.ClockDesign
import app.aura.core.studio.ClockMaterial
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
        assertTrue(first.all { it.design.artistic.enabled && it.design.informationMode == ClockInformationMode.PURE })
        assertFalse(first.any { it.design.behindSubject })
    }

    @Test
    fun savedStyleKeepsTypographyAndMaterialAndOnlyShowsClock() {
        val reference = ClockDesign(customFont = "clock_oswald", centerY = 0.54f, showColon = false,
            stretchY = 1.9f, material = ClockMaterial.GLASS, informationMode = ClockInformationMode.FULL)
        val proposals = AutoDesign.propose(pixels, width, height, null, 0, reference)
        assertEquals(reference.centerY, proposals.first().design.centerY)
        assertTrue(proposals.all {
            it.design.customFont == reference.customFont && it.design.material == reference.material &&
                it.design.informationMode == ClockInformationMode.PURE && !it.design.showWeather && !it.design.showDate
        })
    }

    @Test
    fun successiveRoundsWithSavedStyleReplaceAllThreeProposals() {
        val reference = ClockDesign(centerX = 0.5f, centerY = 0.5f, stretchX = 0.5f, stretchY = 2.5f)
        val rounds = (0..16).map { round ->
            AutoDesign.propose(pixels, width, height, null, round, reference).map { it.design }.toSet()
        }
        assertTrue(rounds.all { it.size == 3 })
        assertTrue(rounds.zipWithNext().all { (previous, next) -> previous.intersect(next).isEmpty() })
    }

    @Test
    fun savedStyleOffersPhotoSpecificContrast() {
        val reference = ClockDesign(customFont = "clock_oswald")
        val light = IntArray(pixels.size) { 0xFFEEEEEE.toInt() }
        val darkDesign = AutoDesign.propose(pixels, width, height, null, 0, reference).last().design
        val lightDesign = AutoDesign.propose(light, width, height, null, 0, reference).last().design
        assertNotEquals(darkDesign.color, lightDesign.color)
        assertEquals(reference.customFont, lightDesign.customFont)
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
