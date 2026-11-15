package app.aura.core

import app.aura.core.observation.Metar
import app.aura.core.weather.Coordinates
import app.aura.core.weather.CurrentConditions
import app.aura.core.weather.HourlyPoint
import app.aura.core.weather.WeatherCondition
import app.aura.core.weather.WeatherData
import app.aura.core.weather.WeatherInsights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetarTest {
    private val cordoba = Coordinates(-31.4135, -64.1811)

    // Respuesta real de aviationweather.gov (15/11/2026), con una segunda observación de lluvia inventada.
    private val body = """
        [{"icaoId":"SACO","obsTime":1794769200,"temp":26,"dewp":18,"wdir":20,"wspd":8,"visib":"6+",
          "rawOb":"METAR SACO 151900Z 02008KT 360V120 9999 FEW027 26/18 Q1009 NOSIG",
          "lat":-31.324,"lon":-64.208,"name":"Cordoba/Taravella Intl, X, AR","cover":"FEW"},
         {"icaoId":"SACE","obsTime":1794765600,"temp":22,"dewp":20,"wspd":12,"wxString":"+TSRA",
          "rawOb":"METAR SACE 151800Z 12012KT 4000 +TSRA BKN030CB 22/20 Q1010",
          "lat":-31.44,"lon":-64.26,"name":"Cordoba Observatorio, X, AR","cover":"BKN"}]
    """.trimIndent()

    @Test
    fun `parses stations with distance, wind in km per hour and humidity`() {
        val obs = Metar.parse(body, cordoba)
        assertEquals(2, obs.size)
        val saco = obs.first { it.stationId == "SACO" }
        assertEquals("Cordoba Taravella Intl", saco.stationName)
        assertEquals(26.0, saco.temperatureC!!, 0.0)
        assertEquals(14.8, saco.windKmh!!, 0.1)
        assertTrue(saco.distanceKm in 9.0..11.0)
        assertEquals(61, saco.humidityPercent)
        assertFalse(saco.isWet)
        assertEquals(WeatherCondition.CLEAR_DAY, saco.condition(true))
        assertTrue(obs.first { it.stationId == "SACE" }.isWet)
    }

    @Test
    fun `metar weather codes map to conditions`() {
        assertEquals(WeatherCondition.THUNDERSTORM, Metar.condition("+TSRA", "BKN", true))
        assertEquals(WeatherCondition.RAIN, Metar.condition("-RA", "OVC", true))
        assertEquals(WeatherCondition.RAIN, Metar.condition("SHRA", "SCT", false))
        assertEquals(WeatherCondition.DRIZZLE, Metar.condition("-DZ", "OVC", true))
        assertEquals(WeatherCondition.FOG, Metar.condition("BR", "OVC", true))
        // Tormenta en las cercanías no es tormenta en la estación.
        assertEquals(WeatherCondition.PARTLY_CLOUDY_NIGHT, Metar.condition("VCTS", "SCT", false))
        assertEquals(WeatherCondition.CLOUDY, Metar.condition(null, "OVC", true))
        assertEquals(WeatherCondition.CLEAR_NIGHT, Metar.condition(null, "CAVOK", false))
    }

    @Test
    fun `best observation must be close and recent`() {
        val obs = Metar.parse(body, cordoba)
        // Más cercana primero (SACE a ~5 km), si es reciente.
        assertEquals("SACE", Metar.best(obs, 1794766000)!!.stationId)
        // Tres horas después SACE ya es vieja y SACO todavía sirve.
        assertEquals("SACO", Metar.best(obs, 1794765600 + 3 * 3600)!!.stationId)
        assertNull(Metar.best(obs, 1794769200 + 3 * 3600))
        assertNull(Metar.best(Metar.parse(body, Coordinates(-34.6, -58.4)), 1794769200))
    }

    @Test
    fun `observation overrides the model for now`() {
        val now = 1794769200L + 600
        val hour = HourlyPoint(1794769200, 24.0, 61, true, 93, WeatherCondition.RAIN, precipitationMm = 0.0)
        val data = WeatherData(CurrentConditions(now, 24.0, weatherCode = 3, isDay = true, condition = WeatherCondition.CLOUDY), listOf(hour), emptyList())
        val dry = Metar.parse(body, cordoba).first { it.stationId == "SACO" }
        val r = WeatherInsights.reconciled(data, now, dry)
        assertEquals(WeatherCondition.CLEAR_DAY, r.current.condition)
        assertEquals(26.0, r.current.temperatureC, 0.0)
        assertEquals("Cordoba Taravella Intl", r.current.observedBy)
        assertFalse(WeatherInsights.isPossible(r.current))
        val wet = Metar.parse(body, cordoba).first { it.stationId == "SACE" }.copy(time = now)
        val r2 = WeatherInsights.reconciled(data, now, wet)
        assertEquals(WeatherCondition.THUNDERSTORM, r2.current.condition)
        // Con la observación vieja se vuelve al modelo.
        val old = WeatherInsights.reconciled(data, now + 4 * 3600, dry)
        assertNull(old.current.observedBy)
        assertEquals(WeatherCondition.CLOUDY, old.current.condition)
    }
}
