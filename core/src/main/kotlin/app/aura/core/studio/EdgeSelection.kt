package app.aura.core.studio

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.roundToInt

/** Ajusta el área marcada a los contornos de la foto. Las entradas están a escala de trabajo. */
object EdgeSelection {
    fun refine(
        pixels: IntArray,
        seeds: BooleanArray,
        width: Int,
        height: Int,
        subject: IntArray? = null,
    ): MaskRegion? {
        val markedPixels = seeds.indices.filter { seeds[it] }
        if (markedPixels.isEmpty()) return null
        val left = markedPixels.minOf { it % width }
        val right = markedPixels.maxOf { it % width }
        val top = markedPixels.first() / width
        val bottom = markedPixels.last() / width
        val margin = (minOf(width, height) * 0.33f).roundToInt().coerceAtLeast(4)
        val x0 = (left - margin).coerceAtLeast(0)
        val y0 = (top - margin).coerceAtLeast(0)
        val w = (right + margin + 1).coerceAtMost(width) - x0
        val h = (bottom + margin + 1).coerceAtMost(height) - y0
        if (w < 3 || h < 3) return null

        val fromPhoto = IntArray(w * h) { (it / w + y0) * width + it % w + x0 }
        val marked = fromPhoto.indices.filter { seeds[fromPhoto[it]] }
        // Reduce ruido de un píxel sin perder diferencias pequeñas de crominancia.
        fun channel(shift: Int): FloatArray = FloatArray(w * h) { i ->
            val photoIndex = fromPhoto[i]
            val x = i % w
            val y = i / w
            fun read(index: Int) = pixels[index] ushr shift and 255
            (read(photoIndex) * 4 + read(photoIndex - if (x > 0) 1 else 0) +
                read(photoIndex + if (x < w - 1) 1 else 0) +
                read(photoIndex - if (y > 0) width else 0) +
                read(photoIndex + if (y < h - 1) width else 0)) / 8f
        }
        val red = channel(16)
        val green = channel(8)
        val blue = channel(0)
        val texture = FloatArray(w * h) { i ->
            val color = pixels[fromPhoto[i]]
            val raw = ((color ushr 16 and 255) + (color ushr 8 and 255) + (color and 255)) / 3f
            abs(raw - (red[i] + green[i] + blue[i]) / 3f)
        }
        fun contrast(a: Int, b: Int): Float =
            maxOf(abs(red[a] - red[b]), abs(green[a] - green[b]), abs(blue[a] - blue[b])) +
                abs(texture[a] - texture[b]) * 0.55f

        val horizontal = FloatArray(w * h) { if (it % w < w - 1) contrast(it, it + 1) else 0f }
        val vertical = FloatArray(w * h) { if (it < w * (h - 1)) contrast(it, it + w) else 0f }
        val strengths = FloatArray(horizontal.size + vertical.size)
        horizontal.copyInto(strengths)
        vertical.copyInto(strengths, horizontal.size)
        strengths.sort()
        val percentile = strengths[(strengths.lastIndex * 0.82f).toInt()]
        val boundary = fromPhoto.indices.filter {
            (it % w == 0 || it % w == w - 1 || it < w || it >= (h - 1) * w) && !seeds[fromPhoto[it]]
        }
        if (boundary.isEmpty()) return null
        // La máscara automática sirve de referencia solo cuando la marca cae sobre ese sujeto.
        val prior = subject?.takeIf { alpha ->
            marked.count { alpha[fromPhoto[it]] >= 128 } * 2 >= marked.size &&
                boundary.any { alpha[fromPhoto[it]] < 96 }
        }
        if (prior == null && strengths.last() < 0.75f) return null
        val noiseFloor = (percentile + 0.35f).coerceAtLeast(0.65f)

        fun distances(sources: List<Int>, foreground: Boolean): FloatArray {
            val best = FloatArray(w * h) { Float.POSITIVE_INFINITY }
            val queue = PriorityQueue<Visit>(compareBy { it.cost })
            sources.forEach { best[it] = 0f; queue.add(Visit(it, 0f)) }
            while (queue.isNotEmpty()) {
                val (i, cost) = queue.remove()
                if (cost != best[i]) continue
                fun visit(j: Int, edge: Float) {
                    val barrier = ((edge - percentile * 0.8f) / noiseFloor).coerceIn(0f, 30f)
                    val alpha = (prior?.get(fromPhoto[j]) ?: 128) / 255f
                    val mismatch = if (foreground) 1f - alpha else alpha
                    val penalty = if (prior != null) mismatch * mismatch * 12f else 0f
                    val next = cost + 1f + 13f * barrier * barrier + penalty
                    if (next < best[j]) {
                        best[j] = next
                        queue.add(Visit(j, next))
                    }
                }
                if (i % w > 0) visit(i - 1, horizontal[i - 1])
                if (i % w < w - 1) visit(i + 1, horizontal[i])
                if (i >= w) visit(i - w, vertical[i - w])
                if (i < w * (h - 1)) visit(i + w, vertical[i])
            }
            return best
        }

        // El interior de la pincelada indica el objeto. La parte exterior puede cruzar su borde.
        val interior = marked.filter { i ->
            val x = i % w
            x in 2 until w - 2 && i >= 2 * w && i < (h - 2) * w &&
                seeds[fromPhoto[i - 2]] && seeds[fromPhoto[i + 2]] &&
                seeds[fromPhoto[i - 2 * w]] && seeds[fromPhoto[i + 2 * w]]
        }
        val foregroundSeeds = interior.ifEmpty { marked }
        val foreground = distances(foregroundSeeds, true)
        val background = distances(boundary, false)
        val maxDistance = maxOf(18f, margin * 0.70f)
        fun selected(i: Int): Boolean =
            foreground[i] < background[i] * 0.91f &&
                (foreground[i] <= maxDistance * 3 || (prior?.get(fromPhoto[i]) ?: 0) >= 128)

        val runs = buildList {
            for (row in 0 until h) {
                var x = 0
                while (x < w) {
                    if (!selected(row * w + x)) {
                        x++
                        continue
                    }
                    val start = x++
                    while (x < w && selected(row * w + x)) x++
                    add(row + y0)
                    add(start + x0)
                    add(x + x0)
                }
            }
        }
        return runs.takeIf { it.isNotEmpty() }?.let { MaskRegion(width, height, it) }
    }

    private data class Visit(val index: Int, val cost: Float)
}

/**
 * Selección asistida por un modelo de sujetos (ML Kit) en la zona del trazo: de todo lo que el modelo
 * reconoce en el recorte, se queda solo con las partes que el dedo tocó.
 */
object SubjectPick {
    /**
     * [alpha]: confianza 0–255 del modelo en el recorte de [w]×[h]; [seeds]: píxeles que pintó el trazo.
     * Devuelve las regiones conectadas (alpha ≥ [threshold]) que toca el trazo, o null si no toca ninguna.
     */
    fun touched(alpha: IntArray, seeds: BooleanArray, w: Int, h: Int, threshold: Int = 128): BooleanArray? {
        require(alpha.size == w * h && seeds.size == w * h)
        val out = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var top = 0
        var touchedSeeds = 0
        for (s in seeds.indices) {
            if (!seeds[s] || out[s] || alpha[s] < threshold) continue
            touchedSeeds++
            out[s] = true
            stack[top++] = s
            while (top > 0) {
                val i = stack[--top]
                val x = i % w
                fun push(j: Int) {
                    if (!out[j] && alpha[j] >= threshold) { out[j] = true; stack[top++] = j }
                }
                if (x > 0) push(i - 1)
                if (x < w - 1) push(i + 1)
                if (i >= w) push(i - w)
                if (i < w * (h - 1)) push(i + w)
            }
        }
        // Si el trazo casi no toca nada del modelo, el objeto que se quiso marcar no es el que vio el modelo.
        val seedCount = seeds.count { it }
        return out.takeIf { touchedSeeds > 0 && seeds.indices.count { seeds[it] && out[it] } * 5 >= seedCount }
    }

    /** Convierte una selección del recorte a tramos de [MaskRegion] en la grilla completa ([gridW]×[gridH]). */
    fun toRegion(selected: BooleanArray, w: Int, h: Int, offsetX: Int, offsetY: Int, gridW: Int, gridH: Int): MaskRegion? {
        val runs = buildList {
            for (y in 0 until h) {
                var x = 0
                while (x < w) {
                    if (!selected[y * w + x]) { x++; continue }
                    val start = x
                    while (x < w && selected[y * w + x]) x++
                    add(y + offsetY); add(start + offsetX); add(x + offsetX)
                }
            }
        }
        return runs.takeIf { it.isNotEmpty() }?.let { MaskRegion(gridW, gridH, it) }
    }
}
