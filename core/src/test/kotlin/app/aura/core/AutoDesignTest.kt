package app.aura.core

import app.aura.core.studio.AutoDesign
import app.aura.core.studio.ClockDesign
import app.aura.core.studio.ClockInformationMode
import app.aura.core.studio.ClockLayout
import app.aura.core.studio.DigitSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDesignTest {
    private val width = 72
    private val height = 156
    private val pixels = IntArray(width * height) { 0xFF202326.toInt() }

    /** Sujeto (opaco) en la mitad derecha, desde el 40 % del alto hacia abajo. */
    private val mask = IntArray(width * height) { i ->
        if (i % width >= 30 && i / width >= (height * 0.4f).toInt()) 0xFF000000.toInt() else 0
    }

    @Test
    fun threeDistinctClockOnlyDesigns() {
        val p = AutoDesign.propose(pixels, width, height, null, 0)
        assertEquals(listOf("Monumento", "Apilado", "Gigante"), p.map { it.title })
        assertEquals(3, p.map { it.design.customFont }.distinct().size)
        assertEquals(listOf(ClockLayout.HORIZONTAL, ClockLayout.VERTICAL, ClockLayout.HORIZONTAL), p.map { it.design.layout })
        // Solo el reloj: nada de fecha ni clima.
        assertTrue(p.all { !it.design.showDate && !it.design.showWeather && it.design.informationMode == ClockInformationMode.PURE })
        assertFalse(p.any { it.design.behindSubject })
    }

    @Test
    fun designsAreBigAndValid() {
        for (round in 0..7) {
            for (d in AutoDesign.propose(pixels, width, height, null, round).map { it.design }) {
                assertEquals(d, d.normalized())
                assertTrue("reloj chico: ${d.size}", d.size >= 0.3f)
            }
        }
        val giant = AutoDesign.propose(pixels, width, height, null, 0)[2].design
        assertTrue(giant.stretchY >= 1.6f)
    }

    @Test
    fun newRoundsChangeEveryProposal() {
        val a = AutoDesign.propose(pixels, width, height, null, 0).map { it.design }
        val b = AutoDesign.propose(pixels, width, height, null, 1).map { it.design }
        a.zip(b).forEach { (x, y) -> assertNotEquals(x, y) }
    }

    @Test
    fun subjectGoesInFrontAndStackFindsTheFreeSide() {
        val p = AutoDesign.propose(pixels, width, height, mask, 0).map { it.design }
        assertTrue(p.all { it.behindSubject })
        assertTrue("apilado del lado libre (izquierda)", p[1].centerX < 0.5f)
        // El monumento queda justo encima del sujeto, tapado en su base.
        assertTrue(p[0].centerY in 0.2f..0.4f)
    }

    @Test
    fun stackedMinutesUseAccent() {
        val d = AutoDesign.propose(pixels, width, height, null, 0)[1].design
        val minutes = d.artistic.style(DigitSlot.MINUTE_TENS).color
        assertTrue(minutes != null && minutes != d.color)
        assertEquals(null, d.artistic.style(DigitSlot.HOUR_TENS).color)
    }

    @Test
    fun keepsSavedHourFormat() {
        val ref = ClockDesign(hourFormat = app.aura.core.time.HourFormat.H24)
        assertTrue(AutoDesign.propose(pixels, width, height, null, 0, ref).all { it.design.hourFormat == ref.hourFormat })
    }
}
