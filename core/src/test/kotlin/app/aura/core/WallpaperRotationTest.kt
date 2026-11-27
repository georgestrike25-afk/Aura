package app.aura.core

import app.aura.core.studio.WallpaperRotation
import app.aura.core.studio.WallpaperRotation.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class WallpaperRotationTest {
    private val ids = (1..6).map { "p$it" }
    /** p1 el más oscuro … p6 el más luminoso. */
    private val light = ids.mapIndexed { i, id -> id to i / 5f }.toMap()
    private val t = LocalDateTime.of(2026, 11, 27, 10, 0)

    @Test
    fun dailyChangesOncePerDayAndVisitsAllBeforeRepeating() {
        assertEquals(WallpaperRotation.pick(Mode.DAILY, ids, light, t), WallpaperRotation.pick(Mode.DAILY, ids, light, t.withHour(23)))
        val days = (0L until 6L).map { WallpaperRotation.pick(Mode.DAILY, ids, light, t.plusDays(it)) }
        assertEquals("una vuelta completa sin repetir", 6, days.toSet().size)
    }

    @Test
    fun hourlyChangesEveryHour() {
        val hours = (0L until 6L).map { WallpaperRotation.pick(Mode.HOURLY, ids, light, t.plusHours(it)) }
        assertEquals(6, hours.toSet().size)
    }

    @Test
    fun timeOfDayUsesDarkDesignsAtNightAndBrightOnesInTheAfternoon() {
        for (d in 0L until 10L) {
            val night = WallpaperRotation.pick(Mode.TIME_OF_DAY, ids, light, t.plusDays(d).withHour(22))!!
            val afternoon = WallpaperRotation.pick(Mode.TIME_OF_DAY, ids, light, t.plusDays(d).withHour(15))!!
            assertTrue("de noche, oscuro: $night", light.getValue(night) <= 0.4f)
            assertTrue("de tarde, luminoso: $afternoon", light.getValue(afternoon) >= 0.6f)
        }
        // A las 2 de la mañana sigue siendo «la noche de ayer»: no cambia a medianoche.
        assertEquals(
            WallpaperRotation.pick(Mode.TIME_OF_DAY, ids, light, t.withHour(23)),
            WallpaperRotation.pick(Mode.TIME_OF_DAY, ids, light, t.plusDays(1).withHour(2)),
        )
    }

    @Test
    fun offOrEmptyGivesNothing() {
        assertNull(WallpaperRotation.pick(Mode.OFF, ids, light, t))
        assertNull(WallpaperRotation.pick(Mode.DAILY, emptyList(), light, t))
        assertEquals("p1", WallpaperRotation.pick(Mode.HOURLY, listOf("p1"), light, t))
    }
}
