package app.aura.core.studio

import kotlinx.serialization.Serializable

/** Stable positions, independent of the numeral displayed at a given minute. */
@Serializable
enum class DigitSlot { HOUR_TENS, HOUR_UNITS, MINUTE_TENS, MINUTE_UNITS }

@Serializable
enum class ArtisticMaterial { INHERIT, AQUA, IGNIS, VENTUS, LUX, TERRA, AETHER, GLACIUS, KINTSUGI }

@Serializable
enum class DigitDepth { INHERIT, BEHIND_SUBJECT, IN_FRONT_OF_SUBJECT }

@Serializable
data class DigitStyle(
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotation: Float = 0f,
    val opacity: Int = 100,
    val color: Int? = null,
    val fontId: String? = null,
    val material: ArtisticMaterial = ArtisticMaterial.INHERIT,
    val depth: DigitDepth = DigitDepth.INHERIT,
    val intensity: Int = 50,
) {
    fun normalized() = copy(
        offsetX = offsetX.finiteOr(0f).coerceIn(-1f, 1f),
        offsetY = offsetY.finiteOr(0f).coerceIn(-1f, 1f),
        scaleX = scaleX.finiteOr(1f).coerceIn(0.25f, 3f),
        scaleY = scaleY.finiteOr(1f).coerceIn(0.25f, 3f),
        rotation = rotation.finiteOr(0f).coerceIn(-180f, 180f),
        opacity = opacity.coerceIn(0, 100),
        intensity = intensity.coerceIn(0, 100),
    )
}

@Serializable
data class ArtisticClock(
    val enabled: Boolean = false,
    val digits: Map<DigitSlot, DigitStyle> = emptyMap(),
) {
    fun style(slot: DigitSlot): DigitStyle = (digits[slot] ?: DigitStyle()).normalized()

    fun edit(slot: DigitSlot, transform: (DigitStyle) -> DigitStyle): ArtisticClock =
        copy(digits = digits + (slot to transform(style(slot)).normalized()))

    fun reset(slot: DigitSlot): ArtisticClock = copy(digits = digits - slot)

    /** A one-digit hour leaves the tens slot empty rather than shifting the remaining slots. */
    fun glyphs(hours: String, minutes: String): Map<DigitSlot, Char> {
        val h = hours.filter { it in '0'..'9' }.takeLast(2)
        val m = minutes.filter { it in '0'..'9' }.takeLast(2)
        return buildMap {
            if (h.length == 2) put(DigitSlot.HOUR_TENS, h[0])
            h.lastOrNull()?.let { put(DigitSlot.HOUR_UNITS, it) }
            if (m.length == 2) put(DigitSlot.MINUTE_TENS, m[0])
            m.lastOrNull()?.let { put(DigitSlot.MINUTE_UNITS, it) }
        }
    }
}

private fun Float.finiteOr(default: Float): Float = if (isFinite()) this else default
