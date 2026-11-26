package app.aura.core.weather

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Salida y puesta del sol calculadas en el teléfono (algoritmo de la NOAA, error típico menor a un
 * minuto entre los círculos polares). Así no hace falta pedírselas a ningún servicio.
 */
object SunTimes {
    /** (salida, puesta) en segundos Unix para la fecha local [date]; null en noche o día polar. */
    fun of(date: LocalDate, latitude: Double, longitude: Double): Pair<Long, Long>? {
        val gamma = 2 * Math.PI / 365 * (date.dayOfYear - 1)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
            0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) - 0.006758 * cos(2 * gamma) +
            0.000907 * sin(2 * gamma) - 0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
        val lat = Math.toRadians(latitude)
        val x = cos(Math.toRadians(90.833)) / (cos(lat) * cos(decl)) - tan(lat) * tan(decl)
        if (x < -1.0 || x > 1.0) return null
        val ha = Math.toDegrees(acos(x))
        val midnightUtc = date.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val sunrise = 720 - 4 * (longitude + ha) - eqTime
        val sunset = 720 - 4 * (longitude - ha) - eqTime
        return (midnightUtc + (sunrise * 60).toLong()) to (midnightUtc + (sunset * 60).toLong())
    }
}
