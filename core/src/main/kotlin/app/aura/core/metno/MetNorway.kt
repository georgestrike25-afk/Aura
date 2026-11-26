package app.aura.core.metno

import app.aura.core.net.HttpClient
import app.aura.core.net.HttpException
import app.aura.core.weather.Coordinates
import app.aura.core.weather.CurrentConditions
import app.aura.core.weather.DailyPoint
import app.aura.core.weather.HourlyPoint
import app.aura.core.weather.ProviderInfo
import app.aura.core.weather.SunTimes
import app.aura.core.weather.WeatherData
import app.aura.core.weather.WeatherProvider
import app.aura.core.weather.WeatherProviderException
import app.aura.core.weather.WmoCodes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * MET Norway (Instituto Meteorológico de Noruega, api.met.no): gratuito también para apps comerciales,
 * sin clave, datos bajo licencia CC BY 4.0 (requiere atribución). Fuera de los países nórdicos usa el
 * modelo europeo ECMWF. Pide identificar la app en el User-Agent y no más de 4 decimales en las coordenadas.
 */
class MetNorwayWeatherProvider(
    private val http: HttpClient,
    private val baseUrl: String = "https://api.met.no/weatherapi/locationforecast/2.0/complete",
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : WeatherProvider {

    override val info = INFO

    override suspend fun fetch(coordinates: Coordinates, timeZoneId: String?): WeatherData = withContext(dispatcher) {
        val response = try {
            http.get(forecastUrl(coordinates))
        } catch (e: IOException) {
            throw WeatherProviderException("No se pudo contactar con MET Norway", e)
        }
        if (response.code !in 200..299) {
            throw WeatherProviderException(
                "MET Norway respondió ${response.code}",
                HttpException(response.code, response.body.take(200)),
            )
        }
        val zone = timeZoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
        MetNorwayParser.parse(response.body, coordinates, zone, nowSeconds = System.currentTimeMillis() / 1000)
    }

    fun forecastUrl(c: Coordinates): String =
        "$baseUrl?lat=${format(c.latitude)}&lon=${format(c.longitude)}"

    companion object {
        val INFO = ProviderInfo(
            id = "met-norway",
            displayName = "MET Norway",
            attribution = "Datos meteorológicos del Instituto Meteorológico de Noruega (MET Norway), CC BY 4.0",
            attributionUrl = "https://api.met.no/",
            termsSummary = "Gratuito, sin clave y apto para uso comercial. Pronóstico de 10 días con el modelo ECMWF.",
        )

        /** 4 decimales (~11 m): lo que pide MET y evita filtrar más precisión de la necesaria. */
        fun format(value: Double): String = String.format(Locale.ROOT, "%.4f", value)
    }
}

/**
 * Convierte la respuesta de Locationforecast 2.0 en los modelos de Aura: actual, 48 horas y hasta 10 días
 * (agrupados por fecha local). Nunca inventa valores: lo que MET no da queda en null.
 */
object MetNorwayParser {
    private val json = Json { ignoreUnknownKeys = true }
    const val MAX_DAYS = 10
    private const val HOURS = 48

    private class Step(
        val time: Long,
        val details: JsonObject,
        val symbol1: String?, val mm1: Double?, val pop1: Int?,
        val symbol6: String?, val mm6: Double?, val pop6: Int?, val max6: Double?, val min6: Double?,
    )

    fun parse(body: String, coordinates: Coordinates, zone: ZoneId, nowSeconds: Long): WeatherData {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw WeatherProviderException("Respuesta JSON no válida", e)
        }
        val series = ((root["properties"] as? JsonObject)?.get("timeseries") as? JsonArray)
            ?: throw WeatherProviderException("Respuesta sin pronóstico")
        val steps = series.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val time = (o["time"] as? JsonPrimitive)?.content?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() }
                ?: return@mapNotNull null
            val data = o["data"] as? JsonObject ?: return@mapNotNull null
            val instant = (data["instant"] as? JsonObject)?.get("details") as? JsonObject ?: return@mapNotNull null
            val n1 = data["next_1_hours"] as? JsonObject
            val n6 = data["next_6_hours"] as? JsonObject
            Step(
                time, instant,
                n1.symbol(), n1.detail("precipitation_amount"), n1.detail("probability_of_precipitation")?.roundToInt(),
                n6.symbol(), n6.detail("precipitation_amount"), n6.detail("probability_of_precipitation")?.roundToInt(),
                n6.detail("air_temperature_max"), n6.detail("air_temperature_min"),
            )
        }.sortedBy { it.time }
        // La primera hora que todavía no terminó es «ahora».
        val first = steps.firstOrNull { it.time + 3600 > nowSeconds } ?: steps.firstOrNull()
            ?: throw WeatherProviderException("Respuesta sin datos actuales")
        val currentTemp = first.details.num("air_temperature") ?: throw WeatherProviderException("Respuesta sin temperatura actual")

        fun condition(step: Step, symbol: String?, mm: Double?, pop: Int?): Triple<Int, Boolean, app.aura.core.weather.WeatherCondition> {
            val code = MetSymbols.toWmo(symbol)
            val day = MetSymbols.isDay(symbol, step.time, coordinates, zone)
            val windKmh = step.details.num("wind_speed")?.times(3.6)
            val cloud = step.details.num("cloud_area_fraction")?.roundToInt()
            return Triple(code, day, WmoCodes.toCondition(code, day, windKmh, mm, cloud, pop))
        }

        val symbolNow = first.symbol1 ?: first.symbol6
        val mmNow = first.mm1 ?: first.mm6?.div(6)
        val popNow = first.pop1 ?: first.pop6
        val (codeNow, dayNow, condNow) = condition(first, symbolNow, mmNow, popNow)
        val humidity = first.details.num("relative_humidity")
        val windMs = first.details.num("wind_speed")
        val current = CurrentConditions(
            time = first.time,
            temperatureC = currentTemp,
            apparentTemperatureC = if (humidity != null && windMs != null) apparent(currentTemp, humidity, windMs) else null,
            humidityPercent = humidity?.roundToInt(),
            windSpeedKmh = windMs?.times(3.6),
            weatherCode = codeNow,
            isDay = dayNow,
            condition = condNow,
            precipitationMm = mmNow,
            cloudCoverPercent = first.details.num("cloud_area_fraction")?.roundToInt(),
            precipitationProbability = popNow,
        )

        // 48 horas: solo los pasos horarios (MET da horas los primeros ~2,5 días y después cada 6 h).
        val hourly = steps.filter { it.time >= first.time && it.symbol1 != null }.take(HOURS).mapNotNull { s ->
            val temp = s.details.num("air_temperature") ?: return@mapNotNull null
            val (code, day, cond) = condition(s, s.symbol1, s.mm1, s.pop1)
            HourlyPoint(
                time = s.time, temperatureC = temp, weatherCode = code, isDay = day,
                precipitationProbability = s.pop1, condition = cond, precipitationMm = s.mm1,
                cloudCoverPercent = s.details.num("cloud_area_fraction")?.roundToInt(),
            )
        }

        // Días: cada paso aporta su intervalo (1 h o 6 h) a la fecha local en que empieza.
        val byDate = LinkedHashMap<java.time.LocalDate, MutableList<Pair<Step, Long>>>()
        steps.forEachIndexed { i, s ->
            val next = steps.getOrNull(i + 1)?.time
            val span = if (next != null) next - s.time else if (s.symbol1 != null) 3600 else 21600
            val date = Instant.ofEpochSecond(s.time).atZone(zone).toLocalDate()
            byDate.getOrPut(date) { mutableListOf() } += s to span
        }
        val today = Instant.ofEpochSecond(first.time).atZone(zone).toLocalDate()
        val daily = byDate.filterKeys { !it.isBefore(today) }.entries.mapNotNull { (date, list) ->
            val temps = list.flatMap { (s, _) -> listOfNotNull(s.details.num("air_temperature"), s.max6, s.min6) }
            if (temps.isEmpty()) return@mapNotNull null
            // Un día con muy pocas horas (el último del pronóstico) no alcanza para máxima y mínima.
            val covered = list.sumOf { it.second }
            if (date != today && covered < 12 * 3600) return@mapNotNull null
            var mm = 0.0
            var anyMm = false
            var pop: Int? = null
            var worst = -1
            var worstSymbol: String? = null
            list.forEach { (s, span) ->
                val hourlyStep = span <= 3600 && s.symbol1 != null
                val symbol = if (hourlyStep) s.symbol1 else s.symbol6 ?: s.symbol1
                val amount = if (hourlyStep) s.mm1 else s.mm6
                val p = if (hourlyStep) s.pop1 else s.pop6
                if (amount != null) { mm += amount; anyMm = true }
                if (p != null) pop = maxOf(pop ?: 0, p)
                val code = MetSymbols.toWmo(symbol)
                if (symbol != null && code > worst) { worst = code; worstSymbol = symbol }
            }
            val sun = SunTimes.of(date, coordinates.latitude, coordinates.longitude)
            val uv = list.mapNotNull { (s, _) -> s.details.num("ultraviolet_index_clear_sky") }.maxOrNull()
            val code = if (worst >= 0) worst else 3
            DailyPoint(
                date = date.toString(),
                maxC = temps.max(),
                minC = temps.min(),
                weatherCode = code,
                precipitationProbabilityMax = pop,
                sunrise = sun?.first,
                sunset = sun?.second,
                condition = WmoCodes.toCondition(code, isDay = true, precipitationMm = if (anyMm) mm else null, probabilityPercent = pop),
                precipitationSumMm = if (anyMm) mm else null,
                uvIndexMax = uv,
            )
        }.take(MAX_DAYS)

        return WeatherData(
            current = current,
            hourly = hourly,
            daily = daily,
            timeZoneId = zone.id,
            utcOffsetSeconds = zone.rules.getOffset(Instant.ofEpochSecond(first.time)).totalSeconds,
        )
    }

    /** Sensación térmica (temperatura aparente de Steadman: humedad y viento). */
    fun apparent(tempC: Double, humidityPercent: Double, windMs: Double): Double {
        val e = humidityPercent / 100.0 * 6.105 * exp(17.27 * tempC / (237.7 + tempC))
        return tempC + 0.33 * e - 0.70 * windMs - 4.00
    }

    private fun JsonObject?.symbol(): String? =
        ((this?.get("summary") as? JsonObject)?.get("symbol_code") as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun JsonObject?.detail(key: String): Double? =
        ((this?.get("details") as? JsonObject)?.get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.doubleOrNull

    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.doubleOrNull
}

/** Símbolos de MET Norway («partlycloudy_night», «heavyrainshowersandthunder»…) → códigos WMO de Aura. */
object MetSymbols {
    fun toWmo(symbol: String?): Int {
        val s = symbol?.substringBefore('_') ?: return 3
        return when {
            s.contains("thunder") -> if (s.startsWith("heavy")) 99 else 95
            s.contains("sleet") -> if (s.contains("showers")) 85 else 67
            s.contains("snow") -> when {
                s.contains("showers") -> if (s.startsWith("heavy")) 86 else 85
                s.startsWith("light") -> 71
                s.startsWith("heavy") -> 75
                else -> 73
            }
            s.contains("rain") -> when {
                s.contains("showers") -> if (s.startsWith("heavy")) 82 else if (s.startsWith("light")) 80 else 81
                s.startsWith("light") -> 61
                s.startsWith("heavy") -> 65
                else -> 63
            }
            s == "fog" -> 45
            s == "clearsky" -> 0
            s == "fair" -> 1
            s == "partlycloudy" -> 2
            s == "cloudy" -> 3
            else -> 3
        }
    }

    /** El sufijo del símbolo dice si es de día; si no lo trae (nublado, lluvia), se usa el sol calculado. */
    fun isDay(symbol: String?, time: Long, c: Coordinates, zone: ZoneId): Boolean {
        when {
            symbol == null -> {}
            symbol.endsWith("_night") -> return false
            symbol.endsWith("_day") || symbol.endsWith("_polartwilight") -> return true
        }
        val date = Instant.ofEpochSecond(time).atZone(zone).toLocalDate()
        val sun = SunTimes.of(date, c.latitude, c.longitude) ?: return Math.abs(c.latitude) < 66 || date.monthValue in 4..9 == (c.latitude > 0)
        return time + 1800 in sun.first..sun.second
    }
}
