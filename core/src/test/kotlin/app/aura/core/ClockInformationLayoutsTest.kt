package app.aura.core

import app.aura.core.studio.ClockDesign
import app.aura.core.studio.ClockInformationField
import app.aura.core.studio.ClockInformationLayouts
import app.aura.core.studio.ClockInformationMode
import app.aura.core.studio.ClockInformationSet
import app.aura.core.studio.ClockInformationStyle
import app.aura.core.studio.InformationBounds
import app.aura.core.studio.editInformation
import app.aura.core.studio.fields
import app.aura.core.studio.informationStyle
import app.aura.core.studio.selectedInformationMode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockInformationLayoutsTest {
    private val width = 72
    private val height = 156
    private val dark = IntArray(width * height) { 0xFF11171B.toInt() }

    @Test
    fun everySetPlacesTheFieldsOfEachMode() {
        val largeClock = listOf(InformationBounds(0.02f, -0.1f, 0.98f, 1.05f))
        val layouts = ClockInformationLayouts(width, height, dark, null, largeClock)
        val arrangements = ClockInformationSet.entries.map { set ->
            layouts.arrange(ClockInformationMode.FULL, set).also { arrangement ->
                assertEquals(set, arrangement.set)
                assertFalse(arrangement.automatic)
                ClockInformationMode.entries.forEach { mode ->
                    val styles = arrangement.styles[mode].orEmpty()
                    assertEquals(mode.fields().toSet(), styles.keys)
                    assertTrue(styles.values.all { it.x in 0.05f..0.95f && it.y in 0.09f..0.94f })
                }
            }
        }
        assertEquals(5, arrangements.map { it.styles[ClockInformationMode.FULL] }.distinct().size)
    }

    @Test
    fun automaticInkReactsToLightAndDarkPhotos() {
        val light = IntArray(dark.size) { 0xFFEEEEEE.toInt() }
        fun ink(pixels: IntArray): Int {
            val arrangement = ClockInformationLayouts(width, height, pixels, null, emptyList()).arrange(ClockInformationMode.FULL)
            assertTrue(arrangement.automatic)
            return arrangement.styles.getValue(ClockInformationMode.FULL).getValue(ClockInformationField.DATE).color!!
        }
        val redOnDark = ink(dark) ushr 16 and 255
        val redOnLight = ink(light) ushr 16 and 255
        assertTrue(redOnDark > 220)
        assertTrue(redOnLight < 70)
    }

    @Test
    fun selectionAdaptsToThePositionOfTheClock() {
        val clear = ClockInformationLayouts(width, height, dark, null, emptyList())
            .arrange(ClockInformationMode.FULL)
        val clock = listOf(InformationBounds(0f, 0.02f, 1f, 0.62f))
        val blocked = ClockInformationLayouts(width, height, dark, null, clock)
            .arrange(ClockInformationMode.FULL)
        assertNotEquals(clear.set, blocked.set)
        assertTrue(blocked.styles.getValue(ClockInformationMode.FULL).values.all { it.y > 0.64f })
    }

    @Test
    fun manualEditsBelongToOneModeAndSaveWithTheSet() {
        val old = Json.decodeFromString<ClockDesign>("""{"informationMode":null,"showDate":false,"showWeather":true}""")
        assertEquals(ClockInformationMode.ORIGINAL, old.selectedInformationMode)
        assertFalse(old.showDate)
        val temp = ClockInformationField.TEMPERATURE
        val arrangement = ClockInformationLayouts(width, height, dark, null, emptyList()).arrange(ClockInformationMode.ESSENTIAL)
        val ambientBefore = arrangement.styles.getValue(ClockInformationMode.AMBIENT).getValue(temp)
        val style = ClockInformationStyle(x = 0.37f, opacity = 64)
        val edited = old.copy(informationMode = ClockInformationMode.ESSENTIAL, informationArrangement = arrangement)
            .editInformation(temp, style)
        val saved = Json.decodeFromString<ClockDesign>(Json.encodeToString(edited))
        assertEquals(style, saved.informationStyle(temp))
        assertEquals(ambientBefore, saved.copy(informationMode = ClockInformationMode.AMBIENT).informationStyle(temp))
        assertFalse(saved.informationArrangement!!.automatic)
    }
}
