package app.aura.studio.render

import android.content.Context
import android.text.format.DateFormat
import app.aura.core.settings.AppSettings
import app.aura.core.studio.ClockInformationField
import app.aura.core.studio.fields
import app.aura.ui.WeatherText
import app.aura.core.studio.ClockDesign
import app.aura.core.studio.ClockText
import app.aura.core.time.ClockPatterns
import app.aura.core.time.HourFormat
import app.aura.core.units.TemperatureFormatter
import app.aura.core.weather.WeatherSnapshot
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Textos del reloj de Studio reutilizando la lógica horaria y meteorológica de Aura: formato 12/24 h
 * (del diseño, de la app o del sistema), fecha localizada y la temperatura en caché.
 */
object ClockExtrasBuilder {
    fun build(context: Context, design: ClockDesign, settings: AppSettings, snapshot: WeatherSnapshot?, now: LocalDateTime): ClockExtras {
        val format = if (design.hourFormat != HourFormat.SYSTEM) design.hourFormat else settings.hourFormat
        val is24 = ClockPatterns.is24(format, DateFormat.is24HourFormat(context))
        val (h, m) = ClockText.parts(now.hour, now.minute, is24)
        val parts = mutableListOf<String>()
        val date = if (design.showDate || design.informationMode != null) {
            val pattern = ClockPatterns.datePattern(design.dateStyle, long = true) { DateFormat.getBestDateTimePattern(Locale.getDefault(), it) }
            now.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault())).replaceFirstChar { it.titlecase(Locale.getDefault()) }
        } else null
        if (design.showDate && date != null) parts += date
        if (design.showWeather && snapshot != null) {
            parts += "${TemperatureFormatter.short(snapshot.data.current.temperatureC, settings.temperatureUnit)} ${snapshot.place.name}"
        }
        val ampm = if (!is24) " " + now.format(DateTimeFormatter.ofPattern("a", Locale.getDefault())) else ""
        val mode = design.informationMode
        val information = mode?.fields()?.mapNotNull { field ->
            val value = when (field) {
                ClockInformationField.DATE -> date
                ClockInformationField.TEMPERATURE -> snapshot?.data?.current?.let { TemperatureFormatter.short(it.temperatureC, settings.temperatureUnit) }
                ClockInformationField.CONDITION -> snapshot?.data?.current?.let { WeatherText.now(context, it) }
                ClockInformationField.LOCATION -> snapshot?.place?.name
                ClockInformationField.RANGE -> snapshot?.data?.daily?.firstOrNull { it.date == now.toLocalDate().toString() }?.let {
                    TemperatureFormatter.short(it.maxC, settings.temperatureUnit) + " / " +
                        TemperatureFormatter.short(it.minC, settings.temperatureUnit)
                }
            }
            value?.let { field to it }
        }.orEmpty()
        return ClockExtras(h, m + ampm.lowercase(Locale.getDefault()).takeIf { it.isNotBlank() }.orEmpty(),
            if (mode == null) parts.joinToString(" · ").ifBlank { null } else null, information)
    }
}
