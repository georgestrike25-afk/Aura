package app.aura.core.studio

import app.aura.core.time.HourFormat
import kotlin.math.abs

/**
 * Aura AutoDesign: mira la foto (y el sujeto, si hay máscara) y arma tres relojes bien distintos, al
 * estilo de los fondos con profundidad: solo la hora, grande, con tipografías condensadas y, cuando hay
 * sujeto, metida detrás de él.
 *
 *  1. «Monumento»: una línea que ocupa casi todo el ancho, arriba, con el sujeto tapando la base.
 *  2. «Apilado»: horas arriba y minutos abajo, del lado libre de la foto, con los minutos en el color de acento.
 *  3. «Gigante»: cifras altísimas detrás del sujeto, en vidrio o en el material que sugiere la foto.
 *
 * Cada vuelta («Otras propuestas») cambia tipografías, colores y acabados. Nunca agrega fecha ni clima.
 */
object AutoDesign {
    data class Proposal(val title: String, val design: ClockDesign)

    /** Medidas de las cifras de cada fuente (en em): avance medio y altura de las cifras. */
    private data class Face(val id: String, val advance: Float, val cap: Float)

    private val BEBAS = Face("clock_bebas", 0.40f, 0.72f)
    private val ANTON = Face("clock_anton", 0.494f, 0.875f)
    private val OSWALD = Face("clock_oswald", 0.514f, 0.832f)
    private val FJALLA = Face("clock_fjallaone", 0.512f, 0.849f)
    private val STAATLICHES = Face("clock_staatliches", 0.461f, 0.717f)
    private val TEKO = Face("clock_teko", 0.408f, 0.631f)

    private val monumentFaces = listOf(BEBAS, ANTON, STAATLICHES, OSWALD)
    private val stackedFaces = listOf(ANTON, FJALLA, BEBAS, OSWALD)
    private val giantFaces = listOf(OSWALD, BEBAS, TEKO, ANTON)

    /** Hueco que queda entre horas y minutos sin los dos puntos (en em). */
    private const val GAP = 0.22f

    fun propose(pixels: IntArray, width: Int, height: Int, subject: IntArray?, round: Int, reference: ClockDesign? = null): List<Proposal> {
        require(width > 0 && height > 0 && pixels.size == width * height)
        require(subject == null || subject.size == pixels.size)
        val aspect = height / width.toFloat()
        val r = Math.floorMod(round, 1 shl 20)
        val scene = Scene.read(subject, width, height)
        val hourFormat = reference?.hourFormat ?: HourFormat.SYSTEM
        val behind = scene != null

        // 1) Monumento: una línea de ~88 % del ancho.
        val f1 = monumentFaces[r % monumentFaces.size]
        val stretch1 = listOf(1.45f, 1.7f, 1.3f, 1.55f)[r % 4]
        val size1 = fitWidth(f1, digitsPerLine = 4, target = 0.88f, stretchX = 1f)
        val h1 = f1.cap * size1 * stretch1 / aspect // alto de las cifras en fracción del alto de pantalla
        val y1 = if (scene != null) {
            // Que el sujeto tape el tercio inferior de las cifras.
            (scene.top - h1 * 0.18f).coerceIn(0.12f + h1 / 2f, 0.5f)
        } else {
            freeRow(pixels, width, height, 0.5f, 0.44f, h1, 0.12f + h1 / 2f, 0.40f)
        }
        val monument = base(pixels, width, height, 0.5f, y1, 0.44f, h1, ClockMaterial.MINIMAL).copy(
            customFont = f1.id, size = size1, stretchX = 1f, stretchY = stretch1,
            letterSpacing = -0.02f, shadow = 34, behindSubject = behind, hourFormat = hourFormat,
        )

        // 2) Apilado: dos líneas del lado libre, minutos en el color de acento.
        val f2 = stackedFaces[r % stackedFaces.size]
        val side = scene?.freeSide ?: if (r % 2 == 0) -1 else 1
        val x2 = 0.5f + side * 0.2f
        val size2 = fitWidth(f2, digitsPerLine = 2, target = 0.52f, stretchX = 1f, max = 0.46f)
        val stretch2 = 1f
        val h2 = (0.86f + f2.cap) * size2 * stretch2 / aspect
        val y2 = if (scene != null) (scene.top + h2 * 0.25f).coerceIn(0.14f + h2 / 2f, 0.62f)
            else freeRow(pixels, width, height, x2, 0.26f, h2, 0.14f + h2 / 2f, 0.5f)
        var stacked = base(pixels, width, height, x2, y2, 0.26f, h2, ClockMaterial.MINIMAL).copy(
            customFont = f2.id, layout = ClockLayout.VERTICAL, size = size2, stretchX = 1f, stretchY = stretch2,
            letterSpacing = -0.03f, shadow = 40, behindSubject = behind, hourFormat = hourFormat,
        )
        if (r % 3 != 2) {
            val accent = vivid(stacked.accentColor, stacked.color)
            stacked = stacked.copy(artistic = stacked.artistic.edit(DigitGroup.MINUTES.slots) { it.copy(color = accent) })
        }

        // 3) Gigante: cifras muy altas detrás del sujeto.
        val f3 = giantFaces[r % giantFaces.size]
        val stretchX3 = 0.86f
        val size3 = fitWidth(f3, digitsPerLine = 4, target = 0.92f, stretchX = stretchX3)
        val stretch3 = (0.40f * aspect / (f3.cap * size3)).coerceIn(1.6f, ClockDesign.MAX_STRETCH)
        val h3 = f3.cap * size3 * stretch3 / aspect
        val y3 = if (scene != null) scene.top.coerceIn(0.1f + h3 / 2f, 0.6f)
            else freeRow(pixels, width, height, 0.5f, 0.46f, h3, 0.1f + h3 / 2f, 0.5f)
        val harmony = harmony(pixels, width, height, 0.5f, y3, 0.46f, h3)
        val material3 = if (r % 2 == 0) ClockMaterial.GLASS else harmony.material
        val giant = base(pixels, width, height, 0.5f, y3, 0.46f, h3, material3).copy(
            customFont = f3.id, size = size3, stretchX = stretchX3, stretchY = stretch3,
            letterSpacing = -0.01f, opacity = 92, behindSubject = behind, hourFormat = hourFormat,
        )

        return listOf(
            Proposal("Monumento", monument),
            Proposal("Apilado", stacked),
            Proposal("Gigante", giant),
        )
    }

    /** Tamaño (alto del em / ancho del lienzo) para que una línea ocupe [target] del ancho. */
    private fun fitWidth(f: Face, digitsPerLine: Int, target: Float, stretchX: Float, max: Float = ClockDesign.MAX_SIZE): Float {
        val em = digitsPerLine * f.advance + if (digitsPerLine == 4) GAP else 0f
        return (target / (em * stretchX)).coerceIn(ClockDesign.MIN_SIZE, max)
    }

    /** Lo que se sabe del sujeto: dónde empieza (arriba) y de qué lado queda más lugar libre. */
    private data class Scene(val top: Float, val freeSide: Int) {
        companion object {
            fun read(subject: IntArray?, w: Int, h: Int): Scene? {
                subject ?: return null
                fun a(x: Int, y: Int) = ((subject[y * w + x] ushr 24) and 0xFF) / 255f
                var total = 0f
                var left = 0f
                var right = 0f
                var top = -1
                for (y in 0 until h) {
                    var row = 0f
                    for (x in 0 until w) {
                        val v = a(x, y)
                        row += v
                        if (x < w / 2) left += v else right += v
                    }
                    total += row
                    if (top < 0 && row / w > 0.06f) top = y
                }
                if (total / (w * h) < 0.01f || top < 0) return null
                return Scene(top / h.toFloat(), if (left > right) 1 else -1)
            }
        }
    }

    /** Fila más tranquila de la foto (poco detalle) para la franja de alto [bandH], entre [minY] y [maxY]. */
    private fun freeRow(px: IntArray, w: Int, h: Int, x: Float, halfW: Float, bandH: Float, minY: Float, maxY: Float): Float {
        if (maxY <= minY) return minY
        val light = FloatArray(px.size) { Fusion.luminance(px[it]) }
        var best = minY
        var bestScore = Float.MAX_VALUE
        var y = minY
        while (y <= maxY + 1e-4f) {
            val y0 = ((y - bandH / 2f) * h).toInt().coerceIn(0, h - 1)
            val y1 = ((y + bandH / 2f) * h).toInt().coerceIn(y0 + 1, h)
            val x0 = ((x - halfW) * w).toInt().coerceIn(1, w - 1)
            val x1 = ((x + halfW) * w).toInt().coerceIn(x0 + 1, w)
            var detail = 0f
            var n = 0
            for (row in y0 until y1) for (col in x0 until x1) {
                val i = row * w + col
                detail += abs(light[i] - light[i - 1])
                n++
            }
            // Preferir arriba: ahí se ve el reloj en la pantalla de bloqueo.
            val score = detail / n.coerceAtLeast(1) + (y - minY) * 0.04f
            if (score < bestScore) { bestScore = score; best = y }
            y += 0.02f
        }
        return best
    }

    private fun harmony(px: IntArray, w: Int, h: Int, x: Float, y: Float, halfW: Float, bandH: Float): Harmony {
        val region = floatArrayOf(
            (x - halfW).coerceIn(0f, 1f), (y - bandH / 2f).coerceIn(0f, 1f),
            (x + halfW).coerceIn(0f, 1f), (y + bandH / 2f).coerceIn(0f, 1f),
        )
        return Fusion.harmonize(Fusion.analyze(px, w, h, region))
    }

    private fun base(
        px: IntArray, w: Int, h: Int, x: Float, y: Float, halfW: Float, bandH: Float, material: ClockMaterial,
    ): ClockDesign {
        val harmony = harmony(px, w, h, x, y, halfW, bandH)
        return ClockDesign(
            boldHours = false,
            color = harmony.color,
            accentColor = harmony.accentColor,
            lightAngle = harmony.lightAngle,
            centerX = x,
            centerY = y,
            showColon = false,
            informationMode = ClockInformationMode.PURE,
            showDate = false,
            showWeather = false,
            artistic = ArtisticClock(enabled = true),
        ).withMaterial(material)
    }

    /** Acento bien visible y distinto del color principal (si la foto no da uno, un naranja cálido). */
    private fun vivid(accent: Int, main: Int): Int {
        fun ch(c: Int, s: Int) = (c shr s) and 0xFF
        val diff = abs(ch(accent, 16) - ch(main, 16)) + abs(ch(accent, 8) - ch(main, 8)) + abs(ch(accent, 0) - ch(main, 0))
        val max = maxOf(ch(accent, 16), ch(accent, 8), ch(accent, 0))
        val min = minOf(ch(accent, 16), ch(accent, 8), ch(accent, 0))
        return if (diff > 160 && max - min > 90) accent else 0xFFE8572A.toInt()
    }
}
