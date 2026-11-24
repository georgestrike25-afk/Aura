package app.aura.core.studio

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.sin

/** Efectos ambientales de Aura Atmosphere. */
@Serializable
enum class AtmosphereEffect(val label: String) {
    NONE("Ninguno"), RAIN("Lluvia"), SNOW("Nieve"), WIND("Viento"), DUST("Polvo luminoso"), FOG("Niebla"), ASH("Cenizas"),
}

/**
 * Ambiente del fondo: un efecto (con intensidad), si se anima y si la luz cambia según la hora.
 * Con [animate] apagado el efecto queda quieto (cero gasto extra de batería).
 */
@Serializable
data class Atmosphere(
    val effect: AtmosphereEffect = AtmosphereEffect.NONE,
    /** 0–100. */
    val intensity: Int = 50,
    val animate: Boolean = true,
    /** Tinte de luz según el momento del día (amanecer, hora dorada, atardecer, noche). */
    val timeLight: Boolean = false,
    /** 0–100: cuánto se nota la luz según la hora. */
    val timeLightStrength: Int = 50,
) {
    val active: Boolean get() = effect != AtmosphereEffect.NONE
    fun normalized() = copy(intensity = intensity.coerceIn(0, 100), timeLightStrength = timeLightStrength.coerceIn(0, 100))
}

/** Una partícula ya ubicada: posición y tamaño en px, alfa 0–1, largo (estelas) y giro en grados. */
data class Particle(val x: Float, val y: Float, val size: Float, val alpha: Float, val length: Float = 0f, val angle: Float = 0f)

/**
 * Partículas sin estado: la posición de cada una es función del tiempo, así cualquier fotograma se
 * calcula directo (sin simular ni guardar memoria) y la vista previa, el fondo estático y el animado
 * coinciden. [t] en segundos.
 */
object AtmosphereParticles {
    fun count(a: Atmosphere): Int = when (a.effect) {
        AtmosphereEffect.NONE, AtmosphereEffect.FOG -> 0
        AtmosphereEffect.RAIN -> 40 + a.intensity * 2
        AtmosphereEffect.SNOW -> 30 + a.intensity
        AtmosphereEffect.WIND -> 12 + a.intensity / 3
        AtmosphereEffect.DUST -> 20 + a.intensity / 2
        AtmosphereEffect.ASH -> 20 + a.intensity / 2
    }

    fun frame(a: Atmosphere, t: Float, w: Int, h: Int, seed: Int = 11): List<Particle> {
        val n = count(a.normalized())
        if (n == 0 || w <= 0 || h <= 0) return emptyList()
        val noise = Noise(seed)
        val k = w / 1080f
        return List(n) { i ->
            val r0 = noise.hash(i, 1)
            val r1 = noise.hash(i, 2)
            val r2 = noise.hash(i, 3)
            when (a.effect) {
                AtmosphereEffect.RAIN -> {
                    val speed = h * (1.1f + 0.6f * r2)
                    val len = (26f + 30f * r2) * k
                    val y = wrap(r1 * (h + len) + t * speed, h + len) - len
                    Particle(wrap(r0 * w + y * 0.12f, w.toFloat()), y, 1.6f * k, 0.25f + 0.35f * r2, len, 8f)
                }
                AtmosphereEffect.SNOW -> {
                    val y = wrap(r1 * (h + 20) + t * h * (0.05f + 0.05f * r2), h + 20f) - 10
                    val x = wrap(r0 * w + sin(t * 0.8f + r2 * 6.28f) * 18f * k, w.toFloat())
                    Particle(x, y, (2.5f + 4f * r2) * k, 0.55f + 0.4f * r2)
                }
                AtmosphereEffect.WIND -> {
                    val len = (90f + 160f * r2) * k
                    val x = wrap(r0 * (w + len) + t * w * (0.35f + 0.4f * r2), w + len) - len
                    val y = r1 * h + sin(t * 0.7f + r2 * 6.28f) * 14f * k
                    Particle(x, y, 1.4f * k, 0.12f + 0.18f * r2, len, -4f)
                }
                AtmosphereEffect.DUST -> {
                    // Motas que flotan y titilan (luz que entra por una ventana).
                    val x = wrap(r0 * w + sin(t * 0.25f + r2 * 6.28f) * 30f * k + t * 6f * k, w.toFloat())
                    val y = wrap(r1 * h - t * 10f * k * (0.5f + r2), h.toFloat())
                    val twinkle = 0.5f + 0.5f * sin(t * (1.2f + r2) + r0 * 6.28f)
                    Particle(x, y, (1.6f + 3.4f * r2) * k, 0.25f + 0.6f * twinkle)
                }
                AtmosphereEffect.ASH -> {
                    // Cenizas: caen lento, se mecen y giran.
                    val y = wrap(r1 * (h + 20) + t * h * (0.025f + 0.03f * r2), h + 20f) - 10
                    val x = wrap(r0 * w + sin(t * 0.5f + r2 * 6.28f) * 40f * k, w.toFloat())
                    Particle(x, y, (2f + 3.5f * r2) * k, 0.35f + 0.4f * r2, (4f + 6f * r2) * k, (t * 40f * (r2 - 0.5f)) % 360f)
                }
                else -> Particle(0f, 0f, 0f, 0f)
            }
        }
    }

    private fun wrap(v: Float, m: Float): Float = ((v % m) + m) % m
}

/** Luz según la hora: color y opacidad del tinte que se aplica sobre la foto. */
object TimeLight {
    /**
     * [minuteOfDay] 0–1439. [sunrise]/[sunset] en minutos del día si se conocen (si no, 6:30 y 19:30).
     * Devuelve ARGB con el alfa ya escalado por [strength] (0–100); 0 = sin tinte.
     */
    fun tint(minuteOfDay: Int, sunrise: Int? = null, sunset: Int? = null, strength: Int = 50): Int {
        val rise = sunrise ?: 390
        val set = sunset ?: 1170
        val m = minuteOfDay
        // (color sin alfa, alfa máx.) por tramo; transiciones suaves de ±45 min.
        val (rgb, alpha) = when {
            m < rise - 60 || m > set + 75 -> 0x1A2850 to 0.42f            // noche: azul profundo
            m < rise + 45 -> 0xFF9A7A to bell(m, rise, 60f) * 0.30f      // amanecer rosado
            m > set - 75 && m <= set + 15 -> 0xFFA040 to bell(m, set - 30, 50f) * 0.34f // hora dorada
            m > set + 15 -> 0x6A4C9C to 0.30f                             // atardecer violeta
            else -> 0xFFFFFF to 0f                                        // día: sin tinte
        }
        val a = (alpha * strength.coerceIn(0, 100) / 50f).coerceIn(0f, 0.85f)
        return ((a * 255).toInt() shl 24) or rgb
    }

    private fun bell(m: Int, center: Int, width: Float): Float {
        val x = (m - center) / width
        return (0.5f + 0.5f * kotlin.math.cos((x.coerceIn(-1f, 1f) * PI).toFloat()))
    }
}
