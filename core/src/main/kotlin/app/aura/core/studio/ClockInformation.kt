package app.aura.core.studio

import app.aura.core.style.Alignment
import app.aura.core.style.FontChoice
import kotlinx.serialization.Serializable

@Serializable
enum class ClockInformationMode(val label: String) {
    PURE("Puro"), ESSENTIAL("Esencial"), AMBIENT("Ambiental"), FULL("Completo"), ORIGINAL("Original")
}

@Serializable
enum class ClockInformationField(val label: String) {
    DATE("Fecha"), TEMPERATURE("Temperatura"), CONDITION("Condición"), LOCATION("Ubicación"), RANGE("Máxima y mínima"),
    CAPTION("Línea de fecha y clima")
}

fun ClockInformationMode.fields(): List<ClockInformationField> = when (this) {
    ClockInformationMode.PURE -> emptyList()
    ClockInformationMode.ORIGINAL -> listOf(ClockInformationField.CAPTION)
    ClockInformationMode.ESSENTIAL -> listOf(ClockInformationField.DATE, ClockInformationField.TEMPERATURE)
    ClockInformationMode.AMBIENT -> listOf(ClockInformationField.TEMPERATURE, ClockInformationField.CONDITION)
    ClockInformationMode.FULL -> listOf(
        ClockInformationField.DATE, ClockInformationField.LOCATION,
        ClockInformationField.TEMPERATURE, ClockInformationField.RANGE,
    )
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
    val alignment: Alignment = Alignment.CENTER,
    val maxWidth: Float = 0.94f,
    val systemFont: FontChoice = FontChoice.REGULAR,
)

@Serializable
enum class ClockInformationSet(val label: String, val description: String) {
    INITIAL("Conjunto 1", "Composición inicial"),
    EDITORIAL("Conjunto 2", "Eje editorial"),
    DIAGONAL("Conjunto 3", "En diagonal"),
    CLOCKSIDE("Conjunto 4", "Junto al reloj"),
    CAPTION("Conjunto 5", "Pie de foto"),
}

@Serializable
data class ClockInformationArrangement(
    val set: ClockInformationSet,
    val automatic: Boolean,
    val styles: Map<ClockInformationMode, Map<ClockInformationField, ClockInformationStyle>>,
)

val ClockDesign.selectedInformationMode: ClockInformationMode get() = informationMode ?: ClockInformationMode.ORIGINAL

fun ClockDesign.informationStyle(field: ClockInformationField): ClockInformationStyle =
    informationArrangement?.styles?.get(selectedInformationMode)?.get(field)
        ?: informationStyles[field]
        ?: ClockInformationStyle(y = 0.13f + field.ordinal * 0.025f)

fun ClockDesign.editInformation(field: ClockInformationField, style: ClockInformationStyle): ClockDesign {
    val arrangement = informationArrangement ?: return copy(informationStyles = informationStyles + (field to style))
    val mode = selectedInformationMode
    val update = arrangement.styles[mode].orEmpty() + (field to style)
    return copy(informationArrangement = arrangement.copy(automatic = false, styles = arrangement.styles + (mode to update)))
}
