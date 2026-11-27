package app.aura.core

import app.aura.core.studio.AutoDesign
import app.aura.core.studio.ClockDesign
import app.aura.core.studio.ClockInformationMode
import app.aura.core.studio.FontMetrics
import app.aura.core.studio.Fusion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

class AutoDesignTest {
    private val width = 72
    private val height = 156

    /** Foto verde azulada oscura con un helecho claro: como la del video del usuario. */
    private val forest = IntArray(width * height) { i ->
        val x = i % width
        val y = i / width
        when {
            x in 30..42 && y > 60 -> 0xFF6FBF7A.toInt() // helecho
            y < 50 -> 0xFF1E3A3F.toInt() // niebla turquesa oscura
            else -> 0xFF14302A.toInt()
        }
    }

    private val mask = IntArray(width * height) { i ->
        if (i % width in 28..44 && i / width >= 60) 0xFF000000.toInt() else 0
    }

    private fun hueDistance(a: Float, b: Float) = min(abs(a - b), 360f - abs(a - b))

    @Test
    fun threeDifferentStylesOnlyTheClock() {
        val p = AutoDesign.propose(forest, width, height, null, 0)
        assertEquals(3, p.size)
        assertEquals(3, p.map { it.title }.distinct().size)
        assertEquals(3, p.map { it.design.customFont }.distinct().size)
        assertTrue(p.all { !it.design.showDate && !it.design.showWeather && it.design.informationMode == ClockInformationMode.PURE })
        p.forEach { assertEquals(it.design, it.design.normalized()) }
        assertFalse(p.any { it.design.behindSubject })
    }

    @Test
    fun roundsAreRandomAndUseManyFonts() {
        val rounds = (0 until 12).map { AutoDesign.propose(forest, width, height, null, it) }
        val fonts = rounds.flatten().mapNotNull { it.design.customFont }.toSet()
        val styles = rounds.flatten().map { it.title }.toSet()
        assertTrue("fuentes distintas: ${fonts.size}", fonts.size >= 12)
        assertEquals(AutoDesign.Style.entries.size, styles.size)
        assertTrue(fonts.all { it in FontMetrics.digits })
        assertNotEquals(rounds[0].map { it.design }, rounds[1].map { it.design })
        // Misma foto y misma vuelta: mismo resultado (se puede volver a una propuesta).
        assertEquals(rounds[3].map { it.design }, AutoDesign.propose(forest, width, height, null, 3).map { it.design })
    }

    @Test
    fun colorsComeFromThePhotoAndStayLegible() {
        val photoHue = AutoDesign.hsl(0xFF1E3A3F.toInt())[0]
        for (round in 0 until 8) {
            for (p in AutoDesign.propose(forest, width, height, null, round)) {
                val d = p.design
                // Nada de blanco puro ni del naranja fijo de antes.
                assertNotEquals(0xFFFFFFFF.toInt(), d.color)
                assertNotEquals(0xFFE8572A.toInt(), d.accentColor)
                // Sobre una foto oscura, cifras claras con buen contraste.
                assertTrue("${p.title}: ${Integer.toHexString(d.color)}", Fusion.contrastRatio(Fusion.luminance(d.color), 0.05f) >= 2.2f)
            }
        }
        // El esquema «tono» usa el color de la foto.
        val (main, accent) = AutoDesign.colors(Fusion.analyze(forest, width, height), 0.08f, AutoDesign.Scheme.TINT)
        val greenish = listOf(AutoDesign.hsl(main)[0], AutoDesign.hsl(accent)[0]).all { hueDistance(it, photoHue) < 60f }
        assertTrue("tono de la foto", greenish)
    }

    @Test
    fun withSubjectTheClockGoesBehindItAndStaysOnScreen() {
        for (round in 0 until 10) {
            for (p in AutoDesign.propose(forest, width, height, mask, round)) {
                val d = p.design
                if (p.title != "Editorial") assertTrue("${p.title} detrás del sujeto", d.behindSubject)
                assertTrue(d.centerY in 0.05f..0.7f)
                assertTrue(d.centerX in 0.15f..0.85f)
            }
        }
    }

    @Test
    fun keepsSavedHourFormat() {
        val ref = ClockDesign(hourFormat = app.aura.core.time.HourFormat.H24)
        assertTrue(AutoDesign.propose(forest, width, height, null, 0, ref).all { it.design.hourFormat == ref.hourFormat })
    }

    private val fire = IntArray(width * height) { i ->
        val y = i / width
        when {
            y > 90 -> 0xFFF07A1E.toInt() // llamas naranjas
            y > 70 -> 0xFFD6401A.toInt()
            else -> 0xFF1A0E0A.toInt() // noche
        }
    }
    private val sea = IntArray(width * height) { i -> if (i / width > 80) 0xFF1F7FA8.toInt() else 0xFF2A9CC7.toInt() }

    @Test
    fun fireSceneUsesAFireFontWithIgnis() {
        for (round in 0 until 5) {
            val first = AutoDesign.propose(fire, width, height, null, round).first().design
            assertEquals("clock_rubikburned", first.customFont)
            assertEquals(app.aura.core.studio.ClockMaterial.IGNIS, first.material)
        }
    }

    @Test
    fun waterSceneUsesAWaterFontWithAqua() {
        val first = AutoDesign.propose(sea, width, height, null, 0).first().design
        assertTrue(first.customFont in setOf("clock_rubikwetpaint", "clock_rubikpuddles"))
        assertEquals(app.aura.core.studio.ClockMaterial.AQUA, first.material)
    }

    @Test
    fun importedThemedFontHasPriority() {
        val burn = AutoDesign.ThemedFont("user:123_Burn.otf", 0.45f, 0.8f, AutoDesign.themeOfName("Burn")!!)
        assertEquals(AutoDesign.Theme.FIRE, burn.theme)
        assertEquals(AutoDesign.Theme.WATER, AutoDesign.themeOfName("WaterSplash"))
        assertEquals(null, AutoDesign.themeOfName("Montserrat"))
        val first = AutoDesign.propose(fire, width, height, null, 0, userFonts = listOf(burn)).first().design
        assertEquals("user:123_Burn.otf", first.customFont)
        // En una escena común no se fuerza nada temático.
        assertTrue(AutoDesign.propose(forest, width, height, null, 0, userFonts = listOf(burn)).none { it.design.customFont == burn.id })
    }

    @Test
    fun sessionSaltAndRecentHistoryGiveVariety() {
        val a = AutoDesign.propose(forest, width, height, null, 0, salt = 1)
        val b = AutoDesign.propose(forest, width, height, null, 0, salt = 2)
        assertNotEquals(a.map { it.design }, b.map { it.design })
        // Los estilos y fuentes recientes se evitan.
        val recent = a.map { AutoDesign.Style.entries.first { s -> s.title == it.title } }.toSet()
        val fonts = a.mapNotNull { it.design.customFont }.toSet()
        val next = AutoDesign.propose(forest, width, height, null, 1, salt = 1, avoidFonts = fonts, recentStyles = recent)
        assertTrue(next.none { it.design.customFont in fonts })
        assertTrue(next.none { AutoDesign.Style.entries.first { s -> s.title == it.title } in recent })
    }

    @Test
    fun designsBlendIntoThePhoto() {
        val all = (0 until 10).flatMap { AutoDesign.propose(forest, width, height, null, it, salt = 5) }.map { it.design }
        assertTrue(all.all { it.opacity in 74..92 && it.shadow <= 32 })
        assertTrue("camaleón en varias", all.count { it.chameleon > 0 } >= 6)
    }
}
