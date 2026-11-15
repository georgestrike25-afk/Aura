package app.aura.core.observation

import app.aura.core.net.HttpClient
import app.aura.core.weather.Coordinates
import app.aura.core.weather.WeatherCondition
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lo que observa una estación de verdad (no un pronóstico): temperatura, viento y el fenómeno
 * presente (lluvia, tormenta, llovizna, niebla…). Viene de los METAR de los aeropuertos.
 */
@Serializable
data class Observation(
    val stationId: String,
    val stationName: String,
    val latitude: Double,
    val longitude: Double,
    /** Momento de la observación, segundos Unix. */
    val time: Long,
    val temperatureC: Double? = null,
    val dewPointC: Double? = null,
    val windKmh: Double? = null,
    /** Fenómeno presente en código METAR (p. ej. "-RA", "TSRA"); null = nada. */
    val weather: String? = null,
    /** Nubosidad más cubierta: SKC, FEW, SCT, BKN, OVC, CAVOK… */
    val cover: String? = null,
    val distanceKm: Double = 0.0,
) {
    /** Humedad relativa a partir de temperatura y punto de rocío (fórmula de Magnus). */
    val humidityPercent: Int?
        get() {
            val t = temperatureC ?: return null
            val d = dewPointC ?: return null
            fun e(x: Double) = kotlin.math.exp(17.625 * x / (243.04 + x))
            return (100 * e(d) / e(t)).toInt().coerceIn(0, 100)
        }

    /** Hay lluvia, nieve o tormenta en la estación ahora (no «en las cercanías»). */
    val isWet: Boolean get() = Metar.condition(weather, cover, true).let { it in WET }

    fun condition(isDay: Boolean): WeatherCondition = Metar.condition(weather, cover, isDay)

    companion object {
        private val WET = setOf(WeatherCondition.DRIZZLE, WeatherCondition.RAIN, WeatherCondition.THUNDERSTORM, WeatherCondition.SNOW)
    }
}

object Metar {
    /** Distancia máxima a la estación para usar su observación. */
    const val MAX_DISTANCE_KM = 30.0

    /** Antigüedad máxima: los aeropuertos informan cada hora (y antes si cambia el tiempo). */
    const val MAX_AGE_SECONDS = 2 * 3600L

    private val json = Json { ignoreUnknownKeys = true }

    /** Fenómeno METAR + nubosidad → condición de Aura. Lo de «VC» (en las cercanías) no cuenta. */
    fun condition(weather: String?, cover: String?, isDay: Boolean): WeatherCondition {
        val tokens = weather.orEmpty().split(' ').filter { it.isNotBlank() && !it.startsWith("VC") }
        fun has(code: String) = tokens.any { it.contains(code) }
        return when {
            has("TS") -> WeatherCondition.THUNDERSTORM
            has("SN") || has("SG") || has("PL") -> WeatherCondition.SNOW
            has("RA") || has("SH") || has("GR") || has("GS") -> WeatherCondition.RAIN
            has("DZ") -> WeatherCondition.DRIZZLE
            has("FG") || has("BR") -> WeatherCondition.FOG
            else -> when (cover?.uppercase()) {
                "SCT" -> if (isDay) WeatherCondition.PARTLY_CLOUDY_DAY else WeatherCondition.PARTLY_CLOUDY_NIGHT
                "BKN", "OVC", "OVX", "VV" -> WeatherCondition.CLOUDY
                else -> if (isDay) WeatherCondition.CLEAR_DAY else WeatherCondition.CLEAR_NIGHT
            }
        }
    }

    /** Respuesta JSON de aviationweather.gov (/api/data/metar?format=json). */
    fun parse(body: String, from: Coordinates): List<Observation> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonArray ?: return emptyList()
        return root.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("icaoId") ?: return@mapNotNull null
            val lat = o.num("lat") ?: return@mapNotNull null
            val lon = o.num("lon") ?: return@mapNotNull null
            val time = (o["obsTime"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val raw = o.str("rawOb").orEmpty()
            Observation(
                stationId = id,
                stationName = o.str("name")?.substringBefore(',')?.replace('/', ' ')?.trim() ?: id,
                latitude = lat,
                longitude = lon,
                time = time,
                temperatureC = o.num("temp"),
                dewPointC = o.num("dewp"),
                windKmh = o.num("wspd")?.times(1.852),
                weather = o.str("wxString")?.takeIf { it.isNotBlank() },
                cover = if (" CAVOK" in raw) "CAVOK" else o.str("cover"),
                distanceKm = distanceKm(from.latitude, from.longitude, lat, lon),
            )
        }
    }

    /** La observación más cercana y reciente que sirve para el lugar; null si no hay ninguna. */
    fun best(observations: List<Observation>, nowSec: Long): Observation? =
        observations
            .filter { it.distanceKm <= MAX_DISTANCE_KM && nowSec - it.time in -600..MAX_AGE_SECONDS }
            .sortedWith(compareBy<Observation> { it.distanceKm.toInt() / 5 }.thenByDescending { it.time })
            .firstOrNull()

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
}

/**
 * Observaciones METAR de aviationweather.gov (NOAA, EE. UU.): servicio público, gratuito y sin clave.
 * Se piden las estaciones en un recuadro de ~35 km alrededor del lugar.
 */
class MetarClient(
    private val http: HttpClient,
    private val baseUrl: String = "https://aviationweather.gov/api/data/metar",
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Nunca falla: si el servicio no responde, devuelve null y Aura sigue con el pronóstico. */
    suspend fun nearest(c: Coordinates, nowSec: Long): Observation? = withContext(dispatcher) {
        runCatching {
            val d = 0.32
            val url = "$baseUrl?format=json&bbox=${f(c.latitude - d)},${f(c.longitude - d)},${f(c.latitude + d)},${f(c.longitude + d)}"
            val response = http.get(url)
            if (response.code !in 200..299) null else Metar.best(Metar.parse(response.body, c), nowSec)
        }.getOrNull()
    }

    private fun f(v: Double) = String.format(java.util.Locale.US, "%.3f", v)
}
