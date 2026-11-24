package app.aura.core

import app.aura.core.studio.ArtisticClock
import app.aura.core.studio.ClockDesign
import app.aura.core.studio.ClockMaterial
import app.aura.core.studio.DigitDepth
import app.aura.core.studio.DigitGroup
import app.aura.core.studio.DigitSlot
import app.aura.core.studio.MagneticGuides
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtisticClockTest {
    @Test
    fun `digits keep their style by slot when the time changes`() {
        val a = ArtisticClock(enabled = true).edit(DigitSlot.MINUTE_UNITS) { it.copy(rotation = 15f, color = 0xFFFF0000.toInt()) }
        assertEquals(mapOf(DigitSlot.HOUR_TENS to '0', DigitSlot.HOUR_UNITS to '9', DigitSlot.MINUTE_TENS to '5', DigitSlot.MINUTE_UNITS to '0'), a.glyphs("09", ":50"))
        assertEquals(mapOf(DigitSlot.HOUR_UNITS to '9', DigitSlot.MINUTE_TENS to '0', DigitSlot.MINUTE_UNITS to '1'), a.glyphs("9", ":01 pm"))
        assertEquals(15f, a.style(DigitSlot.MINUTE_UNITS).rotation)
    }

    @Test
    fun `group edits skip locked digits and lock can always be toggled`() {
        var a = ArtisticClock(enabled = true).setLocked(setOf(DigitSlot.HOUR_TENS), true)
        a = a.edit(DigitGroup.HOURS.slots) { it.copy(scaleX = 2f) }
        assertEquals(1f, a.style(DigitSlot.HOUR_TENS).scaleX)
        assertEquals(2f, a.style(DigitSlot.HOUR_UNITS).scaleX)
        a = a.reset(DigitGroup.ALL.slots)
        assertTrue(a.style(DigitSlot.HOUR_TENS).locked)
        assertEquals(1f, a.style(DigitSlot.HOUR_UNITS).scaleX)
        a = a.setLocked(setOf(DigitSlot.HOUR_TENS), false)
        assertFalse(a.style(DigitSlot.HOUR_TENS).locked)
    }

    @Test
    fun `values are clamped and layer order moves`() {
        val a = ArtisticClock().edit(DigitSlot.HOUR_TENS) { it.copy(scaleX = 99f, offsetX = Float.NaN, skewX = -5f, opacity = 300) }
        val s = a.style(DigitSlot.HOUR_TENS)
        assertEquals(3f, s.scaleX); assertEquals(0f, s.offsetX); assertEquals(-1f, s.skewX); assertEquals(100, s.opacity)
        val b = a.moveLayer(DigitSlot.HOUR_TENS, +2)
        assertEquals(listOf(DigitSlot.HOUR_UNITS, DigitSlot.MINUTE_TENS, DigitSlot.HOUR_TENS, DigitSlot.MINUTE_UNITS), b.drawOrder())
        assertEquals(DigitSlot.entries, ArtisticClock(order = listOf(DigitSlot.HOUR_TENS)).drawOrder())
    }

    @Test
    fun `depth per digit decides whether a subject layer is needed`() {
        val front = ClockDesign(behindSubject = false)
        assertFalse(front.anyBehindSubject())
        val oneBehind = front.copy(artistic = ArtisticClock(enabled = true).edit(DigitSlot.MINUTE_TENS) { it.copy(depth = DigitDepth.BEHIND_SUBJECT) })
        assertTrue(oneBehind.anyBehindSubject())
        assertTrue(oneBehind.artistic.isBehind(DigitSlot.MINUTE_TENS, false))
        assertFalse(oneBehind.artistic.isBehind(DigitSlot.HOUR_TENS, false))
    }

    @Test
    fun `magnetic guides snap within threshold`() {
        assertEquals(100f to 100f, MagneticGuides.snap(104f, listOf(50f, 100f), 6f))
        assertEquals(120f to null, MagneticGuides.snap(120f, listOf(50f, 100f), 6f))
        assertEquals(5f to null, MagneticGuides.snap(5f, emptyList(), 6f))
    }

    @Test
    fun `old projects without artistic fields still load and new materials serialize`() {
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString(ClockDesign.serializer(), """{"size":0.3,"material":"METAL"}""")
        assertFalse(old.artistic.enabled)
        assertNull(old.artistic.digits[DigitSlot.HOUR_TENS])
        val d = ClockDesign().withMaterial(ClockMaterial.KINTSUGI).copy(
            artistic = ArtisticClock(enabled = true).edit(DigitSlot.HOUR_UNITS) { it.copy(material = ClockMaterial.IGNIS) },
        )
        val back = json.decodeFromString(ClockDesign.serializer(), json.encodeToString(ClockDesign.serializer(), d))
        assertEquals(d, back)
        assertTrue(ClockMaterial.AQUA.isElement && !ClockMaterial.METAL.isElement)
    }
}
