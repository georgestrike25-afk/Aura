package app.aura.core

import app.aura.core.studio.Atmosphere
import app.aura.core.studio.AtmosphereEffect
import app.aura.core.studio.AtmosphereParticles
import app.aura.core.studio.StudioProject
import app.aura.core.studio.TimeLight
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AtmosphereTest {
    @Test
    fun `particles stay on screen, are deterministic and move with time`() {
        AtmosphereEffect.entries.forEach { e ->
            val a = Atmosphere(effect = e, intensity = 80)
            val f0 = AtmosphereParticles.frame(a, 3.5f, 1080, 2340)
            assertEquals(f0, AtmosphereParticles.frame(a, 3.5f, 1080, 2340))
            f0.forEach { p ->
                assertTrue("$e x ${p.x}", p.x >= -400f && p.x <= 1080f)
                assertTrue("$e y ${p.y}", p.y >= -80f && p.y <= 2360f)
                assertTrue(p.alpha in 0f..1f)
            }
            if (e != AtmosphereEffect.NONE && e != AtmosphereEffect.FOG) {
                assertTrue(f0.isNotEmpty())
                assertNotEquals("$e se mueve", f0, AtmosphereParticles.frame(a, 4.5f, 1080, 2340))
            }
        }
        assertTrue(AtmosphereParticles.count(Atmosphere(AtmosphereEffect.RAIN, 100)) > AtmosphereParticles.count(Atmosphere(AtmosphereEffect.RAIN, 0)))
    }

    @Test
    fun `time light tints night, dawn and golden hour but not midday`() {
        fun alpha(c: Int) = c ushr 24
        assertEquals(0, alpha(TimeLight.tint(13 * 60)))
        assertTrue(alpha(TimeLight.tint(2 * 60)) > 60)
        assertTrue(alpha(TimeLight.tint(390)) > 30) // amanecer
        val golden = TimeLight.tint(1170 - 30)
        assertTrue(alpha(golden) > 30 && (golden shr 16 and 0xFF) > 0xF0)
        assertEquals(0, alpha(TimeLight.tint(2 * 60, strength = 0)))
    }

    @Test
    fun `old projects load with no atmosphere`() {
        val json = Json { ignoreUnknownKeys = true }
        val p = json.decodeFromString(StudioProject.serializer(), """{"id":"a","name":"x","createdAt":1,"updatedAt":2}""")
        assertEquals(AtmosphereEffect.NONE, p.atmosphere.effect)
    }
}
