package app.aura.core

import app.aura.core.metno.MetNorwayParser
import app.aura.core.metno.MetNorwayWeatherProvider
import app.aura.core.metno.MetSymbols
import app.aura.core.weather.Coordinates
import app.aura.core.weather.SunTimes
import app.aura.core.weather.WeatherCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

class MetNorwayTest {
    private val cordoba = Coordinates(-31.4201, -64.1888)
    private val zone = ZoneId.of("America/Argentina/Cordoba")
    private val start = Instant.parse("2026-11-26T12:00:00Z").epochSecond // 09:00 en Córdoba

    /** Respuesta con la forma real de Locationforecast 2.0: 60 h horarias y después cada 6 h hasta 10 días. */
    private fun sample(): String {
        val steps = buildList {
            var t = start
            for (h in 0 until 60) { add(t); t += 3600 }
            while (t < start + 11 * 86400) { add(t); t += 6 * 3600 }
        }
        val items = steps.mapIndexed { i, t ->
            val hourly = i < 60
            val localHour = Instant.ofEpochSecond(t).atZone(zone).hour
            val temp = 18.0 + 8 * kotlin.math.sin((localHour - 9) / 24.0 * 2 * Math.PI)
            val rainy = i in 30..33
            val sym = if (rainy) "rain" else if (localHour in 7..19) "partlycloudy_day" else "clearsky_night"
            val n1 = if (hourly) ""","next_1_hours":{"summary":{"symbol_code":"$sym"},"details":{"precipitation_amount":${if (rainy) 2.5 else 0.0},"probability_of_precipitation":${if (rainy) 90 else 5}}}""" else ""
            val n6 = ""","next_6_hours":{"summary":{"symbol_code":"$sym"},"details":{"air_temperature_max":${temp + 2},"air_temperature_min":${temp - 2},"precipitation_amount":${if (rainy) 6.0 else 0.0},"probability_of_precipitation":${if (rainy) 80 else 10}}}"""
            """{"time":"${Instant.ofEpochSecond(t)}","data":{"instant":{"details":{"air_temperature":$temp,"relative_humidity":60.0,"wind_speed":3.0,"cloud_area_fraction":40.0,"ultraviolet_index_clear_sky":${if (localHour in 10..16) 9.5 else 0.0}}}$n1$n6}}"""
        }
        return """{"type":"Feature","properties":{"meta":{"updated_at":"2026-11-26T11:30:00Z"},"timeseries":[${items.joinToString(",")}]}}"""
    }

    @Test
    fun parsesCurrent48HoursAndTenDays() {
        val d = MetNorwayParser.parse(sample(), cordoba, zone, start + 600)
        assertEquals(start, d.current.time)
        assertEquals(WeatherCondition.PARTLY_CLOUDY_DAY, d.current.condition)
        assertNotNull(d.current.apparentTemperatureC)
        assertEquals(48, d.hourly.size)
        assertTrue("10 días como máximo: ${d.daily.size}", d.daily.size in 9..10)
        assertEquals("2026-11-26", d.daily.first().date)
        d.daily.forEach { assertTrue(it.maxC > it.minC) }
        assertEquals("America/Argentina/Cordoba", d.timeZoneId)
        assertEquals(-3 * 3600, d.utcOffsetSeconds)
        // Sol calculado y UV del día.
        assertNotNull(d.daily.first().sunrise)
        assertEquals(9.5, d.daily.first().uvIndexMax!!, 0.01)
    }

    @Test
    fun rainHoursBecomeRainWithMillimetres() {
        val d = MetNorwayParser.parse(sample(), cordoba, zone, start + 600)
        val rainy = d.hourly[31]
        assertEquals(WeatherCondition.RAIN, rainy.condition)
        assertEquals(2.5, rainy.precipitationMm!!, 0.01)
        assertEquals(90, rainy.precipitationProbability)
        val day = d.daily.first { it.precipitationSumMm != null && it.precipitationSumMm!! > 0 }
        assertEquals(WeatherCondition.RAIN, day.condition)
    }

    @Test
    fun symbolsMapToWmo() {
        assertEquals(0, MetSymbols.toWmo("clearsky_day"))
        assertEquals(2, MetSymbols.toWmo("partlycloudy_night"))
        assertEquals(82, MetSymbols.toWmo("heavyrainshowers_day"))
        assertEquals(95, MetSymbols.toWmo("rainandthunder"))
        assertEquals(99, MetSymbols.toWmo("heavyrainshowersandthunder_day"))
        assertEquals(71, MetSymbols.toWmo("lightsnow"))
        assertEquals(45, MetSymbols.toWmo("fog"))
        assertFalse(MetSymbols.isDay("clearsky_night", start, cordoba, zone))
    }

    @Test
    fun sunTimesForCordobaAreRealistic() {
        // 26/11 en Córdoba: sale ~06:05 y se pone ~20:10 (hora local).
        val (rise, set) = SunTimes.of(LocalDate.of(2026, 11, 26), cordoba.latitude, cordoba.longitude)!!
        val r = Instant.ofEpochSecond(rise).atZone(zone).toLocalTime()
        val s = Instant.ofEpochSecond(set).atZone(zone).toLocalTime()
        assertTrue("salida $r", abs(r.toSecondOfDay() - 6 * 3600 - 5 * 60) < 20 * 60)
        assertTrue("puesta $s", abs(s.toSecondOfDay() - 20 * 3600 - 10 * 60) < 20 * 60)
        assertNull(SunTimes.of(LocalDate.of(2026, 6, 21), 80.0, 15.0)) // día polar
    }

    @Test
    fun urlHasFourDecimals() {
        val url = MetNorwayWeatherProvider({ throw java.io.IOException() }).forecastUrl(Coordinates(-31.420123456, -64.188877))
        assertTrue(url.endsWith("?lat=-31.4201&lon=-64.1889"))
    }
}
