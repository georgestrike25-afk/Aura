package app.aura.core.studio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Texturas paramétricas de Aura Elements, generadas en el teléfono (sin imágenes descargadas):
 * cuadrados ARGB que se repiten sin costuras (ruido periódico). Las usa el pintor del reloj como
 * relleno de los números; la intensidad y la escala se aplican al dibujar.
 */
object ElementTexture {
    private val fireColors = intArrayOf(
        0x260E0705, 0xAA461007.toInt(), 0xEDAC2708.toInt(),
        0xFFFF6B13.toInt(), 0xFFFFC347.toInt(), 0xFFFFF1C6.toInt(),
    )

    fun hasSurface(material: ClockMaterial): Boolean = when (material) {
        ClockMaterial.METAL, ClockMaterial.GLASS, ClockMaterial.STONE -> true
        else -> false
    }

    /** Colores característicos de cada material: [cara clara, cara oscura, brillo]. */
    fun palette(m: ClockMaterial): IntArray = when (m) {
        ClockMaterial.AQUA -> intArrayOf(0xFF59D2E8.toInt(), 0xFF0E5E78.toInt(), 0xFF9EF2FF.toInt())
        ClockMaterial.IGNIS -> intArrayOf(0xFFFFB347.toInt(), 0xFF8A1A08.toInt(), 0xFFFF6A1A.toInt())
        ClockMaterial.VENTUS -> intArrayOf(0xFFF1F5F8.toInt(), 0xFF9FB0BD.toInt(), 0xFFE6EEF4.toInt())
        ClockMaterial.LUX -> intArrayOf(0xFFFFF6D8.toInt(), 0xFFFFD98A.toInt(), 0xFFFFE9A8.toInt())
        ClockMaterial.TERRA -> intArrayOf(0xFF8A5A33.toInt(), 0xFF4A2F1B.toInt(), 0xFF7FA65A.toInt())
        ClockMaterial.AETHER -> intArrayOf(0xFFB04BC9.toInt(), 0xFF2B1757.toInt(), 0xFF6FE3E3.toInt())
        ClockMaterial.GLACIUS -> intArrayOf(0xFFDDF4FF.toInt(), 0xFF6FB9DE.toInt(), 0xFFB8ECFF.toInt())
        ClockMaterial.KINTSUGI -> intArrayOf(0xFFEFE9DD.toInt(), 0xFFC9C0AE.toInt(), 0xFFD4A64A.toInt())
        else -> intArrayOf(0xFFFFFFFF.toInt(), 0xFF888888.toInt(), 0xFFFFFFFF.toInt())
    }

    /** Superficie ARGB; Ignis se repite en horizontal y se ajusta a la altura del glifo. */
    fun generate(m: ClockMaterial, size: Int = 256, seed: Int = 7): IntArray {
        if (!m.isElement && !hasSurface(m)) return IntArray(0)
        val out = IntArray(size * size)
        val n = Noise(seed + m.ordinal * 101)
        val p = palette(m)
        for (y in 0 until size) for (x in 0 until size) {
            val u = x / size.toFloat()
            val v = y / size.toFloat()
            out[y * size + x] = when (m) {
                ClockMaterial.AQUA -> water(n, u, v)
                ClockMaterial.IGNIS -> fire(n, u, v)
                ClockMaterial.METAL -> {
                    val line = n.value(u, v, 8, 112) * 0.8f + n.value(u, v, 18, 56) * 0.2f
                    val shade = line - 0.5f
                    withAlpha(if (shade > 0f) -1 else 0, (abs(shade) * 150).toInt())
                }
                ClockMaterial.GLASS -> {
                    val swirls = n.fbm(u + 0.25f * n.value(u, v, 4), v, 3, 5)
                    val streak = (1f - abs(sin(2 * PI * (2 * u - 3 * v + swirls * 0.65f)).toFloat())).pow(18)
                    withAlpha(0xFFF0FAFF.toInt(), (swirls * 24 + streak * 48).toInt())
                }
                ClockMaterial.STONE -> {
                    val mineral = n.fbm(u, v, 5, 5)
                    val grain = n.hash(x, y)
                    val vein = (1f - abs(sin(2 * PI * (u + 2 * v + mineral * 1.45f)).toFloat())).pow(10)
                    val body = mix(0xFF504944.toInt(), 0xFFD0C1AF.toInt(), mineral * 0.85f + grain * 0.22f)
                    val seams = mix(body, 0xFFE5D9C7.toInt(), vein * mineral * 0.7f)
                    if (grain < 0.06f) mix(seams, 0xFF242022.toInt(), 0.50f) else seams
                }
                ClockMaterial.VENTUS -> {
                    val f = n.fbm(u + 0.3f * n.fbm(v, u, 2, 2), v, 5, 3)
                    withAlpha(mix(p[1], p[0], f), (110 + 145 * f).toInt())
                }
                ClockMaterial.LUX -> {
                    val f = n.fbm(u, v, 3, 3)
                    val streak = (1f - abs(sin(2 * PI * (2 * u + v + 0.2 * f)).toFloat())).pow(12) * 0.6f
                    lighten(mix(p[1], p[0], 0.4f + 0.6f * f), streak)
                }
                ClockMaterial.TERRA -> {
                    val f = n.fbm(u, v, 4, 4)
                    val ring = 0.5f + 0.5f * sin(2 * PI * (8 * v + 1.6 * f)).toFloat()
                    val wood = mix(p[1], p[0], 0.3f + 0.7f * ring)
                    val moss = n.fbm(u + 0.5f, v, 4, 3)
                    if (moss > 0.62f) mix(wood, mix(0xFF3F6A2E.toInt(), p[2], (moss - 0.62f) * 2.6f), 0.85f) else wood
                }
                ClockMaterial.AETHER -> {
                    val a = n.fbm(u, v, 5, 3)
                    val b = n.fbm(u + 0.37f, v + 0.11f, 4, 4)
                    val nebula = mix(mix(p[1], p[0], a), p[2], (b - 0.45f).coerceIn(0f, 1f) * 1.4f)
                    if (n.hash(x, y) > 0.996f) 0xFFFFFFFF.toInt() else nebula // estrellas
                }
                ClockMaterial.GLACIUS -> {
                    val f = n.fbm(u, v, 4, 4)
                    val crack = n.ridge(u, v, 4, 3)
                    val ice = mix(p[1], p[0], 0.35f + 0.65f * f)
                    when {
                        crack > 0.965f -> 0xFFFFFFFF.toInt()
                        n.hash(x, y) > 0.985f -> lighten(ice, 0.6f) // escarcha
                        else -> ice
                    }
                }
                ClockMaterial.KINTSUGI -> {
                    val f = n.fbm(u, v, 3, 3)
                    val vein = n.ridge(u, v, 3, 2)
                    val ceramic = mix(p[1], p[0], 0.55f + 0.45f * f)
                    when {
                        vein > 0.95f -> lighten(p[2], (vein - 0.95f) * 8f)
                        vein > 0.92f -> p[2]
                        else -> ceramic
                    }
                }
                else -> 0
            }
        }
        return out
    }

    private fun water(n: Noise, u: Float, v: Float): Int {
        val swell = n.fbm(u, v, 3, 3)
        val x = u + (swell - 0.5f) * 0.18f
        val y = v + (n.fbm(u + 0.31f, v + 0.17f, 3, 3) - 0.5f) * 0.18f
        val distance = n.cellEdge(x, y, 7)
        val caustic = (1f - distance * 11f).coerceAtLeast(0f).pow(2)
        val scattered = (1f - distance * 3.5f).coerceAtLeast(0f).pow(3)
        val tint = mix(0x7F06364F, 0xCC34AEBB.toInt(), (swell * 1.45f - 0.2f))
        return mix(tint, 0xF2D7FCFF.toInt(), (0.72f * caustic + 0.23f * scattered))
    }

    private fun fire(n: Noise, u: Float, v: Float): Int {
        val turbulence = n.fbm(u, v * 0.39f, 4, 5)
        val x = u + (turbulence - 0.5f) * (0.19f - v * 0.08f)
        val tongues = n.fbm(x, v * 0.21f, 3, 10)
        val heat = (v - 0.13f) * 0.64f + (tongues - 0.33f) * 1.63f + (turbulence - 0.5f) * 0.40f
        val flame = ramp(heat, fireColors)
        val embers = n.value(u + 0.18f * (1f - v), v * 1.28f, 48)
        val spark = ((embers - 0.94f) * 14f).coerceIn(0f, 1f) * (1f - v)
        return mix(flame, 0xFFFFD883.toInt(), spark)
    }

    // ------------------------------------------------------------------ color

    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(s: Int) = (((a ushr s) and 0xFF) + (((b ushr s) and 0xFF) - ((a ushr s) and 0xFF)) * k).toInt().coerceIn(0, 255)
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun lighten(c: Int, t: Float) = mix(c, (c and 0xFF000000.toInt()) or 0xFFFFFF, t)

    private fun withAlpha(c: Int, a: Int) = (a.coerceIn(0, 255) shl 24) or (c and 0xFFFFFF)

    private fun ramp(t: Float, stops: IntArray): Int {
        val x = t.coerceIn(0f, 1f) * (stops.size - 1)
        val i = floor(x).toInt().coerceAtMost(stops.size - 2)
        return mix(stops[i], stops[i + 1], x - i)
    }
}

/** Ruido de valor periódico (se repite cada [period] celdas), determinista por semilla. */
class Noise(private val seed: Int) {
    fun hash(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 144665
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0x7FFFFFFF) / 2147483647f
    }

    private fun smooth(t: Float) = t * t * (3 - 2 * t)

    /** Ruido en [0,1] para coordenadas en [0,1), periódico con [period] celdas. */
    fun value(u: Float, v: Float, period: Int, periodY: Int = period): Float {
        val x = u * period
        val y = v * periodY
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = smooth(x - x0)
        val fy = smooth(y - y0)
        fun h(i: Int, j: Int) = hash(Math.floorMod(i, period), Math.floorMod(j, periodY))
        val a = h(x0, y0) + (h(x0 + 1, y0) - h(x0, y0)) * fx
        val b = h(x0, y0 + 1) + (h(x0 + 1, y0 + 1) - h(x0, y0 + 1)) * fx
        return a + (b - a) * fy
    }

    /** Distancia relativa a las aristas de celdas irregulares, para la red de cáusticas. */
    fun cellEdge(u: Float, v: Float, period: Int): Float {
        val x = u * period
        val y = v * period
        val ix = floor(x).toInt()
        val iy = floor(y).toInt()
        var closest = Float.MAX_VALUE
        var next = Float.MAX_VALUE
        for (j in iy - 1..iy + 1) for (i in ix - 1..ix + 1) {
            val cx = Math.floorMod(i, period)
            val cy = Math.floorMod(j, period)
            val dx = i + 0.12f + 0.76f * hash(cx, cy) - x
            val dy = j + 0.12f + 0.76f * hash(cx + period, cy) - y
            val d = dx * dx + dy * dy
            if (d < closest) {
                next = closest
                closest = d
            } else if (d < next) {
                next = d
            }
        }
        return sqrt(next) - sqrt(closest)
    }

    /** Suma de octavas (fBm) normalizada a [0,1]. */
    fun fbm(u: Float, v: Float, octaves: Int, basePeriod: Int): Float {
        var sum = 0f
        var amp = 0.5f
        var norm = 0f
        var period = basePeriod
        repeat(octaves) {
            sum += value(u - floor(u), v - floor(v), period) * amp
            norm += amp
            amp *= 0.5f
            period *= 2
        }
        return sum / norm
    }

    /** Ruido «cresta»: cerca de 1 sobre líneas finas (grietas, vetas). */
    fun ridge(u: Float, v: Float, octaves: Int, basePeriod: Int): Float = 1f - 2f * abs(fbm(u, v, octaves, basePeriod) - 0.5f)
}
