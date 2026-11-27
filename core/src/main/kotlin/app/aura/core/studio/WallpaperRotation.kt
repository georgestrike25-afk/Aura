package app.aura.core.studio

import java.time.LocalDateTime
import kotlin.random.Random

/**
 * Modo aleatorio del fondo: elige cuál de los diseños de Studio se muestra según el día o la hora.
 * Es determinista (mismo momento → mismo diseño) y no repite hasta haber pasado por todos.
 */
object WallpaperRotation {
    enum class Mode(val label: String) {
        OFF("Apagado"),
        DAILY("Cada día"),
        TIME_OF_DAY("Según la hora"),
        HOURLY("Cada hora"),
    }

    /** Momento del día: mañana (6–12), tarde (12–19) y noche (19–6). */
    enum class Period { MORNING, AFTERNOON, NIGHT }

    fun period(time: LocalDateTime): Period = when (time.hour) {
        in 6..11 -> Period.MORNING
        in 12..18 -> Period.AFTERNOON
        else -> Period.NIGHT
    }

    /** Identifica el tramo actual: cuando cambia, cambia el fondo. */
    fun slot(mode: Mode, time: LocalDateTime): Long {
        val day = time.toLocalDate().toEpochDay()
        return when (mode) {
            Mode.OFF -> 0
            Mode.DAILY -> day
            Mode.HOURLY -> day * 24 + time.hour
            // La noche después de medianoche pertenece al día anterior (no cambia a las 0 h).
            Mode.TIME_OF_DAY -> {
                val p = period(time)
                val d = if (p == Period.NIGHT && time.hour < 6) day - 1 else day
                d * 3 + p.ordinal
            }
        }
    }

    /**
     * Diseño que toca mostrar. [ids]: los diseños de Studio; [brightness]: luminosidad media de cada uno
     * (0–1), para que de noche salgan los oscuros y de día los luminosos en el modo «Según la hora».
     */
    fun pick(mode: Mode, ids: List<String>, brightness: Map<String, Float>, time: LocalDateTime): String? {
        if (mode == Mode.OFF || ids.isEmpty()) return null
        val pool = if (mode == Mode.TIME_OF_DAY) poolFor(period(time), ids, brightness) else ids.sorted()
        val s = slot(mode, time)
        // Una vuelta completa por todos antes de repetir, en un orden distinto en cada vuelta.
        val cycle = Math.floorDiv(s, pool.size.toLong())
        val order = pool.shuffled(Random(cycle * 1_000_003L + pool.size))
        return order[Math.floorMod(s, pool.size.toLong()).toInt()]
    }

    private fun poolFor(period: Period, ids: List<String>, brightness: Map<String, Float>): List<String> {
        val sorted = ids.sortedWith(compareBy({ brightness[it] ?: 0.5f }, { it }))
        if (sorted.size < 3) return sorted
        val third = (sorted.size + 2) / 3
        return when (period) {
            Period.NIGHT -> sorted.take(maxOf(third, sorted.size / 2))
            Period.AFTERNOON -> sorted.takeLast(maxOf(third, sorted.size / 2))
            Period.MORNING -> sorted.drop(sorted.size / 4).take(maxOf(third, sorted.size / 2))
        }
    }
}
