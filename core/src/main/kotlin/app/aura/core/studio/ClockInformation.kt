package app.aura.core.studio

import kotlinx.serialization.Serializable

@Serializable
enum class ClockInformationMode(val label: String) {
    PURE("Puro"), ESSENTIAL("Esencial"), AMBIENT("Ambiental"), FULL("Completo")
}

@Serializable
enum class ClockInformationField(val label: String) {
    DATE("Fecha"), TEMPERATURE("Temperatura"), CONDITION("Condición"), LOCATION("Ubicación"), RANGE("Máxima y mínima")
}

fun ClockInformationMode.fields(): List<ClockInformationField> = with(ClockInformationField) {
    when (this@fields) {
        ClockInformationMode.PURE -> emptyList()
        ClockInformationMode.ESSENTIAL -> listOf(DATE, TEMPERATURE)
        ClockInformationMode.AMBIENT -> listOf(TEMPERATURE, CONDITION)
        ClockInformationMode.FULL -> listOf(DATE, LOCATION, TEMPERATURE, RANGE)
    }
}

@Serializable
data class ClockInformationStyle(
    val x: Float = 0.5f,
    val y: Float = 0.13f,
    val size: Float = 0.04f,
    val fontId: String? = null,
    val color: Int? = null,
    val opacity: Int = 90,
    val visible: Boolean = true,
)

fun ClockDesign.informationStyle(field: ClockInformationField): ClockInformationStyle =
    informationStyles[field] ?: ClockInformationStyle(y = 0.13f + field.ordinal * 0.025f)
