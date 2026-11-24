package app.aura.core.studio

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Rasgos visuales de una foto (calculados en el teléfono sobre una versión reducida). */
data class ImageTraits(
    /** Colores dominantes (ARGB), del más al menos frecuente. */
    val palette: List<Int>,
    /** Peso de cada color dominante (suma 1). */
    val weights: List<Float>,
    /** Luminancia media 0–1. */
    val brightness: Float,
    /** Contraste: desvío estándar de la luminancia, 0–~0.5. */
    val contrast: Float,
    /** Saturación media 0–1. */
    val saturation: Float,
    /** Calidez: rojo − azul medio, −1 (frío) a 1 (cálido). */
    val warmth: Float,
    /** Luminancia media en la zona del reloj. */
    val regionBrightness: Float,
    /** Hacia dónde está la zona más luminosa vista desde el reloj (grados, 0 = derecha, 90 = abajo). */
    val lightAngle: Int,
)

/** Lo que propone «Armonizar». Nada se aplica sin que la persona toque «Aplicar». */
data class Harmony(
    val color: Int,
    val accentColor: Int,
    val material: ClockMaterial,
    val fontId: String,
    val lightAngle: Int,
    val reasons: List<String>,
)

object Fusion {
    /**
     * Analiza [pixels] (ARGB, [w]×[h], idealmente ≤ 128 px de lado). [region] = zona del reloj en
     * fracciones (izq., arriba, der., abajo).
     */
    fun analyze(pixels: IntArray, w: Int, h: Int, region: FloatArray = floatArrayOf(0.2f, 0.2f, 0.8f, 0.4f), k: Int = 5): ImageTraits {
        require(pixels.size == w * h && w > 0 && h > 0)
        var lumSum = 0.0
        var lumSq = 0.0
        var satSum = 0.0
        var warmSum = 0.0
        var regionSum = 0.0
        var regionN = 0
        val lums = FloatArray(pixels.size)
        pixels.forEachIndexed { i, c ->
            val r = (c shr 16 and 0xFF) / 255f
            val g = (c shr 8 and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
            lums[i] = l
            lumSum += l
            lumSq += l * l
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            satSum += if (mx <= 0f) 0f else (mx - mn) / mx
            warmSum += r - b
            val x = (i % w) / w.toFloat()
            val y = (i / w) / h.toFloat()
            if (x >= region[0] && x <= region[2] && y >= region[1] && y <= region[3]) {
                regionSum += l
                regionN++
            }
        }
        val n = pixels.size
        val mean = (lumSum / n).toFloat()
        val contrast = sqrt(max(0.0, lumSq / n - mean * mean)).toFloat()

        // Puntos más luminosos (sobre el percentil 97) → dirección de la luz respecto del reloj.
        // Estrictamente por encima del umbral: una zona grande y pareja no se confunde con la fuente de luz.
        val threshold = lums.sortedArray()[(n * 0.97f).toInt().coerceAtMost(n - 1)]
        var bx = 0.0
        var by = 0.0
        var bn = 0
        val strict = lums.any { it > threshold }
        lums.forEachIndexed { i, l -> if (if (strict) l > threshold else l >= threshold) { bx += (i % w).toDouble(); by += (i / w).toDouble(); bn++ } }
        val cx = (region[0] + region[2]) / 2 * w
        val cy = (region[1] + region[3]) / 2 * h
        val angle = if (bn == 0) 225 else Math.floorMod(Math.toDegrees(atan2(by / bn - cy, bx / bn - cx)).toInt(), 360)

        val (palette, weights) = dominant(pixels, k)
        return ImageTraits(
            palette = palette,
            weights = weights,
            brightness = mean,
            contrast = contrast,
            saturation = (satSum / n).toFloat(),
            warmth = (warmSum / n).toFloat(),
            regionBrightness = if (regionN == 0) mean else (regionSum / regionN).toFloat(),
            lightAngle = angle,
        )
    }

    /** k-medias determinista sobre los píxeles (semillas repartidas por luminancia). */
    fun dominant(pixels: IntArray, k: Int): Pair<List<Int>, List<Float>> {
        val pts = pixels.map { floatArrayOf((it shr 16 and 0xFF).toFloat(), (it shr 8 and 0xFF).toFloat(), (it and 0xFF).toFloat()) }
        val sorted = pts.sortedBy { 0.2126f * it[0] + 0.7152f * it[1] + 0.0722f * it[2] }
        val centers = Array(k) { i -> sorted[((i + 0.5f) / k * sorted.size).toInt().coerceAtMost(sorted.size - 1)].copyOf() }
        val assign = IntArray(pts.size)
        repeat(8) {
            pts.forEachIndexed { i, p ->
                var best = 0
                var bestD = Float.MAX_VALUE
                centers.forEachIndexed { j, c ->
                    val d = (p[0] - c[0]).pow(2) + (p[1] - c[1]).pow(2) + (p[2] - c[2]).pow(2)
                    if (d < bestD) { bestD = d; best = j }
                }
                assign[i] = best
            }
            val sum = Array(k) { FloatArray(3) }
            val cnt = IntArray(k)
            pts.forEachIndexed { i, p -> val j = assign[i]; sum[j][0] += p[0]; sum[j][1] += p[1]; sum[j][2] += p[2]; cnt[j]++ }
            for (j in 0 until k) if (cnt[j] > 0) centers[j] = floatArrayOf(sum[j][0] / cnt[j], sum[j][1] / cnt[j], sum[j][2] / cnt[j])
        }
        val counts = IntArray(k).also { c -> assign.forEach { c[it]++ } }
        val order = (0 until k).filter { counts[it] > 0 }.sortedByDescending { counts[it] }
        val colors = order.map { j -> argb(centers[j][0], centers[j][1], centers[j][2]) }
        val weights = order.map { counts[it] / pts.size.toFloat() }
        return colors to weights
    }

    /** Propuesta coherente con la foto; el reloj siempre se puede seguir editando a mano. */
    fun harmonize(t: ImageTraits): Harmony {
        val reasons = mutableListOf<String>()
        val hue = dominantHue(t)
        val material = when {
            t.saturation < 0.12f && t.contrast < 0.16f -> ClockMaterial.VENTUS.also { reasons += "Foto suave y poco saturada: aire y bruma (Ventus)" }
            t.brightness < 0.25f && hue in 230f..320f -> ClockMaterial.AETHER.also { reasons += "Noche con tonos violetas: nebulosa (Aether)" }
            t.brightness < 0.25f && t.warmth > 0.05f -> ClockMaterial.IGNIS.also { reasons += "Escena oscura y cálida: brasas (Ignis)" }
            t.brightness > 0.65f && t.warmth < -0.02f -> ClockMaterial.GLACIUS.also { reasons += "Luz fría y clara: hielo (Glacius)" }
            hue in 170f..230f && t.saturation > 0.2f -> ClockMaterial.AQUA.also { reasons += "Predominan azules y turquesas: agua (Aqua)" }
            hue in 70f..170f && t.saturation > 0.18f -> ClockMaterial.TERRA.also { reasons += "Predominan verdes: madera y musgo (Terra)" }
            t.warmth > 0.08f && t.saturation < 0.35f -> ClockMaterial.KINTSUGI.also { reasons += "Tonos cálidos y terrosos: cerámica con oro (Kintsugi)" }
            t.brightness > 0.6f -> ClockMaterial.LUX.also { reasons += "Foto luminosa: luz y resplandor (Lux)" }
            else -> ClockMaterial.METAL.also { reasons += "Escena equilibrada: metal pulido" }
        }
        // Color con buen contraste respecto de la zona del reloj, tomado de la propia foto si se puede.
        val wantLight = t.regionBrightness < 0.5f
        val candidates = t.palette + listOf(0xFFEDE9E1.toInt(), 0xFF111418.toInt())
        val color = candidates.maxByOrNull { c -> contrastRatio(luminance(c), t.regionBrightness) + if ((luminance(c) > 0.5f) == wantLight) 0.5f else 0f }!!
            .let { c -> if (contrastRatio(luminance(c), t.regionBrightness) < 3f) (if (wantLight) 0xFFEDE9E1.toInt() else 0xFF111418.toInt()) else c }
        reasons += if (wantLight) "Números claros: el fondo detrás del reloj es oscuro" else "Números oscuros: el fondo detrás del reloj es claro"
        val accent = t.palette.maxByOrNull { saturationOf(it) } ?: 0xFFD8BA8B.toInt()
        val font = when {
            t.contrast > 0.25f -> "clock_anton".also { reasons += "Mucho contraste: tipografía monumental" }
            t.saturation < 0.15f -> "clock_poiret".also { reasons += "Escena calma: tipografía zen" }
            t.brightness < 0.25f -> "clock_michroma".also { reasons += "Escena nocturna: tipografía futura" }
            t.warmth > 0.08f -> "clock_playfair".also { reasons += "Tonos cálidos: tipografía editorial" }
            else -> "clock_bebas".also { reasons += "Tipografía limpia y legible" }
        }
        reasons += "Luz desde la zona más clara de la foto"
        return Harmony(color, accent, material, font, t.lightAngle, reasons)
    }

    // ------------------------------------------------------------------ utilidades

    fun luminance(c: Int): Float =
        0.2126f * (c shr 16 and 0xFF) / 255f + 0.7152f * (c shr 8 and 0xFF) / 255f + 0.0722f * (c and 0xFF) / 255f

    /** Relación de contraste tipo WCAG entre dos luminancias (1–21). */
    fun contrastRatio(l1: Float, l2: Float): Float = (max(l1, l2) + 0.05f) / (min(l1, l2) + 0.05f)

    private fun saturationOf(c: Int): Float {
        val r = c shr 16 and 0xFF
        val g = c shr 8 and 0xFF
        val b = c and 0xFF
        val mx = max(r, max(g, b))
        return if (mx == 0) 0f else (mx - min(r, min(g, b))) / mx.toFloat()
    }

    /** Tono (0–360) del color dominante más saturado con peso razonable. */
    private fun dominantHue(t: ImageTraits): Float {
        val c = t.palette.zip(t.weights).filter { it.second > 0.12f }.maxByOrNull { saturationOf(it.first) }?.first ?: t.palette.first()
        val r = (c shr 16 and 0xFF) / 255f
        val g = (c shr 8 and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        if (mx - mn < 1e-3f) return -1f
        val d = mx - mn
        val h = when (mx) {
            r -> ((g - b) / d) % 6f
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } * 60f
        return if (h < 0) h + 360f else abs(h)
    }

    private fun argb(r: Float, g: Float, b: Float) =
        (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
}
