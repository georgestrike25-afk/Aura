package app.aura.core.studio

import kotlinx.serialization.Serializable

/** Posición fija de cada dígito, independiente del número que muestre en cada minuto. */
@Serializable
enum class DigitSlot { HOUR_TENS, HOUR_UNITS, MINUTE_TENS, MINUTE_UNITS }

/** Delante o detrás del sujeto de la foto; INHERIT sigue el ajuste general del reloj. */
@Serializable
enum class DigitDepth { INHERIT, BEHIND_SUBJECT, IN_FRONT_OF_SUBJECT }

/** Grupos de edición rápida. */
enum class DigitGroup(val label: String, val slots: Set<DigitSlot>) {
    ALL("Todos", DigitSlot.entries.toSet()),
    HOURS("Horas", setOf(DigitSlot.HOUR_TENS, DigitSlot.HOUR_UNITS)),
    MINUTES("Minutos", setOf(DigitSlot.MINUTE_TENS, DigitSlot.MINUTE_UNITS)),
}

/**
 * Estilo propio de un dígito. Desplazamientos en fracciones del lienzo; el resto se suma (o reemplaza,
 * si no es null) al estilo general del reloj. Se conserva aunque cambie la hora: va atado a la posición.
 */
@Serializable
data class DigitStyle(
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotation: Float = 0f,
    /** Inclinación (deformación) horizontal, −1 a 1. */
    val skewX: Float = 0f,
    val opacity: Int = 100,
    val color: Int? = null,
    val fontId: String? = null,
    val material: ClockMaterial? = null,
    val depth: DigitDepth = DigitDepth.INHERIT,
    /** Intensidad del material del dígito (0–100). */
    val intensity: Int = 70,
    val locked: Boolean = false,
    val hidden: Boolean = false,
) {
    fun normalized() = copy(
        offsetX = offsetX.finiteOr(0f).coerceIn(-1f, 1f),
        offsetY = offsetY.finiteOr(0f).coerceIn(-1f, 1f),
        scaleX = scaleX.finiteOr(1f).coerceIn(0.25f, 3f),
        scaleY = scaleY.finiteOr(1f).coerceIn(0.25f, 3f),
        rotation = rotation.finiteOr(0f).coerceIn(-180f, 180f),
        skewX = skewX.finiteOr(0f).coerceIn(-1f, 1f),
        opacity = opacity.coerceIn(0, 100),
        intensity = intensity.coerceIn(0, 100),
    )
}

/**
 * Reloj artístico: cada uno de los cuatro dígitos es un objeto independiente (posición, escala, giro,
 * inclinación, color, fuente, opacidad, material, profundidad), con bloqueo, visibilidad y orden de capas.
 * Con [enabled] en false el reloj se dibuja como un bloque, igual que antes.
 */
@Serializable
data class ArtisticClock(
    val enabled: Boolean = false,
    val digits: Map<DigitSlot, DigitStyle> = emptyMap(),
    /** Orden de dibujo de abajo hacia arriba. */
    val order: List<DigitSlot> = DigitSlot.entries,
) {
    fun style(slot: DigitSlot): DigitStyle = (digits[slot] ?: DigitStyle()).normalized()

    /** Aplica [transform] a cada dígito de [slots] que no esté bloqueado. */
    fun edit(slots: Set<DigitSlot>, transform: (DigitStyle) -> DigitStyle): ArtisticClock =
        copy(digits = digits + slots.filterNot { style(it).locked }.associateWith { transform(style(it)).normalized() })

    fun edit(slot: DigitSlot, transform: (DigitStyle) -> DigitStyle): ArtisticClock = edit(setOf(slot), transform)

    /** Bloquear/desbloquear ignora el bloqueo (si no, nunca se podría desbloquear). */
    fun setLocked(slots: Set<DigitSlot>, locked: Boolean): ArtisticClock =
        copy(digits = digits + slots.associateWith { style(it).copy(locked = locked) })

    fun reset(slots: Set<DigitSlot>): ArtisticClock = copy(digits = digits - slots.filterNot { style(it).locked }.toSet())

    /** Orden completo y sin repetidos (tolera listas guardadas incompletas). */
    fun drawOrder(): List<DigitSlot> = (order.distinct() + DigitSlot.entries).distinct()

    /** Sube (+1) o baja (−1) un dígito en el orden de capas. */
    fun moveLayer(slot: DigitSlot, delta: Int): ArtisticClock {
        val list = drawOrder().toMutableList()
        val i = list.indexOf(slot)
        val j = (i + delta).coerceIn(0, list.lastIndex)
        if (i == j) return this
        list.removeAt(i)
        list.add(j, slot)
        return copy(order = list)
    }

    /** Una hora de una cifra deja vacío el lugar de las decenas en vez de correr los demás. */
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

    /** ¿Este dígito va detrás del sujeto? [behindByDefault] es el ajuste general del reloj. */
    fun isBehind(slot: DigitSlot, behindByDefault: Boolean): Boolean = when (style(slot).depth) {
        DigitDepth.INHERIT -> behindByDefault
        DigitDepth.BEHIND_SUBJECT -> true
        DigitDepth.IN_FRONT_OF_SUBJECT -> false
    }
}

/**
 * Guías magnéticas: si un valor (p. ej. el centro de un dígito) queda a menos de [threshold] de alguna
 * guía, se pega a ella. Devuelve el valor final y la guía usada (null si no se pegó).
 */
object MagneticGuides {
    fun snap(value: Float, guides: List<Float>, threshold: Float): Pair<Float, Float?> {
        val best = guides.minByOrNull { kotlin.math.abs(it - value) } ?: return value to null
        return if (kotlin.math.abs(best - value) <= threshold) best to best else value to null
    }
}

private fun Float.finiteOr(default: Float): Float = if (isFinite()) this else default
