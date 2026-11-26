package app.aura.data

import android.content.Context
import android.location.Address
import android.location.Geocoder
import app.aura.core.weather.Coordinates
import app.aura.core.weather.Place
import app.aura.core.weather.PlaceSearchProvider
import app.aura.core.weather.WeatherProviderException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import kotlin.math.abs

/**
 * Buscador de ciudades con el geocodificador que trae Android (servicio del sistema, gratis y sin
 * condiciones de uso no comercial). La zona horaria se deduce del país (y de la longitud si el país
 * tiene varias), con la base de zonas del propio teléfono.
 */
class AndroidPlaceSearch(context: Context) : PlaceSearchProvider {
    private val app = context.applicationContext

    override suspend fun search(query: String, languageTag: String, limit: Int): List<Place> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()
        if (!Geocoder.isPresent()) throw WeatherProviderException("Este teléfono no tiene buscador de ciudades")
        return withContext(Dispatchers.IO) {
            val addresses = try {
                @Suppress("DEPRECATION")
                Geocoder(app, Locale.forLanguageTag(languageTag)).getFromLocationName(trimmed, limit).orEmpty()
            } catch (e: IOException) {
                throw WeatherProviderException("No se pudo buscar la ciudad", e)
            }
            addresses.mapNotNull { it.toPlace() }.distinctBy { it.name to it.region }
        }
    }

    private fun Address.toPlace(): Place? {
        if (!hasLatitude() || !hasLongitude()) return null
        val name = locality ?: subAdminArea ?: featureName ?: adminArea ?: return null
        return Place(
            id = "geo:%.3f,%.3f".format(Locale.ROOT, latitude, longitude),
            name = name,
            region = adminArea?.takeIf { it != name },
            country = countryName,
            coordinates = Coordinates(latitude, longitude),
            timeZoneId = zoneFor(countryCode, longitude),
        )
    }

    companion object {
        /** Zona del país; si tiene varias, la que mejor coincide con la longitud (15° por hora). */
        fun zoneFor(countryCode: String?, longitude: Double): String? {
            if (countryCode.isNullOrBlank()) return null
            val ids = android.icu.util.TimeZone.getAvailableIDs(countryCode.uppercase(Locale.ROOT)).orEmpty()
                .filter { it.contains('/') }
            if (ids.isEmpty()) return null
            val now = System.currentTimeMillis()
            return ids.minByOrNull { id ->
                val offsetHours = java.util.TimeZone.getTimeZone(id).getOffset(now) / 3_600_000.0
                abs(offsetHours - longitude / 15.0)
            }
        }
    }
}
