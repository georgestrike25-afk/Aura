package app.aura.core.studio

import app.aura.core.style.Alignment
import app.aura.core.style.FontChoice
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class InformationBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun overlap(other: InformationBounds): Float {
        val width = (min(right, other.right) - max(left, other.left)).coerceAtLeast(0f)
        val height = (min(bottom, other.bottom) - max(top, other.top)).coerceAtLeast(0f)
        return width * height / ((right - left) * (bottom - top)).coerceAtLeast(0.00001f)
    }
}

/** Layouts are computed once per selection. Live wallpapers only draw the saved coordinates. */
class ClockInformationLayouts(
    private val width: Int,
    private val height: Int,
    pixels: IntArray,
    private val subject: IntArray?,
    private val clock: List<InformationBounds>,
    private val textLengths: Map<ClockInformationField, Int> = emptyMap(),
) {
    private val brightness = pixels.map(::luminance).toFloatArray()
    private val above = ((clock.minOfOrNull { it.top } ?: 0.4f) - 0.035f).coerceIn(0.12f, 0.75f)
    private val below = ((clock.maxOfOrNull { it.bottom } ?: 0.57f) + 0.045f).coerceIn(0.18f, 0.87f)

    fun arrange(active: ClockInformationMode, requested: ClockInformationSet? = null): ClockInformationArrangement {
        val sets = requested?.let(::listOf) ?: ClockInformationSet.entries
        val reference = if (active == ClockInformationMode.PURE) ClockInformationMode.FULL else active
        var best: ClockInformationArrangement? = null
        var bestScore = Float.NEGATIVE_INFINITY
        for (set in sets) {
            val evaluated = ClockInformationMode.entries.filter { it != ClockInformationMode.PURE }.associateWith { mode ->
                val layout = template(set, mode)
                shifts.map { shift -> score(layout, shift) }.maxBy { it.score }
            }
            val score = evaluated.values.sumOf { it.score.toDouble() }.toFloat() + evaluated.getValue(reference).score * 2f
            if (score > bestScore) {
                bestScore = score
                best = ClockInformationArrangement(set, requested == null, evaluated.mapValues { it.value.styles })
            }
        }
        return checkNotNull(best)
    }

    private data class Evaluated(val styles: Map<ClockInformationField, ClockInformationStyle>, val score: Float)

    private fun score(layout: List<Placement>, shift: Float): Evaluated {
        if (layout.any { it.style.y + shift !in 0.09f..0.94f }) return Evaluated(emptyMap(), Float.NEGATIVE_INFINITY)
        var score = 0f
        val styles = layout.associate { (field, candidate) ->
            val style = candidate.copy(y = candidate.y + shift)
            val box = fieldBounds(field, style)
            val colors = if (field == ClockInformationField.TEMPERATURE) listOf(gold, ivory, dark) else listOf(ivory, dark)
            val x0 = floor(box.left * width).toInt().coerceIn(0, width - 1)
            val x1 = ceil(box.right * width).toInt().coerceIn(x0 + 1, width)
            val y0 = floor(box.top * height).toInt().coerceIn(0, height - 1)
            val y1 = ceil(box.bottom * height).toInt().coerceIn(y0 + 1, height)
            val light = luminance(ivory)
            val warm = luminance(gold)
            val ink = luminance(dark)
            var texture = 0f
            var subjectWeight = 0f
            var paleContrast = 0f
            var warmContrast = 0f
            var darkContrast = 0f
            for (y in y0 until y1) for (x in x0 until x1) {
                val i = y * width + x
                val value = brightness[i]
                paleContrast += readableContrast(value, light)
                warmContrast += readableContrast(value, warm)
                darkContrast += readableContrast(value, ink)
                if (x + 1 < width) texture += abs(value - brightness[i + 1])
                if (y + 1 < height) texture += abs(value - brightness[i + width])
                subjectWeight += ((subject?.get(i) ?: 0) ushr 24) / 255f
            }
            val count = (x1 - x0) * (y1 - y0)
            fun contrast(color: Int): Float = when (color) {
                ivory -> paleContrast / count
                gold -> warmContrast / count + 0.06f
                else -> darkContrast / count
            }
            val color = colors.maxBy(::contrast)
            score += contrast(color) - texture / count * 1.5f - subjectWeight / count * 1.4f
            score -= clock.maxOfOrNull(box::overlap)?.times(5f) ?: 0f
            field to style.copy(color = color)
        }
        return Evaluated(styles, score / layout.size - abs(shift) * 3f)
    }

    private fun fieldBounds(field: ClockInformationField, style: ClockInformationStyle): InformationBounds {
        val characters = (textLengths[field] ?: field.defaultLength()).coerceAtLeast(1)
        val widthFraction = min(style.size * characters * 0.56f, style.maxWidth)
        val left = when (style.alignment) {
            Alignment.START -> style.x
            Alignment.CENTER -> style.x - widthFraction / 2
            Alignment.END -> style.x - widthFraction
        }
        return InformationBounds(left, style.y - style.size * width / height, left + widthFraction, style.y + 0.006f)
    }

    private fun template(set: ClockInformationSet, mode: ClockInformationMode): List<Placement> {
        if (mode == ClockInformationMode.ORIGINAL) {
            val style = when (set) {
                ClockInformationSet.INITIAL -> item(CAP, 0.5f, below, 0.039f)
                ClockInformationSet.EDITORIAL -> item(CAP, 0.5f, below + 0.01f, 0.038f, font = FontChoice.SERIF)
                ClockInformationSet.DIAGONAL -> item(CAP, 0.93f, below + 0.025f, 0.038f, Alignment.END, 0.64f)
                ClockInformationSet.CLOCKSIDE -> item(CAP, 0.065f, below + 0.01f, 0.036f, Alignment.START, 0.64f)
                ClockInformationSet.CAPTION -> item(CAP, 0.5f, 0.92f, 0.04f)
            }
            return listOf(style)
        }
        return when (set) {
            ClockInformationSet.INITIAL -> when (mode) {
                ClockInformationMode.ESSENTIAL -> paired(mode, above, above)
                ClockInformationMode.AMBIENT -> column(mode, 0.085f, 0.18f, Alignment.START)
                else -> paired(mode, 0.172f, 0.172f)
            }
            ClockInformationSet.EDITORIAL -> column(mode, 0.5f, 0.18f, Alignment.CENTER)
            ClockInformationSet.DIAGONAL -> paired(mode, 0.152f, max(0.73f, below + 0.045f).coerceAtMost(0.84f))
            ClockInformationSet.CLOCKSIDE -> when (mode) {
                ClockInformationMode.ESSENTIAL -> paired(mode, below, below)
                ClockInformationMode.AMBIENT -> column(mode, 0.935f, below + 0.02f, Alignment.END)
                else -> paired(mode, (above - 0.035f).coerceAtLeast(0.10f), below + 0.02f)
            }
            ClockInformationSet.CAPTION -> when (mode) {
                ClockInformationMode.AMBIENT -> column(mode, 0.08f, 0.885f, Alignment.START)
                else -> paired(mode, 0.88f, 0.88f)
            }
        }
    }

    private fun paired(mode: ClockInformationMode, leftY: Float, rightY: Float): List<Placement> = when (mode) {
        ClockInformationMode.ESSENTIAL -> listOf(
            item(DATE, 0.075f, leftY, 0.044f, Alignment.START, 0.58f, FontChoice.SERIF),
            item(TEMP, 0.93f, rightY, if (abs(leftY - rightY) > 0.17f) 0.095f else 0.058f, Alignment.END, 0.22f, FontChoice.LIGHT),
        )
        ClockInformationMode.AMBIENT -> listOf(
            item(COND, 0.075f, leftY, 0.043f, Alignment.START, 0.68f, FontChoice.SERIF),
            item(TEMP, 0.93f, rightY, 0.10f, Alignment.END, 0.26f, FontChoice.LIGHT),
        )
        else -> listOf(
            item(CITY, 0.075f, leftY, 0.075f, Alignment.START, 0.51f, FontChoice.SERIF),
            item(DATE, 0.075f, leftY + 0.028f, 0.040f, Alignment.START, 0.56f),
            item(TEMP, 0.93f, rightY, 0.095f, Alignment.END, 0.25f, FontChoice.LIGHT),
            item(RANGE, 0.93f, rightY + 0.028f, 0.042f, Alignment.END, 0.28f),
        )
    }

    private fun column(mode: ClockInformationMode, x: Float, y: Float, align: Alignment): List<Placement> = when (mode) {
        ClockInformationMode.ESSENTIAL -> listOf(
            item(DATE, x, y + 0.042f, 0.05f, align, 0.78f, FontChoice.SERIF),
            item(TEMP, x, y + 0.089f, 0.082f, align, 0.30f, FontChoice.LIGHT),
        )
        ClockInformationMode.AMBIENT -> listOf(
            item(TEMP, x, y, 0.10f, align, 0.35f, FontChoice.LIGHT),
            item(COND, x, y + 0.031f, 0.043f, align, 0.7f),
        )
        else -> listOf(
            item(CITY, x, y, 0.075f, align, 0.72f, FontChoice.SERIF),
            item(DATE, x, y + 0.031f, 0.04f, align, 0.75f),
            item(TEMP, x, y + 0.088f, 0.09f, align, 0.3f, FontChoice.LIGHT),
            item(RANGE, x, y + 0.117f, 0.038f, align, 0.32f),
        )
    }

    private data class Placement(val field: ClockInformationField, val style: ClockInformationStyle)

    private fun item(
        field: ClockInformationField, x: Float, y: Float, size: Float,
        align: Alignment = Alignment.CENTER, width: Float = 0.86f, font: FontChoice = FontChoice.REGULAR,
    ) = Placement(field, ClockInformationStyle(x, y, size, opacity = 98, alignment = align, maxWidth = width, systemFont = font))

    private fun ClockInformationField.defaultLength(): Int = when (this) {
        DATE -> 24
        TEMP -> 4
        COND -> 21
        CITY -> 12
        RANGE -> 11
        CAP -> 26
        else -> error("Unsupported information field")
    }

    private companion object {
        val DATE = ClockInformationField.DATE
        val TEMP = ClockInformationField.TEMPERATURE
        val COND = ClockInformationField.CONDITION
        val CITY = ClockInformationField.LOCATION
        val RANGE = ClockInformationField.RANGE
        val CAP = ClockInformationField.CAPTION
        val shifts = listOf(0f, -0.035f, 0.035f, -0.070f, 0.070f, -0.12f, 0.12f)
        val ivory = 0xFFF6EFDE.toInt()
        val gold = 0xFFF1D0A2.toInt()
        val dark = 0xFF172027.toInt()

        private val linear = FloatArray(256) { byte ->
            val value = byte / 255f
            if (value <= 0.04045f) value / 12.92f else ((value + 0.055f) / 1.055f).pow(2.4f)
        }

        fun luminance(pixel: Int): Float =
            linear[pixel ushr 16 and 255] * 0.2126f + linear[pixel ushr 8 and 255] * 0.7152f + linear[pixel and 255] * 0.0722f

        fun readableContrast(background: Float, text: Float): Float =
            (((max(background, text) + 0.05f) / (min(background, text) + 0.05f)) / 7f).coerceAtMost(1f)
    }
}
